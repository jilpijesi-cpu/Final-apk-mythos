# Audit dan implementasi sinkronisasi MYTHOS–Termux

Tanggal: 1 November 2026
Source: branch `fix/mythos-debug-keystore-signing` (source ZIP dari commit `ddba8e6d16ebd3ad8f15cd1bc5eb9df7c39d2462`)

## Temuan arsitektur

1. Pengaturan MYTHOS disimpan pada Android `SharedPreferences` dengan nama `cfg`; konfigurasi Termux berada pada HOME milik paket Termux. Kedua aplikasi berjalan dengan ruang data/UID terpisah, jadi aplikasi tidak boleh menganggap file privat Termux bisa dibaca langsung.
2. Kanal resmi yang sudah dipakai proyek adalah `com.termux.RUN_COMMAND` dengan izin `com.termux.permission.RUN_COMMAND`. Implementasi sinkronisasi menggunakan kanal yang sama, bukan akses langsung ke file privat.
3. Folder kerja, URL repository, nama penyedia, dan nama model adalah metadata non-rahasia yang dipakai bersama. Token GitHub, API key, autentikasi CLI, serta kunci penandatanganan bukan metadata dan tidak boleh disalin ke bridge.
4. Skrip build APK sudah mendukung `GH_TOKEN` dari MYTHOS atau sesi `gh auth` Termux. Sebelumnya, `validate_project` hanya memeriksa apakah token aplikasi diisi sehingga dapat memberi peringatan yang keliru saat `gh auth login` sudah aktif.
5. Mode sandbox/proot terpisah dari host Termux. Sesi `gh` pada Termux tidak dianggap tersedia di dalam sandbox.

## Perubahan yang diterapkan

- Tambah `TermuxSettingsSync.kt` dan codec murni JVM `TermuxSettingsCodec.kt`.
- Tambah menu **Pengaturan → Sinkronisasi dua arah Termux** dengan aksi Periksa, Kirim ke Termux, dan Ambil dari Termux.
- Kirim ke Termux memasang helper `mythos-settings` dan berkas `~/.config/mythos/android-app-sync-v1.properties`.
- Setelah pengguna mengaktifkan bridge sekali, perubahan folder kerja, URL repository, penyedia AI aktif, dan nama model yang disimpan melalui dialog Pengaturan otomatis dikirim kembali ke metadata Termux.
- Perubahan dari Termux dapat dibuat dengan `mythos-settings set workdir NILAI`, `mythos-settings set repo URL`, `mythos-settings set provider NAMA`, atau `mythos-settings set model NAMA`, lalu ditarik melalui aplikasi. Aplikasi memvalidasi path, URL, penyedia, dan model serta meminta konfirmasi sebelum menerapkan. Mengubah provider tanpa menetapkan model baru mengembalikan preferensi provider tujuan ke model default-nya.
- Helper menolak menimpa perintah `mythos-settings` yang sudah ada kecuali file tersebut memiliki penanda kepemilikan MYTHOS. Berkas konfigurasi ditulis atomik dan menggunakan permission `600`; helper menggunakan `700`.
- Pemeriksaan status menampilkan ketersediaan Git, Node/npm, Python 3, Java, proot-distro, Gemini CLI, Codex CLI, Claude CLI, serta status autentikasi `gh` tanpa menampilkan token atau isi file kredensial.
- `validate_project` sekarang menerima sesi `gh auth` Termux sebagai alternatif token di Pengaturan, hanya saat tidak berjalan di sandbox.
- Naikkan versi aplikasi menjadi versionCode `56`, versionName `2.41`, agar APK berikutnya dapat menjadi update dari Build #19 (2.40 / code 55).

## Batas sinkronisasi yang disengaja

Ini adalah sinkronisasi allowlist V1, bukan penyalinan seluruh konfigurasi perangkat. Hanya folder kerja, URL repository, penyedia AI aktif, dan nama model aktif—metadata yang memang dipakai bersama oleh aplikasi dan Termux—yang disinkronkan dua arah. API key, token, password, file autentikasi CLI, signing key, instruksi pribadi, dan pengaturan UI khusus Android tetap berada di penyimpanan masing-masing. Status paket/CLI dibaca dari Termux tetapi tidak diinstal atau diubah otomatis.

Termux → MYTHOS memerlukan aksi **Ambil dari Termux** dan konfirmasi. Android tidak menjalankan watcher terus-menerus untuk memantau setiap file Termux; karena itu perubahan tidak muncul seketika sebelum sinkronisasi dilakukan. Sinkronisasi tidak memindahkan folder proyek, mengganti remote Git, atau mengubah `.bashrc`/`.zshrc`.

## Verifikasi

| Pemeriksaan | Hasil |
|---|---|
| Codec konfigurasi: round-trip Unicode/spasi, schema, dan Base64 rusak | Lulus (smoke test Kotlin/JVM) |
| Helper shell: `bash -n` | Lulus |
| Helper shell: `set` lalu `show` untuk workdir, repo, provider, dan model | Lulus |
| Helper shell: mengganti provider menghapus metadata model lama | Lulus |
| Permission berkas metadata helper pada lingkungan uji | `600` |
| Penulisan konfigurasi memakai temp file unik, rename atomik, dan cleanup | Diterapkan; helper diuji di HOME sementara |
| Pemeriksaan trailing whitespace pada berkas yang disentuh | Bersih |
| Kompilasi sintaks Kotlin bridge dengan stub Android/proyek | Lulus (pemeriksaan sintaks/tipe terbatas, bukan build Android penuh) |
| Build Android / `testDebugUnitTest` | Belum dapat dijalankan: Gradle Wrapper perlu mengunduh Gradle 8.9, tetapi DNS/jaringan lingkungan ini gagal (`UnknownHostException: services.gradle.org`) |
| Runtime di HP/Termux dan GitHub Actions | Belum diverifikasi dari lingkungan ini; perlu dipasang/dijalankan di perangkat atau workflow setelah perubahan diterapkan ke repository |

## Langkah verifikasi setelah memasang APK

1. Buka Pengaturan → Sinkronisasi dua arah Termux → Periksa.
2. Tekan Kirim ke Termux satu kali.
3. Buka Termux, jalankan `mythos-settings show`.
4. Uji `mythos-settings set workdir MYTHOS`, lalu di MYTHOS tekan Ambil dari Termux dan konfirmasi.
5. Uji `mythos-settings set provider "Gemini CLI (Google)"` lalu `mythos-settings set model gemini-2.5-flash`; tarik perubahan di MYTHOS dan periksa penyedia/model aktif.
6. Simpan perubahan folder kerja, repository, penyedia, atau model di Pengaturan MYTHOS; setelah bridge aktif, nilai terbaru akan dikirim otomatis ke metadata Termux.
7. Jalankan `validate_project`; bila `gh auth login` aktif di Termux dan sandbox tidak aktif, autentikasi GitHub seharusnya tidak lagi ditandai belum tersedia.
