# Kunci model OpenRouter: hanya `openrouter/free`

STATUS: BELUM DIKOMPILASI di Android. Logika murni (`ModelLock.kt`) dikompilasi dan 4 tes `ModelLockTest` lulus (35 tes bersama tes sebelumnya).
Bagian `MainActivity` dibaca ulang saja. Belum dicoba dengan API key OpenRouter sungguhan.

Aturan: bila penyedia = OpenRouter, model selalu `openrouter/free`; tidak bisa dipilih, diketik, atau diganti otomatis di dalam OpenRouter.

## Perubahan
- `ModelLock.kt` (baru): `OPENROUTER_FREE_MODEL`, `lockedModelFor(provider)`, `poolModelsFor`, `lockedModelMessage`.
- `ProviderConfig.pvOf`: untuk kunci "model" mengembalikan model terkunci, mengabaikan nilai tersimpan (`p_OpenRouter_model` lama tidak berpengaruh). Semua pembaca model
  (label, cadangan failover, pengaturan) lewat fungsi ini. `cfgModel()` juga mendahulukan kunci, jadi override failover tidak bisa menimpanya.
- `MainActivity`: preset OpenRouter berisi `openrouter/free` sebagai model bawaan.
- `ModelPicker`: `pickModel` dan `typeModel` hanya menampilkan "Model OpenRouter dikunci ke openrouter/free."; `autoSetupModels` melewati pengambilan dan uji daftar model.
- `ModelCall.poolFor`: cadangan failover OpenRouter hanya `openrouter/free`. Bila gagal, failover tetap bisa pindah ke PENYEDIA lain yang punya API key, bukan ke model lain di OpenRouter.
- `SettingsDialog`: kolom nama model dinonaktifkan untuk OpenRouter.

## Yang perlu diketahui
- `openrouter/free` memilih sendiri model gratis di belakangnya. Tidak semua model gratis mendukung alat (tool call); bila OpenRouter membalas "tanpa endpoint dengan dukungan alat",
  aplikasi akan mencoba penyedia lain bila ada, kalau tidak galatnya muncul seperti biasa.
- Batas harian model gratis OpenRouter berlaku untuk semua model ":free" sekaligus (lihat komentar di OpenSource.kt).
- Penyedia lain tidak berubah. Untuk mengunci penyedia lain, tambahkan di `lockedModelFor`.

## Penyedia utama bawaan = OpenRouter
- `DEFAULT_PROVIDER = "OpenRouter"` (ModelLock.kt) menggantikan "Gemini" di `MainActivity.prov` dan di pembacaan awal (`MainLayout.kt`, lewat `initialProvider`).
- Hanya berlaku bila belum ada pilihan tersimpan. Pilihan yang sudah tersimpan (mis. Ollama lokal) tidak berubah; ganti lewat Pengaturan.
- Pengguna lama yang tidak punya pilihan tersimpan tetapi masih menyimpan API key Gemini versi lama (kunci `key`) tetap di Gemini.
- Tes: `ModelLockTest` (`penyediaUtamaBawaanOpenRouter`, `pilihanTersimpanDanPenggunaGeminiLamaDihormati`).

## Pembersihan kode OpenRouter lama (OpenSource.kt)
Dihapus karena OpenRouter tidak lagi memilih atau menguji model: `OPENROUTER_PROBE_MAX` (batas 8 model uji), pembatasan `cap` khusus OpenRouter di `probeCandidates`,
dan "OpenRouter" dari `OPEN_SOURCE_HOSTS` / `OPEN_SOURCE_ONLY_HOSTS`. Skor open source dan bonus `:free` tetap ada untuk Groq, Ollama, dan Kustom.
Tidak dihapus (masih relevan): deteksi kunci `sk-or-` (KeyDetect.kt), penanganan galat OpenRouter di ApiErrors.kt (mis. "tanpa endpoint dengan dukungan alat"),
dan preset OpenRouter.
Tes disesuaikan: `OpenSourceTest` (dua tes OpenRouter diganti satu tes bonus gratis dan satu tes "OpenRouter bukan penyedia open source otomatis"),
`OpenSourceFixesTest` (batas 8 model diganti tes Groq; gambar ke `openrouter/free` diizinkan).
Akibat kecil: `modelSeesImages("OpenRouter", ...)` kini selalu true, sama seperti sebelumnya untuk `openrouter/free`.
