package com.norvex.chatbot;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.UsesPermissions;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.util.YailList;

@DesignerComponent(
    version = 1,
    versionName = "1.0",
    description = "AI teks: teruskan prompt ke halaman backend (hidden WebView) + terima jawaban. "
        + "Wajib CallCreateChatbot dulu (tanpa input), tunggu WhenChatbotCreate(success=true, ready=true). "
        + "Single-flight: 1 kerjaan dalam 1 waktu, sibuk = error 3. History hanya bila AutoHistory=true.",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    androidMinSdk = 21)
@SimpleObject(external = true)
@UsesPermissions({"android.permission.INTERNET"})
public class AiTeks extends AndroidNonvisibleComponent {

  private final ChatbotEngine engine;

  public AiTeks(ComponentContainer container) {
    super(container.$form());
    engine = ChatbotEngine.get(container, this, null);
    engine.attachTeks(this);
  }

  // ---------------- E0 / C0 / C0b / I0 ----------------

  @SimpleEvent(description = "Hasil CallCreateChatbot. success = WebView+halaman termuat; "
      + "ready = sinyal backend (penerjemah+plugin+history siap). Siap penuh = keduanya true.")
  public void WhenChatbotCreate(boolean success, boolean ready, int errorCode, String message) {
  }

  @SimpleFunction(description = "CALL: bangun WebView tersembunyi + muat halaman backend SEKALI. "
      + "Tanpa input apa pun. Panggil sekali di awal (mis. Screen Initialize).")
  public void CallCreateChatbot() {
    engine.callCreate();
  }

  @SimpleFunction(description = "CALL: buang WebView + bersihkan sesi dan mirror history.")
  public void RemoveChatbot() {
    engine.callRemove();
  }

  @SimpleFunction(description = "IS: true bila backend sudah ready (siap terima generate).")
  public boolean IsChatbotReady() {
    return engine.isReady();
  }

  // ---------------- E1 / E2 / C1 / C2 / C3 / I1 ----------------

  @SimpleEvent(description = "Jawaban AI teks. Streaming: ischunk=true per potong (isfinished=false), "
      + "lalu sekali ischunk=false + respon penuh + isfinished=true. v1: utuh dulu bila chunk=false.")
  public void WhenAIGenerateTeks(boolean ischunk, String respon, boolean isfinished) {
  }

  @SimpleEvent(description = "Galat AI teks. code: 1=timeout, 2=belum siap, 3=sibuk, "
      + "4=dibatalkan, 5=salah halaman, 6=upload gagal.")
  public void WhenAIGenerateTeksError(int code, String message) {
  }

  @SimpleFunction(description = "CALL: kirim prompt teks. chunk=true minta streaming via E1. "
      + "AutoHistory=true: cukup 1 string, halaman yang tambah giliran.")
  public void CallGenerateTeks(String prompt, boolean chunk) {
    engine.callGenerateTeks(prompt, chunk);
  }

  @SimpleFunction(description = "CALL: teks + 1 gambar (FASE 2 vision). image = https URL langsung, "
      + "atau path / content:// (extension baca -> halaman jadi Blob -> AI). isUri=true untuk URL/uri.")
  public void CallGenerateTeksWithImage(String prompt, boolean isUri, String image) {
    engine.callGenerateTeksWithImage(prompt, isUri, image);
  }

  @SimpleFunction(description = "CALL: batalkan generate teks yang berjalan. Sukses -> error 4.")
  public void CallCancelTeks() {
    engine.callCancelTeks();
  }

  @SimpleFunction(description = "IS: true bila generate teks sedang berjalan (kunci tombol kirim).")
  public boolean IsGeneratingTeks() {
    return engine.isGenerating();
  }

  // ---------------- S1 / S2 ----------------

  @SimpleFunction(description = "SET: system prompt. Berlaku TERAKHIR, nempel otomatis ke Call "
      + "berikut, ke depan saja. Boleh dipanggil berkali-kali.")
  public void SetSystemPrompt(String text) {
    engine.setSystemPrompt(text);
  }

  @SimpleFunction(description = "SET: true = history dipegang halaman (1 string cukup). "
      + "false = manual: kelola sendiri di global var/TinyDB, H-blok diabaikan.")
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

  @SimpleFunction(description = "Hapus giliran N (1-based): input #N + output #N ikut hilang.")
  public void RemoveInputUser(double index) {
    engine.removePair((int) Math.floor(index));
  }

  @SimpleFunction(description = "Hapus giliran N (1-based): input #N + output #N ikut hilang.")
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
