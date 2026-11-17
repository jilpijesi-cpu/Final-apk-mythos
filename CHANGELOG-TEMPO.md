# Tempo Santai: alur live streaming yang lebih lama dinikmati

Profil kecepatan tampil jawaban: **Santai** (bawaan), **Normal** (kecepatan lama), **Cepat**.
Dipilih di Pengaturan > Lanjutan > "Kecepatan tampil jawaban"; berlaku mulai jawaban berikutnya.

## File baru
- `StreamTempo.kt`: semua konstanta tempo di satu tempat (kecepatan, batas total, fade, jeda napas, jeda baris status).

## Diubah
- `StreamSmoother.kt`: laju dari profil; batas total waktu (Santai 25 dtk, dihitung dari backlog puncak); jeda napas di
  akhir kalimat/koma/paragraf (tidak di angka desimal dan tidak di dalam blok kode); pengali percepatan `boost`.
- `ChatMessages.kt`: ketuk teks untuk langsung melihat sisanya; fade 420 ms pada 160 huruf terakhir; penutupan segmen
  tanpa memblokir (`requestSegmentEnd`); blok kode/tabel muncul memudar bergantian saat jawaban difinalkan.
- `CodexActivityUi.kt`: `flushAll()` tidak lagi menumpahkan antrean sekaligus; antrean dikuras berurutan, teks yang masih
  punya kejadian di belakangnya dipercepat 3x (bukan instan), teks terakhir tetap pelan. Jeda settle mengikuti profil
  (Santai 1000 ms). Baris status live tidak muncul lagi saat pengurasan akhir.
- `AgentLoop.kt`: `endLiveSegment()` tidak lagi `Thread.sleep` (maks 15 dtk) di thread agen, jadi alat tetap berjalan
  selagi teks diketik. Teks live lewat antrean berurutan; sebelum jawaban akhir, `awaitVisualQueue()` menunggu antrean
  tampilan habis (hanya di akhir tugas, dipercepat setelah 12 dtk, batas keras 45 dtk).
- `LiveStatusLine.kt`, `ActivityZone.kt`: tahan minimum baris status mengikuti profil (Santai 1300/1500 ms).
- `SettingsDialog.kt`, `MainLayout.kt`: pilihan profil + muat saat aplikasi mulai (kunci prefs `stream_tempo`).

## Tes
- `StreamSmootherTest.kt`: angka waktu disesuaikan ke Santai; tes batas total; kelas baru `StreamTempoTest`
  (jeda kalimat, desimal, blok kode, Normal/Cepat tanpa jeda, percepatan, profil bawaan).
- 26 tes JVM murni (Smoother/Fade/Tempo/Markup) lulus. Kode Android (UI) belum dikompilasi penuh karena tidak ada
  Android SDK di lingkungan ini: jalankan `./gradlew testDebugUnitTest assembleDebug` dan uji di perangkat.

## Perbaikan: sulit ganti AI saat penyedia aktif macet (ModelPicker.kt, ModelProbe.kt)
Masalah (dari rekaman layar): ketuk chip model > "Memuat daftar model..." > "Gagal memuat daftar: timeout" > hanya kotak
ketik manual "Model (Ollama lokal)" tanpa jalan untuk ganti penyedia. Tombol "Ganti penyedia AI" hanya ada di daftar model.
- Gagal memuat daftar kini membuka dialog: **Coba lagi / Ganti penyedia AI / Ketik manual**.
- Dialog "ketik manual" juga punya tombol **Ganti penyedia AI**.
- Batas waktu memuat daftar model 8 dtk sambung + 12 dtk baca (sebelumnya 20 + 30 dtk).
- Ketukan berulang pada chip tidak lagi menumpuk permintaan dan toast ("Masih memuat daftar model...").
