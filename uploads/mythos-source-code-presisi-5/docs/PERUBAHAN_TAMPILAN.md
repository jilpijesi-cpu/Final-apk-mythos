# Perubahan tampilan v2.2 (modern, sederhana, tajam)

Tujuan: layar lebih bersih, satu cara untuk tiap hal, mudah dioperasikan satu tangan.
Logika agen, penyedia model, dan keamanan tidak diubah.

## Chat
- Latar putih bersih (dulu gradasi biru berawan). Teks AI 16,5sp, spasi baris 1,35, warna tinta tunggal.
- Hapus kilau biru berjalan pada teks yang sedang mengalir, dan hapus kata tebal/kode yang berwarna biru.
  Tebal = tebal biasa; kode inline = abu lembut.
- Hapus header "AI Agent - status" di tiap balasan (mengulang baris aktivitas). Status hanya di SATU pil abu di atas jawaban.
- Pil status tanpa awalan "AI Agent"; hasil akhir ringkas: "Berhasil - n langkah".
- Gelembung pengguna lebih rapat; chip saran putih bergaris halus (tidak lagi biru).

## Header dan input
- Header: pil model di kiri, chat baru + pengaturan (ikon roda gigi) di kanan. Ikon menu tiga garis (yang dulu membuka Pengaturan) dan avatar dihapus.
- Input: satu tombol aksi saja (mikrofon saat kosong, panah kirim saat ada teks, kotak saat berjalan). Tombol mikrofon ganda dihapus.
- Layar kosong kini punya sapaan dan tiga contoh perintah (mengisi kolom input, tidak langsung menjalankan).

## Pengaturan, model, izin
- Pengaturan jadi lembar bawah: Penyedia AI, API key, Simpan. Menu pendukung berupa baris tautan. Opsi lanjutan memakai saklar dengan keterangan satu baris.
- Pemilih model jadi lembar bawah dengan daftar bersih, centang pada model aktif, penanda "sibuk".
- Dialog izin: penjelasan, label risiko berwarna lembut, perintah di kotak abu. Tombol "Jalankan", "Tolak", "Ya, semua".
- Dialog sistem: sudut 28dp, tombol tanpa huruf kapital semua.

## File
- Baru: Ui.kt (pembantu bentuk, tombol, lembar bawah).
- Diubah: Palette, MainActivity, ChatMessages, Markdown, MessageMenu, ActivityZone, AgentStepList, StatusBar,
  SettingsDialog, ModelPicker, ApprovalDialog, ChatControls, ModelCall/ToolExecution (hanya teks tombol), styles/colors/dialog_bg.
- Belum dikompilasi di lingkungan penyusunan: jalankan WITH_TESTS=1 bash build-termux.sh.

## AI bekerja mandiri (v2.3)
- Prompt sistem ditulis ulang: dari sekitar 30 aturan kaku menjadi satu paragraf pendek. Isinya hanya fakta lingkungan
  (Termux, folder kerja, alat, folder lampiran) dan kebiasaan yang meningkatkan mutu kerja (pahami tujuan, baca dulu,
  belajar dari kegagalan, periksa hasil, lapor jujur). Keputusan bertindak atau menjawab ada di AI.
- Dihapus: pesan dorongan tersembunyi "Kamu belum menjalankan langkah apa pun..." (AgentLoop).
- Tahap "Memahami permintaan" kini mati secara bawaan (pengaturan "understand" = false); hemat satu panggilan model per pesan.
- Tetap ada: dialog persetujuan, mode hanya baca/sandbox/otonom, pengingat verifikasi build (maks 2 kali).

## Baris status live + terminal live (v2.5)
Tujuan: satu baris status yang bersih seperti di aplikasi Claude, dan kode/perintah AI bisa dilihat langsung saat diketuk.

- Pil abu + daftar langkah lama diganti `LiveStatusLine` (LiveStatusLine.kt): ikon, teks berkilau, panah.
  - Ikon berganti sesuai kerja AI: titik oranye mengorbit saat berpikir; saat menjalankan langkah ikon mengikuti jenisnya
    (baca, cari, tulis, build = roda gigi berputar, arsip, unduh, uji, ...). Ikon baru muncul dengan efek membal.
  - Teks berkilau abu-ke-gelap, naik-memudar saat berganti, dipotong "..." sebelum panah. Tahan 0,7 detik agar tidak berkedip (TextGate).
  - Teks langkah kini memakai kalimat `deskripsi` yang ditulis AI sendiri (mis. "Memeriksa isi arsip ZIP"), bukan judul generik.
  - Saat selesai: ikon centang hijau / silang merah / dll, teks diam ("Berhasil - 3 langkah"). Panah tetap bisa dibuka untuk meninjau.
- Ketuk baris -> terminal gelap terbuka dengan animasi tinggi + pudar (`LiveTerminal`):
  - Perintah diberi prompt `$` dan diwarnai (perintah cyan, opsi oranye, string hijau, operator ungu, variabel kuning).
  - Keluaran mengalir baris demi baris (efek ketik) dan diwarnai: `> Task` redup, `BUILD SUCCESSFUL` hijau, error merah, warning kuning,
    angka oranye, path ungu. Kursor hijau berkedip selama perintah jalan.
  - Tulis file: path + isi bernomor dengan warna kode; setelah selesai tampil diff (+ hijau, - merah).
  - Deretan chip langkah (geser samping): ketuk untuk melihat langkah lain; ketuk chip terakhir untuk kembali mengikuti live.
  - Tombol Salin (salin isi terminal), Besar/Kecil (tinggi 250 / 460 dp), penghitung waktu berjalan, tombol Setuju/Tolak saat menunggu izin.
  - Tekan lama judul terminal -> panel komputer lengkap (seperti sebelumnya).
  - Gulir otomatis mengikuti keluaran terbaru; bila pengguna menggulir ke atas, tidak ditarik turun.
- Pembacaan keluaran live (`LiveTail`) dipercepat dari 2,5 dtk ke 1,5 dtk (build tetap 5 dtk).
- Baru: LiveStatusLine.kt (tampilan), TerminalLex.kt (pewarnaan murni, teruji), test TerminalLexTest.kt.
- Diubah: ActivityZone.kt (memakai LiveStatusLine), AgentLoop.kt (`stepTitleFor`), LiveTail.kt (interval).
- Belum dikompilasi di lingkungan penyusunan: jalankan WITH_TESTS=1 bash build-termux.sh.

## Penyelarasan gaya chat dengan rekaman Claude (v2.6)
Acuan: rekaman layar `Screen_Recording_20261021_023219.mp4` (warna diukur dari piksel, ukuran huruf dari tinggi huruf kapital).

- Palet hangat menggantikan palet biru: latar `#F9F9F6`, gelembung/pil/tombol bulat `#EFEDEB`, teks `#141413`, ikon `#353532`,
  teks pendukung `#77756F`, garis `#E5E3DE`, titik "berpikir" `#D67658`, tombol utama `#C6613F`. Semua layar yang memakai `Palette`
  (pengaturan, pemilih model, dialog izin, catatan perintah, setup) ikut berubah otomatis.
- Gelembung pengguna: sudut 12dp, garis tipis 1px `#D0CECC`, teks 14sp, bantalan 14dp, lebar maksimal hampir penuh. Balasan AI: 16sp, spasi baris 1,3.
- Header: ikon menu tiga garis (kiri, membuka Catatan perintah), gelembung chat berisi + (chat baru), titik tiga (Pengaturan). Pil model dipindah dari header ke kolom input.
- Kolom input: satu kotak membulat 28dp berisi teks di atas dan baris bawah `+` | pil model | mikrofon | kirim/berhenti (tombol bulat 40dp).
  Mikrofon selalu ada; tombol kirim hanya muncul saat ada teks; tombol berhenti (kotak berongga) muncul saat agen bekerja.
- Baris status: teks 14sp, titik oranye `#D67658`.
- Bilah status memakai warna latar, bilah navigasi `#FDFDFD`.
- Baru di `Views.kt`: ikon MENU3, KEBAB, CHAT_PLUS, STOP_BOX. `PaletteTest` memakai `Palette.TEXT_MAIN` (bukan angka tetap).
- Belum dikompilasi dan belum dibandingkan di perangkat: jalankan build di GitHub Actions lalu bandingkan dengan rekamanmu.

## Penalaran mendalam (v2.7)
"Penalaran mendalam untuk masalah kompleks", dipasang pada agen. Aktifkan lewat menu `+` (lembar "Tambahkan"); bila aktif, ikon otak muncul di baris
bawah kolom input (ketuk untuk mematikan). Pengaturan tersimpan (`deep`), tidak ikut terhapus saat chat baru.
- Tahap berpikir sebelum bertindak (`DeepReasoning.kt`): model membuat analisis tanpa tool (tujuan, fakta/batasan, minimal dua pendekatan,
  langkah terpilih, risiko dan cara verifikasi). Pesan sederhana dijawab "SEDERHANA" dan tidak diberi catatan. Status tampil "Berpikir mendalam".
- Hasil analisis dibawa di prompt sistem selama tugas itu, bersama tambahan aturan: periksa asumsi, nilai tiap hasil alat, cek ulang kesimpulan.
- Mode berpikir bawaan penyedia (hanya bila aman): Gemini 2.5 (`thinkingBudget` 24576 / 32768 untuk Pro) dan model reasoning OpenAI asli
  (`reasoning_effort: high`, hanya ke api.openai.com). Anthropic tidak memakai mode bawaan; ia mengandalkan tahap berpikir dan aturan prompt.
- Biaya: satu panggilan model tambahan per pesan baru (hanya saat fitur aktif).
- Tes: `DeepReasoningTest`. Belum dikompilasi; jalankan lewat GitHub Actions.

### Perbaikan baris ikon bawah kolom input (v2.7.1)
Saat ikon otak muncul bersama nama model panjang (mis. gemini-3.1-flash-lite-preview), tombol mikrofon terdorong keluar layar.
Pil model sekarang berada di pembungkus yang mengisi sisa ruang; nama panjang dipotong dengan "…" sehingga mikrofon dan tombol kirim/berhenti selalu terlihat.

### Pemeriksaan ulang susunan (v2.7.2)
Semua baris horizontal di aplikasi (±30 tempat) ditinjau lewat kode: header, kolom input, lembar bawah, pengaturan, pemilih model,
status, kartu aktivitas, panel komputer, catatan perintah, setup, dan baris lampiran. Hampir semuanya sudah aman (bobot, ellipsize, atau scroll).
Perbaikan: baris statistik di laporan akhir `ActivityZone` kini mengisi sisa ruang dan dipotong "…" (sebelumnya selebar teks, bisa terdorong keluar layar);
latar lembar bawah memakai warna permukaan hangat yang sama dengan palet.

## Peralihan model senyap dan konfirmasi minimal (v2.8)
- Kartu "Tersambung ke ... / percobaan sebelumnya" (FailoverView) tidak lagi tampil. Peralihan model tetap berjalan otomatis di latar belakang;
  baris status hanya menampilkan "Mengalihkan AI otomatis". Bila semua cadangan gagal, pesan error biasa tetap muncul.
- Konfirmasi minimal (Pengaturan > Lanjutan > "Konfirmasi seperlunya", bawaan aktif): langkah rutin jalan tanpa dialog; yang meminta jawaban hanya
  pertanyaan AI (ask_user), penghapusan/penimpaan data, area sensitif/di luar folder kerja, dan perintah berjaringan di sandbox.
  Dialog yang tersisa disederhanakan: "Izinkan?" dengan tombol Izinkan / Tolak (tanpa "Ya, semua").
- Prompt sistem diberi arahan: kerjakan langsung, tanya lewat ask_user hanya untuk keputusan penting yang tidak bisa dibatalkan.
- Belum dikompilasi; jalankan lewat GitHub Actions.

## Gelombang cahaya yang lebih dalam pada teks status live (v2.9)
Teks status yang berjalan langsung (mis. "Membaca AgentRuntime.kt") kini memakai gelombang cahaya yang lebih dalam (`ShimmerText` di `Views.kt`):
- Profil pita bertitik banyak: satu puncak sorot penuh dengan "gema" lebih redup di belakangnya, bahu halus di kedua sisi (bukan lagi tiga titik datar).
- Gerak menyapu dengan percepatan mulus (smoothstep) lalu jeda singkat sebelum gelombang berikutnya; satu siklus 2,2 detik.
- Kontras lebih tinggi: teks abu `#A7A59F` disapu sorot hampir hitam `#141413`; untuk teks gelap, sorotnya abu terang `#BDBBB5`.
- Profil warna dipisah ke `Palette.kt` (`mixArgb`, `waveColors`, `WAVE_STOPS`) dan diuji di `ShimmerWaveTest`.
- Teks jawaban AI yang sedang mengalir tidak diberi gelombang (tetap seperti aplikasi Claude); bisa ditambahkan bila diinginkan.
- Belum dikompilasi dan belum dilihat di perangkat: ukur kesannya di HP lalu sesuaikan konstanta `SHIMMER_PERIOD_MS`, `SHIMMER_BAND`, dan warna.

---

## Beranda disamakan dengan rekaman layar Claude (24 Okt 2026)

- Bilah atas: menu tiga garis (kiri, garis bawah separuh) dan ikon hantu (kanan). Ikon hantu membuka menu kecil: **Chat baru** dan **Pengaturan** (dulu dua ikon terpisah).
- Layar kosong: logo bintang oranye di tengah + "Halo, BOS" (huruf tipis, sebutan dari preferensi `sapaan`). Subjudul dan tiga tombol saran ("Lihat isi folder", "Cari dan perbaiki error", "Build aplikasi") dihapus.
- Kolom input: petunjuk "Chat Mythos ...", tombol bulat 36dp, pil model, mikrofon, dan tombol kirim oranye yang selalu tampak. Mengetuk kirim saat kolom kosong hanya menampilkan "Tulis pesan dulu".
- **Belum dikompilasi** (tidak ada kotlinc di lingkungan kerja). Jalankan `./gradlew assembleDebug` atau workflow GitHub Actions.

## Streaming live AI disamakan dengan rekaman Claude (27 Okt 2026)
Acuan: `Screen_Recording_20261026_183644.mp4` (kerapatan layar rekaman diukur ~2,75; semua ukuran dikonversi ke dp).
- Ikon status (`ClaudeDots.kt`, baru): satu ikon titik oranye `#D67658` yang berganti bentuk. Berpikir/menunggu = 3 titik mengorbit
  (kecepatan tiap titik berbeda, kemiringan orbit bergelombang, fase awal acak); langkah alat = 4 titik di sudut persegi, sepasang
  titik diagonal berputar 180 derajat sambil merapat ke pusat. Perpindahan bentuk: menyusut ke satu titik (120+100 ms),
  bertahan 260 ms, mengembang 300 ms. Rumus pasangan titik ada di `ClaudeDotsMath` (teruji di `ClaudeDotsTest`).
- Teks status: dua teks bertumpuk; teks lama terpotong dari kanan sambil memudar, teks baru memudar masuk (200 ms, tanpa geser),
  lebar wadah beranimasi 250 ms sehingga panah ">" ikut bergeser. Tata letak: kolom ikon 40dp, teks mulai tepat setelahnya, panah menempel.
- Ikon hasil akhir (centang/silang) tetap memakai `IconView`.
- Belum ada: baris ringkasan berpikir berikon jam ("Menyelidiki ... >") di atas jawaban (perlu sumber teks ringkasan).
- Belum dikompilasi: jalankan lewat GitHub Actions.

### Teks status sesuai pekerjaan agen (27 Okt 2026)
- Baru `StepText.kt`: `describeCommand` menurunkan kalimat dari perintah yang benar-benar dijalankan ("Membaca ModelCall.kt",
  `Mencari "failover" di agent`, "Memeriksa isi a.zip", "Menjalankan gradle testDebugUnitTest"); `describeWrite` untuk write_file.
- `stepTitleFor`: kalimat 'deskripsi' dari AI tetap diutamakan, tetapi bila kosong atau umum ("Menjalankan perintah") dipakai hasil turunan di atas.
- Teks antar-langkah kini "Membaca hasil: <langkah terakhir>" (dulu selalu "Memproses hasil langkah").
- Petunjuk parameter 'deskripsi' ke model diperketat: menyebut sasaran konkret (file/fungsi/tujuan), maks 8 kata.
- Tes: `StepTextTest`. Belum dikompilasi.

### Gerak titik tidak berulang (27 Okt 2026)
- Sprite rekaman dihapus (dulu bagian akhirnya berulang). Orbit 3 titik kini dihitung: tiap titik punya kecepatan sendiri, kemiringan
  orbit bergelombang (periode 1,8 dtk dan 7,3 dtk), fase/kecepatan acak setiap ikon muncul. Kotak 4 titik: tiap denyut memilih acak
  pasangan yang berputar, arah, kedalaman, durasi (520-780 ms), dan jeda (80-880 ms); sekitar 1 dari 4 denyut memutar kedua pasangan berlawanan arah.

### "Proses berpikir" dan "Sedang menjalankan perintah" tampil lebih lama (27 Okt 2026)
- "Proses berpikir" (ikon orbit) kini teks bawaan saat AI menyusun langkah: awal tugas, dan setelah alat selesai (dulu "Sedang berpikir",
  "Menganalisis permintaan", "Membaca hasil: ..."). Tahan minimal 1,6 dtk sebelum diganti.
- Saat alat dipanggil: tampil "Sedang menjalankan perintah" (ikon orbit) selama 1,4 dtk, lalu diganti kalimat langkah yang spesifik (ikon 4 titik),
  seperti urutan di rekaman Claude. Pembaruan tertunda dibatalkan bila langkah sudah berganti (`stepSeq`).
- "Menunggu respons model AI" baru muncul setelah 5 dtk tanpa token (dulu 1,8 dtk) agar "Proses berpikir" tidak cepat tertimpa.
- `TextGate` menerima fungsi tahan per teks (`holdOf`); tes baru di `StepTextTest`.
