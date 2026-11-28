# Status perbaikan audit keamanan

Diperbarui: 24 November 2026 (hasil audit ulang atas isi arsip, dibaca statis; belum dikompilasi atau dites oleh Gradle).
Penomoran mengikuti laporan audit menyeluruh.

## Hasil audit ulang: dokumen lama usang
Beberapa temuan ternyata sudah ada perbaikannya di kode, tetapi tabel lama masih menulis BELUM.

| # | Temuan | Status sekarang | Bukti |
|---|---|---|---|
| 2 | Symlink hasil ekstraksi zip | **SUDAH** | `buildExtractCommand` (`AttachmentDigest.kt`) menolak zip bersimpul dan menghapus hasil bila ada symlink |
| 5 | Pembungkus data tak tepercaya | **SUDAH (24 Nov 2026)** | `Cx.neutralizeMarkers` (`ConnectorsCore.kt`): tak peka huruf, toleran spasi, menetralkan penanda pembuka dan penutup; tes di `ConnectorsTest.kt` |
| 8 | `CommandLog` menyimpan token | **SUDAH** | `CommandLog.toJson` memanggil `redactSecrets` pada perintah, deskripsi, dan hasil |
| 12 | `.gitignore` tidak ada | **SUDAH** | `.gitignore` ada dan memuat keystore, `*.jks`, `.env`, dll. |
| - | Workflow menulis `keystore.properties` plaintext | **SUDAH** | langkah `if: always()` menghapus `keystore.properties` dan material kunci |
| 7 | Skrip tanpa `set -e` | **Disengaja** | skrip memakai `set -uo pipefail`; `-e` sengaja tidak dipakai agar skrip bisa lanjut. Tetap tinjau per skrip |

## PERINGATAN: Batas keras (temuan 1) tidak ada di arsip ini
Dokumen lama menandai temuan 1 "SELESAI" dengan berkas `HardLimits.kt`, `HardLimitsTest.kt`, `DownloadLedger.kt`.
**Berkas-berkas itu tidak ada di arsip ini**, dan tidak ada kode yang memanggilnya (hanya `ToolPathTest.kt` menyebut namanya
dalam daftar kelas). `docs/BATAS_KERAS.md` karenanya tidak menggambarkan kode yang ada.
Selain itu, agen utama kini berjalan lewat paket `cc` (`CcBridge.kt`, `cc/Agent.kt`); di paket itu tidak ditemukan alat shell bebas,
jadi sebagian jalur yang dijaga batas keras lama mungkin sudah tidak ada. Yang perlu diputuskan pemilik:
1. cari `HardLimits.kt` di sumber asli dan masukkan kembali; atau
2. nyatakan batas keras dihapus dan perbarui `BATAS_KERAS.md`.
Jangan mengandalkan dokumen itu sampai salah satunya dilakukan.

## Masih terbuka
| # | Temuan | Tingkat | Catatan |
|---|---|---|---|
| 3 | Workspace absolut (mis. `/sdcard`) tidak dibatasi ke HOME | Sedang | `requireHomeContainment = !startsWith("/")`: path absolut sengaja dilepas; beri peringatan keras di UI |
| 4 | Pemasangan CLI tanpa versi terpatok | Sedang | **Sebagian**: Gemini CLI dipatok `0.71.1` (`GEMINI_CLI_VERSION`). Codex berasal dari repositori Termux (`pkg`), versi uji `0.172.0` dicatat di `CODEX_TESTED_VERSION` tetapi tidak dipatok di perintah (repositori hanya menyimpan versi terbaru). Claude Code masih `latest`: butuh versi uji |
| 6 | Isi pesan WA/Telegram/email bisa sampai ke penyedia model | Sedang | perlu keputusan produk: opt-in eksplisit sebelum isi pesan dikirim ke model |
| 10 | APK paket lain tidak diverifikasi tanda tangannya | Rendah | `SelfUpgradeIntegration.kt`: hanya paket sendiri diperiksa |
| 11 | `compileSdk`/`targetSdk` 34 | Rendah | naikkan sesuai syarat Play Store, lalu uji izin dan foreground service |
| - | ProGuard minimal | Rendah | `keepclassmembers enum ** { *; }` terlalu luas |
| - | Bitrise membangun debug tanpa tanda tangan | Info | jangan distribusikan APK dari Bitrise |

## Aturan pembaruan dokumen ini
Setiap temuan yang selesai dipindahkan ke tabel "Sudah dikerjakan" dengan berkas yang diubah dan nama tesnya.
Jangan menandai SELESAI sebelum tes unit terkait lulus di Gradle.

## Keputusan pemilik: batas keras dihapus
`docs/BATAS_KERAS.md` dihapus dan rujukannya dibersihkan (README, STRUKTUR, DANA_TERTAHAN, AUDIT-LAPORAN). Gantinya satu blok `ToolDocs.ATURAN_KERJA_GLOBAL` di `ToolGuidance.kt` (sumber tunggal, tanpa salinan dokumen), diterapkan ke semua penyedia. Pengaman di kode (persetujuan, `force=true`, `CommandPolicy`) tidak diubah. Belum dikompilasi atau dites di Gradle.
