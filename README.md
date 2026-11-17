# MYTHOS (Mythos)

Aplikasi agen AI untuk Android (`com.build.mythos`, minSdk 26) yang menjalankan alat lewat Termux,
dengan persetujuan pengguna untuk tindakan berisiko.

## Mulai cepat
```bash
./gradlew testDebugUnitTest   # tes unit (JVM murni)
./gradlew assembleDebug       # APK debug -> app/build/outputs/apk/debug/
./gradlew lintDebug           # lint (hasil: app/build/reports/)
```
Build satu perintah di Linux (menyiapkan Android SDK Platform 34 dan Build Tools 34.0.0 bila belum ada):
```bash
./build.sh                    # pertama kali akan meminta persetujuan lisensi Android SDK
```
Perlu JDK 17+, `curl`, `unzip`, dan koneksi internet untuk penyiapan awal. Untuk Termux, gunakan `bash build-termux.sh`.
Build di GitHub Actions: `.github/workflows/build-apk.yml` (push, pull request, manual).
Penandatanganan rilis: salin `keystore.properties.example` menjadi `keystore.properties`
(jangan di-commit; sudah ada di `.gitignore`).

## Peta dokumen
| Butuh apa | Baca |
|---|---|
| Kondisi aplikasi saat ini (sumber kebenaran) | `docs/DOKUMENTASI.md` |
| Struktur kode | `docs/STRUKTUR.md` |
| Daftar alat agen, peta path, galat GitHub | `docs/PANDUAN_ALAT.md` |
| Sandbox perintah | `docs/SANDBOX.md` |
| Konektor email, Telegram, WhatsApp | `docs/KONEKTOR.md` |
| Sinkronisasi pengaturan MYTHOS–Termux | `docs/SINKRONISASI_DUA_ARAH_TERMUX.md` |
| Audit diri dan upgrade diri | `docs/SELF_UPGRADE.md` |
| Tampilan dan animasi | `docs/PERUBAHAN_TAMPILAN.md` |
| Audit kode dan status temuan | `docs/AUDIT_KODE_DAN_UNIT_TEST.md` |
| Riwayat perubahan ringkas | `CHANGELOG.md` |
| Laporan lama | `docs/arsip/` dan berkas `docs/AUDIT_*`, `docs/PERBAIKAN_*` |

Berkas `AUDIT_*` dan `PERBAIKAN_*` dibiarkan di akar karena dirujuk oleh `WorkspaceMigration.kt`
dan komentar tes; jangan dipindahkan tanpa memperbarui rujukan itu.
