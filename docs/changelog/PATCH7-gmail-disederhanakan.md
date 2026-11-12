# patch7: login Gmail/Google disederhanakan
- BUG: login kini selalu `prompt=consent select_account` supaya Google SELALU memberi refresh_token (sebelumnya akun yang pernah disetujui hanya dapat token ~1 jam).
- Pertukaran kode ditolak dengan pesan jelas bila refresh_token tetap tidak ada.
- Satu fungsi login `GoogleWorkspaceConfig.newLogin` dipakai Pengaturan dan tombol Konektor (sebelumnya kode ganda).
- `GoogleWorkspaceConfig.describeFailure`: galat Google (invalid_grant, invalid_client, access_denied, API belum aktif, scope kurang) jadi penyebab + langkah perbaikan.
- Sakelar layanan: menyalakan layanan yang belum pernah diminta izinnya memunculkan petunjuk "Tambah akun".
- Tes: GoogleWorkspaceTest diperbarui + 2 tes baru.

## patch7 (lanjutan): agen mengulang jawaban yang sama
- Penyebab: balasan tanpa alat yang memuat "belum bisa/gagal" dianggap pekerjaan setengah jalan (`shouldKeepGoingFor`), layar dikosongkan, lalu model dipaksa menjawab lagi sampai 3 kali. Model yang memang tidak bisa mengakses alat hanya mengetik jawaban yang sama berulang, dan aliran teks terus terhapus.
- Perbaikan: (1) dorongan "lanjut" teks hanya berlaku bila sudah ada alat yang dipanggil pada tugas itu; (2) balasan identik dengan yang ditolak sebelumnya tidak ditolak lagi; (3) bila model mengaku "alat/lampiran tidak tersedia" tanpa memanggil alat, diberi SATU koreksi yang menyebut alat dan folder uploads/ lalu diminta langsung memanggil Glob/Read.
- Tes baru di PersistenceTest.
- Codex CLI: prompt Codex kini menegaskan bahwa alat Mythos TERSEDIA lewat blok <tool_use> (sebelumnya hanya melarang alat bawaan Codex, sehingga model mengaku "alat tidak tersedia").
- Batas giliran agen dinaikkan 120 -> 300 (AgentConfig.maxSteps).
- Nama APK: Mythos-v83-patch7-<debug|release>.apk (base.archivesName); artefak CI Mythos-v83-patch7-debug-apk. applicationId tidak diubah.
- Kartu draf email: tombol "Kirim email" kini LANGSUNG mengirim lewat Gmail API akun aktif (ketukan = konfirmasi). Status Mengirim… / Terkirim ✓ / galat di kartu; kartu terkirim tersimpan (tidak bisa terkirim dua kali). Bila Gmail belum terhubung/dimatikan, jatuh ke cara lama (membuka aplikasi email). Riwayat draf mencatat status "terkirim lewat Gmail". Butuh izin gmail.compose (sudah diminta).
- Warna aksen: Pengaturan > Tampilan (juga lewat menu ⋮ chat > "Tampilan (warna)"). 10 warna (Biru, Sian, Hijau, Hijau limau, Kuning, Oranye, Merah Jambu, Magenta, Ungu, Hitam) + Terakota (bawaan, tampilan lama tidak berubah). Berlaku seketika dan diingat (SettingsStore.accent_color). MythosColors.Accent/Send kini dibaca dari AccentTheme (state Compose). Tes: AccentThemeTest.
- Prompt agen: email dengan penerima yang sudah diketahui selalu lewat ComposeEmail (kartu + tombol Kirim email), bukan blok 'Teks siap salin'. Blok salin hanya untuk kanal non-email (portal, formulir, chat dalam aplikasi) dan agen menyebut kanalnya. Teks tombol diperbarui (kirim langsung via Gmail).
- Baris aksi jawaban: ikon 18dp (sasaran 36dp) dan ikon+jumlah sumber kini satu baris (rata kanan, favicon 20dp, maks 2).

## patch7 (audit menyeluruh & perbaikan F-01 s.d. F-12)
- **F-01 (`Settings.kt`, `GoogleWorkspace.kt`, `SettingsScreen.kt`)**: Menyelaraskan sakelar layanan global (`gmailEnabled`, `calendarEnabled`, `driveEnabled`) saat menambahkan/mengganti akun Google aktif, serta memisahkan status izin OAuth yang pernah diberikan (`gmailGranted`, `calendarGranted`, `driveGranted`) dari status sakelar layanan (`gmailConnected`, `calendarConnected`, `driveConnected`).
- **F-02 (`GoogleWorkspace.kt`, `Settings.kt`, `AgentViewModel.kt`)**: Menambahkan callback `onTokenRefreshed` pada `HttpGoogleWorkspaceService` dan `UserSettings.withUpdatedGoogleAccessToken(...)` agar `accessToken` hasil penyegaran otomatis tersimpan kembali ke `SettingsStore` terenkripsi.
- **F-03 (`GoogleWorkspace.kt`)**: Memperbaiki `fetchAccountEmail` agar memeriksa sakelar layanan yang diaktifkan dan menambahkan fallback ke endpoint `calendars/primary` saat hanya Google Calendar yang diaktifkan.
- **F-04 (`BrowserAgentScreen.kt`)**: Mengubah nilai awal `allowAll` dari `true` menjadi `false` agar kebijakan domain tepercaya (`BrowserDomainPolicy`) aktif secara bawaan saat Browser Agent dibuka.
- **F-05 (`SettingsScreen.kt`, `AgentViewModel.kt`)**: Menghapus sesi PKCE (`onStartPkceSession(null)`) dan menerapkan fallback `loginHint` setelah pertukaran kode OAuth manual berhasil.
- **F-06 (`EmailDraftCard.kt`, `ChatModels.kt`, `ChatStore.kt`, `AgentViewModel.kt`)**: Menambahkan properti `openedInApp` pada `EmailDraftItem` yang hanya disetel bila aplikasi email eksternal benar-benar berhasil dibuka (`openEmailDraft(cur) == null`), serta mengikat state `copied` ke `remember(item.id)`.
- **F-07 (`Agent.kt`)**: Memperluas penjaga konteks tercemar konten eksternal (`st.taintedByExternal`) agar `GithubBuild` serta `Write`/`Edit` ke `.github/**`, `*.sh`, `*.gradle`, dan `*.gradle.kts` selalu meminta persetujuan pengguna walau dalam mode `ACCEPT_EDITS`.
- **F-08 (`GoogleWorkspace.kt`)**: Menambahkan `SensitiveDataGuard.sanitizeExternalContent(...)` pada header `Date` di `gmailSearchRead`, judul agenda (`summary`) di `calendarSync("list_upcoming")`, dan nama berkas (`name`) di `driveVault("list_files")`.
- **F-09 (`Settings.kt`, `app/build.gradle.kts`, `gradle.properties`, `SettingsScreen.kt`)**: Menghapus field warisan `googleAccessToken` dan `googleRefreshToken` dari `UserSettings` (migrasi satu arah di `SettingsStore.load()` tetap dipertahankan dan kunci lama dihapus saat `save()`), serta memindahkan konfigurasi OAuth Client ID ke `gradle.properties`.
- **F-11 (`README.md`, `scripts/*.sh`)**: Menyelaraskan dokumentasi versi AGP (`8.6.1`), batas lampiran (`20`), dan pengiriman gambar (`12`), serta mengaktifkan bit eksekusi (`+x`) pada `scripts/pin-actions.sh` dan `scripts/pin-gradle-wrapper.sh`.
- **F-12 (`Agent.kt`, `SettingsScreen.kt`, `BrowserAgentScreen.kt`)**: Mengabaikan tanda `?` di dalam URL (`https?://\S+`) pada `shouldKeepGoingFor`, menampilkan `hint` kunci API di `KeysSection`, dan membersihkan kode/impor tak terpakai.
