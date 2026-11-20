package com.norvex.chatbot;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.appinventor.components.runtime.ActivityResultListener;
import com.google.appinventor.components.runtime.AndroidViewComponent;
import com.google.appinventor.components.runtime.Form;
import com.google.appinventor.components.runtime.util.YailDictionary;
import com.google.appinventor.components.runtime.util.YailList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * UI chat HTML (teks + gambar) di sisi aplikasi untuk pemula.
 * WebView milik extension, dipasang di Arrangement MIT. Bicara ke engine
 * lewat bridge window.Android; engine dorong update lewat evaluateJavascript
 * (window.norvex). Gambar vision di-downscale di JS (<=1280px) sebelum
 * lewat bridge agar aman dari batas Binder.
 */
final class ChatUiWeb {

  private final Form form;
  private final Context context;
  private final ChatbotEngine engine;

  private WebView webView;
  private boolean loaded = false;
  private ValueCallback<Uri[]> fileCallback;
  private int fileRequestCode = -1;
  private ActivityResultListener fileListener;

  ChatUiWeb(Form form, Context context, ChatbotEngine engine) {
    this.form = form;
    this.context = context;
    this.engine = engine;
  }

  /** Bangun + pasang ke wadah. Panggil di UI thread. */
  void build(ViewGroup parent) {
    detach();
    WebView wv = new WebView(context);
    try {
      wv.getSettings().setJavaScriptEnabled(true);
      wv.getSettings().setDomStorageEnabled(true);
      wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
      wv.getSettings().setSupportZoom(false);
      wv.getSettings().setBuiltInZoomControls(false);
      wv.getSettings().setDisplayZoomControls(false);
    } catch (Exception e) {
      // abaikan
    }
    wv.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    wv.addJavascriptInterface(new UiBridge(), "Android");
    wv.setWebViewClient(new WebViewClient() {
      @Override
      public void onPageFinished(WebView view, String url) {
        loaded = true;
        try {
          engine.onChatUiLoaded();
        } catch (Exception e) {
          // abaikan
        }
      }
    });
    wv.setWebChromeClient(new WebChromeClient() {
      @Override
      public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
          FileChooserParams params) {
        return openChooser(callback, params);
      }
    });
    try {
      parent.addView(wv);
    } catch (Exception e) {
      try {
        wv.destroy();
      } catch (Exception e2) {
        // abaikan
      }
      throw new RuntimeException(String.valueOf(e.getMessage()));
    }
    webView = wv;
    try {
      wv.loadDataWithBaseURL("https://norvex.local/", ChatUiHtml.page(),
          "text/html", "UTF-8", null);
    } catch (Exception e) {
      // abaikan, onPageFinished tetap dipanggil
    }
  }

  /** Lepas + hancurkan. Aman dipanggil kapan pun (UI thread). */
  void detach() {
    cancelChooser();
    unregisterChooser();
    try {
      if (webView != null) {
        if (webView.getParent() instanceof ViewGroup) {
          ((ViewGroup) webView.getParent()).removeView(webView);
        }
        webView.removeJavascriptInterface("Android");
        webView.destroy();
      }
    } catch (Exception e) {
      // abaikan
    }
    webView = null;
    loaded = false;
  }

  boolean attached() {
    return webView != null;
  }

  // ---------------- Java -> JS (boleh dari thread mana pun) ----------------

  void pushHistory(final YailList history) {
    postUi(new Runnable() {
      public void run() {
        eval("window.norvex&&window.norvex.push(" + toJsonArray(history) + ");");
      }
    });
  }

  void pushChunk(final String c) {
    final String q = quote(c);
    postUi(new Runnable() {
      public void run() {
        eval("window.norvex&&window.norvex.chunk(" + q + ");");
      }
    });
  }

  void pushDone(final String full) {
    final String q = quote(full);
    postUi(new Runnable() {
      public void run() {
        eval("window.norvex&&window.norvex.done(" + q + ");");
      }
    });
  }

  void pushError(final int code, final String msg) {
    final String q = quote(msg == null ? "" : msg);
    postUi(new Runnable() {
      public void run() {
        eval("window.norvex&&window.norvex.error(" + code + "," + q + ");");
      }
    });
  }

  void pushImgDone(final String url, final String prompt) {
    final String u = quote(url == null ? "" : url);
    final String p = quote(prompt == null ? "" : prompt);
    postUi(new Runnable() {
      public void run() {
        eval("window.norvex&&window.norvex.imgDone(" + u + "," + p + ");");
      }
    });
  }

  void pushImgError(final int code, final String msg) {
    final String q = quote(msg == null ? "" : msg);
    postUi(new Runnable() {
      public void run() {
        eval("window.norvex&&window.norvex.imgError(" + code + "," + q + ");");
      }
    });
  }

  void pushState(final boolean ready) {
    postUi(new Runnable() {
      public void run() {
        eval("window.norvex&&window.norvex.state(" + (ready ? "true" : "false") + ");");
      }
    });
  }

  private void postUi(Runnable r) {
    try {
      form.runOnUiThread(r);
    } catch (Exception e) {
      // abaikan
    }
  }

  private void eval(String js) {
    try {
      WebView wv = webView;
      if (wv != null) {
        wv.evaluateJavascript(js, null);
      }
    } catch (Exception e) {
      // abaikan
    }
  }

  private static String quote(String s) {
    try {
      return org.json.JSONObject.quote(s == null ? "" : s);
    } catch (Exception e) {
      return "\"\"";
    }
  }

  private static String toJsonArray(YailList history) {
    StringBuilder sb = new StringBuilder("[");
    try {
      if (history != null) {
        Object[] arr = history.toArray();
        boolean first = true;
        for (int i = 0; i < arr.length; i++) {
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
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("{\"role\":").append(quote(role))
              .append(",\"text\":").append(quote(text))
              .append(",\"image\":").append(quote(image)).append("}");
        }
      }
    } catch (Exception e) {
      // abaikan, kirim sebisanya
    }
    sb.append("]");
    return sb.toString();
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

  // ---------------- JS -> Java ----------------

  private final class UiBridge {
    @android.webkit.JavascriptInterface
    public void uiReady() {
      try {
        engine.onChatUiLoaded();
      } catch (Exception e) {
        // abaikan
      }
    }

    @android.webkit.JavascriptInterface
    public void uiSend(String text) {
      try {
        engine.callGenerateTeks(text == null ? "" : text, true);
      } catch (Exception e) {
        // abaikan
      }
    }

    @android.webkit.JavascriptInterface
    public void uiVision(String dataUrl, String prompt) {
      try {
        String d = dataUrl == null ? "" : dataUrl;
        String p = prompt == null ? "" : prompt;
        if (!d.regionMatches(true, 0, "data:image/", 0, 11)) {
          engine.dispatchTeksErrorPublic(5, "gambar tidak terbaca");
          return;
        }
        if (p.length() == 0) {
          engine.dispatchTeksErrorPublic(5, "prompt/image kosong");
          return;
        }
        engine.callGenerateTeksWithImage(p, false, d);
      } catch (Exception e) {
        // abaikan
      }
    }

    @android.webkit.JavascriptInterface
    public void uiGenImage(String prompt, String nPrompt, String seed,
        String resolution, String guidance) {
      try {
        String p = prompt == null ? "" : prompt;
        String n = nPrompt == null ? "" : nPrompt;
        String r = resolution == null || resolution.length() == 0 ? "512x512" : resolution;
        double s;
        try {
          s = Double.parseDouble(seed);
        } catch (Exception e) {
          s = -1;
        }
        double g;
        try {
          g = Double.parseDouble(guidance);
        } catch (Exception e) {
          g = 7;
        }
        engine.callGenerateImage(p, n, s, r, g, false);
      } catch (Exception e) {
        // abaikan
      }
    }

    @android.webkit.JavascriptInterface
    public void uiSetSystem(String text) {
      try {
        engine.setSystemPrompt(text == null ? "" : text);
      } catch (Exception e) {
        // abaikan
      }
    }

    @android.webkit.JavascriptInterface
    public void uiClear() {
      try {
        engine.clearHistory();
      } catch (Exception e) {
        // abaikan
      }
    }

    @android.webkit.JavascriptInterface
    public void uiDownload(String url, String name) {
      try {
        download(url == null ? "" : url, name == null ? "" : name);
      } catch (Exception e) {
        // abaikan
      }
    }
  }

  // ---------------- file chooser (upload gambar) ----------------

  private boolean openChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
    try {
      cancelChooser();
      fileCallback = callback;
      Intent intent = null;
      try {
        intent = params.createIntent();
      } catch (Exception e) {
        intent = null;
      }
      if (intent == null) {
        intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
      }
      final ValueCallback<Uri[]> cb = callback;
      fileListener = new ActivityResultListener() {
        public void resultReturned(int requestCode, int resultCode, Intent data) {
          if (requestCode != fileRequestCode) {
            return;
          }
          Uri[] uris = null;
          try {
            if (resultCode == Activity.RESULT_OK && data != null) {
              if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
              } else if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                ArrayList<Uri> list = new ArrayList<Uri>();
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                  try {
                    Uri u = data.getClipData().getItemAt(i).getUri();
                    if (u != null) {
                      list.add(u);
                    }
                  } catch (Exception e) {
                    // abaikan item rusak
                  }
                }
                if (!list.isEmpty()) {
                  uris = list.toArray(new Uri[list.size()]);
                }
              }
            }
          } catch (Exception e) {
            uris = null;
          }
          try {
            cb.onReceiveValue(uris);
          } catch (Exception e) {
            // abaikan
          }
          cancelChooser();
        }
      };
      fileRequestCode = form.registerForActivityResult(fileListener);
      Intent chooser = Intent.createChooser(intent, "Pilih gambar");
      try {
        ((Activity) context).startActivityForResult(chooser, fileRequestCode);
      } catch (Exception e) {
        cancelChooser();
        try {
          callback.onReceiveValue(null);
        } catch (Exception e2) {
          // abaikan
        }
        return false;
      }
      return true;
    } catch (Exception e) {
      try {
        callback.onReceiveValue(null);
      } catch (Exception e2) {
        // abaikan
      }
      return false;
    }
  }

  private void cancelChooser() {
    try {
      if (fileCallback != null) {
        ValueCallback<Uri[]> cb = fileCallback;
        fileCallback = null;
        try {
          cb.onReceiveValue(null);
        } catch (Exception e) {
          // abaikan
        }
      }
    } catch (Exception e) {
      // abaikan
    }
    fileCallback = null;
  }

  private void unregisterChooser() {
    try {
      if (fileListener != null) {
        form.unregisterForActivityResult(fileListener);
      }
    } catch (Exception e) {
      // abaikan
    }
    fileListener = null;
    fileRequestCode = -1;
  }

  // ---------------- unduh gambar ----------------

  private void download(String url, String name) {
    String n = name == null ? "" : name.trim();
    if (n.length() == 0) {
      n = "norvex-" + System.currentTimeMillis() + ".jpg";
    }
    n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
    final String fname = n;
    try {
      if (url.regionMatches(true, 0, "http", 0, 4)) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
          toast("Unduh gagal: layanan unduhan tidak ada");
          return;
        }
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        try {
          req.setTitle(fname);
          req.setNotificationVisibility(
              DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
          req.setDestinationInExternalPublicDir(
              Environment.DIRECTORY_DOWNLOADS, fname);
        } catch (Exception e) {
          // abaikan, lanjut enqueue
        }
        dm.enqueue(req);
        toast("Mengunduh " + fname);
        return;
      }
      if (url.regionMatches(true, 0, "data:image/", 0, 11)) {
        int ci = url.indexOf(',');
        if (ci < 0) {
          toast("Unduh gagal: data rusak");
          return;
        }
        byte[] data;
        try {
          data = Base64.decode(url.substring(ci + 1), Base64.DEFAULT);
        } catch (Exception e) {
          toast("Unduh gagal: data rusak");
          return;
        }
        if (saveImageBytes(data, fname)) {
          toast("Tersimpan: " + fname);
        } else {
          toast("Unduh gagal");
        }
        return;
      }
      toast("Unduh gagal: format tak dikenal");
    } catch (Exception e) {
      toast("Unduh gagal");
    }
  }

  private boolean saveImageBytes(byte[] data, String fname) {
    if (data == null || data.length == 0) {
      return false;
    }
    String mime = "image/jpeg";
    String low = fname.toLowerCase();
    if (low.endsWith(".png")) {
      mime = "image/png";
    } else if (low.endsWith(".webp")) {
      mime = "image/webp";
    }
    if (Build.VERSION.SDK_INT >= 29) {
      try {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, fname);
        v.put(MediaStore.Images.Media.MIME_TYPE, mime);
        v.put(MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/Norvex");
        v.put(MediaStore.Images.Media.IS_PENDING, Integer.valueOf(1));
        Uri uri = context.getContentResolver().insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
          return false;
        }
        OutputStream os = null;
        try {
          os = context.getContentResolver().openOutputStream(uri);
          if (os == null) {
            return false;
          }
          os.write(data);
        } finally {
          try {
            if (os != null) {
              os.close();
            }
          } catch (Exception e) {
            // abaikan
          }
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, Integer.valueOf(0));
        context.getContentResolver().update(uri, v, null, null);
        return true;
      } catch (Exception e) {
        return false;
      }
    }
    try {
      File dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
      if (dir == null) {
        return false;
      }
      File out = new File(dir, fname);
      FileOutputStream fos = null;
      try {
        fos = new FileOutputStream(out);
        fos.write(data);
      } finally {
        try {
          if (fos != null) {
            fos.close();
          }
        } catch (Exception e) {
          // abaikan
        }
      }
      toast("Tersimpan di: " + out.getAbsolutePath());
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private void toast(final String msg) {
    try {
      form.runOnUiThread(new Runnable() {
        public void run() {
          try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
          } catch (Exception e) {
            // abaikan
          }
        }
      });
    } catch (Exception e) {
      // abaikan
    }
  }

  /** Wadah MIT -> ViewGroup (pola sama seperti Tools_Image). */
  static ViewGroup groupOf(AndroidViewComponent container) {
    if (container == null) {
      return null;
    }
    try {
      View v = container.getView();
      if (v instanceof ViewGroup) {
        return (ViewGroup) v;
      }
    } catch (Exception e) {
      // abaikan
    }
    return null;
  }
}
