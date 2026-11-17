# 2.34 — Alur Live Streaming Aktivitas: Gemini disamakan dengan Codex, indikator pesan kedua

Dasar: rekaman 1000241992 (Codex, build 2.33 terbukti terpasang) dan pembandingan jalur Codex vs Gemini di kode.

## 1. Indikator hilang ±7 detik pada pesan kedua dan seterusnya
- Gejala (0:26–0:33): setelah "Audit" dikirim, layar kosong tanpa indikator; pada pesan pertama indikator muncul dalam 2 detik.
- Penyebab (dari membaca kode, belum dibuktikan di perangkat): `onTextActive()` menyembunyikan baris live (`setLiveVisible(false)`)
  selama jawaban mengalir dan tidak pernah menampilkannya lagi di putaran berikutnya. Panel aktivitas masih tampil, sehingga
  `setStatus` ikut menyembunyikan status di dalam chat. Hasilnya tidak ada indikator sama sekali.
- Perbaikan: `CodexActivityUi.reset()` (dipanggil di awal tiap putaran Codex dan Gemini) kini menampilkan lagi baris live.

## 2. Gemini memakai panduan alur yang sama dengan Codex
- Sebelumnya hanya wrapper Codex yang menempelkan panduan (kalimat sebelum alat -> temuan setelah alat -> jawaban akhir
  "Inti penjelasan"). Gemini mengirim pesan apa adanya, sehingga alurnya tidak sama.
- Kini panduan ada di satu konstanta, `CLI_NARRATION_GUIDE` (`CliGuide.kt`), dipakai wrapper Codex (isi string identik) dan Gemini.
- Dialog izin tetap menampilkan perintah ringkas; perintah yang dijalankan membawa panduan (`runCmdText`). Mode biasa (CLI lama) juga.
- Tes: `CliGuideTest.kt`.

## 3. Versi dinaikkan ke 2.34 (kode 49)
Supaya jelas APK mana yang terpasang (Pengaturan/laporan menampilkan versi).

## Belum diubah
- Label alat Gemini (`list_directory`, `glob`, `grep_search`, `read_many_files`, alat lain) masih diterjemahkan kasar.
- Poin daftar (•): baris lanjutan belum sejajar dengan teks poin.
- Belum dikompilasi atau dijalankan di sandbox (tanpa Gradle); jalankan `./gradlew testDebugUnitTest`, lalu uji di perangkat.
