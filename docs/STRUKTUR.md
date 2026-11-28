# Peta kode (app/src/main/java/com/build/mythos)

Semua file memakai paket `com.build.mythos`. Sebagian besar fungsi adalah ekstensi `MainActivity`,
jadi cukup cari nama fungsinya (grep) untuk menemukan lokasinya. Setiap file punya komentar
judul di bagian atas yang menjelaskan isinya.

## Inti
- MainActivity.kt   : kelas Activity, status/konfigurasi (preset penyedia, prompt sistem), onCreate
- Models.kt         : konstanta dan kelas data kecil (Call, Hist, Preset, ...)
- Views.kt          : tampilan kustom (IconView, DotsView, ShimmerText, LogoView, DiffBg)
- Palette.kt        : palet warna langkah agen (ubah warna di sini)
- Ui.kt             : pembantu tampilan bersama: latar membulat, tombol, efek sentuh, lembar bawah (showSheet)
- Secret.kt         : enkripsi kunci API

## Tampilan chat
- ActivityFeed.kt   : wadah kartu tetap (maks 2 kartu, animasi masuk/geser/keluar 420 ms), kartu langkah + bar progres
- ActivityModel.kt  : logika murni teruji: antrean kartu, progres NYATA dari keluaran, penjaga teks tengah, RunLog + laporan akhir
- ActivityZone.kt   : wadah di bawah chat: memegang LiveStatusLine (baris status + terminal live) dan feed kartu tersembunyi
- LiveStatusLine.kt : baris status live (ikon animasi + teks berkilau + panah) dan terminal live yang terbuka saat diketuk
- TerminalLex.kt    : pewarnaan terminal murni (bash, kode, keluaran), penyusunan baris langkah, pencocokan keluaran tail (teruji)
- StatusBar.kt      : baris status fase (mengirim / menunggu / menulis) di bawah chat
- ChatMessages.kt   : menambah gelembung pesan, catatan, kartu APK/undo
- MessageMenu.kt    : menu tekan-lama (salin, ulangi, edit) dan chip saran
- ChatControls.kt   : hapus, chat baru, suara, tombol kirim/stop
- Markdown.kt       : render markdown dan blok kode
- DiffView.kt       : tampilan diff file
- SettingsDialog.kt : dialog Pengaturan
- ModelPicker.kt    : memilih model
- FileImport.kt, Attachments.kt : lampiran file
- SetupScreen.kt    : layar "Siapkan Termux"

## Agen
- AgentLoop.kt      : loop kirim -> model -> alat
- AgentGuard.kt     : pengaman keberhasilan (wajib verifikasi, error berulang, ringkasan error), logika murni teruji
- ToolExecution.kt  : persetujuan perintah dan runTool
- ContextSummary.kt : prompt sistem, pemangkasan dan ringkasan riwayat
- ChatHistory.kt    : simpan/baca history.json
- Classify.kt, ApprovalDialog.kt, CommandLog.kt, CommandLogActivity.kt : klasifikasi risiko, dialog setuju, catatan perintah

## Jaringan / penyedia model
- ModelCall.kt      : pemanggilan umum, ulangi saat gagal, stream
- ModelProbe.kt     : daftar dan uji model
- ProviderOpenAI.kt, ProviderAnthropic.kt, ProviderGemini.kt : satu file per penyedia

## Sandbox (opt-in)
- CommandPolicy.kt     : assessCommand() di atas classify(): SAFE / NEEDS_APPROVAL / BLOCKED, tanda jaringan, pembatas workdir (murni, teruji)
- CommandRunner.kt     : antarmuka CommandRunner, RunLimits, OutputLimiter, parseTermuxResult (murni, teruji)
- SandboxRunner.kt     : bungkus proot-distro + env bersih + timeout + batas output (murni, teruji)
- SandboxIntegration.kt: saklar "sandbox", penilaian, eksekusi, pencatatan perintah diblokir/ditolak (ekstensi MainActivity)
- Lihat SANDBOX.md.

## Termux
- TermuxShell.kt    : menjalankan perintah (RUN_COMMAND)
- TermuxFiles.kt    : path, cadangan, undo, tulis file
- TermuxSetup.kt    : pemeriksaan penyiapan Termux
- CliLogins.kt      : daftar CLI berlangganan (Codex, Gemini, Claude Code); tambah CLI = tambah satu baris
- CliLogin.kt       : login satu ketukan di Termux dan cek status login
- Report.kt         : "Salin laporan masalah" (info aplikasi + Termux + perintah terakhir ke papan klip)
- ActionIcons.kt    : memilih ikon langkah agen (baca, cari, tulis, build, ...)
- ApkInstall.kt, ApkProvider.kt : memasang APK hasil build
- AgentService.kt   : foreground service

## Konektor (lihat KONEKTOR.md)
- ConnectorsCore.kt       : utilitas, parser/pembangun email (MIME), klien IMAP (baca) dan SMTP (kirim) di atas TLS (murni, teruji)
- ConnectorsHttp.kt       : Telegram Bot API dan WhatsApp Cloud API lewat HTTPS (murni, teruji)
- ConnectorsIntegration.kt: deklarasi alat, gerbang persetujuan kirim, pelaksana alat, uji koneksi (ekstensi MainActivity)
- ConnectorsSettings.kt   : layar Pengaturan lengkap per konektor (kolom manual)
- ConnectorHub.kt         : layar "Jelajahi konektor": kartu + tombol Hubungkan + alur per konektor (ekstensi MainActivity)
- ConnectorsPairing.kt    : deteksi papan klip, pemasangan chat Telegram, kotak masuk WhatsApp (murni, teruji)
- WaListenerService.kt    : NotificationListenerService: tangkap pesan dan balas lewat notifikasi WhatsApp

## Audit dan upgrade diri (lihat SELF_UPGRADE.md)
- SelfAudit.kt       : model temuan, parser penanda KITA_*, laporan, status runtime, pembangun perintah skrip (murni, teruji)
- SelfUpgrade.kt     : parser diff/baseline, ProtectedCore, gerbang evaluateUpgrade, versi pulih, playbook prompt (murni, teruji)
- ApkVerify.kt       : evaluateApk: paket, versionCode, kesinambungan tanda tangan (murni, teruji)
- StartupHealth.kt   : deteksi versi baru yang tidak stabil (murni, teruji)
- SelfUpgradeIntegration.kt: tool self_audit dan self_upgrade, pembaca fakta APK, penyimpanan status boot (ekstensi MainActivity)
- assets/kita-self.sh: skrip tepercaya (audit, baseline, diff, restore, bump); dikirim dari aplikasi tiap dipakai

## Panduan alat dan validasi proyek (lihat PANDUAN_ALAT.md)
- ToolGuidance.kt          : daftar alat + peta path untuk prompt (ToolDocs), pendeteksi kode alat di shell, validasi parameter folder, template hasil-uji (HasilUji), skrip dan laporan validate_project (ProjectCheck) (murni, teruji)
- ProjectCheckIntegration.kt: tool validate_project (ekstensi MainActivity): jalankan skrip, tambah fakta token GitHub dan folder reference
