# Perubahan terbaru

## Agen lebih presisi: prompt tertata + penjaga baca-dulu (belum dirilis)
- `baseSystem()` dan `compactSystem()` (`ProviderConfig.kt`) ditulis ulang jadi blok berlabel: LINGKUNGAN, ALAT, CARA KERJA (7 langkah berurutan: pahami, rencana, selidiki/baca, ubah sedikit, buktikan, diagnosis bila gagal, laporan dengan bukti), ATURAN KERAS. Isi fungsional lama dipertahankan; kalimat ambigu dan duplikat dirapikan. Prompt ringkas model lokal naik dari ±1 rb ke ±2,5 rb karakter (termasuk aturan umum), jadi sedikit lebih lambat dibaca CPU HP.
- `ReadLedger.kt` (baru): `write_file` ke file yang SUDAH ADA dan belum dibaca pada tugas ini ditahan satu kali dengan pesan jelas (`heldWriteMessage`), sebelum dialog persetujuan. Percobaan kedua pada path yang sama lolos, jadi tidak ada jalan buntu. File baru dan file yang sudah dibaca lewat cat/head/tail/sed -n/grep dll. tidak terpengaruh. Pencatatan di `AgentLoop` (`noteCommand`, `reset` per tugas baru); penahanan di `ToolExecution` (cabang `write_file`).
- Lampiran "folder kerja kosong" (`WorkContext.kt`, `FileImport.kt`): blok konteks kerja menyebut ruang kerja KOSONG setiap kali daftar proyek kosong, tanpa melihat lampiran, dan juga saat cache proyek belum terisi (chat baru atau probe gagal). `WorkContextRules.block` kini menerima `attachments` dan `projectsKnown`: ada lampiran = "TIDAK kosong" plus nama dan path-nya; belum dipindai = "BUKAN berarti kosong, periksa dengan ls". `emptyWorkspaceHint` punya `claimEmpty` (bawaan true, perilaku lama). Prompt ditambah aturan yang sama. Pesan gagal salin lampiran kini memuat penyebab dari Termux dan petunjuk `termux-setup-storage` bila izin penyimpanan bermasalah (dulu hanya "Gagal menyalin ke folder attachments workspace"). Tes baru: `AttachmentWorkContextTest`.
- Dua dunia terpisah: agen API bekerja penuh di dalam aplikasi, hanya agen CLI yang memakai Termux. `agentLoop` tidak lagi mengalihkan penyedia API ke loop Termux (konfigurasi belum lengkap = berhenti dengan petunjuk); saklar `cc_agent` dihapus. Upload lampiran untuk agen API disalin langsung ke `cc_projects/<chat>/uploads/` tanpa Termux dan tanpa izin penyimpanan (`AppWorld.kt`, `FileImport.kt`); pesan memuat path relatif (`uploads/...`) yang bisa dibaca alat `Read`; zip diekstrak aman di dalam aplikasi; PDF belum bisa dibaca teksnya di mode ini. Menghapus chat kini juga menghapus folder proyek agen API. Tes baru: `AppWorldTest`. Perbaikan ReadLedger, prompt, dan konteks "folder kosong" sebelumnya hanya berlaku untuk jalur lama (kini obrolan ringan dan agen CLI); mesin `cc` sudah punya guard baca-sebelum-ubah sendiri.
- Ruang kerja agen API sepenuhnya di dalam aplikasi: pemindaian proyek lewat Termux (`refreshWorkContext`) kini hanya dijalankan untuk agen CLI, tidak lagi sebelum setiap tugas agen API. Penyapu folder yatim baru `sweepOrphanCcProjects` (`CcBridge.kt`) membersihkan `cc_projects/<chat>` yang chat-nya sudah tidak ada, dengan pengaman sama seperti penyapu Termux (dilewati bila agen sibuk atau daftar chat kosong). `sanitizeChatId` dan `orphanProjectDirs` ada di `AppWorld.kt` dan diuji di `AppWorldOrphanTest`.
- Paket alat lengkap untuk agen API dalam-aplikasi (`cc/KitTools.kt`): `Mkdir`, `Move`, `Copy`, `Zip`, `Unzip`, `Time`, `Calc`, `WebSearch`, `WebFetch`, `Export`. Terdaftar di `ToolRegistry.default` (alat jaringan/Android disuntikkan `CcBridge`), punya skema native di `NativeTools.kt`, judul kartu dan ikon di UI, dan dicatat per file di jurnal pemulihan. `Export` selalu minta persetujuan dan menyalin satu file ke `Download/MYTHOS/export`. Tes baru: `KitToolsTest`, `MathEvalTest`.
- Perbaikan dua ketidakcocokan: (1) lampiran agen API kini di `uploads/` (bukan `attachments/`), sesuai prompt `cc` dan agar `GithubBuild` tidak mengirim file pribadi ke GitHub; (2) item 9 prompt `cc` dikoreksi (teks Office tidak pernah dibuat jadi `<nama>.txt`; PDF tidak terbaca). Prompt `cc` juga memuat bagian "More tools you have" dan mode rencana mengizinkan `Time`, `Calc`, `WebSearch`, `WebFetch`.
- Tes baru: `ReadLedgerTest`. Tidak ada fitur atau fungsi yang dihapus. Kotlin belum dikompilasi di lingkungan pembuat perubahan; jalankan `./gradlew testDebugUnitTest` di CI sebelum merilis.

## Build agen gagal terus (2.46)
- Proyek buatan agen (`StudioProject.kt`) kini mengunggah artifact `mythos-debug` (dulu `agentapp-debug`, sehingga skrip build selalu gagal "APK tidak dihasilkan") dan langkah tesnya diberi nama "Jalankan tes unit" agar status tes terbaca.
- `build-github.sh` (tiga salinan, tetap identik dengan `BuildApk.SCRIPT`): identitas git LOKAL diatur otomatis bila kosong (Termux baru gagal commit); kegagalan `git commit` tidak lagi disembunyikan dan repo tanpa commit dihentikan dengan pesan jelas; push memakai `-u origin HEAD` dan menampilkan galat aslinya (token disamarkan); peringatan bila token klasik tak punya scope `workflow`, plus petunjuk saat GitHub menolaknya.
- Template workflow berangkas (`templat/build-apk.yml`): tanpa rahasia keystore kini memakai `-PallowUnsigned=true` agar guard Gradle 2.44 tidak menggagalkan build. Workflow repo MYTHOS sendiri tetap sengaja mewajibkan rahasia keystore.
- Simulasi: `tools/uji-skrip-build/run.sh` (gh palsu, tanpa identitas git). Skrip lama gagal di simulasi ini, skrip baru `KITA_BUILD=ok`.
- Tes baru: `BuildScriptFixTest`. Kotlin belum dikompilasi di sini; jalankan `./gradlew testDebugUnitTest` di CI.

## Audit gagal di chat baru (2.45)
- `WorkContextRules.emptyWorkspaceHint`: bila folder kerja chat KOSONG tetapi ada salinan source di `MYTHOS/reference`, blok konteks kerja memberi path absolut dan perintah salin ke `projects/aktif`, serta menegaskan bahwa membaca langsung dari reference/ diperbolehkan. Agen tidak lagi menyimpulkan "tidak ada proyek" atau menebak path relatif yang salah.
- `JSONObject.cleanStr`: JSON null dan teks literal null/undefined/None dianggap kosong (optString Android mengubah null menjadi teks "null", sumber `ls null`). Dipakai untuk command, path, dan folder.
- `nullArgProblem`: run_command ditolak dengan pesan jelas bila command kosong atau perintah berpath (ls, cd, cat, find, dst.) diberi path null/undefined. `write_file` dengan path kosong juga ditolak.
- Tes baru: `EmptyWorkspaceAndNullArgTest`. Belum dikompilasi di lingkungan pembuat perbaikan; jalankan `./gradlew testDebugUnitTest` di CI.

## Teks balasan
- Catatan "Agen diminta menjelaskan rencananya dulu sebelum bekerja." dihapus (penahanan tetap berjalan diam-diam).
- Baris pertama tiap paragraf baru di balasan menjorok 20dp (`LeadingMarginSpan` di `mdToSpannable`); daftar, kutipan, judul, dan paragraf pertama tidak.

## Langkah selesai jadi satu baris
- `FeedRow` (ActivityFeed.kt): langkah agen yang sudah selesai/tertinggal tampil datar satu baris (ikon, teks abu-abu terpotong "…", tanda ›), sedikit menjorok ke dalam, tanpa kotak/bar/detail; ketuk tetap membuka detail. Langkah yang berjalan tetap kartu penuh.

## Perbaikan audit 2.44 (F-01 s/d F-09)
- Keamanan: shell WRITE/skrip selalu minta persetujuan dengan isi+SHA-256 skrip dan pin hash; penjaga rahasia pada Git push dan source bundle APK; token GitHub lewat stdin; status build dipisah (artifact/workflow/tes); applicationId bisa diganti `-PappId`; checksum Gradle dan Actions dipin SHA; koneksi web terikat IP tervalidasi. Rincian dan batasnya: `docs/PERBAIKAN_AUDIT_2_44.md`.

## Belum dirilis — Kerapian, kepatuhan targetSdk 34, dan pengaman rilis
- Path Termux dan /sdcard yang dipakai kode dikumpulkan di `WorkspacePaths` (TERMUX_HOME, TERMUX_BIN, TERMUX_TMP, SDCARD_ATTACHMENTS, dst.); teks petunjuk untuk pengguna/model tidak diubah.
- Tambah `res/xml/data_extraction_rules.xml` (semua domain dikecualikan dari cadangan cloud dan transfer perangkat) dan atribut `dataExtractionRules` di manifest.
- `systemUiVisibility` diganti `WindowInsetsController` di Android 11+ (`Window.applyBarAppearance`); Android 10 ke bawah tetap memakai cara lama.
- Tombol kembali Android 13+ memakai `OnBackInvokedCallback` yang hanya terdaftar selama drawer terbuka (manifest: `enableOnBackInvokedCallback`); `onBackPressed` tetap untuk Android 12 ke bawah.
- Build APK (`assemble*`, `bundle*`, `install*`) kini gagal dengan pesan jelas bila `keystore.properties` tidak ada, tidak lengkap, atau `storeFile` tidak ditemukan. Pengecualian sadar: `-PallowUnsigned=true` (dipakai Bitrise).
- `Views.kt`: `DashPathEffect` ikon NEW_CHAT tidak lagi dibuat tiap frame di `onDraw` (di-cache per ukuran).
- Bola oranye (ikon titik di baris status) kini lebih bervariasi: ORBIT berganti gaya acak tiap 2-5 detik (miring, angka delapan, bernapas, gelombang) dengan peralihan halus; GRID memilih jenis denyut acak (putar 180, napas serentak, putar 90, riak berurutan). Tes: `ClaudeDotsMathTest`.
- Mode "Kurangi gerakan" dihapus: `Motion.kt` (termasuk `applyMotion`, `startUnlessReduced`), tombolnya di Pengaturan > Opsi lain, dan semua pengecekan `Motion.reduced` di ClaudeDots, kilau teks, pemutar langkah, kedip, dan BurstView. Animasi kini selalu berjalan, juga saat animasi sistem dimatikan.
- Teks "Masih diproses..." di bawah baris status diperkecil dari 15sp ke 13sp agar sebanding dengan baris status (17sp).
- Gelombang cahaya pada teks status live diperdalam dan dipercantik: profil lonceng halus (17 titik warna) dengan inti terang tajam, halo lembut, dan gema redup; semburat oranye hangat di tepi depan; pita sedikit miring seperti berkas cahaya; warna dasar teks lebih redup (`Live.SHIMMER_LOW`) sehingga kontras lebih jelas; dan gelombang kini menyapu sepanjang teks sebenarnya, bukan seluruh lebar view. Siklus 2,6 dtk. Tes: `WaveColorsTest`.
- Perbaikan crash saat membuka aplikasi di Android 15 (NPE `getInsetsController` di `onCreate`): `applyBarAppearance` kini memasang DecorView lebih dulu dan jatuh ke cara lama bila controller tidak tersedia.
- Aksesibilitas (TalkBack): helper baru `A11y.kt` (`a11yExpandable`, `a11ySelected`). Header yang bisa dibuka/tutup (baris status live, status bar) kini terbaca sebagai tombol dengan keadaan "Terbuka/Tertutup" dan petunjuk "Perluas/Ciutkan"; baris model yang dipilih terbaca "dipilih"; tombol "Salin" balasan dan kode punya deskripsi ("Salin balasan", "Salin kode") dan berubah saat tersalin. Teks baru ada di `strings.xml`.
- Skala teks tunggal `Typo.kt`: 134 angka `textSize` yang tersebar (14 nilai berbeda, termasuk selisih setengah sp) diganti 12 konstanta bernama. Pembulatan: 11/11,5/12,5 menjadi 12; 13,5 menjadi 14; 15,5 menjadi 16. Tes `TypoTest` menggagalkan build bila angka langsung muncul lagi.
- Area ketuk diperluas tanpa mengubah gambar (`TouchTargets.kt`): tombol 36dp di kolom input (tambah, penalaran, model, mikrofon, kirim) kini ~44x46dp lewat `TouchPadDelegate`; pil dan chip di panel status live (Salin, Besar, Tolak, Setuju, chip langkah) serta "Salin" balasan dan kode ~40dp tingginya lewat `extendTouchVertical` (tinggi baris-baris itu bertambah).
- Layar lebar: lebar kolom isi maksimum 760dp sudah ada (`applyResponsive`); sekarang tombol gulir ke bawah juga ikut kolom, tidak menempel di tepi layar tablet/lanskap.
- Getaran halus (`Haptics.kt`: `hapticTap`, `hapticConfirm`, `hapticReject`) untuk aksi utama: kirim (konfirmasi) dan hentikan, tombol menu/hantu, tambah, model, mikrofon, gulir ke bawah, salin balasan dan kode (konfirmasi), serta Setuju (konfirmasi) dan Tolak (tolak) di panel status. Mengikuti pengaturan umpan balik sentuhan sistem. Android 11+ memakai pola CONFIRM/REJECT, di bawahnya ketukan biasa.
- Warna yang tertulis langsung dirapikan ke `Palette`: `DotsView` tidak lagi berwarna hitam tetap (tak terlihat di mode gelap; kini `Palette.INK`); pil terpilih di PageKit memakai `Palette.STRONG` (sebelumnya hampir hitam tetap, hilang di mode gelap); pratinjau diff dan biru "Tampilkan foto terbaru" punya versi terang dan gelap (`DIFF_*`, `LINK_BLUE`, kontras >= 4,5:1); shimmer status bar memakai `Palette.SHIMMER_HI`; oranye `D67658` kini satu sumber (`Palette.ORANGE_DOT`); warna slate di blok kode dan titik jendela memakai `Live.*`. Tes: `PaletteTokensTest`.
- Tes baru: `AgentLoopTest` dan `ModelCallTest`; logika murni jeda ulang dan parsing argumen alat di `ModelCall.kt` dipisah agar bisa diuji.

## 2.42 — Penyiapan folder kerja otomatis dan pemeriksaan Git yang jelas
- Saat pengaturan disimpan, MYTHOS sekarang membuat folder kerja bila belum ada, lalu memastikan direktori aktual dapat di-resolve sebelum preferensi disimpan.
- Nama folder relatif seperti `MYTHOS` dan path `~/...` dijaga tetap berada di HOME Termux setelah symlink di-resolve; path yang mengarah keluar HOME, `/usr`, root `/`, atau `MYTHOS/reference` ditolak.
- Peringatan Git sekarang menampilkan lokasi repository induk yang benar-benar ditemukan. Deteksi tetap independen dari URL/token GitHub.
- Jika Termux gagal membuat atau memverifikasi folder, pengaturan tidak disimpan dan UI menjelaskan penyebabnya; pemeriksaan yang gagal tidak lagi dapat dilewati dengan konfirmasi paksa.
- Versi aplikasi dinaikkan ke versionCode 57 / versionName 2.42.


## 2.41 — Jembatan sinkronisasi pengaturan MYTHOS–Termux
- Tambah pengaturan sinkronisasi dua arah untuk folder kerja, URL repository, penyedia aktif, dan nama model non-rahasia, memakai helper `mythos-settings` di Termux dan konfirmasi saat menarik perubahan ke aplikasi.
- Setelah jembatan diaktifkan, penyimpanan pengaturan MYTHOS otomatis memperbarui metadata di Termux; perubahan Termux dapat diambil manual.
- Status `gh auth` dan alat Termux diperiksa secara baca-saja tanpa menampilkan kredensial. Pemeriksaan proyek kini mengenali sesi `gh` Termux sebagai opsi autentikasi untuk build APK.
- API key, token, kredensial CLI, dan signing key tidak pernah dimasukkan ke berkas sinkronisasi.

## 2.39 — Kalimat pembuka sebelum alat
- Prompt sistem jalur API kini meminta satu kalimat pembuka sebelum alat dan temuan singkat sesudahnya (`API_NARRATION_GUIDE`). Versi 2.39 (kode 54).

## 2.38 — Langkah agen API terasa
- Langkah alat agen API kini lewat antrean aktivitas yang sama dengan Codex (berurutan, berjeda, ada ringkasan di chat), jadi tidak lewat sekejap. Versi 2.38 (kode 53). Rincian: docs/PERBAIKAN_ALUR_STREAMING_227.md.

## 2.37 — Live streaming agen API sesuai rekaman acuan
- Teks model kini mengalir live di chat pada penyedia API (OpenRouter dll.), baris aktivitas muncul sesudahnya; teks pembuka giliran beralat tidak lagi dibuang. Versi 2.37 (kode 52). Rincian: docs/PERBAIKAN_ALUR_STREAMING_226.md.

## 2.36 — Teks live streaming sering tidak muncul
- Pembacaan aliran Termux yang macet kini diberi tahu di chat; event alat yang antre tidak lagi tertahan sampai akhir; indikator live kembali bila teks tak tiba dalam 5 detik. Versi 2.36 (kode 51). Rincian: docs/PERBAIKAN_ALUR_STREAMING_225.md (termasuk perbaikan layar kosong pada run agen API).

## 2.35 — Daftar pilihan model Gemini CLI
- Pengaturan -> Gemini CLI: tombol "Pilih dari daftar" menampilkan auto, pro, flash, flash-lite, dan beberapa ID bernomor; tidak perlu menghafal nama.
- "Ketik sendiri" dan kolom ketik tetap ada untuk model lain. Versi 2.35 (kode 50). Rincian: docs/PERBAIKAN_PILIHAN_MODEL_GEMINI.md.

## 2.34 — Alur live streaming: Gemini disamakan dengan Codex
- Gemini CLI kini menerima panduan alur penyampaian yang sama dengan Codex (`CLI_NARRATION_GUIDE`).
- Baris status live tampil lagi di setiap putaran baru, sehingga pesan kedua tidak lagi kosong tanpa indikator.
- Versi dinaikkan ke 2.34 (kode 49). Rincian: docs/PERBAIKAN_ALUR_STREAMING_224.md.

## 2.30 — Pemilih model Gemini CLI
- Tambah kolom Model Gemini CLI pada Pengaturan saat provider Gemini CLI dipilih; tampilan utama MYTHOS tidak diubah.
- `auto` menjadi nilai bawaan dan menggunakan routing otomatis native Gemini CLI; alias (`pro`, `flash`, `flash-lite`) serta ID model konkret bisa dimasukkan manual.
- Perintah chat meneruskan pilihan melalui `--model`, membersihkan flag model lama, dan mendukung `-p`, `--prompt`, serta `--prompt=...`.
- Validasi nama model dan shell quoting mencegah input model berubah menjadi argumen/perintah shell.
- Uji helper terisolasi lulus untuk 13 skenario. Build Gradle penuh belum terverifikasi karena distribusi Gradle tidak dapat diunduh di lingkungan ini.

## 2.22
- Narasi aktivitas tidak lagi diketik per karakter; tampil utuh lewat pergantian teks seperti rekaman acuan (teks lama memudar, teks baru memudar masuk, lebar dan panah ikut bergeser, teks berkilau).
- Teks narasi diturunkan dari item Codex yang nyata: nama file yang dibaca, perintah yang dijalankan, file yang diubah, atau kueri pencarian. Kalimat umum "Saya sedang ..." dihapus.
- Dua jenis baris selesai di chat sesuai video: ringkasan alat ("Membaca sebuah file, menjalankan 2 perintah" + centang kecil + panah, tanpa ikon kiri) dan baris penalaran (ikon jam abu-abu + teks abu-abu + panah).
- Sub-baris "Masih diproses..." muncul di bawah baris status bila 6 detik tanpa aktivitas baru.
- Tempo disesuaikan dengan hitungan dari video acuan: narasi tertahan 3,6 detik (video: 2-6 detik), lalu status umum 1,5 detik ("Sedang menjalankan perintah", "Sedang membaca file", "Proses berpikir") sebelum narasi berikutnya.
- Narasi kini berupa kalimat penjelasan yang lebih panjang (memuat nama file/perintah nyata), bukan frasa pendek.
- Prompt Codex diberi panduan: dua sampai tiga kalimat penjelasan sebelum tiap rangkaian alat dan satu paragraf temuan sesudahnya, tanpa kalimat pendek tunggal di antara alat.
- Bentuk ikon bola mengikuti hasil ukur video: dasar 3 titik mengorbit; 4 titik (grid) tampil 7 detik di awal tiap rangkaian alat, lalu kembali ke 3 titik. Kode gerak ikon (ClaudeDots.kt) dan Palette.kt tidak diubah.

## 2.21
- Narasi aktivitas tidak lagi menyembunyikan ikon tiga bola; status kerja kembali di antara narasi.

# Changelog

## 2.20
- Tampilkan narasi aktivitas singkat secara bertahap berdasarkan event tool Codex yang nyata.
- Setelah narasi selesai, kembalikan indikator kerja tanpa membentuk ulang ikon tiga bola oranye.
- Jawaban tetap dirender dari delta Codex; tidak memakai efek ketik sintetis untuk jawaban.
- Ikon oranye tidak diubah, dan UI lain dipertahankan.

## 2.18 — Alur live streaming mengikuti video referensi
- Output Codex App Server dibingkai sebagai JSONL tunggal berisi delta teks dan event aktivitas, sehingga urutan pesan → aktivitas alat → pesan berikutnya tetap terjaga.
- Render Codex memperbarui teks langsung ketika delta diterima; tidak ada typewriter sintetis yang tertinggal dari output model.
- Item `commandExecution`, `fileChange`, pencarian web, dan alat lain ditampilkan sebagai baris aktivitas kecil tanpa kartu latar; mengetuk baris membuka detail aktivitas.
- Pesan AI difinalisasi pada batas item alat agar aktivitas tampil di posisi kronologis yang tepat; segmen akhir tetap mendapat kontrol salin.
- Pembacaan event memperhitungkan potongan JSONL dan UTF-8 parsial; penanganan pending chunk saat proses selesai diperbaiki untuk jalur Codex.
- Uji protokol tiruan Node.js lulus; build Android dan tes Gradle belum terverifikasi karena `services.gradle.org` tidak dapat diakses di lingkungan ini.

## 2.16 — Codex streaming stabilitas
- Codex menggunakan `app-server` JSON-RPC dan event `item/agentMessage/delta` untuk menampilkan teks ketika dihasilkan, dengan fallback item final untuk versi tanpa delta.
- Pembacaan file live berjalan secara inkremental berdasarkan offset byte dan base64 untuk mempertahankan UTF-8 serta mencegah pemotongan hasil panjang oleh batas stdout satu perintah.
- Diagnostik stderr dipisahkan dari teks jawaban AI; status gagal Codex diteruskan ke UI.
- Ticker animasi mengetik dipertahankan sepanjang stream, tidak dibuat ulang pada setiap polling.
- Pemeriksaan kesiapan/pemasangan Codex memastikan Node.js dan dukungan `app-server` tersedia.
- Versi aplikasi dinaikkan ke versionCode 29 / versionName 2.16; tes login CLI diperbarui.


## 2.15 (versionCode 28) - BELUM DIKOMPILASI
- Preset Codex tidak lagi menganggap keberadaan `codex` saja sebagai instalasi yang valid; pemeriksaan memastikan paket Termux kompatibel terpasang dan menolak pembungkus/mock `Termux Mode` yang sebelumnya hanya mengaku menerima prompt.
- Jika perlu memasang ulang, aplikasi memasang paket komunitas yang ditargetkan untuk Android/Termux (`@bash0816/codex-termux`) setelah menghapus paket upstream `@openai/codex` yang tidak menargetkan Termux secara resmi.
- Status login memakai `codex login status`, bukan hanya memeriksa `~/.codex/auth.json`; UI membedakan CLI tidak siap dengan akun belum login.
- Catatan keamanan: build Termux tersebut adalah distribusi komunitas, bukan rilis resmi OpenAI. Login tetap dijalankan di sesi Termux; evaluasi sumber paket sebelum menghubungkan akun. Tes metadata: `CliLoginsTest`. Build lokal belum berhasil diverifikasi karena Gradle tidak bisa mengakses `services.gradle.org` dari lingkungan ini.

## Memori obrolan tahan upgrade - BELUM DIKOMPILASI
- File memori berversi (`v:2`); format lama tetap terbaca. `ChatMemory.merge` menyatukan dua daftar catatan.
- Cadangan otomatis ke Termux `~/MYTHOS/memory/chat_memory.json` dan pemulihan (penggabungan) saat aplikasi dibuka; "Hapus memori obrolan" ikut menghapus cadangan.
- Impor chat lama kini menggabung, bukan menimpa, isi memori terkini.
- Pemanggilan ingatan memberi bobot lebih pada kata yang jarang muncul (makin banyak catatan, makin tajam). Tes: `ChatMemoryTest` (+3).

## Perbaikan tulis ulang berulang dan respons kosong (patch v2) - BELUM DIKOMPILASI
- `WriteTracker` (AgentGuard.kt): menghitung penimpaan file yang sama; peringatan ke model di penimpaan ke-3, agen berhenti di ke-5. Tes/build yang berhasil (bukan perintah baca seperti `cat test_all.py`) mengosongkan hitungan.
- `unittest` dan `pytest` kini dikenali sebagai tes (`verificationKind`).
- Respons model kosong: diulang sekali, lalu `EmptyResponseException` memindahkan ke model cadangan (status BUSY, tidak memblokir model permanen). Bila semua model kosong, pesan jelas ke pengguna.
- Tes: `WriteTrackerTest`.

## 2.14 (versionCode 27) - BELUM DIKOMPILASI
- Konektor Email (IMAP/SMTP), Telegram (bot), dan WhatsApp (Cloud API, hanya kirim): setelah dihubungkan di Pengaturan > Koneksi, agen punya alat
  `email_daftar`, `email_baca`, `email_kirim`, `telegram_baca`, `telegram_kirim`, `whatsapp_kirim` (hanya alat konektor yang aktif dikirim ke model).
  Membaca langsung jalan; mengirim selalu lewat dialog persetujuan yang tidak bisa dilewati mode otonom (kecuali "Kirim otomatis" per konektor).
  Isi email/pesan masuk dibungkus penanda data tidak tepercaya (anti prompt injection). Rahasia disimpan terenkripsi. Tes: `ConnectorsTest`. Rincian: `KONEKTOR.md`.
- Layar "Jelajahi konektor" (Pengaturan > Konektor): kartu dengan tombol **Hubungkan** dan alur pendek per konektor. Email: preset server + sandi aplikasi dari papan klip;
  Telegram: token dari papan klip lalu chat ID terpasang sendiri lewat tautan /start berkode; WhatsApp di HP: izin Akses notifikasi (layanan `WaListenerService`,
  alat `whatsapp_baca` dan `whatsapp_balas`); WhatsApp Business API: token Meta. Manifest: layanan `WaListenerService` (BIND_NOTIFICATION_LISTENER_SERVICE).

## 2.13 (versionCode 26) - BELUM DIKOMPILASI
- Berangkas bawaan APK (`app/src/main/assets/berangkas/`): aturan kerja, skrip, panduan, templat; hanya baca, ikut berganti tiap upgrade.
  Alat baru `berangkas_baca` dan `berangkas_salin` (salinan dibuat ulang di `~/.mythos/berangkas`), dengan pemeriksaan `SHA256SUMS`.
  Skrip baru `kita-cek.sh` untuk cek kesiapan alat di Termux tanpa menilai dari folder kerja. Tes: `BerangkasTest`.

## Belum dirilis (BELUM DIKOMPILASI)
- Memori obrolan (`ChatMemory.kt`, `ChatMemoryStore.kt`, `MEMORI_OBROLAN.md`): tanya-jawab dari semua chat disimpan lokal dan 1-3 catatan paling relevan disisipkan
  ke pesan di mode obrolan model lokal; riwayat lama diimpor sekali; tombol hapus di Pengaturan > Opsi lain. Tes: `ChatMemoryTest`.
- Layar Pengaturan > Lanjutan disederhanakan: "Setujui otomatis perintah baca" tidak lagi ditampilkan (nilainya tetap, bawaan aktif; sudah tercakup
  "Konfirmasi seperlunya"); "Mode obrolan" hanya tampil untuk model lokal; "Mode sandbox" dan "Kurangi gerakan" pindah ke tombol "Opsi lain".
  Mode obrolan model lokal kini selalu aktif dan saklarnya dihapus (AI internet tidak terpengaruh; model lokal tidak lagi bisa menjalankan alat). Label GitHub diperjelas. "Konfirmasi seperlunya" kini selalu aktif dan saklarnya dihapus dari Pengaturan (`minimalConfirm()` selalu true).
- Cache hasil uji model kedaluwarsa setelah 24 jam (`ModelCache.kt`): daftar lama dihitung ulang, model pilihan tetap dipakai bila masih lolos.
  Penyegaran diam-diam di latar saat aplikasi dibuka (`autoSetupModels(silent = true)`), tidak jalan saat tugas berlangsung dan dibatasi sekali per jam bila gagal.
  Cache lama tanpa waktu simpan dianggap basi sekali, lalu diuji ulang.
- Error 404 "model not found" / dihentikan penyedia kini dikenali (`ModelGoneException`): tugas dialihkan ke model lain, model itu dibuang dari cache uji,
  dan bila itu model tersimpan, diganti model terbaik yang tersisa. Model terkunci (OpenRouter) tidak disentuh. Tes: `ModelCacheTest`.
- Peralihan AI otomatis juga berjalan saat penyedia internet tidak menjawab (koneksi gagal/putus/timeout setelah percobaan ulang); server lokal tetap tidak dialihkan.
  Tes: `UnreachableTest`. Rincian: `PERBAIKAN_PINDAH_AI_TIDAK_MENJAWAB.md`.
- OpenRouter jadi penyedia utama bawaan; kode pilih-model lama OpenRouter dihapus; model dikunci ke `openrouter/free` (tidak bisa dipilih, diketik, atau diganti failover dalam OpenRouter). Tes: `ModelLockTest`. Rincian: `KUNCI_MODEL_OPENROUTER.md`.
- Model lokal: status "membaca prompt" menampilkan perkiraan token dan waktu (kecepatan dipelajari otomatis); laporan masalah memuat versi Ollama, `ollama ps`, daftar model
  dan log cache; `CliLogin` memakai pembungkus LD_PRELOAD yang sama dengan `TermuxShell` (kini fungsi `preloadWrapper`); "Halo" dibalas "Halo". Tes: `LocalProgressTest`.
- Model lokal: hanya `run_command`, `write_file`, `ask_user` yang dikirim (prompt ±2.200 → ±1.000 token menurut hitungan); playbook ringkas disesuaikan.
  Catatan "Awal prompt berubah" bila sistem/alat berubah antar giliran (cache Ollama). Tes: `LocalToolsTest`. Rincian: `PERBAIKAN_KECEPATAN_LOKAL.md`.
- Model lokal: obrolan pendek tanpa kata kerja tindakan ("Waalaikum salam, sangat siap") kini lewat jalur sapaan (tanpa alat, prompt ringkas);
  cadangan failover di server lokal hanya model terpilih; langkah "Varian cepat (6 thread)" di Siapkan Termux membuat Modelfile `num_thread` otomatis.
  Tes: `LightChatTest`. Rincian dan status verifikasi: `PERBAIKAN_KECEPATAN_LOKAL.md`.
- Perintah Termux: bash kini dijalankan ulang sekali dengan `LD_PRELOAD` ke `libtermux-exec-ld-preload.so` (bila ada), supaya skrip ber-shebang
  `#!/usr/bin/env` (gemini, codex) tidak lagi gagal dengan "bad interpreter". Versi dinaikkan ke 2.12 (versionCode 25) agar mudah dibedakan di laporan.
- Riwayat banyak chat: tiap chat satu file di `filesDir/chats`, indeks untuk drawer, chat lama tidak lagi terhapus saat "Chat baru"; `history.json` lama dimigrasi otomatis.
  Daftar "Terbaru", halaman "Semua chat" (cari, ubah nama, hapus). Tes: `ChatStoreTest`.
- Drawer: "Proyek" dan "Artefak" kini halaman sungguhan (folder proyek aktif/arsip; file buatan agen yang bisa dibuka, dibagikan, dihapus).
  `ShareProvider` baru di manifest. Tes: `WorkspaceListTest`. Rincian: `PERUBAHAN_RIWAYAT_PROYEK_ARTEFAK.md`.
- Lembar "Tambahkan ke chat": strip "Foto terbaru" (12 foto terakhir, ketuk untuk melampirkan). Izin `READ_MEDIA_IMAGES` / `READ_MEDIA_VISUAL_USER_SELECTED`
  (Android 14 akses sebagian didukung); ditolak = jatuh kembali ke pemilih foto. Tes: `RecentPhotosTest`.
- Chat samaran: checkpoint agen hanya di memori (tidak ke disk), sapuan pratinjau gambar yatim saat aplikasi dibuka, dan peringatan di chat
  saat agen menulis file di folder kerja. Tes: `IncognitoGuardTest`. Rincian: `PERUBAHAN_FOTO_TERBARU_SAMARAN.md`.
- Drawer: lebar dibatasi 360dp (tablet/lanskap) dan ikut diperbarui saat layar diputar; geser kini mengikuti jari dan menyelesaikan gerak
  berdasarkan kecepatan lemparan atau setengah jalan. Tes: `DrawerGeometryTest`. Rincian: `PERUBAHAN_DRAWER_GESER.md`.

## 2.11 (versionCode 22)
- Aksesibilitas: `contentDescription` (teks di `res/values/strings.xml`, awal lokalisasi) untuk tombol menu, ikon hantu, tambah, pilih model,
  penalaran mendalam, mikrofon, kirim/hentikan (deskripsi tombol aksi ikut berganti saat agen sibuk).
- CI: berjalan juga pada pull request; `concurrency` membatalkan run lama hanya untuk pull request (push tidak, agar `build_apk` tidak melihat run-nya dibatalkan); batas waktu 30 menit;
  laporan tes selalu diunggah; lint dijalankan (belum menggagalkan job).
- Threading: 14 `Thread { ... }.start()` kerja latar sekali jalan (ApkInstall, CliLogin, FileImport, LiveTail.stop, ModelPicker x2,
  Report, SettingsDialog, SetupScreen, TaskCleanup, TermuxFiles, TermuxSetup, TermuxShell.killRemote, WorkspaceMigration)
  dipindah ke helper `bg { }` (Background.kt, coroutine di Dispatchers.IO). `Thread.sleep` di `ModelCall`/`TermuxShell`
  dibiarkan karena kini berjalan di dalam runInterruptible milik AgentLoop. Tes: `BackgroundTest`.
- Agen: `AgentLoop` kini berjalan sebagai Job (`bgCancellable`, runInterruptible) bukan Thread lepas. `worker` bertipe `Job?`; tombol
  Berhenti dan `onDestroy` memakai `worker?.cancel()` yang tetap menginterupsi thread yang sedang memblokir (HTTP, `Thread.sleep`,
  Termux). `onFinally` (bersih-bersih `busy`, laporan, antrean pesan) dijamin jalan walau dibatalkan sebelum loop mulai. Alasan
  start ATOMIC ada di Background.kt. Tes: `BgCancellableTest`. Perilaku `cancelled`/`Cancelled` tidak diubah.
- `LiveTail` kini coroutine polling (`delay` + `runInterruptible`), bukan thread daemon; `stop()` memanggil `job.cancel()`.
  `ApkInstall` (thread rx) sengaja tetap thread: pendek, di-`join` dengan batas waktu, dan diputus lewat `server.close()`.
- Kolom pesan (`EditText`) tidak diberi `contentDescription`, karena bisa menimpa teks yang dibacakan TalkBack; `hint` sudah cukup.
- Tampilan chat: tabel Markdown kini dirender sebagai tabel (`MarkdownTable.kt`, `tableView` di `Markdown.kt`; sebelumnya tampil sebagai
  baris `| a | b |` mentah). Tombol gulir ke bawah memakai `IconView` (chevron) karena glyph "↓" tampil sebagai tanda petik di font
  sebagian perangkat. Tes: `MarkdownTableTest`.
- Audit ulang (27 Okt 2026, statis): keseimbangan kurung 155 file Kotlin, XML, YAML, dan string resource diperiksa. Perbaikan:
  `catch (_: ...)` diganti nama biasa di 8 file (garis bawah sebagai nama parameter catch tidak pasti didukung Kotlin 1.9.24);
  `@OptIn(DelicateCoroutinesApi)` untuk `CoroutineStart.ATOMIC`; `bgCancellable` tidak melaporkan "dihentikan" bila kerja sudah selesai
  normal tetapi Job keburu dibatalkan; tabel mendukung tekan-lama (menu pesan). Tetap BELUM dikompilasi.
- Versi disingkat dari `2.10-panduan-alat-validasi-proyek` menjadi `2.11`.
- Dokumentasi: `README.md` (indeks) dan `CHANGELOG.md` ditambahkan.
- BELUM dikompilasi di lingkungan ini (tanpa Android SDK); jalankan CI untuk memastikan.

## 2.10
- Panduan alat dan validasi proyek (lihat `PANDUAN_ALAT.md`).

Riwayat lebih lama: `PERUBAHAN_TAMPILAN.md`, `PERBAIKAN_*.md`, `AUDIT_*.md`.
