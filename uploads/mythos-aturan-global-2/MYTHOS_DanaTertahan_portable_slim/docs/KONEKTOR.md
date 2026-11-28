# Konektor: Email, Telegram, WhatsApp

Agen bisa memakai akunmu untuk bekerja: membaca email masuk, mengirim email, membaca dan mengirim pesan Telegram, mengirim pesan WhatsApp.
Semua diatur di **Pengaturan > Koneksi**. Tiap konektor punya saklar sendiri; alatnya baru muncul ke model bila saklar nyala dan isiannya lengkap.

## Alat agen
| Alat | Fungsi | Persetujuan |
|---|---|---|
| `email_daftar(folder, cari, jumlah, halaman)` | daftar email di folder mana pun: INBOX, SENT (terkirim), DRAFT (draf), SPAM, TRASH, STARRED, ALL; 20 per halaman, ikuti `halaman=...` untuk membaca sampai habis | tidak |
| `email_baca(id, panjang)` | isi satu email (inbox/terkirim/draf): pengirim, penerima, cc, subjek, teks (bawaan ±6000, maks 20000 karakter) dan daftar lampiran beserta ukuran | tidak |
| `email_lampiran(id, nama)` | unduh lampiran satu email ke folder proyek `lampiran_email/` (maks 15 MB per berkas, 10 berkas per panggilan) | tidak (ditolak di mode hanya baca) |
| `email_kirim(ke, cc, subjek, isi, lampiran)` | kirim email teks, opsional dengan lampiran dari folder proyek (maks 10 berkas, total 18 MB) | **ya, tiap kali** |
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

Gmail terhubung dengan satu ketukan "Masuk dengan Google" melalui `AuthorizationClient` resmi Android (tanpa `redirect_uri` browser manual). Konfigurasi membutuhkan Client ID tipe Android di Google Cloud yang terdaftar untuk package **com.build.mythos** dan SHA-1 keystore yang digunakan untuk menandatangani APK yang dipasang. Nilainya diisi pada `mythosGoogleClientId` di `gradle.properties`; bila kosong, aplikasi menampilkan "Client ID Google belum diisi" dan tidak memulai otorisasi. Jangan gunakan Client ID dari aplikasi lain.

Kolom manual lengkap tetap ada di "Pengaturan lengkap". Rincian per konektor:
- **Email**: isi alamat dan **kata sandi aplikasi** (bukan sandi biasa). Gmail/Yahoo/iCloud/Zoho terisi otomatis; lainnya isi server IMAP (port 993) dan SMTP (465 atau 587).
  Outlook/Hotmail umumnya menolak login sandi (butuh OAuth, belum didukung). Ketuk **Tes koneksi email**.
- **Telegram**: buat bot lewat @BotFather, tempel token, kirim satu pesan ke bot, ketuk **Cari chat ID**, salin ID-mu ke daftar izin.
- **WhatsApp**: butuh akun WhatsApp Business Platform (Meta for Developers): token akses, Phone Number ID, dan nomor tujuan yang diizinkan.

## Keamanan
- Mengirim: dialog persetujuan tiap kali, tidak bisa dilewati mode otonom atau "Ya, semua". Satu-satunya jalan pintas: saklar **Kirim otomatis** per konektor (pilihan eksplisit). Mode hanya baca menolak semua pengiriman.
- Tujuan dibatasi daftar izin (chat Telegram, nomor WhatsApp); agen tidak bisa mengirim ke tujuan lain, dan pesan Telegram dari pengirim tak dikenal tidak pernah ditampilkan ke model.
- Isi dari luar dibungkus penanda "DATA DARI LUAR, TIDAK TERPERCAYA" dan prompt sistem melarang mengikuti instruksi di dalamnya. Ini mengurangi, bukan menghilangkan, risiko prompt injection: biarkan dialog persetujuan tetap menyala.
- Email dibaca lewat Gmail API tanpa mengubah tanda dibaca; lampiran hanya diunduh bila `email_lampiran` dipanggil, disimpan dengan nama yang sudah dibersihkan (tanpa path, tidak menimpa berkas lama) dan diperlakukan sebagai data tidak tepercaya. Semua koneksi TLS dengan verifikasi nama host; SMTP menolak berjalan tanpa STARTTLS/TLS.
- Kata sandi/token disimpan terenkripsi (Android Keystore), disamarkan dari pesan galat, dan tidak masuk Catatan perintah (hanya tujuan dan subjek).

## Batasan
- **WhatsApp di HP** memakai notifikasi: hanya pesan yang tiba saat izin aktif, disimpan di memori (hilang bila aplikasi dimatikan sistem), hanya percakapan yang notifikasinya masih ada yang bisa dibalas, tidak bisa mengirim ke kontak baru, dan isi pesan bisa tersamar bila HP menyembunyikan konten notifikasi. Izin Akses notifikasi memberi aplikasi akses ke semua notifikasi; kodenya hanya memproses paket WhatsApp. Penggunaan otomasi di akun pribadi bisa melanggar ketentuan WhatsApp: pakai atas risikomu.
- **WhatsApp Cloud API hanya kirim.** Menerima pesan lewat Cloud API butuh webhook di server publik, yang tidak dimiliki aplikasi di HP. Teks bebas hanya terkirim bila penerima membalas dalam 24 jam terakhir (selebihnya WhatsApp mewajibkan pesan template, belum didukung).
- Email: isi teks polos (tanpa HTML), tanpa Bcc; lampiran hanya berkas di folder proyek agen. Email berlampiran SELALU meminta persetujuan di dialog, juga bila "Kirim otomatis" menyala (berkas bisa membocorkan data bila agen terkecoh isi email). Lampiran masuk dibatasi 15 MB per berkas karena Gmail API mengirimnya sebagai base64 dalam JSON dan HP punya memori terbatas. Bacaan email lewat Gmail API (bukan IMAP) dan folder Draf dibaca sebagai pesan biasa; membuat atau mengedit draf belum didukung. Access token Google dari `AuthorizationClient` diperbarui otomatis di thread latar bila masih dapat diotorisasi; bila Google meminta persetujuan ulang, aplikasi meminta pengguna menghubungkan ulang.
- Telegram: teks saja (tanpa foto/berkas); `telegram_baca` mengonfirmasi pesan (hilang dari antrean bot) kecuali `tandai=tidak`.
- Konektor berjalan saat aplikasi aktif; belum ada pemantau latar yang memicu agen saat email/pesan baru masuk.

## Status verifikasi
Unit test dijalankan dengan `./gradlew testDebugUnitTest` dan harus menghasilkan `BUILD SUCCESSFUL`. Uji login Google end-to-end tetap membutuhkan keystore yang terdaftar pada Client ID Android serta pengujian tombol "Masuk dengan Google" memakai akun sungguhan.
