# Penyesuaian tampilan sesuai rekaman layar (v2.12)

- Bar atas: tombol menu dan tombol hantu berbentuk lingkaran bergaris tipis. Menu membuka drawer. Hantu menyalakan Chat samaran
  (tombol jadi hitam, judul "Chat samaran" tampil di tengah, pesan tidak ditulis ke history.json).
- Layar kosong: logo bintang oranye digambar lewat kode (BurstView), selalu bergerak mengikuti waktu (sinar berputar, memanjang-memendek, cincin bola berdenyut), sapaan serif "Begadang, <nama>?".
  Nama diambil dari preferensi `nama_tampil` (bawaan: Jefri Nicholee). Layar Chat samaran: hantu hitam + keterangan + tautan "Pelajari selengkapnya".
- Kolom input: placeholder "Mythos...", teks 17sp.
- Drawer samping (Drawer.kt): geser dari tepi kiri atau ketuk menu. Chat, Proyek, Code, Artefak, daftar Terbaru, Semua chat,
  avatar inisial (membuka Pengaturan), tombol hitam "Chat baru". Lapisan utama bergeser 82% dengan sudut membulat.
  Proyek = Siapkan Termux, Code = Catatan perintah, Artefak = pilih file lampiran.
- Tombol (+) membuka lembar "Tambahkan ke chat": Kamera, Foto, File, Tampilkan foto terbaru, Tambahkan ke proyek (membuka Pengaturan),
  Penalaran mendalam (saklar, menggantikan baris Konektor karena aplikasi ini tidak punya konektor).
- Baru: Glyphs.kt (ikon garis + logo bintang), Drawer.kt. Diubah: MainActivity, ChatHistory, Palette, Sapaan, strings.xml.
- Belum dikompilasi di lingkungan penyusunan: jalankan WITH_TESTS=1 bash build-termux.sh.
