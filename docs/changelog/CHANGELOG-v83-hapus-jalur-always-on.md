# v83 - Tindak lanjut audit no. 2, 3, 5, 6, dan 8: hapus jalur always-on; pembatalan cadangan + TTL berkas live; OAuth state; versi dan CI; batas zip + Teredo

## Dihapus
- `CodexPersistentService`, `CodexAlwaysOnBootReceiver`, `CodexPersistentStatus`, dan klien sesi persisten (satu berkas: `CodexPersistentService.kt`).
- Aset `codex_persistent_app_server.js` dan `TermuxLauncher.buildPersistentAppServerCommand` / `startPersistentCodexRunner`.
- Izin `FOREGROUND_SERVICE_SPECIAL_USE`, deklarasi service `specialUse`, dan receiver boot Codex di manifest
  (menyederhanakan tinjauan Play; `RECEIVE_BOOT_COMPLETED` tetap dipakai `DeadlineReceiver`).
- Saklar "Codex selalu aktif" di Pengaturan, `UserSettings.codexAlwaysOn`, `CODEX_PER_REQUEST_ONLY`, serta alur izin notifikasi yang hanya
  melayani saklar itu. Kunci lama `codex_always_on` dibersihkan dari SharedPreferences saat pengaturan disimpan berikutnya.

## Dokumentasi
- README tidak lagi menyebut mode selalu aktif. Changelog v80-v81 yang menyebutnya adalah riwayat dan tidak diubah.

## Tes
- Dihapus: `CodexPersistentServiceTest`, dua tes runner persisten di `TermuxLauncherTest`, tes `codexAlwaysOn` di `SettingsTest`.
- `CodexHardeningTest` kini hanya memeriksa `codex_app_server.js`.
- Catatan: tes Kotlin (`gradle :app:testDebugUnitTest`) belum dijalankan; hanya `node --check` dan pemeriksaan keseimbangan kurung.

## Pembatalan dan berkas live (audit no. 3)
- Jalur pembatalan cadangan: bila sesi dibatalkan, timeout, atau gagal sebelum selesai wajar (mis. soket belum tersambung saat Termux lambat),
  aplikasi kini mengirim perintah Termux `pkill` (pola `[a]pp_server\.js PORT TOKEN`, unik per sesi) lalu `rm` berkas live.
  Runner menangani SIGTERM/SIGHUP/SIGINT: menghentikan process group Codex, lalu keluar paling lambat 4 detik. SIGKILL cadangan setelah 2 detik.
- TTL: tiap sesi baru menghapus `~/.mythos_codex/live/*` dan `app_server.js.*` yang lebih tua dari 60 menit (timeout sesi 15 menit, jadi sesi
  paralel yang hidup aman). Berkas live yang tertinggal karena aplikasi dimatikan paksa tidak lagi menumpuk.
- Diuji di shell: pola `pkill` hanya mengenai sesi bersangkutan (sesi lain dengan port berbeda tetap hidup), runner uji keluar setelah SIGTERM,
  dan filter TTL hanya menghapus berkas lama.
- Tes baru: `TermuxLauncherTest` (TTL, perintah bunuh + validasi masukan, pemanggilan di `finally`) dan `CodexHardeningTest` (handler sinyal).
- Asumsi yang belum teruji di perangkat: `pkill`/`find` tersedia di Termux (`pgrep` sudah dipakai kode lama).

## OAuth Google (audit no. 5)
- URL otorisasi kini membawa `state` acak (24 byte, `SecureRandom`) yang disimpan di sesi PKCE terenkripsi; tautan balik hanya diproses bila
  `state`-nya cocok (perbandingan waktu-konstan). Berlaku untuk login sekali ketuk (Konektor) maupun tombol di Pengaturan.
- Pencocokan redirect diperbaiki: sebelumnya kondisi `scheme != ... && !path.contains(...)` menerima URI apa pun berskema `com.mythos.app`.
  Kini hanya `com.mythos.app:/oauth2redirect` tanpa host yang diterima; sisanya diabaikan diam-diam.
- Sesi PKCE kedaluwarsa setelah 10 menit dan dihapus dari penyimpanan saat dibaca. Sesi lama tanpa `state` (sebelum v83) ditolak: pengguna
  cukup menekan Hubungkan lagi.
- Tautan balik palsu tidak menghapus sesi yang sedang berjalan, jadi tidak bisa menggagalkan login pengguna.
- Alur tempel manual: kode mentah tetap diterima (PKCE tetap melindungi); bila yang ditempel URL berisi `state`, harus cocok.
- Tes baru di `GoogleWorkspaceTest`: state di URL, pencocokan redirect, verifikasi state, TTL, sesi lama, round-trip JSON.
- Catatan: tes Kotlin belum dijalankan; regex `state` dicek terpisah dan keseimbangan kurung kurawal diperiksa.

## Kebersihan rilis dan CI (audit no. 6)
- Versi: `versionCode`/`versionName` sebelumnya 74/1.0.74 padahal nomor rilis dan changelog sudah v83. Kini sumber tunggalnya
  `gradle.properties` (`mythosVersionCode=83`, `mythosVersionName=1.0.83`), dibaca `app/build.gradle.kts`. Versi 1.0.74 yang tertulis keras di
  `codex_app_server.js` diganti placeholder `__MYTHOS_VERSION__` yang diisi `BuildConfig.VERSION_NAME` saat runner dikirim ke Termux.
  `VersionConsistencyTest` gagal bila versionCode tidak sama dengan nomor changelog terbaru, atau bila angka versi ditulis keras lagi.
  Changelog yang sebelumnya berhenti di v81 kini sampai v83 (v82 dan v83 ditambahkan oleh patch ini).
- CI: ditambah langkah `gradle/actions/wrapper-validation@v4` yang memverifikasi `gradle-wrapper.jar` terhadap rilis resmi Gradle.
- Pin action ke SHA: klaim di `dependabot.yml` bahwa action sudah dipin ke SHA tidak benar (semua `uses:` memakai tag); komentarnya dikoreksi.
  `scripts/pin-actions.sh --check` (tanpa jaringan) kini melaporkan action yang belum dipin dan keluar dengan kode 1.
  SHA TIDAK saya isi dari ingatan karena tidak bisa diverifikasi tanpa jaringan: jalankan `bash scripts/pin-actions.sh` sekali di mesin berjaringan.
- `distributionSha256Sum` wrapper Gradle: skrip baru `scripts/pin-gradle-wrapper.sh` mengunduh distribusi, membandingkannya dengan
  checksum resmi di services.gradle.org, lalu menulis propertinya (idempoten; menolak bila tidak cocok). Nilainya sengaja belum ditulis,
  karena checksum yang salah akan mematahkan semua build. Skrip diuji dengan curl palsu: sukses, idempoten, dan checksum salah ditolak.
- Belum diubah: `continue-on-error` pada Lint dan pemanggilan `gradle` (bukan `gradlew`) di CI. Lint belum pernah dijalankan pada kode ini,
  jadi menjadikannya memblokir sebelum ada hasil pertama berisiko menghentikan semua build.

## Temuan minor (audit no. 8)
- `AttachmentText.zipEntries` (docx/pptx/xlsx): selain batas 20 MB per entri, kini ada batas 5.000 entri dan 100 MB total byte dekompresi.
  Entri yang tidak diminta tetap dialirkan dan dihitung, karena `ZipInputStream` harus menginflasinya untuk melewatinya; sebelumnya ribuan entri
  kecil atau satu entri raksasa yang dilewati tidak terhitung sama sekali. Melewati batas melempar `IOException` (arsip dianggap rusak).
- Guard SSRF (`isPrivateAddress`): Teredo (2001:0000::/32) diblokir seluruhnya, termasuk alamat server/klien yang disematkan dan dikaburkan.
  Ditambah: dokumentasi 2001:db8::/32, discard-only 100::/64, dan ISATAP (`::5efe:a.b.c.d`) bila IPv4 di dalamnya privat.
  Alamat IPv6 global biasa (mis. 2606:4700:4700::1111, 2001:4860:4860::8888) tetap lolos; ada tes untuk keduanya.
- Tes baru: `WebTest` (Teredo/ISATAP/rentang khusus) dan `AttachmentsTest` (batas entri, batas total, entri dilewati ikut dihitung).

## Cacat yang ikut hilang
- `startForeground` gagal yang mematikan preferensi pengguna selamanya, loop wake lock ganda, dan race `isBound`.

## Belum dikerjakan dari audit
Sisa no. 6 (jalankan dua skrip pin di atas; lint memblokir) dan no. 7.
