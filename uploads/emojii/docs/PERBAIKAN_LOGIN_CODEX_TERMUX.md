# Perbaikan login Codex di Mythos (Android/Termux)

## Perubahan

- Tombol **Masuk dengan ChatGPT** tetap membuka sesi Termux yang terlihat, agar pengguna bisa menyelesaikan alur login/browser.
- Pemeriksaan kesiapan memastikan paket `@bash0816/codex-termux` benar-benar terpasang, memvalidasi keluaran versi, dan menolak shim yang melaporkan `Termux Mode`; perintah dengan nama `codex` saja tidak dianggap cukup.
- Instalasi pemulihan memakai `@bash0816/codex-termux`, paket komunitas dengan patch kompatibilitas Android/Termux, bukan `@openai/codex` upstream.
- Status autentikasi diperiksa melalui `codex login status`; file `~/.codex/auth.json` tidak lagi menjadi satu-satunya indikator.
- Saat CLI belum siap, status Pengaturan dan pesan chat menunjukkan bahwa CLI harus diperbaiki sebelum login.

## Batasan dan keamanan

CLI native upstream Codex bukan target resmi Android/Termux. Karena itu, preset ini menggunakan build komunitas. Build tersebut bukan distribusi resmi OpenAI; tinjau repositori dan paket penerbit sebelum memasukkan akun. Autentikasi dilakukan oleh CLI di Termux, bukan dengan menyimpan password ChatGPT di aplikasi Mythos. File autentikasi pada `~/.codex` berisi kredensial/token dan tidak boleh dibagikan atau dimasukkan ke Git.

Rujukan: [repositori Codex resmi](https://github.com/openai/codex), [referensi autentikasi Codex](https://learn.chatgpt.com/docs/auth), dan [paket komunitas Codex Termux](https://github.com/bash0816/Codex-Termux).

## Verifikasi

- Tes metadata unit ditambahkan pada `app/src/test/java/com/build/mythos/CliLoginsTest.kt`.
- Pemeriksaan string kesiapan Kotlin berhasil dikompilasi dengan `kotlinc`.
- Tes Gradle penuh belum dijalankan: wrapper gagal mengunduh Gradle 8.9 karena DNS/akses jaringan ke `services.gradle.org` tidak tersedia pada lingkungan ini.
