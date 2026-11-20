package com.norvex.chatbot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.appinventor.components.runtime.AndroidViewComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.Form;
import com.google.appinventor.components.runtime.util.YailDictionary;
import com.google.appinventor.components.runtime.util.YailList;

import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Mesin bersama untuk AiTeks + AiImage. INTI: penerus + jalur saja.
 * Halaman (extension-mit-plugin) yang pegang otak: history, konteks, AI.
 *
 * Protokol:
 *  Java -> halaman : window.norvexReceive({kind,id,...}) via evaluateJavascript.
 *  Halaman -> Java : window.Android.onX(...) via @JavascriptInterface (INTERNAL).
 *  id = id_internal hidden (bukan soket blok). Single-flight global: 1 kerjaan
 *  dalam 1 waktu untuk kedua komponen (backend juga single-flight).
 */
public class ChatbotEngine {

  // PENTING: jangan pakai null.perchance.org — origin "null" bikin
  // plugin AI (coupled server) tidak bisa dipakai. Pakai subdomain asli.
  static final String BACKEND_URL = "https://04fa0030a954ffb6dc080d0afc56ecea.perchance.org/extension-mit-plugin";
  static final String HIST_ID = "__hist__";

  static final int TEXT_TIMEOUT_MS = 120000;
  static final int IMAGE_TIMEOUT_MS = 180000;
  static final int READY_TIMEOUT_MS = 45000;
  static final int MIRROR_CAP = 40; // 20 giliran

  private static ChatbotEngine instance = null;

  static synchronized ChatbotEngine get(ComponentContainer container, AiTeks teks, AiImage image) {
    if (instance == null) {
      instance = new ChatbotEngine(container.$form(), container.$context());
    }
    if (teks != null) {
      instance.teks = teks;
    }
    if (image != null) {
      instance.image = image;
    }
    return instance;
  }

  private final Form form;
  private final Context context;
  private final Object lock = new Object();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  /** UI chat HTML (pemula). Dibaca/ditulis di UI thread; chatBusy volatile. */
  private ChatUiWeb chatUi = null;
  private volatile boolean chatBusy = false;

  private AiTeks teks = null;
  private AiImage image = null;

  private WebView webView = null;
  private boolean pageOk = false;
  private boolean ready = false;
  private boolean busy = false;
  private String activeKind = "";
  private String activeId = "";
  private long genTag = 0;
  private int genSeq = 0;
  private int activeSeq = 0;

  private String pendingPrompt = "";
  private String pendingImage = "";
  private boolean pendingIsUri = false;
  private boolean chunkMode = false;
  private final StringBuilder textAccum = new StringBuilder();
  private String[] imgParts = null;
  private int imgReceived = 0;
  private String imgMime = "";

  private String systemPrompt = "";
  private boolean autoHistory = true;

  // ---------------- batch image (C6): antre prompt, generate satu per satu ----------------
  static final int IMAGE_BATCH_CAP = 20;
  private boolean batchActive = false;
  private final ArrayList<String> batchPrompts = new ArrayList<String>();
  private int batchIndex = 0;
  private int batchOk = 0;
  private int batchFail = 0;
  private String batchNPrompt = "";
  private int batchSeed = -1;
  private String batchResolution = "512x512";
  private int batchGuidance = 7;
  private boolean batchBase64 = true;
  private final ArrayList<Integer> batchFailedIdx = new ArrayList<Integer>();

  private static final class Turn {
    final String role;
    String text;
    final String image;
    Turn(String role, String text, String image) {
      this.role = role;
      this.text = text;
      this.image = image;
    }
  }
  private final ArrayList<Turn> mirror = new ArrayList<Turn>();
  private boolean clearPending = false;

  private Runnable timeoutTask = null;
  private Runnable readyTimeoutTask = null;

  private ChatbotEngine(Form form, Context context) {
    this.form = form;
    this.context = context;
  }

  // ---------------- public API (dipanggil blok) ----------------

  void attachTeks(AiTeks t) { synchronized (lock) { teks = t; } }
  void attachImage(AiImage im) { synchronized (lock) { image = im; } }

  void emitCreate(boolean success, boolean rdy, int code, String msg) {
    if (teks != null) {
      teks.WhenChatbotCreate(success, rdy, code, msg);
    }
    if (image != null) {
      image.WhenChatbotCreate(success, rdy, code, msg);
    }
    if (chatUi != null) {
      try {
        chatUi.pushState(success && rdy);
      } catch (Exception e) {
        // abaikan
      }
    }
  }

  void callCreate() {
    synchronized (lock) {
      if (webView != null) {
        final boolean r = ready;
        dispatch(new Runnable() {
          public void run() {
            emitCreate(true, r, 0, r ? "sudah siap" : "sudah dibuat, menunggu backend");
          }
        });
        return;
      }
      pageOk = false;
      ready = false;
      busy = false;
    }
    form.runOnUiThread(new Runnable() {
      public void run() {
        try {
          WebView wv = new WebView(context);
          wv.getSettings().setJavaScriptEnabled(true);
          wv.getSettings().setDomStorageEnabled(true);
          wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
          wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
              boolean fire;
              synchronized (lock) {
                pageOk = true;
                fire = !ready;
              }
              if (fire) {
                dispatch(new Runnable() {
                  public void run() {
                    emitCreate(true, false, 0, "halaman termuat, menunggu sinyal backend");
                  }
                });
                armReadyTimeout();
              }
            }
            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
              synchronized (lock) {
                pageOk = false;
              }
              cancelReadyTimeout();
              dispatch(new Runnable() {
                public void run() {
                  emitCreate(false, false, 5, "gagal muat halaman");
                }
              });
            }
          });
          wv.addJavascriptInterface(new Bridge(), "Android");
          synchronized (lock) {
            webView = wv;
          }
          wv.loadUrl(BACKEND_URL);
        } catch (final Exception e) {
          synchronized (lock) {
            webView = null;
          }
          dispatch(new Runnable() {
            public void run() {
              emitCreate(false, false, 5, String.valueOf(e.getMessage()));
            }
          });
        }
      }
    });
  }

  void callRemove() {
    cancelTimeout();
    cancelReadyTimeout();
    synchronized (lock) {
      busy = false;
      activeKind = "";
      activeId = "";
      chatBusy = false;
      ready = false;
      pageOk = false;
      mirror.clear();
      clearPending = false;
      batchActive = false;
      batchPrompts.clear();
      batchIndex = 0;
      batchOk = 0;
      batchFail = 0;
      textAccum.setLength(0);
      imgParts = null;
      imgReceived = 0;
    }
    form.runOnUiThread(new Runnable() {
      public void run() {
        try {
          WebView wv;
          synchronized (lock) {
            wv = webView;
            webView = null;
          }
          if (wv != null) {
            wv.stopLoading();
            wv.removeJavascriptInterface("Android");
            wv.destroy();
          }
          if (chatUi != null) {
            try {
              chatUi.detach();
            } catch (Exception e) {
              // abaikan
            }
            chatUi = null;
          }
        } catch (Exception e) {
          // abaikan
        }
      }
    });
  }

  // ---------------- UI chat HTML (pemula: teks + gambar) ----------------

  /**
   * Pasang UI chat jadi di Arrangement + pulihkan backup (boleh kosong).
   * Selalu pastikan backend Create (idempoten). Aman dipanggil ulang.
   */
  void setupChatUi(final AndroidViewComponent container, final YailList backup) {
    restoreMirror(backup);
    callCreate();
    form.runOnUiThread(new Runnable() {
      public void run() {
        try {
          ViewGroup vg = ChatUiWeb.groupOf(container);
          if (chatUi != null) {
            try {
              chatUi.detach();
            } catch (Exception e) {
              // abaikan
            }
            chatUi = null;
          }
          if (vg == null) {
            dispatchTeksError("", 5, "wadah chat tidak valid (pakai VerticalArrangement kosong)");
            return;
          }
          ChatUiWeb ui = new ChatUiWeb(form, context, ChatbotEngine.this);
          ui.build(vg);
          chatUi = ui;
          // history + status dikirim saat halaman UI selesai dimuat (onChatUiLoaded)
        } catch (Exception e) {
          dispatchTeksError("", 5, String.valueOf(e.getMessage()));
        }
      }
    });
  }

  /** Dipanggil UI-WebView saat halamannya siap (boleh berkali-kali, idempoten). */
  void onChatUiLoaded() {
    final ChatUiWeb ui = chatUi;
    if (ui == null) {
      return;
    }
    final YailList hist = getAllHistory();
    final boolean st;
    synchronized (lock) {
      st = ready && webView != null;
    }
    dispatch(new Runnable() {
      public void run() {
        try {
          ui.pushHistory(hist);
          ui.pushState(st);
        } catch (Exception e) {
          // abaikan
        }
        emitChatUi(true, 0, "siap");
      }
    });
  }

  /** Kirim dari UI chat: teks (streaming). */
  void chatSendTeks(String prompt) {
    chatBusy = true;
    callGenerateTeks(prompt == null ? "" : prompt, true);
  }

  /** Kirim dari UI chat: vision (dataURL dari JS, sudah downscale). */
  void chatSendVision(String dataUrl, String prompt) {
    chatBusy = true;
    callGenerateTeksWithImage(prompt == null ? "" : prompt, false,
        dataUrl == null ? "" : dataUrl);
  }

  /** Kirim dari UI chat: gambar single (selalu url-mode). */
  void chatSendImage(String p, String n, double s, String r, double g) {
    chatBusy = true;
    callGenerateImage(p == null ? "" : p, n == null ? "" : n,
        s, r == null || r.length() == 0 ? "512x512" : r, g, false);
  }

  /** Wrapper publik untuk validasi bridge (tanpa id internal). */
  void dispatchTeksErrorPublic(int code, String msg) {
    dispatchTeksError("", code, msg);
  }

  /** Pulihkan mirror dari backup list-of-dict (validasi longgar, maks 40). */
  private void restoreMirror(YailList backup) {
    if (backup == null) {
      return;
    }
    try {
      Object[] arr = backup == null ? new Object[0] : backup.toArray();
      ArrayList<Turn> tmp = new ArrayList<Turn>();
      for (int i = 0; i < arr.length && tmp.size() < 40; i++) {
        Object o = arr[i];
        if (!(o instanceof YailDictionary)) {
          continue;
        }
        YailDictionary d = (YailDictionary) o;
        String role = strOf(d.get("role"));
        String text = strOf(d.get("text"));
        String image = strOf(d.get("image"));
        if (!("user".equals(role) || "ai".equals(role))) {
          continue;
        }
        if (text.length() == 0 && image.length() == 0) {
          continue;
        }
        if (image.length() > 4000) {
          image = "";
        }
        tmp.add(new Turn(role, text, image));
      }
      synchronized (lock) {
        mirror.clear();
        mirror.addAll(tmp);
      }
    } catch (Exception e) {
      // abaikan, pakai mirror apa adanya
    }
  }

  private static String strOf(Object o) {
    if (o == null) {
      return "";
    }
    try {
      return String.valueOf(o);
    } catch (Exception e) {
      return "";
    }
  }

  /** Dorong seluruh history ke UI chat (mis. setelah H-blok). */
  private void pushHistoryAll() {
    final ChatUiWeb ui = chatUi;
    if (ui == null) {
      return;
    }
    final YailList hist = getAllHistory();
    dispatch(new Runnable() {
      public void run() {
        try {
          ui.pushHistory(hist);
        } catch (Exception e) {
          // abaikan
        }
      }
    });
  }

  /** Event backup ke MIT: snapshot history terbaru (TinyDB-ready). */
  private void emitChatUi(final boolean success, final int code, final String msg) {
    final YailList hist = getAllHistory();
    dispatch(new Runnable() {
      public void run() {
        try {
          if (teks != null) {
            teks.WhenChatUI(success, hist, code, msg);
          }
        } catch (Exception e) {
          // abaikan
        }
      }
    });
  }

  /** Sinkron ulang UI chat + event backup (setelah H-blok). */
  private void refreshChatUi() {
    pushHistoryAll();
    emitChatUi(true, 0, "ok");
  }

  boolean isReady() {
    synchronized (lock) {
      return ready && webView != null;
    }
  }

  boolean isGenerating() {
    synchronized (lock) {
      return busy;
    }
  }

  int currentResponseId() {
    synchronized (lock) {
      return activeSeq;
    }
  }

  void setSystemPrompt(String text) {
    final String s = text == null ? "" : text;
    synchronized (lock) {
      systemPrompt = s;
    }
    sendSimple("setSystem", s);
  }

  void setAutoHistory(boolean enabled) {
    synchronized (lock) {
      autoHistory = enabled;
    }
    sendSimple("setAuto", enabled);
  }

  void callGenerateTeks(String prompt, boolean chunk) {
    String p = prompt == null ? "" : prompt;
    synchronized (lock) {
      if (!ready || webView == null) {
        dispatchTeksError("", 2, "belum siap");
        return;
      }
      if (busy) {
        dispatchTeksError("", 3, "sibuk");
        return;
      }
      startGen("text", p, "", false, chunk);
    }
    sendGenerate();
  }

  void callGenerateTeksWithImage(String prompt, boolean isUri, String image) {
    String p = prompt == null ? "" : prompt;
    String im = image == null ? "" : image;
    synchronized (lock) {
      if (!ready || webView == null) {
        dispatchTeksError("", 2, "belum siap");
        return;
      }
      if (busy) {
        dispatchTeksError("", 3, "sibuk");
        return;
      }
      if (p.length() == 0 || im.length() == 0) {
        dispatchTeksError("", 5, "prompt/image kosong");
        return;
      }
      startGen("textWithImage", p, im, isUri, false);
    }
    if (!needsLocalImageRead(im)) {
      sendGenerate();
      return;
    }
    // Path / file:// / content:// tidak bisa dibaca halaman (WebView) —
    // extension baca di sini lalu kirim sebagai base64 (halaman jadikan Blob).
    final String myId;
    final String fIm = im;
    synchronized (lock) {
      myId = activeId;
    }
    new Thread(new Runnable() {
      public void run() {
        byte[] data = readLocalImageBytes(fIm);
        String b64 = null;
        if (data != null) {
          try {
            b64 = Base64.encodeToString(data, Base64.NO_WRAP);
          } catch (Exception e) {
            b64 = null;
          }
        }
        boolean drop = false;
        boolean unreadable = false;
        synchronized (lock) {
          if (!busy || !myId.equals(activeId)
              || !"textWithImage".equals(activeKind)) {
            drop = true;
          } else if (b64 == null) {
            busy = false;
            activeKind = "";
            activeId = "";
            unreadable = true;
          } else {
            pendingImage = b64;
            pendingIsUri = false;
          }
        }
        if (drop) {
          return;
        }
        if (unreadable) {
          cancelTimeout();
          dispatchTeksError("", 5, "gambar tidak terbaca");
          return;
        }
        sendGenerate();
      }
    }).start();
  }

  /** True bila gambar harus dibaca extension (path / file:// / content:// /
   *  nama file), bukan diteruskan mentah (http(s) / data: / base64). */
  private boolean needsLocalImageRead(String im) {
    if (im.regionMatches(true, 0, "http", 0, 4)) {
      return false;
    }
    if (im.regionMatches(true, 0, "data:", 0, 5)) {
      return false;
    }
    if (im.regionMatches(true, 0, "content://", 0, 10)
        || im.regionMatches(true, 0, "file://", 0, 7)
        || (im.length() > 0 && im.charAt(0) == '/')) {
      return true;
    }
    // Sisa: base64 mentah (teruskan) atau nama file relatif (coba baca).
    return !looksInlineBase64(im);
  }

  private boolean looksInlineBase64(String s) {
    String t = s.trim();
    if (t.length() < 100) {
      return false;
    }
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '='
          || c == '\r' || c == '\n';
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  /** Baca byte gambar lokal (maks 20 MB). null bila tidak terbaca. */
  private byte[] readLocalImageBytes(String im) {
    InputStream in = null;
    try {
      if (im.regionMatches(true, 0, "content://", 0, 10)) {
        in = context.getContentResolver().openInputStream(Uri.parse(im));
      } else {
        String p = im;
        if (p.regionMatches(true, 0, "file://", 0, 7)) {
          p = p.substring(7);
        }
        File f = new File(p);
        if (!f.isAbsolute() || !f.exists()) {
          return null;
        }
        in = new FileInputStream(f);
      }
      if (in == null) {
        return null;
      }
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      long total = 0;
      while ((n = in.read(buf)) >= 0) {
        total += n;
        if (total > 20 * 1024 * 1024) {
          try {
            in.close();
          } catch (Exception e) {
            // abaikan
          }
          return null;
        }
        bos.write(buf, 0, n);
      }
      try {
        in.close();
      } catch (Exception e) {
        // abaikan
      }
      return bos.toByteArray();
    } catch (Exception e) {
      try {
        if (in != null) {
          in.close();
        }
      } catch (Exception ex) {
        // abaikan
      }
      return null;
    }
  }

  void callCancelTeks() {
    callCancel();
  }

  void callGenerateImage(String prompt, String nPrompt, double seedD, String resolution,
      double guidanceD, boolean base64) {
    String p = prompt == null ? "" : prompt;
    String np = nPrompt == null ? "" : nPrompt;
    String res = mapResolution(resolution);
    int seed = (int) Math.floor(seedD);
    if (seedD != seedD || seed < 0) {
      seed = -1;
    }
    int guidance = (int) Math.floor(guidanceD);
    if (guidanceD != guidanceD) {
      guidance = 7;
    }
    if (guidance < 1) {
      guidance = 1;
    }
    if (guidance > 30) {
      guidance = 30;
    }
    final int fSeed = seed;
    final int fGuidance = guidance;
    final boolean fBase64 = base64;
    synchronized (lock) {
      if (!ready || webView == null) {
        dispatchImageError(2, "belum siap");
        return;
      }
      if (busy) {
        dispatchImageError(3, "sibuk");
        return;
      }
      if (p.length() == 0) {
        dispatchImageError(5, "prompt kosong");
        return;
      }
      if (res == null) {
        dispatchImageError(5, "resolusi salah");
        return;
      }
      batchActive = false;
      sendImageItemLocked(p, np, fSeed, res, fGuidance, fBase64);
    }
  }

  void callGenerateImageBatch(YailList prompts, String nPrompt, double seedD,
      String resolution, double guidanceD, boolean base64) {
    ArrayList<String> list = new ArrayList<String>();
    try {
      if (prompts != null) {
        Object[] arr = prompts.toArray();
        for (int i = 0; i < arr.length; i++) {
          Object o = arr[i];
          list.add(o == null ? "" : o.toString());
        }
      }
    } catch (Exception e) {
      dispatchImageError(5, "daftar prompt tidak terbaca");
      return;
    }
    String np = nPrompt == null ? "" : nPrompt;
    String res = mapResolution(resolution);
    int seed = (int) Math.floor(seedD);
    if (seedD != seedD || seed < 0) {
      seed = -1;
    }
    int guidance = (int) Math.floor(guidanceD);
    if (guidanceD != guidanceD) {
      guidance = 7;
    }
    if (guidance < 1) {
      guidance = 1;
    }
    if (guidance > 30) {
      guidance = 30;
    }
    final int fSeed = seed;
    final int fGuidance = guidance;
    final boolean fBase64 = base64;
    synchronized (lock) {
      if (!ready || webView == null) {
        dispatchImageError(2, "belum siap");
        return;
      }
      if (busy) {
        dispatchImageError(3, "sibuk");
        return;
      }
      if (list.isEmpty()) {
        dispatchImageError(5, "daftar prompt kosong");
        return;
      }
      if (list.size() > IMAGE_BATCH_CAP) {
        dispatchImageError(5, "kebanyakan (maks " + IMAGE_BATCH_CAP + ")");
        return;
      }
      for (int i = 0; i < list.size(); i++) {
        if (list.get(i).trim().length() == 0) {
          dispatchImageError(5, "prompt kosong (no " + (i + 1) + ")");
          return;
        }
      }
      if (res == null) {
        dispatchImageError(5, "resolusi salah");
        return;
      }
      batchActive = true;
      batchPrompts.clear();
      for (int i = 0; i < list.size(); i++) {
        batchPrompts.add(list.get(i));
      }
      batchIndex = 0;
      batchOk = 0;
      batchFail = 0;
      batchFailedIdx.clear();
      batchNPrompt = np;
      batchSeed = fSeed;
      batchResolution = res;
      batchGuidance = fGuidance;
      batchBase64 = fBase64;
      sendImageItemLocked(batchPrompts.get(0), np, fSeed, res, fGuidance, fBase64);
    }
  }

  /** Siapkan + kirim 1 request gambar. Panggil dengan lock dipegang. */
  private void sendImageItemLocked(String prompt, String np, int seed, String res,
      int guidance, boolean base64) {
    busy = true;
    activeKind = "image";
    activeId = newId();
    chunkMode = false;
    pendingPrompt = prompt;
    pendingImage = "";
    imgParts = null;
    imgReceived = 0;
    imgMime = "";
    armTimeout(genTag, IMAGE_TIMEOUT_MS + 5000, true);
    try {
      JSONObject o = new JSONObject();
      o.put("kind", "image");
      o.put("id", activeId);
      o.put("prompt", prompt);
      o.put("n_prompt", np);
      o.put("seed", seed);
      o.put("resolution", res);
      o.put("guidance", guidance);
      o.put("base64", base64);
      o.put("systemPrompt", systemPrompt);
      eval(o.toString());
    } catch (Exception e) {
      busy = false;
      activeKind = "";
      activeId = "";
      if (batchActive) {
        batchActive = false;
      }
      dispatchImageError(5, String.valueOf(e.getMessage()));
    }
  }

  /**
   * Maju ke prompt berikut. Panggil dengan lock dipegang + batchActive true.
   * true = item berikut sudah disiapkan (timeout di-arm ulang, pemanggil
   * tinggal eval di luar lock); false = batch habis (state direset).
   */
  private boolean advanceBatchLocked() {
    batchIndex++;
    if (batchIndex >= batchPrompts.size()) {
      batchActive = false;
      busy = false;
      activeKind = "";
      activeId = "";
      return false;
    }
    activeId = newId();
    pendingPrompt = batchPrompts.get(batchIndex);
    pendingImage = "";
    imgParts = null;
    imgReceived = 0;
    imgMime = "";
    armTimeout(genTag, IMAGE_TIMEOUT_MS + 5000, true);
    return true;
  }

  /** Kirim request item batch yang sudah disiapkan (baca state apa adanya). */
  private void evalBatchItem() {
    final String id;
    final String prompt;
    final String np;
    final int seed;
    final String res;
    final int guidance;
    final boolean base64;
    final String sys;
    synchronized (lock) {
      id = activeId;
      prompt = pendingPrompt;
      np = batchNPrompt;
      seed = batchSeed;
      res = batchResolution;
      guidance = batchGuidance;
      base64 = batchBase64;
      sys = systemPrompt;
    }
    try {
      JSONObject o = new JSONObject();
      o.put("kind", "image");
      o.put("id", id);
      o.put("prompt", prompt);
      o.put("n_prompt", np);
      o.put("seed", seed);
      o.put("resolution", res);
      o.put("guidance", guidance);
      o.put("base64", base64);
      o.put("systemPrompt", sys);
      eval(o.toString());
    } catch (Exception e) {
      abortBatch(5, String.valueOf(e.getMessage()));
    }
  }

  /** Hentikan batch paksa (dipakai bila pengiriman item berikut gagal total). */
  private void abortBatch(int code, String msg) {
    final int total;
    final int ok;
    final int fail;
    final YailList fIdx;
    synchronized (lock) {
      if (!batchActive) {
        return;
      }
      batchActive = false;
      busy = false;
      activeKind = "";
      activeId = "";
      total = batchPrompts.size();
      ok = batchOk;
      fail = batchFail;
      fIdx = batchFailedIdxList();
    }
    cancelTimeout();
    dispatchImageError(code, msg);
    dispatchBatchDone(total, ok, fail, fIdx);
  }

  private void dispatchBatchDone(final int total, final int ok, final int fail,
      final YailList failedIdx) {
    dispatch(new Runnable() {
      public void run() {
        if (image != null) {
          image.WhenAIGenerateImageBatchDone(total, ok, fail, failedIdx);
        }
      }
    });
  }

  private void dispatchBatchItem(final int index, final int total,
      final String b64, final String url) {
    dispatch(new Runnable() {
      public void run() {
        if (image != null) {
          image.WhenAIGenerateImageBatchItem(index, total, b64, url);
        }
      }
    });
  }

  private YailList batchFailedIdxList() {
    ArrayList<Integer> out = new ArrayList<Integer>();
    for (int i = 0; i < batchFailedIdx.size(); i++) {
      out.add(batchFailedIdx.get(i));
    }
    return YailList.makeList(out);
  }

  int getPairCount() {
    synchronized (lock) {
      return mirror.size() / 2;
    }
  }

  YailList getBatchProgress() {
    int done;
    int total;
    synchronized (lock) {
      if (batchActive) {
        done = batchOk + batchFail;
        total = batchPrompts.size();
      } else {
        done = 0;
        total = 0;
      }
    }
    ArrayList<Integer> out = new ArrayList<Integer>();
    out.add(Integer.valueOf(done));
    out.add(Integer.valueOf(total));
    return YailList.makeList(out);
  }

  void callCancelImage() {
    callCancel();
  }

  // ---------------- history blok (baca mirror sync) ----------------

  YailList getAllHistory() {
    ArrayList<YailDictionary> out = new ArrayList<YailDictionary>();
    synchronized (lock) {
      for (int i = 0; i < mirror.size(); i++) {
        Turn t = mirror.get(i);
        YailDictionary d = new YailDictionary();
        d.put("role", t.role);
        d.put("text", t.text);
        d.put("image", t.image);
        out.add(d);
      }
    }
    return YailList.makeList(out);
  }

  YailList getHistoryResponAI() {
    ArrayList<String> out = new ArrayList<String>();
    synchronized (lock) {
      for (int i = 0; i < mirror.size(); i++) {
        Turn t = mirror.get(i);
        if ("ai".equals(t.role)) {
          out.add(t.text);
        }
      }
    }
    return YailList.makeList(out);
  }

  YailList getHistoryInputUser() {
    ArrayList<String> out = new ArrayList<String>();
    synchronized (lock) {
      for (int i = 0; i < mirror.size(); i++) {
        Turn t = mirror.get(i);
        if ("user".equals(t.role)) {
          out.add(t.text);
        }
      }
    }
    return YailList.makeList(out);
  }

  void removePair(int giliran) {
    boolean send = false;
    synchronized (lock) {
      int idx = (giliran - 1) * 2;
      if (giliran >= 1 && idx >= 0 && idx + 1 < mirror.size()) {
        mirror.remove(idx + 1);
        mirror.remove(idx);
        send = ready && webView != null;
      }
    }
    if (send) {
      sendPairOp("removePair", giliran, null, 0);
    }
    refreshChatUi();
  }

  void replaceUser(int giliran, String text) {
    String t = text == null ? "" : text;
    boolean send = false;
    synchronized (lock) {
      int idx = (giliran - 1) * 2;
      if (giliran >= 1 && idx >= 0 && idx + 1 < mirror.size()) {
        mirror.get(idx).text = t;
        send = ready && webView != null;
      }
    }
    if (send) {
      sendPairOp("replaceUser", giliran, t, 0);
    }
    refreshChatUi();
  }

  void replaceAi(int giliran, String text) {
    String t = text == null ? "" : text;
    boolean send = false;
    synchronized (lock) {
      int idx = (giliran - 1) * 2;
      if (giliran >= 1 && idx >= 0 && idx + 1 < mirror.size()) {
        mirror.get(idx + 1).text = t;
        send = ready && webView != null;
      }
    }
    if (send) {
      sendPairOp("replaceAi", giliran, t, 0);
    }
    refreshChatUi();
  }

  void clearHistory() {
    boolean send;
    synchronized (lock) {
      mirror.clear();
      if (!ready || webView == null) {
        clearPending = true;
        send = false;
      } else {
        send = true;
      }
    }
    if (send) {
      sendKindOnly("clearHistory");
    }
    refreshChatUi();
  }

  // ---------------- internal ----------------

  private String newId() {
    genTag++;
    return "q" + genTag + "_" + System.nanoTime();
  }

  private void startGen(String kind, String prompt, String image, boolean isUri, boolean chunk) {
    busy = true;
    activeKind = kind;
    activeId = newId();
    genSeq++;
    activeSeq = genSeq;
    pendingPrompt = prompt;
    pendingImage = image;
    pendingIsUri = isUri;
    chunkMode = chunk;
    textAccum.setLength(0);
    armTimeout(genTag, TEXT_TIMEOUT_MS + 5000, false);
  }

  private void sendGenerate() {
    final String kind;
    final String id;
    final String p;
    final String im;
    final boolean isU;
    final boolean ch;
    final String sys;
    final boolean au;
    synchronized (lock) {
      kind = activeKind;
      id = activeId;
      p = pendingPrompt;
      im = pendingImage;
      isU = pendingIsUri;
      ch = chunkMode;
      sys = systemPrompt;
      au = autoHistory;
    }
    try {
      JSONObject o = new JSONObject();
      o.put("kind", kind);
      o.put("id", id);
      o.put("prompt", p);
      o.put("chunk", ch);
      o.put("systemPrompt", sys);
      o.put("autoHistory", au);
      if ("textWithImage".equals(kind)) {
        o.put("image", im);
        o.put("isUri", isU);
      }
      eval(o.toString());
    } catch (Exception e) {
      synchronized (lock) {
        if (id.equals(activeId)) {
          busy = false;
        }
      }
      cancelTimeout();
      dispatchTeksError(id, 5, String.valueOf(e.getMessage()));
    }
  }

  private void sendSimple(String kind, Object val) {
    final String k = kind;
    final Object v = val;
    boolean ok;
    synchronized (lock) {
      ok = ready && webView != null;
    }
    if (!ok) {
      return;
    }
    try {
      JSONObject o = new JSONObject();
      o.put("kind", k);
      o.put("id", newId());
      if ("setSystem".equals(k)) {
        o.put("systemPrompt", String.valueOf(v));
      } else {
        o.put("auto", ((Boolean) v).booleanValue());
      }
      eval(o.toString());
    } catch (Exception e) {
      // abaikan: per-request juga bawa nilai yang sama
    }
  }

  private void sendPairOp(String kind, int giliran, String text, int unused) {
    try {
      JSONObject o = new JSONObject();
      o.put("kind", kind);
      o.put("id", newId());
      o.put("index", giliran);
      if (text != null) {
        o.put("text", text);
      }
      eval(o.toString());
    } catch (Exception e) {
      // abaikan
    }
  }

  private void sendKindOnly(String kind) {
    try {
      JSONObject o = new JSONObject();
      o.put("kind", kind);
      o.put("id", newId());
      eval(o.toString());
    } catch (Exception e) {
      // abaikan
    }
  }

  private void callCancel() {
    final String id;
    final String kind;
    final boolean wasBatch;
    final int bTotal;
    final int bOk;
    final int bFail;
    final YailList bFailed;
    synchronized (lock) {
      if (!busy) {
        return;
      }
      id = activeId;
      kind = activeKind;
      wasBatch = batchActive && "image".equals(kind);
      bTotal = batchPrompts.size();
      bOk = batchOk;
      bFail = batchFail;
      bFailed = batchFailedIdxList();
      busy = false;
      activeKind = "";
      activeId = "";
      batchActive = false;
    }
    cancelTimeout();
    // Beritahu halaman agar kerja AI berhenti; balasan halaman (id lama)
    // otomatis diabaikan karena id sudah tidak cocok.
    try {
      JSONObject o = new JSONObject();
      o.put("kind", "cancel");
      o.put("id", newId());
      o.put("targetId", id);
      eval(o.toString());
    } catch (Exception e) {
      // abaikan: lokal sudah berhenti
    }
    if ("image".equals(kind)) {
      dispatchImageError(4, "dibatalkan");
      if (wasBatch) {
        dispatchBatchDone(bTotal, bOk, bFail, bFailed);
      }
    } else {
      dispatchTeksError("", 4, "dibatalkan");
    }
  }

  private void eval(final String json) {
    form.runOnUiThread(new Runnable() {
      public void run() {
        try {
          WebView wv;
          synchronized (lock) {
            wv = webView;
          }
          if (wv != null) {
            wv.evaluateJavascript("window.norvexReceive(" + json + ");", null);
          }
        } catch (Exception e) {
          // abaikan
        }
      }
    });
  }

  private void dispatch(Runnable r) {
    try {
      form.runOnUiThread(r);
    } catch (Exception e) {
      // abaikan
    }
  }

  private void dispatchTeksError(final String id, final int code, final String msg) {
    dispatch(new Runnable() {
      public void run() {
        if (teks != null) {
          teks.WhenAIGenerateTeksError(code, msg);
        }
        if (chatBusy) {
          chatBusy = false;
          if (chatUi != null) {
            try {
              chatUi.pushError(code, msg);
            } catch (Exception e) {
              // abaikan
            }
          }
        }
        emitChatUi(false, code, msg);
      }
    });
  }

  private void dispatchImageError(final int code, final String msg) {
    dispatch(new Runnable() {
      public void run() {
        if (image != null) {
          image.WhenAIGenerateImageError(code, msg);
        }
        if (chatBusy && chatUi != null) {
          chatBusy = false;
          try {
            chatUi.pushImgError(code, msg);
          } catch (Exception e) {
            // abaikan
          }
        }
      }
    });
  }

  private void armTimeout(long tag, int ms, final boolean isImage) {
    cancelTimeout();
    final long t = tag;
    timeoutTask = new Runnable() {
      public void run() {
        boolean fireSingle = false;
        boolean batchCont = false;
        boolean batchStop = false;
        int bTotal = 0;
        int bOk = 0;
        int bFail = 0;
        YailList bFailed = null;
        synchronized (lock) {
          if (busy && genTag == t) {
            if (isImage && batchActive) {
              batchFail++;
              batchFailedIdx.add(Integer.valueOf(batchIndex + 1));
              if (advanceBatchLocked()) {
                batchCont = true;
              } else {
                batchStop = true;
                bTotal = batchPrompts.size();
                bOk = batchOk;
                bFail = batchFail;
                bFailed = batchFailedIdxList();
              }
            } else {
              busy = false;
              activeKind = "";
              activeId = "";
              fireSingle = true;
            }
          }
        }
        if (batchCont) {
          dispatchImageError(1, "timeout");
          evalBatchItem();
        } else if (batchStop) {
          dispatchImageError(1, "timeout");
          dispatchBatchDone(bTotal, bOk, bFail, bFailed);
        } else if (fireSingle) {
          if (isImage) {
            dispatchImageError(1, "timeout");
          } else {
            dispatchTeksError("", 1, "timeout");
          }
        }
      }
    };
    mainHandler.postDelayed(timeoutTask, ms);
  }

  private void cancelTimeout() {
    try {
      if (timeoutTask != null) {
        mainHandler.removeCallbacks(timeoutTask);
        timeoutTask = null;
      }
    } catch (Exception e) {
      // abaikan
    }
  }

  private void armReadyTimeout() {
    cancelReadyTimeout();
    readyTimeoutTask = new Runnable() {
      public void run() {
        boolean fire = false;
        synchronized (lock) {
          if (!ready && webView != null) {
            fire = true;
          }
        }
        if (fire) {
          dispatch(new Runnable() {
            public void run() {
              emitCreate(true, false, 2, "backend diam (timeout)");
            }
          });
        }
      }
    };
    mainHandler.postDelayed(readyTimeoutTask, READY_TIMEOUT_MS);
  }

  private void cancelReadyTimeout() {
    try {
      if (readyTimeoutTask != null) {
        mainHandler.removeCallbacks(readyTimeoutTask);
        readyTimeoutTask = null;
      }
    } catch (Exception e) {
      // abaikan
    }
  }

  private static String mapResolution(String raw) {
    if (raw == null) {
      return null;
    }
    String r = raw.trim().toLowerCase();
    if (r.equals("512x512") || r.equals("square")) {
      return "512x512";
    }
    if (r.equals("512x768") || r.equals("portrait") || r.equals("portrait_hd")) {
      return "512x768";
    }
    if (r.equals("768x512") || r.equals("landscape") || r.equals("landscape_hd")) {
      return "768x512";
    }
    if (r.equals("768x768") || r.equals("square_hd") || r.equals("squarehd")) {
      return "768x768";
    }
    return null;
  }

  private void appendPair(String userText, String userImage, String aiText) {
    synchronized (lock) {
      mirror.add(new Turn("user", userText, userImage));
      mirror.add(new Turn("ai", aiText, ""));
      while (mirror.size() > MIRROR_CAP) {
        mirror.remove(0);
      }
    }
  }

  private final class Bridge {

    @JavascriptInterface
    public void onReady() {
      cancelReadyTimeout();
      synchronized (lock) {
        ready = true;
      }
      try {
        JSONObject o = new JSONObject();
        o.put("kind", "getHistory");
        o.put("id", HIST_ID);
        eval(o.toString());
      } catch (Exception e) {
        // abaikan
      }
      dispatch(new Runnable() {
        public void run() {
          emitCreate(true, true, 0, "siap");
        }
      });
    }

    @JavascriptInterface
    public void onTextChunk(String id, int seq, int total, String chunk) {
      final String c = chunk == null ? "" : chunk;
      boolean fire = false;
      int respId = 0;
      synchronized (lock) {
        if (busy && id.equals(activeId)
            && ("text".equals(activeKind) || "textWithImage".equals(activeKind))) {
          textAccum.append(c);
          fire = chunkMode;
          respId = activeSeq;
        }
      }
      if (fire) {
        final int fId = respId;
        dispatch(new Runnable() {
          public void run() {
            if (teks != null) {
              teks.WhenAIGenerateTeks(true, c, false, fId);
            }
            if (chatBusy && chatUi != null) {
              try {
                chatUi.pushChunk(c);
              } catch (Exception e) {
                // abaikan
              }
            }
          }
        });
      }
    }

    @JavascriptInterface
    public void onTextDone(String id, String text) {
      final String t = text == null ? "" : text;
      if (HIST_ID.equals(id)) {
        applyHistorySnapshot(t);
        return;
      }
      String kind;
      String prompt;
      String img;
      boolean wasChunk;
      int respId;
      synchronized (lock) {
        if (!busy || !id.equals(activeId)) {
          return;
        }
        kind = activeKind;
        prompt = pendingPrompt;
        img = pendingImage;
        wasChunk = chunkMode;
        respId = activeSeq;
        busy = false;
        activeKind = "";
        activeId = "";
      }
      cancelTimeout();
      String userImage = "";
      if ("textWithImage".equals(kind) && pendingIsUriSnapshot(img)) {
        userImage = img;
      }
      appendPair(prompt, userImage, wasChunk ? textAccum.toString() : t);
      if (wasChunk && textAccum.toString().length() == 0) {
        appendForced(t);
      }
      final String full = t;
      final int fId = respId;
      dispatch(new Runnable() {
        public void run() {
          if (teks != null) {
            teks.WhenAIGenerateTeks(false, full, true, fId);
          }
          if (chatBusy) {
            chatBusy = false;
            if (chatUi != null) {
              try {
                chatUi.pushDone(full);
              } catch (Exception e) {
                // abaikan
              }
            }
          }
          emitChatUi(true, 0, "ok");
        }
      });
    }

    @JavascriptInterface
    public void onError(String id, int code, String message) {
      final String msg = message == null ? "" : message;
      String kind;
      boolean batchCont = false;
      boolean batchStop = false;
      int bTotal = 0;
      int bOk = 0;
      int bFail = 0;
      YailList bFailed = null;
      synchronized (lock) {
        if (!busy || !id.equals(activeId)) {
          return;
        }
        kind = activeKind;
        if ("image".equals(kind) && batchActive) {
          if (code == 4) {
            batchActive = false;
            busy = false;
            activeKind = "";
            activeId = "";
            batchStop = true;
            bTotal = batchPrompts.size();
            bOk = batchOk;
            bFail = batchFail;
            bFailed = batchFailedIdxList();
          } else {
            batchFail++;
            batchFailedIdx.add(Integer.valueOf(batchIndex + 1));
            if (advanceBatchLocked()) {
              batchCont = true;
            } else {
              batchStop = true;
              bTotal = batchPrompts.size();
              bOk = batchOk;
              bFail = batchFail;
              bFailed = batchFailedIdxList();
            }
          }
        } else {
          busy = false;
          activeKind = "";
          activeId = "";
        }
      }
      if (!batchCont) {
        cancelTimeout();
      }
      final int c = code;
      if ("image".equals(kind)) {
        dispatch(new Runnable() {
          public void run() {
            if (image != null) {
              image.WhenAIGenerateImageError(c, msg);
            }
          }
        });
        if (batchCont) {
          evalBatchItem();
        } else if (batchStop) {
          dispatchBatchDone(bTotal, bOk, bFail, bFailed);
        }
      } else {
        dispatchTeksError("", c, msg);
      }
    }

    @JavascriptInterface
    public void onImageDone(String id, String b64, String mime) {
      final String b = b64 == null ? "" : b64;
      final String m = mime == null ? "" : mime;
      String chatP = "";
      boolean batchCont = false;
      boolean batchStop = false;
      boolean wasBatchItem = false;
      int itemNo = 0;
      int itemTotal = 0;
      int bTotal = 0;
      int bOk = 0;
      int bFail = 0;
      YailList bFailed = null;
      synchronized (lock) {
        if (!busy || !id.equals(activeId) || !"image".equals(activeKind)) {
          return;
        }
        if (batchActive) {
          wasBatchItem = true;
          itemNo = batchIndex + 1;
          itemTotal = batchPrompts.size();
          batchOk++;
          if (advanceBatchLocked()) {
            batchCont = true;
          } else {
            batchStop = true;
            bTotal = batchPrompts.size();
            bOk = batchOk;
            bFail = batchFail;
            bFailed = batchFailedIdxList();
          }
        } else {
          busy = false;
          activeKind = "";
          activeId = "";
          chatP = pendingPrompt == null ? "" : pendingPrompt;
        }
      }
      if (!batchCont) {
        cancelTimeout();
      }
      if (wasBatchItem) {
        dispatchBatchItem(itemNo, itemTotal, b, "");
      } else {
        final String fb = b;
        final String fp = chatP;
        dispatch(new Runnable() {
          public void run() {
            if (image != null) {
              image.WhenAIGenerateImage(true, fb, "");
            }
            if (chatBusy && chatUi != null) {
              chatBusy = false;
              try {
                chatUi.pushImgDone(fb, fp);
              } catch (Exception e) {
                // abaikan
              }
            }
          }
        });
      }
      if (batchCont) {
        evalBatchItem();
      } else if (batchStop) {
        dispatchBatchDone(bTotal, bOk, bFail, bFailed);
      }
    }

    @JavascriptInterface
    public void onImageChunk(String id, int seq, int total, String chunk, String mime) {
      final String c = chunk == null ? "" : chunk;
      boolean done = false;
      String full = null;
      String chatP = "";
      boolean batchCont = false;
      boolean batchStop = false;
      boolean wasBatchItem = false;
      int itemNo = 0;
      int itemTotal = 0;
      int bTotal = 0;
      int bOk = 0;
      int bFail = 0;
      YailList bFailed = null;
      synchronized (lock) {
        if (!busy || !id.equals(activeId) || !"image".equals(activeKind)) {
          return;
        }
        if (imgParts == null || imgParts.length != total || total <= 0) {
          imgParts = new String[total > 0 ? total : 1];
          imgReceived = 0;
          imgMime = mime == null ? "" : mime;
        }
        if (seq >= 0 && seq < imgParts.length && imgParts[seq] == null) {
          imgParts[seq] = c;
          imgReceived++;
        }
        if (imgReceived >= imgParts.length) {
          StringBuilder sb = new StringBuilder();
          for (int i = 0; i < imgParts.length; i++) {
            if (imgParts[i] != null) {
              sb.append(imgParts[i]);
            }
          }
          full = sb.toString();
          imgParts = null;
          imgReceived = 0;
          if (batchActive) {
            wasBatchItem = true;
            itemNo = batchIndex + 1;
            itemTotal = batchPrompts.size();
            batchOk++;
            if (advanceBatchLocked()) {
              batchCont = true;
            } else {
              batchStop = true;
              bTotal = batchPrompts.size();
              bOk = batchOk;
              bFail = batchFail;
              bFailed = batchFailedIdxList();
            }
          } else {
            busy = false;
            activeKind = "";
            activeId = "";
            chatP = pendingPrompt == null ? "" : pendingPrompt;
          }
          done = true;
        }
      }
      if (done) {
        if (!batchCont) {
          cancelTimeout();
        }
        if (wasBatchItem) {
          dispatchBatchItem(itemNo, itemTotal, full, "");
        } else {
          final String f = full;
          final String fp = chatP;
          dispatch(new Runnable() {
            public void run() {
              if (image != null) {
                image.WhenAIGenerateImage(true, f, "");
              }
              if (chatBusy && chatUi != null) {
                chatBusy = false;
                try {
                  chatUi.pushImgDone(f, fp);
                } catch (Exception e) {
                  // abaikan
                }
              }
            }
          });
        }
        if (batchCont) {
          evalBatchItem();
        } else if (batchStop) {
          dispatchBatchDone(bTotal, bOk, bFail, bFailed);
        }
      }
    }

    @JavascriptInterface
    public void onImageUrl(String id, String url) {
      final String u = url == null ? "" : url;
      String chatP = "";
      boolean batchCont = false;
      boolean batchStop = false;
      boolean wasBatchItem = false;
      int itemNo = 0;
      int itemTotal = 0;
      int bTotal = 0;
      int bOk = 0;
      int bFail = 0;
      YailList bFailed = null;
      synchronized (lock) {
        if (!busy || !id.equals(activeId) || !"image".equals(activeKind)) {
          return;
        }
        if (batchActive) {
          wasBatchItem = true;
          itemNo = batchIndex + 1;
          itemTotal = batchPrompts.size();
          batchOk++;
          if (advanceBatchLocked()) {
            batchCont = true;
          } else {
            batchStop = true;
            bTotal = batchPrompts.size();
            bOk = batchOk;
            bFail = batchFail;
            bFailed = batchFailedIdxList();
          }
        } else {
          busy = false;
          activeKind = "";
          activeId = "";
          chatP = pendingPrompt == null ? "" : pendingPrompt;
        }
      }
      if (!batchCont) {
        cancelTimeout();
      }
      if (wasBatchItem) {
        dispatchBatchItem(itemNo, itemTotal, "", u);
      } else {
        final String fu = u;
        final String fp = chatP;
        dispatch(new Runnable() {
          public void run() {
            if (image != null) {
              image.WhenAIGenerateImage(true, "", fu);
            }
            if (chatBusy && chatUi != null) {
              chatBusy = false;
              try {
                chatUi.pushImgDone(fu, fp);
              } catch (Exception e) {
                // abaikan
              }
            }
          }
        });
      }
      if (batchCont) {
        evalBatchItem();
      } else if (batchStop) {
        dispatchBatchDone(bTotal, bOk, bFail, bFailed);
      }
    }
  }

  private boolean pendingIsUriSnapshot(String img) {
    synchronized (lock) {
      return pendingIsUri;
    }
  }

  private void appendForced(String t) {
    // Ganti AI terakhir bila akumulasi chunk kosong tapi done membawa teks.
    synchronized (lock) {
      if (mirror.size() >= 2) {
        Turn last = mirror.get(mirror.size() - 1);
        if ("ai".equals(last.role) && last.text.length() == 0) {
          last.text = t;
        }
      }
    }
  }

  private void applyHistorySnapshot(String json) {
    final ArrayList<Turn> fresh = new ArrayList<Turn>();
    try {
      JSONArray arr = new JSONArray(json);
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o == null) {
          continue;
        }
        String role = o.optString("role", "user");
        if (!"ai".equals(role)) {
          role = "user";
        }
        fresh.add(new Turn(role, o.optString("text", ""), o.optString("image", "")));
      }
    } catch (Exception e) {
      return;
    }
    boolean needClear = false;
    synchronized (lock) {
      if (clearPending) {
        clearPending = false;
        mirror.clear();
        needClear = true;
      } else {
        mirror.clear();
        int start = Math.max(0, fresh.size() - MIRROR_CAP);
        for (int i = start; i < fresh.size(); i++) {
          mirror.add(fresh.get(i));
        }
      }
    }
    if (needClear) {
      sendKindOnly("clearHistory");
    }
  }
}
