# Audit ketiga: perbaikan

Audit statis (belum dijalankan lewat Gradle; jalankan `./gradlew testDebugUnitTest` untuk memastikan).

## Diperbaiki
| # | Masalah | Perbaikan | File |
|---|---------|-----------|------|
| 1a | Konfirmasi minimal (bawaan) menjalankan `curl -d @file https://...` tanpa dialog: data bisa keluar lewat prompt injection | `needsExtraCare()` menandai perintah jaringan di posisi perintah (curl, wget, nc, ssh, scp, rsync, /dev/tcp, dll.), termasuk setelah sudo/env/xargs. Kata "curl" sebagai argumen tidak ikut | `CommandPolicy.kt` |
| 1b | `cat` kredensial layanan lolos sebagai baca-saja | `SENSITIVE` ditambah: `.config/gh`, `.aws`, `.npmrc`, `.pypirc`, `.env`, `.bash_history`, `~/.gradle/gradle.properties`, `keystore.properties`, `*.jks`, `*.keystore` | `Classify.kt` |
| 2 | `build_apk` memakai `git add -A` tanpa `.gitignore` (template StudioProject tidak punya): keystore dan local.properties bisa ter-push | Skrip membuat `.gitignore` bila belum ada, lalu `git reset` untuk berkas rahasia sebelum commit. `tools/build-github.sh` dan `BuildApk.SCRIPT` tetap identik | `tools/build-github.sh`, `BuildApk.kt` |

Tes baru: `AuditKetigaTest.kt`.

## Efek samping yang disengaja
- `build-termux.sh`-style perintah `wget`/`curl` oleh agen kini selalu meminta persetujuan (di luar sandbox juga).
- `keystore.properties.example` ikut ditandai sensitif (konservatif).

## Diperbaiki pada putaran kedua
| # | Perbaikan | File |
|---|-----------|------|
| 4a | Workflow CI memakai `permissions: contents: read` (token bawaan tidak bisa menulis ke repo) | `.github/workflows/build-apk.yml` |
| 4b | `build-termux.sh` menyalin APK sebagai `MYTHOS-<versionName>.apk`, bukan nama tetap `MYTHOS-1.1.apk` | `build-termux.sh` |
| 5 | Koneksi HP putus saat mengambil log menyembunyikan penyebab build gagal ("failed to get run log"). Kini log dan unduhan artifact dicoba 3 kali; bila tetap gagal, ditampilkan nama langkah yang gagal dan tautan run | `tools/build-github.sh`, `BuildApk.kt` |
| 6 | **Bug nyata:** template proyek baru (`StudioProject.kt`) memakai `\"` di dalam string mentah `"""`, yang tidak punya escape. Hasilnya `settings.gradle.kts` berisi `rootProject.name = \"x-studio\"` dan build GitHub gagal ("Expecting an element"). Tes lama hanya memeriksa file ada, bukan isinya | `StudioProject.kt`, `StudioProjectTest.kt` |
| 7 | **Bug nyata:** dialog "AI bertanya" tanpa cara menjawab, hanya tombol "Batal tugas". Sebabnya `setItems()` tidak tampil bila `setMessage()` dipakai, jadi pilihan dari AI tak terlihat. Pilihan kini berupa tombol di isi dialog, selalu ada kolom ketik jawaban lain, dan batas waktu naik dari 60 detik ke 5 menit (sebelumnya tugas langsung dibatalkan) | `AskUser.kt` |
| 8 | Pertanyaan terbuka ("Apa yang ingin...") salah dianggap ya/tidak karena kata "ingin". Kini kata tanya (apa, siapa, mana, dst.) di awal berarti bukan ya/tidak | `AskUserLogic.kt`, `AskUserLogicTest.kt` |
| 9 | Setelah `build_apk` (GitHub Actions) sukses, tombol "Pasang APK" tidak muncul. Hanya jalur `run_command` yang memanggil `addInstallCard`; hasil `KITA_APK` dari `build_apk` dibaca tapi tidak dipakai | `ToolExecution.kt` |
| 10 | Tampilan streaming balasan: efek ketik kini adaptif (dulu 1 sampai 2 karakter per 26 ms, jawaban 2.000 karakter butuh sekitar 26 detik walau sudah diterima penuh; kini mengejar dalam kurang dari 2 detik), ada logo bintang (ic_logo_cursor.png) yang berputar pelan di ujung teks sebagai kursor, tidak memecah emoji, dan ketuk balasan untuk langsung menampilkan semuanya | `StreamPacing.kt`, `ChatMessages.kt`, `StreamPacingTest.kt` |
| 11 | Indikator siaga di chat: titik kecil + "Siaga, menunggu perintah berikutnya" diganti logo bintang yang "bernapas" pelan (memudar dan membesar bergantian) dengan kata yang berganti tiap 2,6 detik (Menunggu, Merenung, Melamun, Memimpikan, Menghayal, Mentriangulasi) di bawah gelombang cahaya. Animasi berhenti otomatis saat baris dilepas | `Standby.kt` |
| 12 | AI selalu memanggil pengguna "BOS" di balasan teks (bawaan `SAPAAN_BAWAAN`, dapat diganti lewat preferensi `sapaan`). Instruksi menegaskan sebutan itu tidak boleh masuk ke perintah shell, kode, nama berkas, atau pesan commit | `Sapaan.kt`, `ContextSummary.kt`, `SapaanTest.kt` |

## Belum dikerjakan (sengaja)
- **Token GitHub di argumen proses:** batas `RUN_COMMAND`, tidak bisa ditutup tanpa menguji perilaku `gh`. Mitigasi tanpa kode: login sekali di Termux (`gh auth login --with-token`), lalu kosongkan kolom token di Pengaturan. Skrip sudah memakai `gh auth status` bila token kosong.
- **Receiver hasil Termux di API <33:** perlu izin signature baru di manifest; risiko rendah (aksi memakai UUID acak).
- **Pin SHA action CI dan `distributionSha256Sum` wrapper:** hash tidak bisa saya verifikasi offline; hash yang salah akan merusak build. Ambil dari sumber resmi lalu isi sendiri.
- **targetSdk 34:** menaikkannya mengubah perilaku runtime (foreground service, receiver); perlu diuji di perangkat.
