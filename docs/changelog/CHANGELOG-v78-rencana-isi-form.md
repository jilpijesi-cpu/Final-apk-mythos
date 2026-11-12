# v78 — Rencana pengisian form dari analisis (roadmap #4) + perbaikan guard

## Baru
- Tombol "Rencanakan isi form dari catatan" di Browser Agent. Alur: peta field halaman + catatan kasus pengguna + teks halaman
  (jawaban perusahaan) -> model menyusun array JSON -> `FormPlanner.parse` memvalidasi -> pengguna meninjau -> dijalankan berurutan.
- `agent/FormPlan.kt` (`FormPlanner`, `FormStep`, `FormPlanResult`), `AgentViewModel.planForm`, parameter `onPlan` pada `BrowserAgentScreen`.

## Keamanan rencana
- Hanya `fill`, `choose`, `radio`, `check`, `date`. Klik/kirim/unggah/hapus/bayar DITOLAK saat parse: model tidak bisa memicu aksi final lewat rencana.
- Rencana selalu ditinjau dulu; tidak ada eksekusi otomatis. Tombol kirim tetap ditekan pengguna lewat instruksi "klik ..." yang dijaga.
- Centang/radio bernada persetujuan tetap melewati dialog konfirmasi saat dijalankan; Batal menghentikan sisa rencana.
- Kolom password/PIN/OTP/CVV/token/captcha ditolak. Teks halaman dan daftar field disanitasi, dibatasi panjang, dan dibungkus `<data>`.
- Maksimum 40 langkah, nilai maksimum 6000 karakter, tanggal tidak valid ditolak (tidak ditebak), duplikat dilewati.

## Perbaikan bug (ditemukan saat menulis tes)
- `BrowserActionGuard` TIDAK mengenali "Saya menyetujui syarat dan ketentuan" ("menyetujui" tidak memuat kata "setuju" utuh).
  Ditambah: menyetujui, persetujuan, i agree. Tautan "Syarat dan Ketentuan" tetap tidak memicu konfirmasi.

## Belum
- Belum dikompilasi/diuji di Android. Jalankan `./gradlew :app:testDebugUnitTest`.
- Kualitas rencana bergantung pada model; selalu tinjau sebelum "Jalankan rencana".
