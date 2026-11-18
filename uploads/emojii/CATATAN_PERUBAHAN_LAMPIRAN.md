# Perbaikan lampiran dan ingatan agen (versi 2.40)

## Masalah
- Lampiran hanya dikirim sebagai teks path ("File terlampir di ..."); isinya tidak pernah masuk percakapan.
- Setelah satu tugas selesai, salinan file dihapus dan foto ditandai `released`, jadi pesan berikutnya kehilangan lampiran.
- Zip tidak pernah dibaca atau diekstrak otomatis.
- Pemangkasan konteks memotong isi lama dengan kasar.

## Perubahan
1. **Isi lampiran dibaca saat file dipilih** (`AttachmentDigest.kt`, `FileImport.kt`):
   teks/kode dibaca langsung, docx diekstrak teksnya, PDF lewat `pdftotext` di Termux (bila terpasang),
   zip dipindai (manifest, README/build.gradle, dsb) lalu diekstrak ke `MYTHOS/projects/aktif/<nama-zip>/`.
   Zip aman: path berbahaya (`..`, absolut) terdeteksi dan zip tidak diekstrak otomatis; ada batas jumlah dan ukuran.
2. **Isi itu ditempel ke pesan pengguna di riwayat** (`AgentLoop.kt`), disembunyikan dari gelembung chat (`ChatHistory.kt`).
3. **Catatan "LAMPIRAN AKTIF" per chat** (`ActiveAttachments.kt`) masuk prompt sistem di setiap giliran (`ContextSummary.kt`),
   jadi nama dan path lampiran tidak ikut terpangkas.
4. **Lampiran tidak lagi dihapus saat tugas selesai.** Baru dibersihkan saat chat dihapus (`purgeChatAttachments`).
   Pindah ke chat lain tidak menghapus lampiran chat yang disimpan (`ChatControls.kt`).
5. **Pemangkasan konteks lebih cerdas** (`ContextTrim.kt`): blok isi lampiran lama dibuang dulu (diganti penanda)
   sebelum teks dipotong; anggaran besar mempertahankan 5 pesan pengguna terakhir (sebelumnya 3).
6. **Foto lama tidak dikirim ulang terus-menerus**: hanya 3 pesan terakhir yang membawa foto (`limitLiveImages`);
   file dan riwayat tetap utuh.
7. Prompt peringkasan kini meminta nama dan path semua lampiran ikut diringkas.
8. Tes baru: `AttachmentDigestTest.kt`.

## Catatan
- Kode ini belum dikompilasi di lingkungan pembuatnya (tanpa Kotlin/Gradle). Jalankan build GitHub Actions atau `./gradlew testDebugUnitTest` dulu.
- Skrip ekstrak zip sudah diuji di bash dengan zip contoh (folder tujuan unik, .git dan node_modules dilewati).
- Model lokal (Ollama) tetap punya jatah isi lampiran kecil (maks 2.500 karakter) karena anggaran konteksnya 8.000 karakter.
