# Sinkronisasi dua arah MYTHOS–Termux

## Cakupan

MYTHOS memakai UID Android sendiri dan menjalankan perintah di Termux melalui `com.termux.RUN_COMMAND`. Karena itu aplikasi tidak membaca `~/.config` atau kredensial Termux secara langsung. Jembatan sinkronisasi memakai kanal eksekusi Termux yang sudah diizinkan pengguna.

Versi ini menyinkronkan empat preferensi non-rahasia: **folder kerja**, **URL repository GitHub**, **penyedia AI aktif**, dan **nama model aktif**. Data disimpan di `~/.config/mythos/android-app-sync-v1.properties`; helper CLI berada di `$PREFIX/bin/mythos-settings`. Jika nama perintah itu sudah dipakai skrip lain, MYTHOS menolak menimpanya. Penulisan memakai file sementara, `mv` atomik, dan permission `600` untuk data serta `700` untuk helper.

## Aktivasi dan penggunaan

1. Buka MYTHOS → Pengaturan → **Sinkronisasi dua arah Termux**.
2. Tekan **Kirim ke Termux** satu kali. Ini memasang helper dan mengirim nilai yang saat itu tersimpan di aplikasi.
3. Sesudah aktivasi, perubahan folder kerja, repository, penyedia, atau model yang disimpan melalui Pengaturan MYTHOS otomatis dikirim lagi ke Termux.
4. Untuk mengubah metadata dari Termux, jalankan: `mythos-settings set workdir MYTHOS`, `mythos-settings set repo https://github.com/pemilik/nama-repo`, `mythos-settings set provider "Gemini CLI (Google)"`, dan `mythos-settings set model gemini-2.5-flash`. Set provider akan menghapus model lama dari metadata supaya model bawaan penyedia baru digunakan sampai model baru ditentukan.
5. Kembali ke MYTHOS dan tekan **Ambil dari Termux**. Aplikasi memvalidasi path dan URL, menampilkan perbedaan, lalu meminta konfirmasi sebelum menerapkan.

`mythos-settings show` menampilkan nilai saat ini. `mythos-settings export` mengeluarkan format properties yang dipakai aplikasi. Perintah ini tidak memindahkan folder, tidak mengganti `git remote`, dan tidak mengedit `.bashrc`/`.zshrc`.

## Keamanan dan batasan yang disengaja

- API key, token GitHub, token penyedia AI, file autentikasi Gemini/Codex/Claude, password, dan signing key **tidak disinkronkan**. Kredensial tetap berada pada tempat asalnya.
- Status alat (`git`, `node`, `npm`, `python3`, `java`, `proot-distro`, `gemini`, `codex`, `claude`) serta status `gh auth` diperiksa secara baca-saja; isi token tidak pernah ditampilkan.
- Sinkronisasi Termux → aplikasi bersifat eksplisit/manual dan memerlukan konfirmasi. Tidak ada watcher latar yang mengubah preferensi Android tanpa sepengetahuan pengguna.
- Sinkronisasi tidak menginstal/menghapus paket, mengubah shell startup, mengubah remote repository, atau mengubah lokasi source MYTHOS.
- Pengaturan UI khusus Android, instruksi pribadi, endpoint API, mode otonom, dan rahasia provider tidak diperlakukan sebagai konfigurasi Termux. Perluasan field bersama harus masuk allowlist dan memiliki validasi/aturan konflik sendiri.

## Autentikasi GitHub

Build APK bisa memakai salah satu sumber autentikasi: token GitHub yang disimpan terenkripsi oleh MYTHOS, atau sesi `gh auth login` milik Termux. Pemeriksaan `validate_project` kini juga mengenali sesi `gh` Termux ketika aplikasi tidak menyimpan token. Sandbox terisolasi tidak otomatis mewarisi sesi autentikasi Termux.
