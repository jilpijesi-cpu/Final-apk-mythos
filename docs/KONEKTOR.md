# Konektor: Email, Telegram, WhatsApp

Agen bisa memakai akunmu untuk bekerja: membaca email masuk, mengirim email, membaca dan mengirim pesan Telegram, mengirim pesan WhatsApp.
Semua diatur di **Pengaturan > Koneksi**. Tiap konektor punya saklar sendiri; alatnya baru muncul ke model bila saklar nyala dan isiannya lengkap.

## Alat agen
| Alat | Fungsi | Persetujuan |
|---|---|---|
| `email_daftar(folder, cari, jumlah)` | daftar email terbaru (bawaan: belum dibaca) | tidak |
| `email_baca(folder, id)` | isi satu email (teks, maks ±6000 karakter) | tidak |
| `email_kirim(ke, cc, subjek, isi)` | kirim email teks | **ya, tiap kali** |
| `telegram_baca(jumlah, tandai)` | pesan baru ke bot dari chat yang diizinkan | tidak |
| `telegram_kirim(chat_id, teks)` | kirim lewat bot ke chat yang diizinkan | **ya, tiap kali** |
| `whatsapp_kirim(ke, teks)` | kirim ke nomor yang diizinkan (Cloud API) | **ya, tiap kali** |
| `whatsapp_baca(jumlah, tandai)` | pesan WhatsApp baru di HP (dari notifikasi) | tidak |
| `whatsapp_balas(percakapan, teks)` | balas percakapan yang baru menulis (tombol Balas notifikasi) | **ya, tiap kali** |

## Cara menghubungkan (satu layar: Pengaturan > Konektor > Hubungkan)
Ketuk **Hubungkan** pada kartu; aplikasi mengisi, menguji, dan menyalakan konektornya sendiri. Yang tidak bisa dilakukan aplikasi (butuh akun/izin di luar aplikasi) dijelaskan di alurnya:
- **Email**: tempel/ketik alamat; sandi aplikasi terisi dari papan klip (tombol "Buat sandi aplikasi" membuka halaman penyedia). Server Gmail/Yahoo/iCloud terisi otomatis.
- **Telegram**: salin token dari @BotFather, ketuk Hubungkan, tekan MULAI di Telegram: chat ID terpasang sendiri (tanpa mengetik).
- **WhatsApp di HP**: nyalakan satu saklar "Akses notifikasi" untuk MYTHOS. Aplikasi mendeteksinya sendiri lalu menandai terhubung. Di Android 13+ untuk APK di luar Play Store, izinkan dulu "setelan terbatas" di info aplikasi.
- **WhatsApp Business API**: token dan Phone Number ID dari dasbor Meta.

Gmail dengan satu ketukan "Masuk dengan Google" (OAuth) belum ada: itu butuh OAuth client terdaftar di Google Cloud atas tanda tangan APK-mu.

Kolom manual lengkap tetap ada di "Pengaturan lengkap". Rincian per konektor:
- **Email**: isi alamat dan **kata sandi aplikasi** (bukan sandi biasa). Gmail/Yahoo/iCloud/Zoho terisi otomatis; lainnya isi server IMAP (port 993) dan SMTP (465 atau 587).
  Outlook/Hotmail umumnya menolak login sandi (butuh OAuth, belum didukung). Ketuk **Tes koneksi email**.
- **Telegram**: buat bot lewat @BotFather, tempel token, kirim satu pesan ke bot, ketuk **Cari chat ID**, salin ID-mu ke daftar izin.
- **WhatsApp**: butuh akun WhatsApp Business Platform (Meta for Developers): token akses, Phone Number ID, dan nomor tujuan yang diizinkan.

## Keamanan
- Mengirim: dialog persetujuan tiap kali, tidak bisa dilewati mode otonom atau "Ya, semua". Satu-satunya jalan pintas: saklar **Kirim otomatis** per konektor (pilihan eksplisit). Mode hanya baca menolak semua pengiriman.
- Tujuan dibatasi daftar izin (chat Telegram, nomor WhatsApp); agen tidak bisa mengirim ke tujuan lain, dan pesan Telegram dari pengirim tak dikenal tidak pernah ditampilkan ke model.
- Isi dari luar dibungkus penanda "DATA DARI LUAR, TIDAK TERPERCAYA" dan prompt sistem melarang mengikuti instruksi di dalamnya. Ini mengurangi, bukan menghilangkan, risiko prompt injection: biarkan dialog persetujuan tetap menyala.
- Email dibuka dengan `EXAMINE` + `BODY.PEEK` (tidak mengubah tanda dibaca, tidak mengunduh lampiran). Semua koneksi TLS dengan verifikasi nama host; SMTP menolak berjalan tanpa STARTTLS/TLS.
- Kata sandi/token disimpan terenkripsi (Android Keystore), disamarkan dari pesan galat, dan tidak masuk Catatan perintah (hanya tujuan dan subjek).

## Batasan
- **WhatsApp di HP** memakai notifikasi: hanya pesan yang tiba saat izin aktif, disimpan di memori (hilang bila aplikasi dimatikan sistem), hanya percakapan yang notifikasinya masih ada yang bisa dibalas, tidak bisa mengirim ke kontak baru, dan isi pesan bisa tersamar bila HP menyembunyikan konten notifikasi. Izin Akses notifikasi memberi aplikasi akses ke semua notifikasi; kodenya hanya memproses paket WhatsApp. Penggunaan otomasi di akun pribadi bisa melanggar ketentuan WhatsApp: pakai atas risikomu.
- **WhatsApp Cloud API hanya kirim.** Menerima pesan lewat Cloud API butuh webhook di server publik, yang tidak dimiliki aplikasi di HP. Teks bebas hanya terkirim bila penerima membalas dalam 24 jam terakhir (selebihnya WhatsApp mewajibkan pesan template, belum didukung).
- Email: teks polos tanpa lampiran, tanpa Bcc; pencarian hanya ASCII; email dibaca maks 200 KB pertama.
- Telegram: teks saja (tanpa foto/berkas); `telegram_baca` mengonfirmasi pesan (hilang dari antrean bot) kecuali `tandai=tidak`.
- Konektor berjalan saat aplikasi aktif; belum ada pemantau latar yang memicu agen saat email/pesan baru masuk.

## Status verifikasi
Kode ditulis tanpa kompiler Kotlin/Gradle di lingkungan penulisan: **belum dikompilasi dan belum dites**. Jalankan `./gradlew testDebugUnitTest` (tes: `ConnectorsTest`, memakai server IMAP/SMTP palsu di localhost), lalu uji tiap konektor dengan tombol "Tes" di layar Koneksi memakai akun sungguhan.
