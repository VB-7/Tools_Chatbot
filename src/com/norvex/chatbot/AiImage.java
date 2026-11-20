package com.norvex.chatbot;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.Options;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.UsesPermissions;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.util.YailList;

@DesignerComponent(
    version = 4,
    versionName = "1.3",
    description = "AI image (stateless, tanpa history/cache/storage lokal). "
        + "Panggil CallCreateChatbot dulu (milik komponen ini sendiri, mesin sama dengan AiTeks) "
        + "lalu tunggu WhenChatbotCreate ready=true. Bisa dipakai TANPA AiTeks. "
        + "PEMULA: pakai base64=false → E3 bawa url, langsung pasang ke Image.Picture.",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    androidMinSdk = 21)
@SimpleObject(external = true)
@UsesPermissions({"android.permission.INTERNET"})
public class AiImage extends AndroidNonvisibleComponent {

  private final ChatbotEngine engine;

  public AiImage(ComponentContainer container) {
    super(container.$form());
    engine = ChatbotEngine.get(container, null, this);
    engine.attachImage(this);
  }

  // ---------------- E0 / C0 / C0b / I0 (mandiri, mesin sama dengan AiTeks) ----------------

  @SimpleEvent(description = "Hasil CallCreateChatbot. success = WebView+halaman termuat; "
      + "ready = sinyal backend. Siap penuh = keduanya true. "
      + "Cukup panggil Create sekali dari AiTeks ATAU AiImage (mesin sama).")
  public void WhenChatbotCreate(boolean success, boolean ready, int errorCode, String message) {
  }

  @SimpleFunction(description = "CALL: bangun WebView tersembunyi + muat halaman backend SEKALI. "
      + "Tanpa input apa pun. Panggil sekali di awal (mis. Screen Initialize). "
      + "Cukup dari salah satu komponen saja.")
  public void CallCreateChatbot() {
    engine.callCreate();
  }

  @SimpleFunction(description = "CALL: buang WebView + bersihkan sesi.")
  public void RemoveChatbot() {
    engine.callRemove();
  }

  @SimpleFunction(description = "IS: true bila backend sudah ready (siap terima generate).")
  public boolean IsChatbotReady() {
    return engine.isReady();
  }

  // ---------------- E3 / E4 / C4 / C5 / I2 ----------------

  @SimpleEvent(description = "Hasil gambar single (isfinished=true sekali). url terisi "
      + "bila Call pakai base64=false (PEMULA pakai ini: url langsung pasang ke Image.Picture); "
      + "base64 terisi bila base64=true — isinya MENTAH tanpa awalan data:, untuk tampil "
      + "harus digabung dulu (mis. data:image/png;base64, + base64). "
      + "HANYA untuk single CallGenerateImage — saat batch, hasil per gambar lewat "
      + "WhenAIGenerateImageBatchItem (ada index), bukan ke sini.")
  public void WhenAIGenerateImage(boolean isfinished, String base64, String url) {
  }

  @SimpleEvent(description = "Hasil 1 gambar batch: index = nomor prompt (1-based), "
      + "total = jumlah prompt. base64/url sama seperti E3. E3 TIDAK ditembak saat batch.")
  public void WhenAIGenerateImageBatchItem(int index, int total, String base64, String url) {
  }

  @SimpleEvent(description = "Galat AI image. code: 1=timeout, 2=belum siap, 3=sibuk, "
      + "4=dibatalkan, 5=salah halaman (mis. resolusi), 6=upload gagal. "
      + "Saat batch: tiap item gagal menembak ini (tanpa index) lalu lanjut berikut; "
      + "daftar nomor yang gagal ada di failedIndexes WhenAIGenerateImageBatchDone.")
  public void WhenAIGenerateImageError(int code, String message) {
  }

  @SimpleEvent(description = "Batch selesai (semua / sebagian / dibatalkan): total = jumlah "
      + "prompt, success = gambar jadi, failed = gagal/timeout, failedIndexes = list nomor "
      + "prompt (1-based) yang gagal. Tiap gambar jadi sudah ditembak satu per satu "
      + "via WhenAIGenerateImageBatchItem.")
  public void WhenAIGenerateImageBatchDone(int total, int success, int failed, YailList failedIndexes) {
  }

  @SimpleFunction(description = "CALL: prompt + n_prompt (negative, kosongkan bila tidak ada) "
      + "+ seed (-1 = acak; pemula biarkan -1) + resolution [dropdown: Square/Portrait/Landscape/SquareHd] "
      + "+ guidance (1-30; pemula biarkan 7) + base64 flag (PEMULA: false → dapat url siap tampil).")
  public void CallGenerateImage(String prompt, String nPrompt, double seed,
      @Options(Resolution.class) String resolution, double guidance, boolean base64) {
    engine.callGenerateImage(prompt, nPrompt, seed, resolution, guidance, base64);
  }

  @SimpleFunction(description = "CALL batch: generate BANYAK gambar sekaligus dari daftar prompt "
      + "(list teks, maks 20, tanpa yang kosong). n_prompt/seed/resolution/guidance/base64 "
      + "berlaku sama untuk semua. Dijalankan satu per satu (tetap single-flight: "
      + "sibuk -> error 3; timeout 180 dtk PER item, gagal -> lanjut berikut). Tiap gambar jadi "
      + "menembak WhenAIGenerateImageBatchItem(index, total, ...) — pasang ke slot bernomor "
      + "itu. Selesai semua menembak WhenAIGenerateImageBatchDone. Batalkan via CallCancelImage.")
  public void CallGenerateImageBatch(YailList prompts, String nPrompt, double seed,
      @Options(Resolution.class) String resolution, double guidance, boolean base64) {
    engine.callGenerateImageBatch(prompts, nPrompt, seed, resolution, guidance, base64);
  }

  @SimpleFunction(description = "CALL: batalkan generate/batch image yang berjalan. Langsung "
      + "berhenti, error 4 seketika (batch ikut berhenti + BatchDone hasil sejauh itu).")
  public void CallCancelImage() {
    engine.callCancelImage();
  }

  @SimpleFunction(description = "IS: true bila generate image/batch sedang berjalan.")
  public boolean IsGeneratingImage() {
    return engine.isGenerating();
  }

  @SimpleFunction(description = "Progres batch image: list [sudahSelesai, total] "
      + "([0,0] bila tidak ada batch). Cocok dipolling via Clock untuk progress bar; "
      + "tiap item juga bawa index+total via WhenAIGenerateImageBatchItem.")
  public YailList GetBatchProgress() {
    return engine.getBatchProgress();
  }
}
