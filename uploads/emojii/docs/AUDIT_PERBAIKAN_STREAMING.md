# Audit Perbaikan Mythos — Codex Streaming

Tanggal: 30 Oktober 2026
Target versi: 2.16 (`versionCode = 29`)

## Perbaikan yang diterapkan

1. **Streaming token langsung** — jalur Codex menggunakan `codex app-server --listen stdio://` dan membaca notifikasi `item/agentMessage/delta`. `item/completed` hanya menjadi cadangan jika tidak ada delta, atau menambahkan akhiran yang benar-benar belum terkirim.
2. **Pembacaan live inkremental** — `LiveTail` mempertahankan offset byte dan hanya membaca data yang baru ditulis. Transfer chunk memakai Base64 sehingga pemotongan pembacaan tidak merusak byte UTF-8; `Utf8StreamAccumulator` menyatukan karakter yang terpisah antar-chunk.
3. **Jawaban panjang** — isi pesan final diambil dari buffer streaming lengkap, bukan dari stdout RUN_COMMAND yang memiliki batas karakter.
4. **Pemisahan error** — stderr proses Codex ditulis ke file terpisah, tidak dimasukkan ke teks jawaban AI. Event `error`, `turn/completed` berstatus gagal, error JSON-RPC, dan exit code dipetakan menjadi pesan diagnostik.
5. **Animasi UI** — satu runnable pengetikan dipertahankan sepanjang stream. Polling tidak lagi membatalkan dan membuat ulang runnable setiap menerima pembaruan.
6. **Prasyarat CLI** — pemasangan Codex sekarang mencakup Node.js; pemeriksaan kesiapan memeriksa `node`, `codex`, versi CLI, dan dukungan `codex app-server`.
7. **Metadata dan tes** — versi APK dinaikkan ke 2.16 / code 29. Ditambahkan `Utf8StreamAccumulatorTest` dan pemeriksaan kesiapan Codex diperbarui dalam `CliLoginsTest`.

## Pemeriksaan yang berhasil di lingkungan audit

- Sintaks wrapper JavaScript: `node --check` lulus.
- Simulasi JSON-RPC: delta dikirim berurutan, teks final tidak diduplikasi, jalur cadangan `item/completed` berfungsi, dan error gagal keluar dengan kode non-zero serta stderr terpisah.
- Tes smoke Kotlin murni: potongan UTF-8 satu byte pada satu waktu mempertahankan emoji dan teks multibahasa.
- Uji pembacaan file inkremental: 235.000 byte dengan karakter multibyte direkonstruksi tanpa kehilangan atau duplikasi.
- Berkas shell: `bash -n` lulus untuk semua `.sh` dan `gradlew`.
- Sebanyak 11 file XML dapat diparse; workflow GitHub Actions lolos parsing YAML.
- Pemindaian pola API key/token literal umum pada kode utama tidak menemukan kecocokan.

## Batas verifikasi yang masih ada

**Build Android dan seluruh unit test Gradle belum berhasil dijalankan.** `./gradlew testDebugUnitTest --no-daemon` berhenti sebelum kompilasi karena unduhan Gradle gagal dengan `java.net.UnknownHostException: services.gradle.org`. Android SDK juga tidak tersedia di lingkungan audit. Karena itu ZIP ini adalah sumber yang telah diperbaiki, bukan APK yang sudah terverifikasi.

Implementasi menggunakan Codex App Server karena protokol itu secara resmi menyediakan delta `item/agentMessage/delta`. Dokumentasi resmi saat audit juga menyatakan perintah App Server masih eksperimental dan belum didukung untuk beban produksi; perilaku akhirnya wajib diuji pada paket Codex Termux yang benar-benar terpasang di perangkat pengguna. Dokumentasi: https://learn.chatgpt.com/docs/app-server

## Perintah verifikasi pada lingkungan Android/Termux atau CI yang memiliki Gradle

Dari direktori proyek:

```bash
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebug --no-daemon
```

APK debug (jika build berhasil) dihasilkan di `app/build/outputs/apk/debug/app-debug.apk`.
