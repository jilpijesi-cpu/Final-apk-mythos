# 2.35 — Daftar pilihan model Gemini CLI

Sebelumnya kolom "Model Gemini CLI" hanya bisa diketik, sehingga pengguna harus hafal nama model.

- Tombol **Pilih dari daftar** di Pengaturan (hanya saat penyedia Gemini CLI) membuka daftar: Otomatis (auto), Pro, Flash,
  Flash-Lite, gemini-3.8-flash, gemini-3.5-flash, gemini-3.5-flash-lite, dan **Ketik sendiri**. Pilihan saat ini ditandai.
- Memilih item mengisi kolom model; kolom itu tetap bisa diedit manual, dan validasi lama (`isValidGeminiCliModel`) tetap berlaku.
- Alias (auto/pro/flash/flash-lite) diselesaikan Gemini CLI ke model terbaru di tingkatnya, jadi tahan lama. ID bernomor tetap pada versinya.
  Nama ID diambil dari dokumentasi model Google per Oktober 2026 dan bisa berganti; gunakan "Ketik sendiri" untuk model lain.
- Ketersediaan tiap model mengikuti akun/API (akun gratis bisa dibatasi pada sebagian model). Daftar tidak mengecek ketersediaan.
- Daftar ada di `GeminiCliModel.kt` (`GEMINI_CLI_MODEL_CHOICES`); tes: `GeminiModelChoicesTest.kt`.
- Belum dikompilasi di sandbox (tanpa Gradle); jalankan `./gradlew testDebugUnitTest` dan cek tampilan dialog di perangkat.
