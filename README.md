# Tools_Chatbot — extension MIT App Inventor (AI teks + AI image)

Dua komponen non-visible, backend = halaman Perchance `extension-mit-plugin`
(hidden WebView milik extension, URL hardcode di source).

- **AiTeks**: Create (tanpa input) → `WhenChatbotCreate(success, ready, ...)` →
  `CallGenerateTeks(prompt, chunk)` / `CallGenerateTeksWithImage(prompt, isUri, image)`
  → `WhenAIGenerateTeks(ischunk, respon, isfinished, id)` (id sama per 1x generate;
  cocokkan dengan `CurrentResponseId`; isfinished = penanda selesai). History kanonis
  `[{role,text,image}]`, auto di halaman (persist localStorage), cap 20 giliran.
- **AiImage**: MANDIRI (tanpa AiTeks). `CallCreateChatbot()` → `WhenChatbotCreate(...)` →
  `CallGenerateImage(prompt, n_prompt, seed, resolution[dropdown], guidance, base64)`
  → `WhenAIGenerateImage(isfinished, base64, url)` (mesin/WebView dibagi dengan AiTeks,
  cukup Create sekali dari salah satu).
  `true` = base64 RAM-only (1 truk + fallback chunk), `false` = upload → url.
  Batch: `CallGenerateImageBatch(prompts[list teks, maks 20], ...param sama...)` →
  `WhenAIGenerateImageBatchItem(index, total, base64/url)` per gambar (index 1-based =
  nomor prompt; E3 tidak tembak saat batch) → `WhenAIGenerateImageBatchDone(total,
  success, failed, failedIndexes)`.

Single-flight global (sibuk → error 3). Error: 1=timeout, 2=belum siap,
4=dibatalkan, 5=salah halaman, 6=upload gagal.

## Pola minimal (pemula — cukup 3 blok)

1. Sediakan `VerticalArrangement` kosong + `TinyDB` di layar.
2. Di `Screen.Initialize`:
   `SetupChatUI(arrangement, TinyDB.GetValue "chatBackup")`
   (list kosong bila belum ada) — UI chat jadi langsung tampil: teks + gambar,
   markdown, upload vision, setting system prompt, unduh gambar.
   Backend ikut dibuat otomatis, tidak perlu CallCreateChatbot.
3. Di `WhenChatUI`: `TinyDB.StoreValue "chatBackup" = historyBackup`.
   Event ini datang saat UI siap + setiap ada perubahan (jawaban/error/H-blok).

Itu saja — tidak perlu rakit ListView/TextBox/Button/event manual.
Gambar hasil generate TIDAK ikut backup (stateless, tampil sesi itu saja).

Chat teks manual tanpa UI (4 blok, UI sendiri dari ListView/TextBox/Button):
`CallCreateChatbot` → tunggu `ready=true` → `CallGenerateTeks(prompt, false)`
(chunk=false = jawaban datang SEKALI utuh) → `WhenAIGenerateTeks` ambil `respon`.
Kunci tombol Kirim selama `IsGeneratingTeks=true` (cegah error sibuk).
History OTOMATIS — jangan sentuh blok H / SetAutoHistory.

Gambar (3 blok): `CallCreateChatbot` → tunggu `ready=true` →
`CallGenerateImage(prompt, "", -1, Square, 7, false)` (base64=false = dapat url) →
`WhenAIGenerateImage` pasang `url` ke `Image.Picture`.
Batch: `CallGenerateImageBatch` → tiap gambar masuk via
`WhenAIGenerateImageBatchItem(index, ...)` (E3 tidak tembak saat batch) →
selesai via `WhenAIGenerateImageBatchDone`.

## Build

Otomatis via GitHub Actions (`build-aix`): push ke `main` → ambil `.aix`
di halaman Actions → Artifacts `Tools_Chatbot-aix`.
Lokal: `git clone --recurse-submodules`, salin `src/` ke
`mit-cml/extension-template`, `ant extensions` (butuh Java 8 + ant).

Spek blok lengkap: lihat `TOOLS_CHATBOT.md` di generator NorVex-Beta.

## Unduh .aix

- Download langsung (raw): https://raw.githubusercontent.com/VB-7/Tools_Chatbot/main/Tools_Chatbot.aix
- Halaman rilis: https://github.com/VB-7/Tools_Chatbot/releases/tag/v1

Cara pasang: MIT App Inventor → Palette → Extension → Import extension → pilih file .aix.
