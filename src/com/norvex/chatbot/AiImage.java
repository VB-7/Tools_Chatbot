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

@DesignerComponent(
    version = 2,
    versionName = "1.1",
    description = "AI image (stateless, tanpa history/cache/storage lokal). "
        + "Panggil CallCreateChatbot dulu (milik komponen ini sendiri, mesin sama dengan AiTeks) "
        + "lalu tunggu WhenChatbotCreate ready=true. Bisa dipakai TANPA AiTeks. "
        + "base64=true -> E3 bawa base64 RAM-only; false -> upload -> E3 bawa url.",
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

  @SimpleEvent(description = "Hasil gambar. isfinished=true sekali. base64 terisi bila "
      + "Call pakai base64=true (RAM-only); url terisi bila base64=false (via upload).")
  public void WhenAIGenerateImage(boolean isfinished, String base64, String url) {
  }

  @SimpleEvent(description = "Galat AI image. code: 1=timeout, 2=belum siap, 3=sibuk, "
      + "4=dibatalkan, 5=salah halaman (mis. resolusi), 6=upload gagal.")
  public void WhenAIGenerateImageError(int code, String message) {
  }

  @SimpleFunction(description = "CALL: prompt + n_prompt (negative, kosong=tidak ada) + seed "
      + "(-1/<0 = acak) + resolution [dropdown: Square/Portrait/Landscape/SquareHd] "
      + "+ guidance (1-30, default 7) + base64 flag.")
  public void CallGenerateImage(String prompt, String nPrompt, double seed,
      @Options(Resolution.class) String resolution, double guidance, boolean base64) {
    engine.callGenerateImage(prompt, nPrompt, seed, resolution, guidance, base64);
  }

  @SimpleFunction(description = "CALL: batalkan generate image yang berjalan. Sukses -> error 4.")
  public void CallCancelImage() {
    engine.callCancelImage();
  }

  @SimpleFunction(description = "IS: true bila generate image sedang berjalan.")
  public boolean IsGeneratingImage() {
    return engine.isGenerating();
  }
}
