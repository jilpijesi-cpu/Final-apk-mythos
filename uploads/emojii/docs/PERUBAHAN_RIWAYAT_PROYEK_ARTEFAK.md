# Riwayat banyak chat, halaman Proyek, dan halaman Artefak

STATUS: BELUM DIKOMPILASI dan tes belum dijalankan (dibuat tanpa Gradle/Kotlin di lingkungan penyusun).
Jalankan `./gradlew testDebugUnitTest assembleDebug` (atau workflow GitHub) sebelum dipakai.

## 1. Riwayat banyak chat
- Tiap chat disimpan sendiri di `filesDir/chats/<id>.json` (judul, waktu, pesan). `chats/index.json` menyimpan ringkasannya untuk drawer
  dan dibangun ulang otomatis bila hilang atau rusak.
- `history.json` lama dipindahkan otomatis menjadi chat pertama (nama file berpindah, isi tidak diubah).
- Drawer: bagian "Terbaru" menampilkan 6 chat terakhir; chat yang sedang dibuka diberi latar. Ketuk membuka chat itu, tekan lama untuk ubah nama atau hapus.
- "Semua chat": daftar lengkap dengan pencarian, tombol Baru, dan menu yang sama.
- "Chat baru" tidak lagi menghapus: chat lama tersimpan dan tetap ada di daftar. Menghapus hanya lewat menu chat (atau `confirmDelete`).
- Gambar pratinjau (`filesDir/chat_images`) kini dihapus per chat, bukan seluruh folder, supaya chat lama tetap utuh.
- Chat samaran tetap tidak ditulis ke disk; membuka chat lain dari drawer ditolak selama mode itu aktif.
- Kode: `ChatStore.kt` (logika murni), `ChatHistory.kt` (penyimpanan), `ChatControls.kt` (`newChat`, `openChat`, `deleteChat`), `ChatPages.kt`, `Drawer.kt`.

## 2. Menu drawer yang sungguhan
- Proyek (`ProjectPages.kt`): daftar folder di `<folder kerja>/projects/aktif` dan `projects/arsip` (tab Aktif/Arsip). Pilihan: Lanjutkan di chat
  (mengisi kolom input), Arsipkan/Pulihkan, Hapus (dengan konfirmasi). Tombol "+ Baru" mengisi kolom input untuk meminta agen membuat proyek.
  Mengubah atau menghapus ditolak saat agen sedang bekerja.
- Artefak (`ArtifactPages.kt`, `ArtifactLog.kt`, `ShareProvider.kt`): file yang ditulis agen lewat `write_file` dan APK hasil build dicatat di
  `filesDir/artifact_log.jsonl`, ditambah isi `<folder kerja>/artifacts`. Tab "Hasil" = APK dan file di luar folder proyek; tab "Semua" = termasuk file sumber proyek.
  Pilihan: Buka (teks tampil di dialog; APK diteruskan ke pemasang; lainnya ke aplikasi lain), Bagikan, Salin path, Hapus.
  File diambil dari Termux lewat soket lokal ke `cache/share` (maks 25 MB) dan disajikan oleh `ShareProvider` (authority `com.build.mythos.share`, ditambahkan di manifest).
- Baris "Code" tetap membuka Catatan perintah.

## Tes baru
- `ChatStoreTest`: judul, id aman, indeks, format file (termasuk format lama), nama ulang, pencarian, path gambar, label hari.
- `WorkspaceListTest`: pembacaan daftar proyek/file dari Termux, nama aman, jenis/MIME, catatan artefak.

## Batasan yang diketahui
- Folder kerja yang diatur ke home (`~`) memakai `~/MYTHOS` sebagai induk untuk halaman Proyek dan Artefak.
- Halaman Proyek dan Artefak membaca lewat Termux, jadi butuh Termux terpasang dan izin RUN_COMMAND.
- Ringkasan konteks (`sum_text`) dan checkpoint agen tidak disimpan per chat; keduanya dikosongkan saat berpindah chat.
