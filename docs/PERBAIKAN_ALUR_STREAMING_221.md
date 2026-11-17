# Perbaikan alur streaming Mythos 2.21

## Perubahan dari 2.20
- Saat narasi aktivitas diketik, ikon tiga bola oranye tetap terlihat dan tetap menggunakan view/animasi yang sudah ada; tidak disembunyikan.
- Narasi tetap berasal dari event aktivitas Codex App Server yang benar-benar diterima, bukan rotasi kalimat acak.
- Setelah narasi selesai, teks status kembali ke `Sedang dikerjakan…` (atau status terbaru yang tertunda) sebelum narasi aktivitas berikutnya dimulai.
- Delta jawaban Codex tetap dirender langsung pada pesan assistant yang aktif; tidak ditambahkan efek ketik sintetis.
- Versi Android dinaikkan menjadi 2.21 / versionCode 34.

## Pemeriksaan
- `ClaudeDots.kt` identik byte-per-byte dengan arsip 2.19.
- `Palette.kt` identik byte-per-byte dengan arsip 2.19.
- Perubahan ikon tidak dilakukan; perubahan hanya pada transisi teks narasi/status.
- Build Android perlu dijalankan pada lingkungan yang memiliki Gradle 8.9 dan akses dependensi. Build tidak dinyatakan berhasil bila distribusi Gradle/dependensi tidak tersedia.

## Batasan
Kesamaan 1:1 dengan video belum dapat disahkan dari perubahan kode saja. Verifikasi visual final memerlukan pemasangan build 2.21 dan rekaman ulang dengan prompt uji yang sama seperti video acuan.
