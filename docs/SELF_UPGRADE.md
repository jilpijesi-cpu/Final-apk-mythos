# Audit diri dan upgrade diri (v2.6)

Status: **belum dikompilasi dan tes Kotlin belum dijalankan** (tidak ada kotlinc di lingkungan kerja). Yang sudah dijalankan nyata:
skrip `app/src/main/assets/kita-self.sh` (audit, baseline, diff, deteksi tamper, bump, restore) pada salinan proyek ini.
Jalankan: `./gradlew testDebugUnitTest assembleDebug`.

## Cara memakai
Katakan ke agen di aplikasi: "audit dirimu" atau "upgrade dirimu: tambah fitur X". Agen memakai dua tool baru:

- `self_audit(folder)`: hanya membaca. Laporan temuan berurut keparahan + status aplikasi terpasang.
- `self_upgrade(folder, tahap)`: `mulai` (cadangan + baseline) -> agen mengubah kode, menaikkan versionCode, `build_apk`, tes ->
  `periksa` (gerbang) -> `pasang` (kartu Pasang APK; **pengguna yang mengetuk**). `pulihkan` bila versi baru bermasalah.

`folder` = folder kode sumber aplikasi ini relatif terhadap folder kerja. Tool menolak folder yang `applicationId`-nya bukan
aplikasi ini, jadi kode aplikasi lain di folder campuran tidak ikut tersentuh.

## Prinsip
1. **Fakta dari skrip tepercaya, bukan tebakan model.** Skrip dikirim dari aplikasi tiap dipakai (bukan dibaca dari folder proyek).
   Rahasia tidak pernah dicetak: temuan kunci hanya berisi berkas:baris.
2. **Versi yang berjalan menilai versi berikutnya.** Gerbang (`evaluateUpgrade`) ada di versi terpasang. Berkas gerbang, skrip,
   persetujuan, klasifikasi risiko, enkripsi kunci, manifest, gradle, dan workflow adalah *area terlindungi* (`ProtectedCore`):
   mengubahnya selalu meminta persetujuan pengguna lewat dialog `force` (tidak bisa dilewati mode otonom atau "Ya, semua").
3. **Pengguna tetap yang memasang.** Android mewajibkan ketukan pengguna di pemasang sistem; itu batas sebenarnya.

## Aturan gerbang
DIBLOKIR (tidak bisa dipasang sebelum diperbaiki): folder bukan aplikasi ini; baseline tidak tercatat atau hash berubah di luar
aplikasi; tidak ada perubahan; APK belum ada atau lebih tua dari kode; versionCode tidak lebih tinggi dari yang terpasang;
jumlah `@Test` turun atau berkas tes dihapus; tes unit baru saja dijalankan dan gagal; fungsi pengaman (kanari S08) hilang;
temuan audit TINGGI baru.

PERLU PERSETUJUAN PENGGUNA: area terlindungi berubah; izin manifest baru; dependensi/plugin baru atau berubah versi;
temuan audit SEDANG baru; tes unit belum dijalankan sejak perubahan terakhir.

LULUS: tidak ada di atas.

## Pemeriksaan APK saat dipasang (berlaku untuk SEMUA pemasangan APK paket ini)
`installApk` kini memeriksa paket, versionCode, dan **kesinambungan tanda tangan** sebelum membuka pemasang. Tanda tangan beda
membuat Android menolak upgrade (atau memaksa uninstall yang menghapus kunci API dan riwayat chat). Agar upgrade bisa dipasang,
semua build harus memakai keystore yang sama: `app/build.gradle.kts` kini menandatangani debug dengan `keystore.properties` bila ada
(lihat `keystore.properties.example`). Buat identitas baru di HP dengan `bash tools/buat-keystore.sh` (sandi acak, kunci tidak keluar dari perangkat); workflow GitHub memakai rahasia `KEYSTORE_B64` dan `KEYSTORE_PASSWORD`. Identitas baru berarti APK lama (kunci debug) tidak bisa ditimpa: uninstall sekali, lalu pasang versi baru. Tanpa itu, APK dari GitHub Actions dan dari Termux berbeda tanda tangan dan tidak bisa saling menimpa.

## Pemulihan
Android menolak turun versi. Jadi `pulihkan` mengembalikan kode ke baseline lalu menaikkan versionCode (dan memberi akhiran `-rN`
pada nama versi) supaya bisa dipasang di atas versi bermasalah. Setelah itu `build_apk`, `periksa`, `pasang`.
`StartupHealth` menandai versi baru yang dibuka berulang kali tanpa bertahan 10 detik dan menawarkan pemulihan.
Cadangan: `.kita-upgrade/snap-*.tgz` (3 terakhir, sudah masuk `.gitignore` lewat `.kita-*`).

## Batas jujur
- Gerbang melindungi dari kesalahan dan kemunduran, bukan dari agen yang sengaja memusuhi dan punya shell penuh.
- Audit statis dan heuristik (grep): tidak membuktikan build atau tes lulus.
- Bila versi baru crash sebelum menampilkan apa pun, logika di dalam aplikasi tidak bisa berjalan; pemulihan lewat cadangan/git di folder proyek.
- `pasang` tidak didukung di mode sandbox (APK ada di dalam sandbox). Matikan sandbox atau bangun lewat GitHub Actions.
- Heuristik celah tes mencocokkan nama deklarasi dengan teks tes; berkas yang memakai `android.*` dilewati.
- Dua versi baru tanpa tes unit dijalankan selalu berujung persetujuan pengguna (sengaja: bukti kurang).
