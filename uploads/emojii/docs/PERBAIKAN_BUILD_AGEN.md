# BUILD agen kini lewat GitHub Actions

Tool `build_apk` tidak lagi memasang JDK/SDK/aapt2 dan menjalankan Gradle di HP. Satu skrip (`tools/build-github.sh`):
`git add -A` + commit + `git push` -> menunggu workflow `Build APK` -> mengunduh artifact `mythos-debug` ke
`app/build/outputs/apk/debug/`. Penanda akhir: `KITA_BUILD=ok|gagal`, `KITA_APK=<path>`. Bila tes unit gagal tetapi APK ada,
log langkah yang gagal ikut dicetak.

Syarat sekali saja di Termux: folder proyek adalah repo git dengan `origin` ke GitHub, dan `gh auth login` (atau `GH_TOKEN`).
Rahasia `KEYSTORE_B64` + `KEYSTORE_PASSWORD` di repo membuat APK memakai kunci yang sama dengan build di HP.
**Belum dikompilasi dan tes belum dijalankan** (tidak ada kotlinc di lingkungan kerja); skrip sudah lolos `bash -n`.
Catatan lama di bawah ini (setup SDK/aapt2 lokal) tidak berlaku lagi untuk `build_apk`.

---

# Perbaikan agar agen bisa membangun APK (v2.5-agen-build)

Latar belakang: agen di aplikasi belum pernah berhasil membangun APK. Audit menemukan penyebabnya di lingkungan,
prompt, diagnosis, dan pengaman agen (bukan di model). **Belum dikompilasi dan tes belum dijalankan** (tidak ada
kotlinc di lingkungan kerja). Skrip persiapan (`tools/build-setup.sh`) sudah diuji dengan bash untuk jalur gagal
dan jalur sukses (SDK dan aapt2 tiruan). Jalankan: `./gradlew testDebugUnitTest assembleDebug`.

## Yang diubah

| # | Masalah | Perbaikan | File |
|---|---------|-----------|------|
| 1 | Sandbox memakai `/bin/sh` (dash di Debian) tanpa `pipefail`: `./gradlew ... \| tail` yang gagal dilaporkan `exit=0` | Dijalankan dengan `/bin/bash` dan `set -o pipefail`; catatan bila bash tidak ada | `SandboxRunner.kt` |
| 2 | Agen harus merangkai build sendiri tanpa bekal | Tool baru `build_apk`: persiapan (JDK, SDK 34, aapt2 arm64, `local.properties`) lewat skrip tepercaya, lalu `./gradlew assembleDebug --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process [-Pandroid.aapt2FromMavenOverride=...]` lewat jalur `run_command` (kebijakan sandbox, persetujuan jaringan, batas 1800 dtk, laporan build tetap berlaku). Setelah sukses, daftar APK dicetak | `BuildApk.kt`, `tools/build-setup.sh`, `ToolExecution.kt`, `MainActivity.kt`, `AgentLoop.kt` |
| 3 | Prompt tidak punya resep build; prompt sandbox bilang alat build tidak ada | `BUILD_PLAYBOOK` (pakai build_apk, cara membaca error, jalan keluar GitHub Actions); kalimat sandbox diperbaiki | `ContextSummary.kt`, `MainActivity.kt` |
| 4 | Diagnosis berisi saran Termux (`pkg`, `$PREFIX`, `termux-*`) yang mustahil di sandbox | `diagnoseFailure(..., sandbox)` memilih saran apt/guest; aturan baru: bashisme di sh, `Killed` tanpa OOM (Android mematikan proses) | `Diagnosis.kt`, `AgentLoop.kt` |
| 5a | Perintah pemeriksaan yang gagal (`ls`, `cat`, `java -version`) dihitung kegagalan dan memicu "hentikan dan jelaskan" | `isProbeCommand`/`countsAsFailure`; protokol mulai di kegagalan ke-3 (dulu ke-2) dan tidak menyuruh menyerah sebelum tiga perbaikan berbeda | `AgentGuard.kt`, `Diagnosis.kt` |
| 5b | Error sama dihitung kumulatif, berhenti di ke-4 walau lingkungan sudah maju | `ErrorTracker.noteProgress()` dipanggil saat perintah pengubah keadaan berhasil; timeout `build_apk` diberi batas 8 | `AgentGuard.kt`, `AgentLoop.kt` |
| 5c | Memantau log (`tail`) dengan hasil sama 3 kali dianggap macet | Pemantauan dan `build_apk` boleh sampai 6 kali | `AgentGuard.kt`, `AgentLoop.kt` |
| 5d | Pesan timeout menyuruh "cek apakah proses masih jalan", padahal sudah dimatikan | Pesan jujur: proses sudah mati, cache tersimpan, panggil `build_apk` lagi | `AgentGuard.kt`, `SandboxRunner.kt` |
| 6 | Keluaran sandbox dipotong dari UJUNG (`head -c`), padahal error Gradle ada di ujung | Untuk build batas dinaikkan ke 90 KB lalu dipadatkan (awal + akhir) | `SandboxIntegration.kt`, `BuildApk.kt` |
| 7 | `exit=141` (SIGPIPE) pada `... \| head` dilaporkan gagal karena pipefail | `softenSigpipe` mengubahnya menjadi exit=0 bila perintah memakai pipa | `AgentGuard.kt` |

Workflow GitHub Actions: APK dibangun dan diunggah dulu, tes dijalankan sesudahnya (job tetap merah bila tes gagal).

## Tes
`BuildApkTest.kt` (folder aman, skrip sama dengan file, penanda, perintah Gradle, kategori BUILD) dan
`BuildAgentGuardTest.kt` (probe, SIGPIPE, kemajuan, detektor, timeout, diagnosis sandbox). `SandboxRunnerTest.kt`
disesuaikan (`/bin/bash`, `pipefail`).

## Belum dikerjakan / batas jujur
- APK hasil build di sandbox berada di dalam Debian (tidak terlihat oleh Termux/Android); `findApk()` hanya mencari di Termux.
  Lokasinya dicetak oleh `build_apk`, tetapi memindahkannya keluar sandbox belum ada.
- `BUILD_RE` masih menganggap `test`, `lint`, `make` sebagai "build" untuk verifikasi (temuan audit 7).
- Lampiran masih disalin ke `/sdcard/Download/ClaudeMythos` dan prompt dasar masih menyebut "Termux" (temuan audit 8).
- Nama paket Debian untuk aapt2 arm64 (`aapt`) dan perilaku proot untuk build 30+ menit perlu diuji di perangkat.
- Di mode Termux (bukan sandbox) dengan konfirmasi minimal, `build_apk` berjalan tanpa dialog karena memang rutinitas
  build; di sandbox dialog jaringan tetap wajib.
