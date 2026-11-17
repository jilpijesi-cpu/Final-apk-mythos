# Berangkas aplikasi (hanya-baca)

Versi berangkas: 1 (mengikuti versi aplikasi; isi diganti otomatis saat upgrade).
Agen boleh MEMBACA dan MENYALIN semua yang ada di sini. Agen tidak boleh mengubahnya.
Baca daftar ini dulu, lalu buka hanya yang perlu.
Alat: `berangkas_baca` (lihat isi) dan `berangkas_salin` (salin ke Termux di `~/.mythos/berangkas`, dibuat ulang dari aplikasi tiap dipanggil).

## Aturan
- `ATURAN.md`: cara kerja agen (kesiapan, berangkas, build, rahasia). Baca di awal tugas.

## Skrip (salin ke Termux sebelum dijalankan)
- `skrip/kita-cek.sh`: cek kesiapan alat di Termux (+ proyek bila folder diberikan). Hanya membaca. Penanda akhir: `KITA_SIAP`, `KITA_PROYEK`.
- `skrip/build-github.sh`: build APK lewat GitHub Actions (push, tunggu workflow, unduh APK). Penanda: `KITA_BUILD` (ok|artifact-only|gagal), `KITA_APK`, `KITA_ARTIFACT`, `KITA_WORKFLOW`, `KITA_TESTS`.
- `skrip/buat-keystore.sh`: membuat keystore penandatangan baru di perangkat.

## Panduan
- `panduan/PANDUAN_ALAT.md`: alat bawaan agen dan cara memakainya.
- `panduan/SANDBOX.md`: mode sandbox (proot-distro): batas dan cara uji.

## Templat
- `templat/build-apk.yml`: workflow GitHub Actions untuk membangun APK debug.
- `templat/keystore.properties.example`: contoh format berkas keystore (tanpa nilai rahasia).

## Integritas
- `SHA256SUMS`: daftar hash semua berkas di atas. Aplikasi memeriksanya sebelum menyerahkan isi berangkas ke agen.
