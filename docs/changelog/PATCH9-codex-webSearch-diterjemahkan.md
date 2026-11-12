# patch9: Codex tidak lagi gagal karena mencoba alat bawaannya (webSearch dkk.)
- GEJALA: "Codex mencoba memakai tool bawaan (webSearch) dua kali; Mythos membatalkannya." Model Codex terbiasa memakai pencarian web bawaannya, bukan `<tool_use>` Mythos.
- PERBAIKAN (tripwire keamanan tetap aktif, pengerasan inti tidak dilonggarkan):
  1. Pencarian web bawaan dimatikan di konfigurasi Codex (`EXTRA_ARG_TIERS`: `web_search="disabled"` dkk.). Bila versi Codex menolak opsinya, dicoba tingkat berikutnya, terakhir hanya pengerasan inti.
  2. Bila Codex tetap memulai `webSearch`, kueri diambil dari item dan diubah menjadi `<tool_use>` WebSearch milik Mythos (tanpa galat, tanpa ulang).
  3. Untuk tool bawaan lain: percobaan ulang naik dari 1 menjadi 2 (total 3), dengan pengingat bertingkat berisi PEMETAAN ALAT; kini juga berjalan bila kalimat pembuka sudah tampil (tidak diulang).
  4. Prompt Codex memuat PEMETAAN ALAT eksplisit (cari web = WebSearch, buka halaman = WebFetch, dst.).
- Tes: `CodexHardeningTest` (tier argumen, penerjemah, pemetaan prompt).

## patch9: balasan AI kini mengikuti Gaya font
- GEJALA: setelah memilih Gaya font (mis. Tulisan tangan), bubel pengguna, baris alat, dan menu berganti, tetapi teks balasan AI tetap serif.
- PENYEBAB: `assistantStyle()` memakai `MythosSerif`, yang dikunci `FontFamily.Serif` (bukan state `FontTheme`).
- PERBAIKAN: `MythosSerif` kini getter ke `FontTheme.serifFamily`: serif klasik hanya pada gaya "Default", selain itu mengikuti pilihan pengguna. Berlaku juga untuk judul (Beranda, Riwayat, Pengaturan, Workspace).
- Tes: `FontAnswerStyleTest`.

## patch9: kartu draf email bergaya Gmail
- `EmailDraftCard` ditulis ulang mengikuti tampilan penulis email Gmail: kepala dengan logo Gmail + tombol kirim bulat (biru muda di mode terang, biru tua di mode gelap) + menu ⋯ (Salin draf), baris **Kepada** dengan chip penerima membulat (Cc bila ada), **Subjek**, lalu **Isi**; lampiran PDF tetap tampil di bawahnya.
- Tombol kirim memakai alur lama (Gmail API bila terhubung, kalau tidak membuka aplikasi email). Status: bulat berputar saat mengirim, centang setelah terkirim, dan tidak bisa ditekan dua kali.
- Warna terang/gelap mengikuti pengaturan sistem (`isSystemInDarkTheme`); font mengikuti Gaya font pilihan.

## patch9: pensil mengubah draf email langsung di kartu
- Ketuk pensil di kartu draf: Kepada, Cc, Subjek, dan Isi berubah menjadi kolom isian. Ketuk centang (atau tombol Simpan) untuk menyimpan, atau Batal untuk membuang perubahan. Tombol kirim redup selama mode ubah.
- Validasi: Kepada wajib ada, alamat Kepada/Cc harus berbentuk email, Subjek dan Isi tidak boleh kosong (`emailEditError`). Beberapa alamat dipisah koma, titik koma, spasi, atau baris baru.
- `AgentViewModel.editEmailDraft` menyimpan hasilnya ke kartu dan riwayat chat, lalu mereset penanda "dibuka di aplikasi email". Draf yang sedang atau sudah dikirim terkunci dan tidak bisa diubah, sehingga aturan anti kirim ganda tetap berlaku. Pengiriman (Gmail API atau aplikasi email) memakai isi yang sudah diubah.
- Tes: `EmailDraftEditTest`.

## patch9: perbaikan hasil build
- `TermuxLauncherTest.assetRunnerBerkomunikasiDenganAppServerViaStdio` disesuaikan dengan argumen spawn baru (`HARDENED_ARGS.concat(EXTRA_ARG_TIERS[argTier], [...])`); pengerasan inti tetap diperiksa.
- Nama APK kini `Mythos-v83-patch9-debug.apk` (`archivesName` sebelumnya masih patch7).
