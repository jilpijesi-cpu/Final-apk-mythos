# Laporan Audit Kode dan Unit Test MYTHOS (Diperbarui)

Audit awal: 15 Oktober 2026
Pembaruan: 21 Oktober 2026 (berdasarkan kode di arsip `automatic-couscous-2`, berkas diubah 20 Oktober 2026)
Proyek: `com.build.mythos`
Metode: inspeksi statis Kotlin/Gradle/Manifest dan peninjauan unit test. **Build dan test belum dijalankan** karena lingkungan audit tidak memiliki Android SDK.

## Ringkasan eksekutif

- Dua temuan prioritas tinggi (H-01, H-02) dan dua temuan sedang (M-01, M-02) dari audit awal **sudah diperbaiki di kode**. Masalah bit eksekusi `gradlew` (M-04) juga sudah beres.
- Ukuran proyek sekarang: **65 file Kotlin produksi**, **26 file test**, dan **208 anotasi `@Test`** (audit awal: 49 file, 12 kelas test, 86 test).
- Yang masih terbuka: belum ada test instrumentasi Android (M-03), thread manual masih tersebar (L-01), dan **belum ada bukti tertulis bahwa test lulus**. Hasil sebenarnya ada di GitHub Actions.

## Status temuan audit awal

| ID | Temuan | Status | Bukti di kode |
|---|---|---|---|
| H-01 | Mode otonom aktif secara default | Selesai | `AgentLoop.kt:13` default `false`. Mengaktifkan lewat Pengaturan butuh konfirmasi eksplisit (`autonomous_ack`). `MainActivity.onCreate` mematikan mode otonom lama pada pengguna yang belum pernah menyetujui. |
| H-02 | Fallback Keystore menyimpan API key plaintext | Selesai | `Secret.encrypt()` mengembalikan `Result`. Saat gagal, key tidak disimpan dan pengguna diberi pesan. Hasil dekripsi dibedakan: `Missing`, `Corrupt`, `KeystoreUnavailable`. |
| M-01 | `timeout rm`, `nice rm`, `find -exec rm` hanya WRITE | Selesai | `Classify.kt` memahami pembungkus `timeout`/`nice` dan menelusuri `find -exec`. `ClassifyTest` sekarang mengharapkan `Risk.DELETE`. |
| M-02 | `usesCleartextTraffic` aktif global | Selesai | Manifest memakai `networkSecurityConfig`. Cleartext dilarang kecuali `localhost` dan `127.0.0.1`. |
| M-03 | Tidak ada test integrasi/instrumentasi | **Belum** | Folder `app/src/androidTest` tidak ada. |
| M-04 | `gradlew` tanpa izin eksekusi, workflow memakai `gradle` global | Selesai | `gradlew` bermode `rwxr-xr-x` di arsip. Workflow memakai `./gradlew testDebugUnitTest` dan `./gradlew assembleDebug`. |
| L-01 | Thread manual dan `Thread.sleep` tersebar | **Belum** | Masih ada di 10 file (`AgentLoop`, `ToolExecution`, `TermuxFiles`, `CliLogin`, `FileImport`, `LiveTail`, `MainActivity`, `ModelCall`, `SettingsDialog`, `ModelPicker`). |
| L-02 | Error Keystore ditelan tanpa pembeda | Selesai | `readStoredKey` memberi pesan berbeda untuk key hilang, rusak, dan Keystore tidak tersedia. Key rusak atau hilang otomatis dihapus. |

## Rincian perbaikan yang diverifikasi

### H-01: Mode otonom
- Default `autonomous` sekarang `false` di `AgentLoop.kt`, `SettingsDialog.kt`, dan `MainActivity.kt`.
- Saat pengguna menyalakan mode otonom di Pengaturan tetapi belum pernah menyetujui, muncul dialog persetujuan. Setelah itu baru `autonomous_ack` disimpan.
- `bypassesApproval()` di `CommandPolicy.kt` memisahkan logika agar bisa diuji. Parameter `force` tidak dapat dilewati oleh mode otonom maupun "Ya, semua".
- "Ya, semua" tidak membungkam tindakan yang bertanda peringatan area sensitif.
- Test: `ApprovalPolicyTest` dan `CommandPolicyTest` memastikan default (`autonomous=false`, `allowAll=false`) masuk ke `confirm()`.

Catatan: pada mode otonom, `ask_user` masih tidak diteruskan ke pengguna (`AskUser.kt:21`). Ini sekarang dapat diterima karena mode otonom sudah opt-in, tetapi tetap perlu dijelaskan di teks dialog persetujuan.

### H-02: Penyimpanan API key
- Ada abstraksi `SecretCryptoProvider` sehingga kegagalan Keystore bisa diuji di JVM.
- `putEncryptedValue()` hanya menyimpan hasil enkripsi yang sukses. `SecretStorageTest` memastikan plaintext tidak pernah masuk penyimpanan jika provider gagal.
- `SettingsDialog.save()` tidak menyimpan key jika enkripsi gagal dan menampilkan pesan.
- `migrateKeys()` menghapus key lama berbentuk plaintext yang gagal diamankan, lalu meminta pengguna memasukkannya ulang.

### M-01 dan M-02
- Perilaku pembungkus dan `find -exec` sudah masuk ke test `penghapusDiBalikPembungkusMenjadiDELETE`.
- `network_security_config.xml` mengizinkan HTTP hanya untuk localhost, cukup untuk preset Ollama lokal.

## Temuan yang masih terbuka

### M-03: Belum ada test instrumentasi
Area yang belum diuji otomatis:
- Lifecycle `MainActivity` (rotasi, pemulihan state, penghentian worker).
- `AgentLoop` dengan streaming dan pembatalan.
- Jembatan Termux (`RUN_COMMAND`, receiver, timeout).
- Pemasangan APK dan `FileProvider`.
- Parsing SSE dan retry pada provider HTTP.
- `Secret` pada Android Keystore asli (test sekarang hanya memakai provider palsu).

Rekomendasi: tambahkan test JVM untuk provider dengan transport palsu, lalu test instrumentasi minimal di emulator untuk Keystore, FileProvider, dan jembatan Termux.

### L-01: Thread manual
Migrasi ke coroutine dan `ViewModel` sebaiknya dilakukan setelah build stabil dan test instrumentasi ada. Jangan dijadikan syarat untuk rilis.

### Verifikasi build
Belum ada bukti bahwa `./gradlew testDebugUnitTest assembleDebug` lulus untuk kode versi ini. Periksa run GitHub Actions terakhir pada commit yang sesuai.

## Evaluasi unit test

### Yang membaik sejak audit awal
- Jumlah test naik dari 86 menjadi 208 anotasi `@Test`, dan file test dari 12 menjadi 26.
- Default preferensi dan kebijakan persetujuan kini diuji (`ApprovalPolicyTest`, `CommandPolicyTest`).
- Kegagalan Keystore kini diuji lewat provider palsu (`SecretStorageTest`).
- Test klasifikasi tidak lagi mengunci celah sebagai `WRITE` untuk pembungkus penghapus.

### Masih kurang
- Logika migrasi key lama kini diuji lewat `migrateStoredKey()` (`KeyMigrationTest`, 5 test). Alur pesan `keyWarning` di UI masih belum diuji.
- Tidak ada test concurrency untuk latch, receiver, timeout, atau Activity yang ditutup saat worker berjalan.
- Tidak ada test untuk preferensi `autoread` dan `readonly`.
- Tidak ada test instrumentasi sama sekali.

## Urutan langkah berikutnya

1. Cek hasil GitHub Actions untuk commit ini. Jika merah, perbaiki sebelum hal lain.
2. ~~Tambahkan test untuk `migrateKeys()`~~ Sudah ditambahkan (`KeyMigrationTest`), tetapi belum pernah dikompilasi atau dijalankan.
3. Buat `app/src/androidTest` dengan test minimal: Keystore asli, `FileProvider`, dan jembatan Termux.
4. Tambahkan test provider HTTP dengan transport palsu (OpenAI, Anthropic, Gemini, termasuk stream terputus).
5. Setelah stabil, migrasikan thread manual ke coroutine secara bertahap, mulai dari `ModelCall` dan `LiveTail`.

## Kesimpulan

Risiko keamanan utama dari audit awal (mode otonom default dan key plaintext) sudah ditutup di kode, begitu pula celah klasifikasi penghapus dan cleartext global. Sebelum APK dibagikan ke pengguna lain, pastikan build GitHub Actions hijau dan jalankan aplikasi sekali di perangkat nyata untuk memeriksa alur Keystore dan dialog persetujuan mode otonom.

## Catatan perubahan sesudah audit: konfirmasi minimal (21 Oktober 2026)
Atas permintaan pemilik aplikasi, dialog persetujuan dikurangi. Pengaturan "Konfirmasi seperlunya" (bawaan aktif) membuat perintah rutin dan penulisan file
(yang selalu dicadangkan dulu) jalan tanpa dialog; yang bertanya hanyalah AI lewat `ask_user`. Aplikasi tetap bertanya sendiri untuk:
penghapusan/penimpaan data (Risk.DELETE), tindakan bertanda peringatan (area sensitif, di luar folder kerja, folder sistem Termux),
perintah berjaringan di mode sandbox (`force`), dan menjalankan CLI. Perintah yang diblokir sandbox tetap diblokir.
Ini melonggarkan temuan H-01 secara terbatas: berbeda dari mode otonom, `ask_user` tetap diteruskan dan batas di atas tidak bisa dilewati.
Dapat dimatikan di Pengaturan > Lanjutan. Dicatat di log sebagai "Otomatis (konfirmasi minimal)". Tes: `MinimalConfirmTest`.


## Catatan 27 Okt 2026
- Ditambahkan `contentDescription` pada tombol utama (aksesibilitas) dan lint di CI. 14 thread lepas dipindah ke `bg {}` (L-01 sebagian selesai; AgentLoop dipindah ke Job yang bisa dibatalkan (`bgCancellable`); LiveTail jadi coroutine polling; hanya thread ApkInstall(rx) yang dipertahankan). M-03 (tes instrumentasi) masih terbuka.
