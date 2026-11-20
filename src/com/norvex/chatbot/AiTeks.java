package com.norvex.chatbot;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.UsesPermissions;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.AndroidViewComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.util.YailList;@DesignerComponent(
    version = 2,
    versionName = "2.0",
    description = "AI teks: teruskan prompt ke halaman backend (hidden WebView) + terima jawaban. "
        + "Wajib CallCreateChatbot dulu (tanpa input), tunggu WhenChatbotCreate(success=true, ready=true). "
        + "Single-flight: 1 kerjaan dalam 1 waktu, sibuk = error 3. History hanya bila AutoHistory=true. "
        + "PEMULA: cukup 4 blok — CallCreateChatbot (di Screen.Initialize) → tunggu ready=true → "
        + "CallGenerateTeks(prompt, false) → WhenAIGenerateTeks ambil respon. History otomatis, abaikan blok H.",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    androidMinSdk = 21)
@SimpleObject(external = true)
@UsesPermissions({"android.permission.INTERNET",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES"})
public class AiTeks extends AndroidNonvisibleComponent {

  private final ChatbotEngine engine;

  public AiTeks(ComponentContainer container) {
    super(container.$form());
    engine = ChatbotEngine.get(container, this, null);
    engine.attachTeks(this);
  }

  // ---------------- E0 / C0 / C0b / I0 ----------------

  @SimpleEvent(description = "Hasil CallCreateChatbot. success = WebView+halaman termuat; "
      + "ready = sinyal backend (penerjemah+plugin+history siap). Siap penuh = keduanya true. "
      + "success=true tapi ready=false = halaman termuat, tunggu event berikut (backend siap).")
  public void WhenChatbotCreate(boolean success, boolean ready, int errorCode, String message) {
  }

  @SimpleFunction(description = "CALL: bangun WebView tersembunyi + muat halaman backend SEKALI. "
      + "Tanpa input apa pun. Panggil sekali di awal (mis. Screen Initialize).")
  public void CallCreateChatbot() {
    engine.callCreate();
  }

  @SimpleFunction(description = "CALL: buang WebView + bersihkan sesi dan mirror history. "
      + "Jarang dipakai (mis. mau mulai bersih total); pemula abaikan.")
  public void RemoveChatbot() {
    engine.callRemove();
  }

  @SimpleFunction(description = "IS: true bila backend sudah ready (siap terima generate).")
  public boolean IsChatbotReady() {
    return engine.isReady();
  }

  // ---------------- E1 / E2 / C1 / C2 / C3 / I1 ----------------

  @SimpleEvent(description = "Jawaban AI teks + id respon (angka 1,2,3... tiap Call). "
      + "ischunk=true = potongan (tambah ke item ber-id itu); isfinished=true = final penuh. "
      + "Semua event 1x generate membawa id SAMA. "
      + "Pola: simpan id terakhir di variabel; chunk + id sama = ganti/isi item itu; "
      + "chunk + id beda = set variabel = id + tambah item baru; "
      + "isfinished = penanda selesai (mis. tombol boleh ditekan lagi). "
      + "Cocokkan id dengan CurrentResponseId untuk tahu ini respon terbaru.")
  public void WhenAIGenerateTeks(boolean ischunk, String respon, boolean isfinished, int id) {
  }

  @SimpleEvent(description = "Galat AI teks. code: 1=timeout, 2=belum siap, 3=sibuk, "
      + "4=dibatalkan, 5=salah halaman, 6=upload gagal.")
  public void WhenAIGenerateTeksError(int code, String message) {
  }

  @SimpleFunction(description = "CALL: kirim prompt teks. PEMULA isi chunk=false: "
      + "jawaban datang SEKALI utuh di E1 (abaikan soket ischunk/id/isfinished). "
      + "chunk=true hanya bila mau streaming kata-per-kata. "
      + "AutoHistory=true: cukup 1 string, halaman yang tambah giliran.")
  public void CallGenerateTeks(String prompt, boolean chunk) {
    engine.callGenerateTeks(prompt, chunk);
  }

  @SimpleFunction(description = "CALL: teks + 1 gambar. isUri=true bila image berupa "
      + "link https (mis. https://.../foto.jpg); isUri=false bila gambar dari HP "
      + "(path / file:// / content:// — extension BACAKAN otomatis jadi base64, maks 20 MB). "
      + "Gambar link disimpan di history; gambar dari HP tidak disimpan.")
  public void CallGenerateTeksWithImage(String prompt, boolean isUri, String image) {
    engine.callGenerateTeksWithImage(prompt, isUri, image);
  }

  @SimpleFunction(description = "CALL: batalkan generate teks yang berjalan. Langsung berhenti, "
      + "error 4 seketika (tidak menunggu halaman).")
  public void CallCancelTeks() {
    engine.callCancelTeks();
  }

  @SimpleFunction(description = "CALL/UI: buatkan UI chat jadi (teks + gambar, markdown, "
      + "upload vision, setting system prompt, unduh gambar) di dalam Arrangement kosong "
      + "(mis. VerticalArrangement). historyBackup = list-of-dict [{role,text,image}] dari "
      + "TinyDB (boleh list kosong = mulai baru). Backend ikut dibuat otomatis bila belum ada; "
      + "cukup panggil blok ini di Screen.Initialize. Panggil ulang untuk pindah/reset tampilan. "
      + "Backup terbaru selalu dikirim via WhenChatUI (simpan ke TinyDB di sana). "
      + "Ikut terbuang saat RemoveChatbot.")
  public void SetupChatUI(AndroidViewComponent container, YailList historyBackup) {
    engine.setupChatUi(container, historyBackup);
  }

  @SimpleEvent(description = "Event UI chat + BACKUP. success = hasil pasang UI; "
      + "historyBackup = snapshot history terbaru (list-of-dict, TinyDB-ready) — "
      + "SIMPAN ke TinyDB di sini, kirim balik via SetupChatUI saat aplikasi dibuka lagi. "
      + "Ditembak saat: UI siap, tiap jawaban/error teks, tiap H-blok (remove/replace/clear). "
      + "Gambar hasil generate TIDAK ikut backup (stateless). "
      + "success=false + errorCode = pesan galat (mis. wadah tidak valid).")
  public void WhenChatUI(boolean success, YailList historyBackup, int errorCode, String message) {
  }

  @SimpleFunction(description = "IS: true bila generate teks sedang berjalan (kunci tombol kirim).")
  public boolean IsGeneratingTeks() {
    return engine.isGenerating();
  }

  @SimpleFunction(description = "IS/GET: id respon generate terakhir/berjalan (0 bila belum pernah). "
      + "Bandingkan dengan id di E1: sama = respon terbaru; beda = respon lama, abaikan.")
  public int CurrentResponseId() {
    return engine.currentResponseId();
  }

  // ---------------- S1 / S2 ----------------

  @SimpleFunction(description = "SET: system prompt. Berlaku TERAKHIR, nempel otomatis ke Call "
      + "berikut, ke depan saja. Boleh dipanggil berkali-kali.")
  public void SetSystemPrompt(String text) {
    engine.setSystemPrompt(text);
  }

  @SimpleFunction(description = "SET: true = history dipegang halaman (1 string cukup). "
      + "false = manual: kelola sendiri di global var/TinyDB, H-blok diabaikan. "
      + "Default true — PEMULA jangan diubah dan abaikan semua blok H.")
  public void SetAutoHistory(boolean enabled) {
    engine.setAutoHistory(enabled);
  }

  // ---------------- H1-H8 (hanya AutoHistory=true) ----------------

  @SimpleFunction(description = "Daftar giliran: list of dict {role (user/ai), text, image}.")
  public YailList GetAllHistory() {
    return engine.getAllHistory();
  }

  @SimpleFunction(description = "List teks jawaban AI saja, urut giliran.")
  public YailList GetHistoryResponAI() {
    return engine.getHistoryResponAI();
  }

  @SimpleFunction(description = "List teks input user saja, urut giliran.")
  public YailList GetHistoryInputUser() {
    return engine.getHistoryInputUser();
  }

  @SimpleFunction(description = "GET: jumlah giliran (pasangan user+AI) di history cermin "
      + "(0 bila kosong). Batas valid N untuk blok Remove/Replace (H4-H7, 1-based).")
  public int GetGiliranCount() {
    return engine.getPairCount();
  }

  @SimpleFunction(description = "Hapus giliran N (1-based): input #N + output #N ikut hilang. "
      + "Efek SAMA dengan RemoveOutputAI (hapus 1 giliran penuh, bukan satu sisi).")
  public void RemoveInputUser(double index) {
    engine.removePair((int) Math.floor(index));
  }

  @SimpleFunction(description = "Hapus giliran N (1-based): input #N + output #N ikut hilang. "
      + "Efek SAMA dengan RemoveInputUser (hapus 1 giliran penuh, bukan satu sisi).")
  public void RemoveOutputAI(double index) {
    engine.removePair((int) Math.floor(index));
  }

  @SimpleFunction(description = "Ganti teks input user giliran N (1-based).")
  public void ReplaceInputUser(double index, String text) {
    engine.replaceUser((int) Math.floor(index), text);
  }

  @SimpleFunction(description = "Ganti teks output AI giliran N (1-based).")
  public void ReplaceOutputAI(double index, String text) {
    engine.replaceAi((int) Math.floor(index), text);
  }

  @SimpleFunction(description = "Hapus seluruh history (chat baru).")
  public void ClearHistory() {
    engine.clearHistory();
  }
}
