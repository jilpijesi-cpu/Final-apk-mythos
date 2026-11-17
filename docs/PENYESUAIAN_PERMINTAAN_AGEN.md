# Penyesuaian atas 5 permintaan agen

1. Metadata proyek: `create_android_project` kini membuat README.md (struktur + alur) dan info.json (nama, package, dependensi utama). StudioProject.kt.
2. Timeout: `self_audit`/`self_upgrade` naik dari 300 dtk ke 900 dtk (`SELF_SCRIPT_TIMEOUT_SEC`); di sandbox memakai batas panjang 1800 dtk. SelfUpgradeIntegration.kt.
3. Folder: struktur baru `MYTHOS/projects/aktif` dan `MYTHOS/projects/arsip` (dibuat otomatis); prompt agen dan pesan penolakan menunjuk ke `projects/aktif`. Proyek lama di `MYTHOS/projects/` perlu dipindah manual: `cd ~/MYTHOS/projects && for d in */; do [ "$d" = aktif/ ] || [ "$d" = arsip/ ] || mv "$d" aktif/; done`
4. Log error: setiap kegagalan alat (kecuali ditolak/dibatalkan) ditambahkan satu baris ke `MYTHOS/logs/error_summary.log` (maks 500 baris). ErrorSummaryLog.kt + tes.
5. Penamaan: folder kebab-case, file kelas Kotlin PascalCase; aturan ini masuk prompt sistem agen (ContextSummary.kt).

## Versi 2.8 (versionCode 19)
- Seluruh MYTHOS/reference (source terbaru + MYTHOS/reference/alat) terkunci untuk agen: hanya baca dan salin. TermuxFiles.kt (referenceRoot), ToolExecution.kt, ContextSummary.kt.
- Audit boleh langsung di reference (hanya membaca); perubahan dan build dikerjakan di MYTHOS/projects/aktif (salin dengan alat/salin-ke-aktif.sh).
- tools/bersihkan-mythos.sh: pembersih folder kerja (default hanya daftar, hapus dengan --hapus).
- Pemasangan referensi: lihat paket-referensi-2.8.zip (pasang-referensi.sh).

## Versi 2.9 (versionCode 20)
- Perbaikan kunci reference: skrip resmi di MYTHOS/reference/alat boleh dijalankan agen (ReferencePolicy.kt + tes). kita-self.sh hanya mode audit/diff; salin-ke-aktif.sh, build-github.sh, build-termux.sh asal argumen tidak menyentuh reference. Rantai perintah (; & > ` $( ) dan pipa ke tee/sh/xargs tetap ditolak. Menulis ke reference tetap diblokir.

## Versi 2.10 (versionCode 21)
- Perbaikan dari wawancara agen: lihat PERBAIKAN_WAWANCARA_AGEN.md dan PANDUAN_ALAT.md (alat validate_project, validasi folder, penolakan kode alat di run_command, pesan galat GitHub 401/403, progres audit, peta path di prompt).
