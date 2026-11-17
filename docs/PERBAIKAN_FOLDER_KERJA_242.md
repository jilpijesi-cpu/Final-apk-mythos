# Perbaikan Folder Kerja MYTHOS 2.42

## Perubahan perilaku

- Saat pengguna menyimpan kolom Folder kerja, MYTHOS menyelesaikan nama relatif terhadap HOME Termux. Contoh `MYTHOS` menjadi `$HOME/MYTHOS`.
- Folder dibuat dengan `mkdir -p` bila belum tersedia. Jika sudah ada, isinya tidak dihapus atau ditimpa.
- Sebelum preferensi disimpan, aplikasi memeriksa direktori aktual menggunakan `realpath`, menolak root `/`, direktori sistem Termux `/usr`, dan area terlindungi `MYTHOS/reference`.
- Nama relatif dan path `~/...` wajib tetap berada di HOME Termux setelah symlink diselesaikan. Path absolut eksplisit di luar HOME tetap didukung, tetapi tetap melalui pemeriksaan root, `/usr`, dan `MYTHOS/reference`.
- Pemeriksaan Git berjalan setelah folder disiapkan. Jika `.git` ditemukan pada workspace atau salah satu induknya, UI menampilkan path repository root sebenarnya. Peringatan ini tidak bergantung pada URL GitHub atau token.
- Jika persiapan atau validasi gagal, pengaturan tidak disimpan dan pengguna mendapatkan alasan kegagalan. Pemeriksaan yang gagal tidak bisa dilewati hanya dengan tombol konfirmasi.
- Prefix setiap perintah Termux mengulang pemeriksaan canonical path sebelum masuk ke workspace, dan memeriksa symlink pada direktori operasional agar tidak menulis keluar HOME melalui symlink.

## File yang berubah

- `WorkspacePaths.kt`: perintah persiapan workspace, pemeriksaan batas path canonical, dan pelaporan root Git.
- `SettingsDialog.kt`: membuat/memastikan folder saat Simpan dan menampilkan lokasi root Git.
- `TermuxFiles.kt`: validasi ulang workspace dan direktori operasional sebelum membuat struktur serta menjalankan perintah.
- `WorkspacePathsTest.kt`: tes normalisasi, pelaporan root Git, pembuatan workspace, serta aturan containment.
- `app/build.gradle.kts`: versi 2.42 / versionCode 57.
- `CHANGELOG.md`: catatan perubahan 2.42.

## Verifikasi yang dijalankan

- 10 tes `WorkspacePathsTest` dijalankan melalui harness JVM lokal: lulus.
- Skrip persiapan diuji pada shell lokal: pembuatan folder baru, folder yang sudah ada tetap utuh, deteksi `.git` pada induk, penolakan symlink yang mengarah keluar HOME, penolakan `MYTHOS/reference`, dan penolakan `/usr`: lulus.
- Prefix perintah Termux diuji sintaks shell dan perilakunya pada direktori sementara: lulus; symlink subfolder yang mengarah keluar HOME ditolak sebelum direktori luar dibuat.
- `./gradlew testDebugUnitTest --no-daemon` belum dapat dijalankan sampai selesai karena wrapper perlu mengunduh Gradle 8.9 dari `services.gradle.org`, tetapi jaringan lingkungan ini tidak dapat menyelesaikan DNS (`UnknownHostException`). Build APK Android belum dijalankan.
