# Tools_Chatbot — extension MIT App Inventor (AI teks + AI image)

Dua komponen non-visible, backend = halaman Perchance `extension-mit-plugin`
(hidden WebView milik extension, URL hardcode di source).

- **AiTeks**: Create (tanpa input) → `WhenChatbotCreate(success, ready, ...)` →
  `CallGenerateTeks(prompt, chunk)` / `CallGenerateTeksWithImage(prompt, isUri, image)`
  → `WhenAIGenerateTeks(ischunk, respon, isfinished)`. History kanonis
  `[{role,text,image}]`, auto di halaman (persist localStorage), cap 20 giliran.
- **AiImage**: MANDIRI (tanpa AiTeks). `CallCreateChatbot()` → `WhenChatbotCreate(...)` →
  `CallGenerateImage(prompt, n_prompt, seed, resolution[dropdown], guidance, base64)`
  → `WhenAIGenerateImage(isfinished, base64, url)` (mesin/WebView dibagi dengan AiTeks,
  cukup Create sekali dari salah satu).
  `true` = base64 RAM-only (1 truk + fallback chunk), `false` = upload → url.

Single-flight global (sibuk → error 3). Error: 1=timeout, 2=belum siap,
4=dibatalkan, 5=salah halaman, 6=upload gagal.

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
