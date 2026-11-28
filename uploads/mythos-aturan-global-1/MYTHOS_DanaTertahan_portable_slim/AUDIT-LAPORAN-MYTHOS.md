# Laporan Audit Proyek Mythos Build 2

**Tanggal laporan:** 18 November 2026  
**Objek audit:** `/home/ubuntu/mythos-build-2`  
**Auditor:** Auditor utama  
**Status keseluruhan:** **FAIL — artefak APK dapat di-assemble, tetapi quality gate unit test gagal; signing, reproducibility, persistence, lifecycle, dan integritas packaging masih memiliki temuan material.**

## 1. Scope, metode, dan batasan

Audit mencakup build Gradle dan dependency, reproducibility, signing dan packaging APK, workflow CI, secret handling, permission/manifest, hard limits dan command policy, arsitektur state/persistence/concurrency, serta kualitas dan konsistensi test. Sintesis ini menggunakan bukti file, baris kode, log build/test, ZIP listing APK, output signing report, dan hasil pemeriksaan checksum yang disediakan untuk proyek.

Metode utama:

- Membandingkan hasil `assembleDebug` dengan hasil `compileDebugUnitTestKotlin`/test gate.
- Menelusuri konfigurasi Gradle, wrapper, plugin, signing, SDK bootstrap, repository, dan workflow CI.
- Menelusuri call site enforcement pada `ToolExecution`, `AgentLoop`, custom tools, dan jalur cc agent.
- Menilai alur secret, provider, backup, network security, exported component, crash reporting, dan source bundle.
- Menilai kontrak model test terhadap API produksi serta risiko atomicity, namespace checkpoint, lifecycle, dan concurrency.
- Memeriksa isi APK secara terbatas melalui ZIP listing, hash, metadata, dan pemeriksaan signature entry.

Batasan audit:

- Verifikasi APK dilakukan dengan `/home/ubuntu/android-sdk/build-tools/34.0.0/apksigner`: v2=true, satu signer Android Debug. ZIP listing tidak memiliki entry META-INF karena APK menggunakan v2-only signing; release signing tetap belum tersedia.
- Tidak tersedia artefak release APK, `mapping.txt`, atau hasil smoke-install device/API minimum; efektivitas R8 dan release signing belum dapat dipastikan.
- Tidak ada reproduksi eksploitasi shell-obfuscation, race, stale callback, atau exfiltration secret. Temuan tersebut diklasifikasikan sebagai risiko/kelemahan desain bila bukti hanya berasal dari implementasi.
- Sintesis bersifat read-only terhadap proyek; laporan ini dibuat di luar root proyek.
- Tidak dilakukan klaim di luar bukti yang disediakan. APK yang berhasil di-assemble tidak dianggap membuktikan unit test lulus atau APK siap dipasang.

## 2. Ringkasan eksekutif

Build `:app:assembleDebug` berhasil dan menghasilkan APK debug berukuran 8.702.378 byte dengan `assets/mythos-source.zip`. Namun, `:app:compileDebugUnitTestKotlin` gagal sehingga suite unit test tidak dapat dikompilasi maupun dijalankan. Kegagalan terutama disebabkan test yang tertinggal dari kontrak API produksi: `ActivityKind.EDIT` tidak ada, konstruktor `StepRecord` tidak cocok, dan fixture `ContextTrimTest` memakai tipe `Pair<String,String>` alih-alih `Pair<Call,String>`.

Risiko paling material berada pada alur signing CI: workflow mendekode keystore dan menulis password ke checkout source sebelum menjalankan Gradle terhadap source yang sama. Jika source atau plugin yang dijalankan tidak tepercaya, signing secret dapat terbaca atau tereksfiltrasi. Selain itu, APK debug yang diaudit memang signed dengan APK Signature Scheme v2, tetapi memakai default Android Debug keystore mesin; konfigurasi release efektif kosong sehingga artefak release belum tersedia.

Reproducibility juga belum memadai. SDK command-line tools diunduh melalui URL `latest` tanpa checksum/signature verification, dependency transitif tidak dikunci atau diverifikasi, dan script lokal hanya memeriksa keberadaan Java tanpa menegakkan JDK 17. CI lint dapat gagal tanpa menggagalkan workflow karena `continue-on-error: true`.

Pada runtime, terdapat risiko correctness tinggi pada penulisan riwayat yang mengabaikan hasil `renameTo()` dan menelan exception, checkpoint agen global lintas-chat, serta pekerjaan background yang memakai scope proses-global tanpa lifecycle owner. Attachment diunggah langsung ke file final sehingga file parsial dapat tertinggal; `AgentStepStore` juga melakukan read-modify-write tanpa serialisasi.

Hal positif yang terverifikasi mencakup AndroidKeyStore AES-256-GCM, backup/transfer exclusion, cleartext network yang ditolak secara default, provider non-exported dengan canonical-path check dan read-only access, source-bundle secret guard, pin Gradle/AGP/Kotlin, serta enforcement hard-limit yang terhubung ke jalur `runTool` utama. Tidak ditemukan credential hardcoded konkret pada source/APK yang diperiksa dan tidak terbukti bypass produksi pada jalur tool utama.

## 3. Rekapitulasi temuan

Tidak ada temuan berlabel **CRITICAL** dalam bahan audit. Rekapitulasi temuan yang dideduplikasi:

| ID | Severity | Area | Lokasi utama | Dampak | Bukti utama |
|---|---|---|---|---|---|
| AUD-01 | HIGH | Test/build gate | `AgentGuardTest.kt:13,19`; `ContextTrimTest.kt:16`; `mythos-test-2.log` | Unit test tidak compile; regression/security gate tidak berjalan | `compileDebugUnitTestKotlin FAILED`, unresolved `ActivityKind.EDIT`, mismatch `StepRecord` dan `Hist.results` |
| AUD-02 | HIGH | Signing & CI secret | `.github/workflows/build-apk.yml:48-99,126-127` | Signing key/password terekspos ke build source arbitrary; risiko pemalsuan update | Workflow menulis `KEYSTORE_B64` dan `keystore.properties`, lalu menjalankan Gradle pada checkout yang sama |
| AUD-03 | HIGH | APK packaging/signature | `app-debug.apk`; `app/build.gradle.kts:59-63,179-189` | Signing debug bergantung pada keystore mesin; release key kosong | `apksigner verify`: v2=true, signer Android Debug; signing report release `Config/Store/Alias: null` |
| AUD-04 | HIGH | Persistence | `ChatHistory.kt:101-106,134-160` | Kehilangan pesan/index stale setelah rename gagal, crash, atau storage penuh | `renameTo()` diabaikan; exception ditelan |
| AUD-05 | HIGH | Checkpoint/state isolation | `AgentCheckpointStore.kt:6,32-55`; `AgentLoop.kt:83-84,144` | Resume dapat mencampur chat/workspace dan target kerja | Satu `agent_checkpoint.json` global tanpa validasi chat/workspace |
| AUD-06 | HIGH | Lifecycle/concurrency | `Background.kt:19-23`; `FileImport.kt:185-212`; `MainActivity.onDestroy()` | Job tetap berjalan setelah Activity/workspace berubah; stale UI update dan referensi lifecycle | Scope proses-global; import tanpa cancellation owner |
| AUD-07 | MEDIUM | Reproducible SDK | `build.sh:31-37`; `build-termux.sh:20-24` | Toolchain dapat berubah dan supply-chain failure tidak terdeteksi | Unduh `commandlinetools-linux-11076708_latest.zip` tanpa checksum/signature |
| AUD-08 | MEDIUM | Dependency/JDK reproducibility | `app/build.gradle.kts:171-176`; `settings.gradle.kts:1-12`; `build.sh:14-16` | Versi transitif/hash build dapat berubah; hasil antar-JDK tidak dijamin | Tidak ada locking/verification; Java major version tidak divalidasi |
| AUD-09 | MEDIUM | Attachment consistency | `FileImport.kt:115-120,205-210` | File parsial/orphan dapat tertinggal saat gagal atau proses mati | Menulis langsung ke file final; catch hanya menghapus object `Attachment` |
| AUD-10 | MEDIUM | State concurrency | `AgentStepModels.kt:36-58,70-82`; `AgentLoop.kt:405-460` | Update step dapat saling menimpa atau tidak konsisten | Read-modify-write `MutableStateFlow` tanpa `Mutex`/actor |
| AUD-11 | MEDIUM | Hard-limit coverage | `AgentGuard.kt:190-201`; jalur `CcBridge.kt:182-234`, `cc.Agent.kt:296-358` | Pola A→B→A→B dapat lolos; enforcement antarjalur belum memiliki kontrak terpadu | Repeat detector hanya membandingkan panggilan terakhir; cc agent memakai tool boundary berbeda |
| AUD-12 | MEDIUM | Crash data exposure | `CrashReporter.kt:22-32`; `CrashActivity.kt:38-50` | Stack trace mentah dapat membawa URL, path, input, atau credential ke clipboard | Hingga 30.000 karakter diteruskan dan dapat disalin tanpa redaction |
| AUD-13 | MEDIUM | Integrity/documentation | `app/src/main/assets/berangkas/SHA256SUMS:6,8`; `INDEKS.md:24-25` | Verifikasi berkas gagal dan alur handoff dapat rusak | Dua checksum tidak cocok dengan file aktual |
| AUD-14 | MEDIUM | Coverage/test fidelity | `app/build.gradle.kts:171-176`; 190 production Kotlin vs 72 test; `SecretStorageTest.kt` | Coverage tidak terukur; API penting dan Android Keystore nyata belum tervalidasi | Tidak ada JaCoCo/Kover/threshold; test crypto memakai JCE fake |
| AUD-15 | LOW | CI lint gate | `.github/workflows/build-apk.yml:157-160` | Workflow dapat hijau meski lint gagal | `continue-on-error: true` |
| AUD-16 | LOW | Test brittleness | `RepoSecretGuardTest.kt:58-70,82-94,120-127` | Test dapat hijau tanpa validasi perilaku end-to-end atau gagal karena refactor kosmetik | Assertion mencocokkan substring source/script |

## 4. Detail temuan

### AUD-01 — Unit-test suite gagal dikompilasi

**Severity:** HIGH  
**Area:** Build, test quality, agent guard, arsitektur model  
**Lokasi:** `app/src/test/java/com/build/mythos/AgentGuardTest.kt:13,19`, `ContextTrimTest.kt:16`, `ActivityModel.kt:72,211-218`, `/home/ubuntu/mythos-test-2.log`

`ActivityKind` produksi hanya mendefinisikan `DOWNLOAD`, `INSTALL`, `BUILD`, dan `OTHER`, sedangkan test menggunakan `ActivityKind.EDIT`. Pemanggilan `StepRecord` juga mengikuti urutan/tipe lama, bukan kontrak produksi `(tool, title, target, kind, state, ms)`. `ContextTrimTest` membuat `List<Pair<String,String>>`, sementara API yang dipakai membutuhkan `List<Pair<Call,String>>`. Log mengonfirmasi `:app:compileDebugUnitTestKotlin FAILED`.

**Dampak:** tidak ada assertion unit yang dapat dipercaya; AgentGuard, context trimming, hard-limit regression, dan test security tidak tervalidasi pada artefak terbaru. `assembleDebug` tetap dapat menghasilkan APK karena tidak menjadi bukti bahwa test compile/pass.

**Perbaikan:** selaraskan fixture dengan API saat ini atau ubah API secara sengaja dengan migration/compatibility test; jalankan `testDebugUnitTest` tanpa build cache dan jadikan hasilnya required gate sebelum artifact publish.

### AUD-02 — Signing secret tersedia bagi build source yang sama

**Severity:** HIGH  
**Area:** Keamanan workflow CI  
**Lokasi:** `.github/workflows/build-apk.yml:48-99,126-127`

Workflow mendekode `KEYSTORE_B64`, menulis `keystore.properties` yang berisi `storePassword` dan `keyPassword`, kemudian menjalankan Gradle pada source checkout yang sama. Build script/plugin proyek berpotensi membaca atau mengirimkan material tersebut melalui network bila source/branch/workflow tidak tepercaya.

**Dampak:** kompromi signing key dapat memungkinkan pemalsuan update. Eksploitasi memerlukan source atau workflow yang tidak tepercaya, tetapi desain alurnya sendiri terbukti dari kode.

**Perbaikan:** build source untrusted secara unsigned dan terisolasi; sign hanya artifact yang telah diverifikasi pada protected job/environment dengan reviewer wajib, permission minimal, secret manager, workspace terpisah, dan penghapusan material sementara.

### AUD-03 — Signing debug bergantung pada keystore mesin dan release key belum tersedia

**Severity:** HIGH  
**Area:** Packaging/signing  
**Lokasi:** `/home/ubuntu/mythos-build-2/app/build/outputs/apk/debug/app-debug.apk`, `app/build.gradle.kts:59-63,179-189`, signing report

APK memiliki hash SHA-256 `02b26313d90c2270d3b96342c900dadcc0413f95f86c1eed8868a46b823aaefd` dan membawa `assets/mythos-source.zip`. Verifikasi `apksigner` menunjukkan `Verifies`, APK Signature Scheme v2 `true`, satu signer `Android Debug`, dan SHA-256 sertifikat `C8:7B:55:82:FC:DE:86:D3:C6:D6:FC:CE:1C:38:8D:39:5A:5C:4D:3B:83:9B:23:D1:A8:6D:EE:05:0E:B5:36:4E`. Tidak adanya entry `META-INF` konsisten dengan signing v2-only. `signingReport` menunjukkan debug memakai default `/home/ubuntu/.android/debug.keystore`, sedangkan release `Config/Store/Alias: null`.

**Dampak:** APK debug ini dapat diverifikasi dan dipasang, tetapi fingerprint debug keystore default tidak reproducible antar-mesin; APK debug dari mesin/CI lain dapat tidak dapat menimpa instalasi lama. APK release belum dapat dibuat/dipastikan karena konfigurasi release signing kosong.

**Perbaikan:** gunakan signing config eksplisit; fail closed untuk release; pertahankan `apksigner verify` sebagai gate; gunakan keystore CI yang sama dan fingerprint yang diaudit bila debug dibagikan; bedakan jelas APK debug dari APK release.

### AUD-04 — Chat history dan index tidak menjamin commit atomik

**Severity:** HIGH  
**Area:** Persistence/correctness  
**Lokasi:** `ChatHistory.kt:101-106,134-160`

Kode menulis temporary file lalu memanggil `renameTo()` tanpa memeriksa nilai hasilnya. `writeIndex()` juga mengabaikan hasil rename, sedangkan `saveHist()` menelan exception.

**Dampak:** crash, storage penuh, atau rename gagal dapat meninggalkan file lama/temporary dan index stale, sementara UI tetap melanjutkan seolah penyimpanan berhasil. Pesan dapat hilang setelah restart.

**Perbaikan:** gunakan temp unik di direktori yang sama, cek hasil write/flush/atomic replace, jangan menelan exception, tampilkan status persistence gagal, dan tambahkan recovery/cleanup untuk temp file.

### AUD-05 — Checkpoint agen global lintas chat/workspace

**Severity:** HIGH  
**Area:** State isolation  
**Lokasi:** `AgentCheckpointStore.kt:6,32-55`; `AgentLoop.kt:83-84,144`

Store menggunakan satu `filesDir/agent_checkpoint.json`; load tidak memeriksa `chat_id` atau workspace. `AgentLoop` memuat checkpoint saat `send()` dan `startWorker()`.

**Dampak:** ketika pengguna berpindah chat, aksi resume dapat mengambil task dari chat sebelumnya dan menjalankannya terhadap workspace aktif, mencampur konteks, riwayat, dan target.

**Perbaikan:** namespace checkpoint berdasarkan chat ID/workspace, validasi `taskId` dan workspace saat load, lalu abaikan/hapus checkpoint stale yang tidak cocok.

### AUD-06 — Background scope proses-global tanpa lifecycle owner

**Severity:** HIGH  
**Area:** Lifecycle/concurrency  
**Lokasi:** `Background.kt:19-23`, `FileImport.kt:185-212`, `MainActivity.onDestroy():502-516`

`BackgroundScope` adalah `SupervisorJob + Dispatchers.IO` sepanjang proses. Import berjalan tanpa Job yang dimiliki Activity dan dapat memanggil UI dari background. `onDestroy()` hanya membatalkan worker agen pada kondisi tertentu, bukan semua pekerjaan background.

**Dampak:** operasi dapat selesai setelah Activity dihancurkan atau workspace/provider berubah, memicu stale update, kebocoran referensi Activity, dan penulisan state lama.

**Perbaikan:** gunakan lifecycle-aware scope/Job per operasi, cancel dan join saat lifecycle tepat, serta gunakan generation/workspace token dan guard Activity aktif sebelum callback UI.

### AUD-07 — SDK bootstrap tidak terverifikasi

**Severity:** MEDIUM  
**Area:** Reproducibility/supply chain  
**Lokasi:** `build.sh:31-37`, `build-termux.sh:20-24`

Command-line tools SDK diunduh dari URL dengan pola `latest` tanpa checksum atau signature verification.

**Dampak:** konten unduhan dapat berubah sehingga toolchain antar-build tidak sama dan kompromi supply chain tidak terdeteksi.

**Perbaikan:** pin URL versi immutable dan checksum, atau gunakan image/toolchain immutable; catat versi platform/build-tools yang digunakan.

### AUD-08 — Dependency graph dan JDK belum dikunci sepenuhnya

**Severity:** MEDIUM  
**Area:** Reproducibility  
**Lokasi:** `app/build.gradle.kts:171-176`, `settings.gradle.kts:1-12`, `build.sh:14-16`

Gradle 8.9, AGP 8.5.2, dan Kotlin 1.9.24 dipin, tetapi tidak ada dependency locking, verification metadata, atau constraints transitif. Repository memakai Google/Maven Central tanpa kebijakan verification. Script lokal hanya memeriksa `java` tersedia, meski target Kotlin/JVM 17 dan CI memakai Temurin 17.

**Dampak:** dependency transitif/artefak resolved dan hash APK dapat berubah. Build pada JDK lain yang lolos pemeriksaan belum tentu konsisten.

**Perbaikan:** aktifkan dependency locking dan verification/checksum; deklarasikan `java.toolchain` 17 atau validasi major version eksplisit pada script.

### AUD-09 — Attachment ditulis langsung ke file final

**Severity:** MEDIUM  
**Area:** Data consistency  
**Lokasi:** `FileImport.kt:115-120,185-212`

`copyToAppAttachments()` menulis langsung ke target final. Jika proses mati, storage penuh, atau cancellation terjadi, file parsial dapat tertinggal; catch hanya menghapus object `Attachment`, bukan file parsial.

**Dampak:** orphan upload dan registry/file state yang tidak sinkron.

**Perbaikan:** tulis ke temp unik, verifikasi ukuran/hasil, atomic rename, hapus temp/partial pada semua error/cancellation, dan lakukan cleanup orphan uploads.

### AUD-10 — AgentStepStore tidak diserialisasi

**Severity:** MEDIUM  
**Area:** Concurrency  
**Lokasi:** `AgentStepModels.kt:36-58,70-82`, `AgentLoop.kt:405-460`

Mutasi `MutableStateFlow` memakai read-modify-write tanpa `Mutex` atau actor. Worker dan jalur UI/non-UI dapat melakukan update bersamaan.

**Dampak:** perubahan status, output, durasi, atau urutan step dapat hilang/tidak konsisten. Tidak ada kegagalan deterministik yang dilaporkan; ini dikategorikan risiko correctness.

**Perbaikan:** serialisasikan dengan `Mutex`/actor atau satu dispatcher, expose immutable snapshots, dan tambahkan concurrent add/update/finish tests.

### AUD-11 — Guard repeat dan enforcement antarjalur belum terpadu

**Severity:** MEDIUM  
**Area:** Hard limits/command policy  
**Lokasi:** `AgentGuard.kt:190-201`; `ToolExecution.kt:167-198,269-273`; `CcBridge.kt:182-234`; `cc.Agent.kt:296-358`

Repeat detector hanya membandingkan panggilan dan hasil terakhir, sehingga pola bergantian A→B→A→B dapat lolos; `WriteTracker` menutup sebagian kasus tetapi bukan budget kumulatif umum. Jalur utama `runTool` memanggil hard block sebelum approval/sandbox/eksekusi, namun cc agent memakai `ToolRegistry.default` dan boundary sendiri.

**Dampak:** loop variasi dapat berlangsung lebih lama dari yang diharapkan dan kontrak global hard-limit belum terbukti untuk jalur cc. Tidak ada bypass produksi yang terbukti dari jalur utama, dan tidak terbukti cc agent dapat menjalankan arbitrary shell.

**Perbaikan:** tambahkan window/set atau budget per tool/path; integration test untuk autonomous, allowAll, sandbox mati, custom tool, dan cc agent; sentralisasikan pemeriksaan pada boundary bila kebijakan memang global.

### AUD-12 — Crash report mentah dapat disalin ke clipboard

**Severity:** MEDIUM  
**Area:** Secret/data handling  
**Lokasi:** `CrashReporter.kt:22-32`, `CrashActivity.kt:38-50`

Stack trace hingga 30.000 karakter dikirim ke Intent, ditampilkan, dan dapat disalin tanpa redaction.

**Dampak:** exception message dapat memuat URL, input, path, atau detail credential. Credential spesifik tidak terbukti dalam artefak, tetapi sanitasi tidak ada.

**Perbaikan:** redaction terpusat untuk API key, Bearer, token Telegram, password, query credential, dan path sensitif; batasi laporan ke detail terstruktur minimal dan minta konfirmasi sebelum copy.

### AUD-13 — SHA256SUMS berangkas tidak cocok

**Severity:** MEDIUM  
**Area:** Packaging/integrity/documentation  
**Lokasi:** `app/src/main/assets/berangkas/SHA256SUMS:6,8`, `INDEKS.md:24-25`

Dua checksum gagal: `build-github.sh` diharapkan `8b7e401e...` tetapi aktual `ea7d3c...`; template `build-apk.yml` diharapkan `d3a51b4...` tetapi aktual `93a0a5...`.

**Dampak:** dokumentasi integritas tidak valid dan dapat menghambat alur penyerahan/validasi berkas.

**Perbaikan:** regenerasi checksum dari isi final, commit bersama perubahan, dan jadikan verifikasi checksum sebagai CI gate.

### AUD-14 — Coverage dan fidelity test belum memadai

**Severity:** MEDIUM  
**Area:** Test quality  
**Lokasi:** `app/build.gradle.kts:171-176`, `SecretStorageTest.kt:14-47`, struktur source/test

Tidak ada konfigurasi Jacoco/Kover, threshold, atau laporan coverage. Terdapat 190 file production Kotlin dan 72 file test; pencocokan nama sederhana hanya menemukan 52 pasangan, sehingga banyak boundary penting tidak memiliki test langsung. `SecretStorageTest` memakai JCE fake, bukan Android Keystore nyata, dan semua test terblokir oleh compile failure.

**Dampak:** coverage tidak terukur; kontrak platform Keystore, lifecycle key, ToolExecution, provider, service, dan UI lifecycle belum tervalidasi secara memadai.

**Perbaikan:** aktifkan coverage XML/HTML dan threshold untuk area kritis; tambahkan Robolectric/instrumented Keystore contract tests dan integration tests pada boundary eksekusi nyata.

### AUD-15 — Lint bukan quality gate

**Severity:** LOW  
**Area:** CI  
**Lokasi:** `.github/workflows/build-apk.yml:157-160`

`lintDebug` memakai `continue-on-error: true`.

**Dampak:** workflow dapat melaporkan sukses walaupun terdapat regresi lint.

**Perbaikan:** hapus `continue-on-error` atau pisahkan job informasional dari required check dengan keputusan eksplisit.

### AUD-16 — Sebagian test brittle karena memeriksa source literal

**Severity:** LOW  
**Area:** Test quality  
**Lokasi:** `RepoSecretGuardTest.kt:58-70,82-94,120-127`

Sebagian assertion mencocokkan substring pada Gradle/script/workflow, bukan mengeksekusi fixture dan memeriksa perilaku.

**Dampak:** test dapat tetap hijau tanpa menjamin perilaku end-to-end, atau gagal karena perubahan kosmetik.

**Perbaikan:** pertahankan static guard yang memang diperlukan, tetapi tambahkan contract/fixture execution untuk bundle scan, script invocation, workflow validation, dan hasil observabel.

## 5. Prioritas perbaikan berurutan

1. **Pulihkan quality gate:** perbaiki `AgentGuardTest` dan `ContextTrimTest`, jalankan `testDebugUnitTest`/`check`, dan hentikan publish bila compile atau test gagal.
2. **Tutup risiko signing secret:** pisahkan build untrusted dari protected signing job; jangan tulis keystore/password ke workspace yang menjalankan source arbitrary.
3. **Kendalikan signing dan distribusi:** tetapkan signing config eksplisit, fail closed untuk release, pertahankan verifikasi `apksigner`, dan bedakan debug dari APK release siap pasang.
4. **Perbaiki persistence dan isolation:** atomic replace dengan error handling, checkpoint per chat/workspace dengan validasi, serta cleanup attachment partial/orphan.
5. **Perbaiki lifecycle dan concurrency:** Job lifecycle-aware, cancellation/join, generation token, serta Mutex/actor untuk `AgentStepStore`.
6. **Kunci supply chain build:** pin dan checksum SDK tools, aktifkan dependency locking/verification, dan enforce JDK 17.
7. **Uji enforcement secara end-to-end:** tambahkan integration test untuk semua jalur `runTool`, custom tools, autonomous/allowAll, sandbox mati, dan cc agent; tambahkan budget kumulatif repeat guard.
8. **Perbaiki exposure data dan integritas berkas:** redaction crash report, regenerasi `SHA256SUMS`, dan validasi checksum di CI.
9. **Tingkatkan observability test:** coverage report/threshold, Keystore instrumented tests, API boundary tests, dan penggantian sebagian string assertion dengan fixture execution.
10. **Perketat CI lint dan dokumentasi:** jadikan lint required atau status informasional yang jelas; selaraskan README, build scripts, Gradle guard, dan workflow tentang prasyarat signing.

## 6. Hal-hal yang sudah baik

- Wrapper mem-pin Gradle 8.9 dengan SHA-256 distribution; AGP 8.5.2 dan Kotlin plugin 1.9.24 juga dipin.
- Secret aplikasi menggunakan AndroidKeyStore AES-256-GCM; tidak ditemukan credential hardcoded konkret pada source/APK yang diperiksa.
- Backup dan transfer data aplikasi dinonaktifkan melalui `allowBackup=false` dan data extraction rules.
- Cleartext network ditolak secara global; localhost/127.0.0.1 adalah pengecualian yang dinyatakan.
- Provider aplikasi (`ApkProvider` dan `ShareProvider`) non-exported, melakukan canonical-path check, dan membuka file read-only.
- `MainActivity` adalah launcher exported, sedangkan component lain yang diperiksa umumnya non-exported; notification listener dilindungi permission service yang sesuai.
- Source bundle hadir di APK dan source-bundle guard dilaporkan lulus untuk 379 berkas tanpa pola rahasia.
- Hard-limit dan write-path check dipanggil sebelum approval/sandbox/eksekusi pada jalur `ToolExecution`; custom tools dirutekan kembali ke `runTool('run_command')`.
- `AgentLoop` memiliki batas `MAX_AGENT_TURNS` (pelacak lama seperti `RepeatDetector`, `ErrorTracker`, `WriteTracker`, dan `DownloadLedger` sudah dihapus; lihat `ToolPathTest`).
- Manifest merger menghasilkan konfigurasi permission/resource utama tanpa bug manifest/resource yang terbukti dari build report.
- Test suite secara tekstual memiliki banyak assertion dan kasus boundary, meskipun tidak dapat dieksekusi karena compile failure; angka assertion tersebut tidak dianggap sebagai coverage.

## 7. Hasil build/test terbaru

| Pemeriksaan | Hasil | Interpretasi |
|---|---|---|
| `:app:assembleDebug` | **SUCCESS** | APK debug berhasil dipaketkan; bukan bukti unit test lulus |
| `:app:compileDebugUnitTestKotlin` | **FAILED** | Test tidak dapat dikompilasi karena drift API/fixture |
| `testDebugUnitTest` | Tidak dapat dinyatakan lulus | Terblokir pada compile test; tidak ada hasil assertion yang dapat dipercaya |
| `signingReport` debug | Default `/home/ubuntu/.android/debug.keystore` | Tidak reproducible antar-mesin; fingerprint dilaporkan `C8:7B:55:82:FC:DE:86:D3:C6:D6:FC:CE:1C:38:8D:39:5A:5C:4D:3B:83:9B:23:D1:A8:6D:EE:05:0E:B5:36:4E` |
| `signingReport` release | `Config/Store/Alias: null` | Release signing tidak tersedia |
| APK signature verification | **PASS (v2)** | `apksigner verify` berhasil; signer Android Debug dari keystore mesin |
| Source bundle guard | **PASS** | APK membawa `assets/mythos-source.zip`; 379 berkas diperiksa |
| SHA256SUMS berangkas | **FAILED** untuk 2 file | Checksum `build-github.sh` dan template workflow mismatch |
| `lintDebug` CI | Tidak required | `continue-on-error: true` memungkinkan workflow tetap hijau |

APK debug yang dilaporkan: `/home/ubuntu/mythos-build-2/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `02b26313d90c2270d3b96342c900dadcc0413f95f86c1eed8868a46b823aaefd`, application ID `com.build.mythos`, version code `61`, version name `2.46`.

## 8. Catatan temuan auditor yang gagal atau belum dapat diverifikasi

- `apksigner verify --verbose --print-certs` berhasil: v2=true, v1/v3/v4=false, satu signer Android Debug. Debug signing tervalidasi, tetapi reproducibility fingerprint antar-mesin dan release signing belum diverifikasi pada artefak release.
- Unit test tidak mencapai tahap eksekusi assertion. Semua klaim tentang hasil runtime test, coverage aktual, atau efektivitas regression suite harus dianggap belum terbukti.
- Audit tidak memiliki release APK, `mapping.txt`, atau hasil smoke-install. Release R8/resource shrinking, release signing, dan compatibility pada API minimum belum diverifikasi.
- Tidak ada reproducer yang membuktikan exfiltration signing secret, bypass shell obfuscation, race `AgentStepStore`, stale callback, atau file corruption. Temuan terkait diklasifikasikan sebagai risiko desain berdasarkan code path, bukan exploit yang telah berhasil.
- Tidak ditemukan credential hardcoded konkret pada artefak yang diperiksa. Ini tidak menghapus risiko alur CI yang memberikan secret kepada build source.
- Tidak ada temuan bypass produksi yang terbukti pada jalur utama `ToolExecution.runTool`; jalur cc agent memiliki boundary enforcement terpisah yang memerlukan coverage kontrak sendiri.
- Angka jumlah file/assertion dan pencocokan nama test digunakan sebagai indikator audit, bukan pengganti line/branch coverage.

## 9. Kesimpulan auditor

Proyek memiliki fondasi hardening yang cukup baik pada penyimpanan secret, manifest, network policy, provider boundary, dan sebagian enforcement tool. Akan tetapi, status rilis saat ini **tidak dapat dinyatakan siap**: unit-test quality gate rusak, signing debug bergantung pada keystore mesin dan release signing belum tersedia, workflow signing mengekspos material signing kepada source build, dan beberapa jalur persistence/lifecycle/state memiliki risiko correctness tinggi. Prioritas pertama adalah memulihkan test gate dan mengisolasi signing; setelah itu perbaiki atomic persistence/isolation, reproducibility, concurrency, dan coverage sebelum mengandalkan artefak sebagai build produksi.
