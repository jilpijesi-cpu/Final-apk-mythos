# Perbaikan stabilitas Codex di Termux

## Perubahan
- Instalasi Codex Mythos menggunakan paket `codex` dari repositori Termux, sesuai instalasi yang berhasil pada perangkat pengguna.
- Pemeriksaan kesiapan memverifikasi paket dpkg, executable, dan versi CLI; tidak bergantung pada wrapper npm komunitas.
- Perintah `codex exec` tidak lagi membuang stderr, sehingga pesan kegagalan jaringan, autentikasi, dan CLI bisa terlihat.
- Batas waktu chat Codex dinaikkan dari 300 ke 900 detik untuk koneksi seluler yang lambat. Tidak ada retry otomatis terhadap prompt agar tindakan berulang tidak terjadi tanpa sepengetahuan pengguna.

## Verifikasi dan batasan
Perubahan source ini belum membuktikan masalah putus koneksi telah selesai pada perangkat. Build APK dan uji runtime Android/Termux tetap harus dilakukan. Jika koneksi putus, pesan error yang kini terlihat harus dipakai untuk menentukan apakah penyebabnya jaringan, proses Termux, autentikasi, atau batas waktu.
