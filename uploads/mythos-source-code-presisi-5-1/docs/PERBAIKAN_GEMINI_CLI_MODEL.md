# Perbaikan Pemilihan Model Gemini CLI — MYTHOS 2.30

## Tujuan
Memberi kontrol pemilihan model Gemini CLI dari Pengaturan MYTHOS, tanpa mengubah layout utama, palet warna, ikon, animasi, atau alur chat.

## Cara kerja
- Saat provider **Gemini CLI (Google)** dipilih, Pengaturan menampilkan kolom **Model Gemini CLI**.
- Nilai awal `auto` menggunakan pemilihan/routing model bawaan Gemini CLI.
- Pengguna dapat memasukkan alias yang dikenali CLI (`auto`, `pro`, `flash`, `flash-lite`) atau ID model konkret yang didukung akun/API.
- Setiap pesan menambahkan `--model <pilihan>` ke perintah Gemini CLI. Argumen `--model` / `-m` lama pada template dibersihkan agar tidak ada dua pilihan model yang bertentangan.
- Opsi prompt `-p`, `--prompt`, dan `--prompt=...` didukung.
- Masukan model divalidasi dan di-quote sebagai satu argumen shell; karakter kontrol/injeksi shell ditolak.
- Pilihan disimpan pada preferensi provider Gemini CLI dan tetap digunakan setelah aplikasi dibuka ulang.
- Provider CLI lain serta alur provider API biasa tidak diubah.

## Cara memakai
1. Buka Pengaturan MYTHOS.
2. Pilih **Gemini CLI (Google)**.
3. Isi **Model Gemini CLI** dengan `auto` untuk routing otomatis, atau masukkan alias/ID model, misalnya `flash`, `flash-lite`, `pro`, atau `gemini-2.5-flash`.
4. Tekan **Simpan**.

`auto` adalah mekanisme otomatis milik Gemini CLI, bukan daftar model yang diambil oleh MYTHOS. Model manual yang tidak dikenal, tidak tersedia di versi CLI, atau tidak diizinkan akun/API akan ditolak oleh Gemini CLI dan error-nya diteruskan ke chat.

## Kuota dan fallback
Pemilihan `auto` membantu CLI memilih/routing model sesuai kemampuannya. Ini bukan cara melewati batas kuota atau menjamin semua error 429 dapat dipulihkan. Fallback bergantung pada routing/policy CLI, akses akun, serta apakah CLI meminta persetujuan perpindahan model. MYTHOS tidak mengirim ulang prompt berulang-ulang secara otomatis, agar perintah atau tool tidak terduplikasi.

## Pemeriksaan
- Uji helper Kotlin terisolasi: 13 skenario lulus (default auto, model manual, penggantian `-m`/`--model`, ID model berpath, `-p`/`--prompt`/`--prompt=`, path executable absolut, penolakan input berbahaya, dan batas panjang).
- Pemeriksaan ZIP dan struktur file dilakukan setelah pengemasan.
- Build Gradle penuh dan tes JUnit belum dapat dijalankan di lingkungan ini: Gradle 8.9 tidak tersedia lokal dan unduhan wrapper gagal karena DNS `services.gradle.org` tidak dapat diresolusi. Karena itu APK belum dinyatakan berhasil dibuild.
