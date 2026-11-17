# Foto terbaru dan Chat samaran yang lebih tegas

STATUS: BELUM DIKOMPILASI dan tes belum dijalankan (dibuat tanpa Gradle/Kotlin di lingkungan penyusun).
Jalankan `./gradlew testDebugUnitTest assembleDebug` (atau workflow GitHub) sebelum dipakai.

## 1. Strip "Foto terbaru" (saran nomor 3)
- Lembar "Tambahkan ke chat": bila izin baca gambar sudah ada, baris "Tampilkan foto terbaru" diganti strip geser berisi 12 foto terakhir
  di galeri. Ketuk satu foto: langsung menjadi lampiran (alur yang sama dengan Foto/Kamera: `importFile`).
- Tanpa izin, baris lama tetap ada. Mengetuknya meminta izin; setelah diizinkan, lembar dibuka ulang dengan strip.
  Bila ditolak, jatuh kembali ke pemilih foto seperti sebelumnya, jadi tidak ada fungsi yang hilang.
- Izin di manifest: `READ_MEDIA_IMAGES` (Android 13+), `READ_MEDIA_VISUAL_USER_SELECTED` (Android 14+), `READ_EXTERNAL_STORAGE` (maks SDK 32).
  Di Android 14+ pengguna boleh memilih "hanya foto tertentu"; strip lalu hanya menampilkan foto itu.
- Foto dibaca dari MediaStore secara lokal; tidak ada yang dikirim ke mana pun sampai pengguna mengirim pesan.
- Kode: `RecentPhotos.kt` (izin murni + kueri + thumbnail + strip), penyambungan di `Drawer.kt` (`showAddSheet`) dan `MainActivity.kt` (hasil izin).

## 2. Chat samaran lebih tegas (saran nomor 6)
Yang sudah benar sebelumnya: keluar dari mode samaran membuang checkpoint, pratinjau gambar, dan salinan lampiran lewat `newChat()`.
Celah yang ditutup sekarang:
- **Checkpoint tidak lagi ditulis ke disk saat samaran.** `agent_checkpoint.json` memuat pesan pengguna; kini di Chat samaran checkpoint hanya
  di memori (`AgentCheckpointStore.kt`), jadi "lanjutkan" tetap jalan selama sesi, tetapi tidak ada jejak di disk bila aplikasi dimatikan paksa.
  Keluar dari mode samaran selalu mengosongkannya, juga bila layar sudah kosong.
- **Sapuan gambar yatim saat aplikasi dibuka** (`sweepOrphanImages`): pratinjau di `chat_images` yang tidak disebut chat mana pun dibuang
  (sisa samaran bila aplikasi mati sebelum keluar mode). Berkas yang lebih baru dari 2 menit dan gambar chat yang sedang terbuka dilindungi.
  Bila satu file chat gagal dibaca, sapuan dibatalkan seluruhnya.
- **Peringatan saat agen menulis file di folder kerja** (`noteIncognitoWrite`, dipanggil dari `recordArtifact`): satu catatan di chat per sesi
  samaran, menyebut path file dan bahwa file itu TIDAK ikut terhapus. Berlaku untuk `write_file` dan APK hasil build.
- Kode: `IncognitoGuard.kt`, `AgentCheckpointStore.kt`, `Drawer.kt` (`setIncognito`), `ArtifactLog.kt`.

## Tes baru
- `RecentPhotosTest`: izin per versi Android, akses sebagian di Android 14, izin lama tidak dihitung di Android 13.
- `IncognitoGuardTest`: kapan peringatan tampil, isi peringatan, gambar yatim (dipakai/baru/lama/kosong).

## Batasan yang diketahui (belum diubah, perlu keputusan)
- `CommandLog` (Catatan perintah) tetap mencatat perintah yang dijalankan agen di Chat samaran. Itu menjaga jejak audit keamanan,
  tetapi isinya bisa memuat potongan percakapan. Pilihan: biarkan, atau jangan catat saat samaran.
- Halaman Artefak tetap mencatat file yang ditulis agen saat samaran (agar file itu bisa ditemukan dan dihapus). Peringatan di chat memberitahu hal ini.
- Salinan lampiran di Termux bila aplikasi dimatikan paksa saat samaran tidak ikut disapu (hanya tercatat di memori).
- Saran nomor 11 (pratinjau lampiran + tombol batal) ternyata sudah ada: `Attachments.kt` menampilkan gambar kecil dan tombol ✕ per lampiran.
