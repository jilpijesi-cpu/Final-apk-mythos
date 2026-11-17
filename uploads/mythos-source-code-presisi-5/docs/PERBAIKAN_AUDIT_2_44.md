# Perbaikan temuan audit MYTHOS 2.44 (F-01 s/d F-09)

Status verifikasi: **kode diubah tetapi BELUM dikompilasi dan BELUM dijalankan di lingkungan pembuat perbaikan**
(tidak ada Android SDK/Kotlin/Gradle di sana). Yang sudah dijalankan sungguhan: skrip shell (`bash -n`, uji git nyata untuk
penjaga rahasia, uji `cek-source-bundle.sh` dengan APK palsu), regex penjaga, kesesuaian `BuildApk.SCRIPT` dengan
`tools/build-github.sh`, dan validasi YAML. Wajib dijalankan di CI/Termux: `./gradlew testDebugUnitTest assembleDebug`.

| ID | Perbaikan |
|----|-----------|
| F-01 | `minimalAutoApproves(..., shell)`: perintah shell hanya jalan tanpa dialog bila terbukti READ. Shell WRITE, interpreter, dan skrip selalu minta persetujuan. `scriptTargets()` mendeteksi `bash x.sh`, `python x.py`, `node x.js`, `./x`; dialog menampilkan SHA-256 dan isi skrip (1800 karakter pertama); `scriptPinGuard()` membatalkan perintah (exit 97) bila skrip berubah setelah disetujui. `bash -c` ikut ditandai. |
| F-02 | `tools/build-github.sh`: berkas bernama rahasia dikeluarkan dari staging; yang sudah terlacak di HEAD menghentikan build; isi semua berkas dipindai (`git grep --cached`) untuk kunci privat/token sebelum commit; push dibatalkan (gagal tertutup). `.gitignore` diperluas. |
| F-03 | `packSource`: daftar pengecualian diperluas + pagar kedua yang memeriksa NAMA dan ISI arsip setelah dibuat dan menggagalkan build. CI menguji dengan fixture rahasia palsu dan `tools/cek-source-bundle.sh`. |
| F-04 | Skrip memisahkan `KITA_ARTIFACT`, `KITA_WORKFLOW`, `KITA_TESTS`; `KITA_BUILD=ok` hanya bila workflow sukses dan tes lulus, selain itu `artifact-only`. App tidak menampilkan "APK siap" dan tidak menawarkan pasang untuk `artifact-only`. |
| F-05 | Token dikirim lewat `RUN_COMMAND_STDIN` (Termux >= 0.109), tidak lagi ada di string perintah. Di mode sandbox token tidak dikirim (stdin tidak diteruskan); skrip memakai `gh auth`. |
| F-06 | Tes baru: `ApprovalPolicyTest`, `SecretStorageTest` (provider AES-GCM palsu, migrasi key), `RepoSecretGuardTest`; `BuildApkTest` dan `WebToolsTest` diperluas. Uji Android Keystore asli tetap butuh perangkat/instrumented test. |
| F-07 | `applicationId` dapat diganti `-PappId=<package>` (bawaan `com.build.mythos`); authority provider kini `${applicationId}.apk/.share` agar tidak bentrok bila dipasang berdampingan. Package target yang benar harus Anda tentukan sendiri; tidak ada rujukan `com.mythos.app` di repo. |
| F-08 | `distributionSha256Sum` Gradle 8.9 (`d725d707...cecab`, dicocokkan dari dua sumber), `wrapper-validation`, dan semua GitHub Actions dipin ke SHA commit penuh (checkout 4.2.2, setup-java 4.7.1, setup-android 3.2.2, setup-gradle 4.4.4, upload-artifact 4.6.2). |
| F-09 | `webGet` me-resolve host sekali, memvalidasi semua alamat, lalu menyambung ke IP tervalidasi (`WebTools.pinnedUrl`) dengan SNI/verifikasi sertifikat tetap atas hostname asli. Perlu dicoba di perangkat. |

## Batas yang jujur
- F-01 tetap analisis teks. Isolasi OS (proot) bukan batas keamanan. Mode otonom/"Ya, semua" tanpa peringatan tetap sesuai pilihan pengguna. `make`, `npm run`, dan `./gradlew` menjalankan kode tak terlihat; `./gradlew` kini ditandai skrip.
- F-02: pemindai pola tidak menangkap semua rahasia; berkas yang sudah pernah ter-push perlu rahasia diputar ulang.
- F-04: penanda `KITA_*` yang dicetak log workflow masih bisa dipalsukan oleh isi log; `artifact-only` menang atas `ok`.

## Tambahan: agen wajib menjelaskan rencana dulu
- `ToolDocs.PLAN_FIRST_RULE` masuk ke prompt agen: sebelum function-call pertama pada tugas, tulis 1-3 kalimat tentang apa yang akan dikerjakan.
- Penjaga di `AgentLoop`: bila giliran pertama langsung memanggil alat tanpa penjelasan (< 30 karakter, kecuali hanya `ask_user`), panggilan itu DITAHAN (tidak dijalankan) dan model diminta menjelaskan lalu mengulang. Ditahan maksimal sekali per tugas agar tidak berputar. Tes: `PlanFirstTest`. Belum dikompilasi.
