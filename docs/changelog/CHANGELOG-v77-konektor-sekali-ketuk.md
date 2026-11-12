# v77 - Konektor sekali ketuk

- Konektor > Google Workspace: bila Client ID sudah diisi, satu ketukan langsung membuka halaman masuk Google
  (sesi PKCE dibuat otomatis, layanan sesuai saklar Gmail/Kalender/Drive). Setelah setuju, Google mengembalikan
  ke aplikasi lewat `com.mythos.app:/oauth2redirect` dan akun tersimpan. Client ID kosong -> Pengaturan terbuka.
  Sudah terhubung -> Pengaturan terbuka untuk kelola akun; subjudul menampilkan email akun.
- Konektor > GitHub Actions: bila belum terhubung, satu ketukan membuka Pengaturan lalu langsung ke halaman buat
  token fine-grained GitHub dengan nama dan izin (Contents/Workflows: write, Actions: read) sudah terisi.
- Berkas: `Sheets.kt`, `Workspace.kt` (WorkspaceHooks), `AgentViewModel.kt` (`prepareGoogleLogin`), `MainActivity.kt`.

## Pengaturan disederhanakan
- Model: tampilan awal hanya ringkasan "Otomatis"; daftar model, "Periksa ulang", dan nama model manual dipindah ke tombol "Pilih model sendiri".
- Google: Client ID disembunyikan setelah terisi; "Uji koneksi" dan "Tidak kembali ke aplikasi?" dipindah ke "Lanjutan".
- Teks penjelasan GitHub, Pencarian web, Izin agen, dan Kunci API dipersingkat menjadi satu kalimat.
- Konektor: baris baru "Tambah akun Google lain" langsung ke halaman masuk Google bila sudah ada akun terhubung.

## Teks Pengaturan seminimal mungkin
- Semua paragraf penjelasan dihapus (GitHub, Pencarian, Izin, Kunci API, Model dikunci); status "Repo valid/Token terisi" dihapus.
- Judul bagian dipendekkan (Model, GitHub, Pencarian, Izin, Google); label tombol dan kolom dipendekkan.
- Subjudul Konektor jadi 1-3 kata ("Terhubung", "Belum terhubung", dst.).

## Logo konektor
- Konektor: GitHub memakai logo resmi yang sudah ada (`ic_provider_github`); Google Workspace dan "Tambah akun Google" memakai `ic_connector_google` bila berkasnya ada.
- Pengaturan: Gmail/Kalender/Drive memakai `ic_connector_gmail|calendar|drive`; kolom Brave/Tavily memakai `ic_connector_brave|tavily`.
- Berkas belum ada -> ikon bawaan (tidak crash). Cukup taruh berkas di `app/src/main/res/drawable/` dengan nama itu; kode tidak perlu diubah. `res/raw/keep.xml` menjaga berkasnya dari R8.
- `ic_connector_gmail.xml` sudah terpasang (Simple Icons, CC0, versi satu warna #EA4335). Sisanya (google, calendar, drive, brave, tavily) menunggu berkas.
- `ic_connector_google.xml`: logo Google "G" empat warna (SVG Logos, CC0). Sisa: calendar, drive, brave, tavily (Gmail masih versi satu warna).

## Akhir jawaban AI: baris aksi (ganti baris waktu)
- Baris waktu kecil di bawah jawaban dimatikan (`LatencyTrace.SHOW = false`).
- Baru: `ui/AnswerActions.kt` - ikon Salin, Bagikan, Bacakan (TextToSpeech id-ID), Suka, Tidak suka (hanya penanda di layar), Ulangi (kirim ulang pesan terakhir; hanya saat agen tidak bekerja),
  plus tumpukan ikon situs dan "N sumber" bila giliran itu memakai WebSearch/WebFetch. Ikon situs diambil dari layanan favicon Google (hanya nama domain dikirim).

## Judul baris untuk tool konektor/perangkat
- `Timeline.kt`: 12 tool (GitHub selain Build, ComposeEmail, SetReminder, SetDeadlineNotice, CaseLedger, Gmail, Kalender, Drive) kini punya judul Indonesia dan ringkasan kelompok, bukan nama mentah/"N langkah".
- Tes baru: `connectorAndDeviceToolsHaveIndonesianTitles` di `TimelineTest`.

## Perbaikan redirect OAuth Google
- `AndroidManifest.xml`: intent-filter redirect memakai `host="oauth2redirect"`, padahal redirect URI `com.mythos.app:/oauth2redirect` tidak punya host, jadi tautan balik dari Google tidak membuka aplikasi. Kini cukup skema `com.mythos.app`.
