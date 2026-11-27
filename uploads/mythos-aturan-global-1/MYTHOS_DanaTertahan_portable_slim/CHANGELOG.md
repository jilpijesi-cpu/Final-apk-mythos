## Kolom input disederhanakan + aksi cepat di layar kosong
- Baris ikon kolom input kini hanya: tambah (+), menu, dan satu tombol dinamis di kanan: mikrofon saat kolom kosong, Kirim saat ada isi atau lampiran, Henti saat agen bekerja, Cari pada mode LeakOSINT (`ChatControls.kt` `updateAction` / `onAction`). Mode LeakOSINT dengan kolom kosong tetap menampilkan toast "Tulis pesan dulu".
- Tombol penyedia AI dan Chat samaran dipindah ke lembar Menu (bagian AI: "Penyedia AI & kunci" dan "Chat samaran"; yang aktif ditandai). Logika `setIncognito`/`showApiKeyDialog` tidak diubah.
- Lebar hitbox tombol kini 48dp (42dp + 3dp kiri/kanan), tinggi 48dp.
- Layar kosong: tiga chip aksi cepat ("Buat aplikasi baru", "Audit proyek", "Lanjutkan proyek terakhir") yang mengisi kolom input tanpa langsung mengirim; hilang otomatis di Chat samaran.
- Belum dikompilasi dan belum dicoba di perangkat.

## Perbaikan desain tampilan (identitas, kontras, animasi)
- Identitas: layar kosong kini menampilkan tulisan "Mythos" dari logo asli (`res/drawable-nodpi/logo_wordmark.png`, warnanya mengikuti tema terang/gelap) di atas ikon mata.
- Kontras (mode terang): `INK_SOFT` 4,37 -> 5,2:1 di atas latar (4,7:1 di atas kolom isian); `BRAND` 4,05 -> 4,76:1 dengan teks putih (`BRAND_2` ikut disesuaikan); `BUBBLE_BORDER` 1,49 -> 2,3:1. Mode gelap tidak diubah.
- Aksen langkah agen (`accentFor`): dari 11 warna menjadi netral + 3 status (hijau = selesai, merah = hapus/berhenti, oranye = sedang berjalan).
- Animasi mata layar kosong (`BurstView`) dibatasi ~30 fps (sebelumnya mengikuti 60 fps tanpa henti).
- Area sentuh tidak diubah: bilah input sudah memakai `touchPads()` (tinggi hitbox 48dp); lebar 42dp dibiarkan agar 5 tombol + "Kirim" tetap muat di layar 360dp.
- Belum dikompilasi dan belum dicoba di perangkat.

## Ikon mata disamakan dengan logo Mythos
- `Glyphs.kt` `drawWatchfulMythosEye`: ditulis ulang dari gambar logo (diukur dari piksel): kelopak almond hitam pekat berujung tumpul, dua sklera putih lancip di kiri-kanan, iris hitam bulat besar, pupil putih di tengah. Sebelumnya hanya garis almond tipis dengan iris kecil sehingga tidak mirip logo.
- Kedipan menutup kelopak sampai hitam penuh; saat melirik, iris dan pupil bergeser dan sklera kiri-kanan menyempit/melebar mengikuti arah.
- Pemanggil (`BurstView`, `ClaudeDots`, `DeepBrain`, `ChatMessages`) memakai rasio tinggi `MYTHOS_EYE_H_RATIO` (0,53); ikon statis `BRAIN` ikut disamakan (kelopak + iris tebal).
- Belum dikompilasi dan belum dicoba di perangkat; bentuknya sudah dibandingkan dengan logo lewat render piksel.

## Logo layanan: ritme dan ukuran disamakan dengan rekaman acuan
- Diukur frame demi frame dari video acuan (25 fps, 19 siklus): logo tampil ~0,36 dtk, titik ~0,8 dtk, periode ~1,15 dtk, logo pertama muncul ~0,9 dtk setelah langkah mulai. Sebelumnya 0,7 dtk / 1,6 dtk / 2,3 dtk / 0,45 dtk.
- `LiveStatusLine.kt`: logo langsung berukuran penuh, tanpa membesar dari 60% dan tanpa efek memantul (di video ukuran logo tetap 35 x 27 px dari frame pertama); hanya alpha yang berubah: masuk ~0,2 dtk, keluar ~0,1 dtk, titik memudar ~0,08 dtk. Ukuran logo 24 dp -> 21 dp (skala video ~1,7 px/dp dari lebar orbit titik 19 dp = 32 px).
- Belum dikompilasi dan belum dibandingkan berdampingan di perangkat; semua angka ada di tujuh konstanta `LOGO_*` bagian atas `LiveStatusLine.kt` bila perlu disetel.

