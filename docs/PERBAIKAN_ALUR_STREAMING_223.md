# Perbaikan alur streaming Mythos 2.23-2.29 (kaku -> mengalir)

Melanjutkan 2.22 (lihat PERBAIKAN_ALUR_STREAMING_222.md). Bagian di 2.22 yang bertentangan (narasi kalimat panjang, panduan 2-3 kalimat utuh, tahan narasi 3,6 dtk / jeda 1,5 dtk) digantikan oleh catatan ini.

## Penyebab
- Teks Codex datang bergelombang (polling Termux), lalu langsung ditampilkan utuh: muncul satu blok, diam, blok berikutnya.
- Narasi aktivitas diketik tetap 30 ms/karakter, sedangkan jawaban dirender langsung: dua tempo berbeda di satu layar.
- Label aktivitas generik dan sering berganti ("Menyiapkan langkah berikutnya…", "Membaca sebuah file").

## Perubahan
- `StreamSmoother.kt` (baru): penghalus per frame (16 ms). Kecepatan menyesuaikan backlog; backlog kecil mengalir pelan (≥45 karakter/detik), backlog >60 karakter dikejar cepat (1.500 karakter ≈ 0,9 detik); tidak memecah surrogate pair.
- `ChatMessages.streamAppend`: delta masuk ke `typewriterTarget`, ditampilkan lewat `startSmoothing()`. Batas item/akhir stream tetap menampilkan teks penuh seketika, jadi urutan teks-aktivitas tidak berubah.
- `CodexActivityUi`: selama satu alat berjalan, baris menampilkan subjek nyata (mis. "Membaca README.md…", "Menjalankan sed -n …"); setelah selesai kembali ke ringkasan.
- `LiveStatusLine`: sesuai video acuan baru, narasi tertahan 3,2 dtk lalu kembali ke "Sedang menjalankan perintah" bila tidak ada narasi berikutnya; bila ada, berganti langsung setelah jeda 0,35 dtk (antrean tidak menumpuk). "Masih diproses..." tetap 6 dtk.
- `ModelCall` (prompt `GUIDE`): model diminta menulis 1-2 kalimat ringkas sebelum alat dan 2-3 kalimat temuan sesudahnya, bukan paragraf panjang di tengah kerja; penjelasan lengkap untuk jawaban akhir. Larangan "kalimat pendek tunggal" dihapus.
- Gulir otomatis (`StatusBar.startFollow`): diganti dari lompatan `scrollTo` menjadi pengikut halus per frame (easing 22% dari jarak tertinggal). Perilaku acuan tetap: layar diam selama isi baru masih muat, mengikuti hanya saat ujung isi melewati tepi bawah, berhenti bila pengguna menggulir ke atas. Listener gulir mengabaikan gerakan otomatis (`autoScrolling`) supaya tidak salah menandai pengguna menggulir ke atas.
- Narasi aktivitas (`CodexActivityUi`) dipendekkan menjadi satu baris ringkasan langkah seperti video acuan ("Membaca README.md", "Menjalankan termux-info", "Memperbarui Foo.kt", "Mencari ..."). Bila server mengirim ringkasan penalaran (`reasoning.summary`), judul/kalimat pertamanya (maks. 80 karakter) dipakai sebagai narasi; isi penalaran mentah tetap tidak ditampilkan. Wrapper Node meneruskan `summary` untuk item `reasoning`.
- Teks yang mengalir: tiga huruf terakhir memudar masuk (alpha 70/140/210) selama teks masih mengejar, lalu penuh saat sudah terkejar.
- Tes baru: `StreamSmootherTest`.

## Belum diverifikasi
Build Android dan tes Gradle belum dijalankan (tidak ada Gradle/kotlinc di lingkungan ini). Perlu dicoba di HP dengan prompt uji yang sama seperti video.

## Tidak diubah
- Jalur baca stream Termux (`LiveTail`, polling 100 ms untuk Codex, tiap putaran memanggil `tail | head | base64`). Mengganti ke pipe/socket langsung mengubah protokol wrapper Node dan tidak bisa diuji di lingkungan ini; dengan penghalus per frame, efek bergelombangnya sudah tersamarkan. Jika masih terasa tersendat di HP, ini kandidat berikutnya.
- Baris jam "Menyiapkan langkah berikutnya…" (penalaran) tetap ada.

## Cara penyampaian agen (prompt `GUIDE`, ModelCall.kt)
Urutan yang diminta dari model: (1) satu kalimat pendek sebelum alat; (2) temuan 1-2 kalimat setelah hasil alat, tanpa mengulang; (3) tanpa paragraf panjang di tengah kerja; (4) penutup rapi: hasil utama, lalu "Inti penjelasan" berisi apa yang berubah dan cara mengatur/memakainya (langkah atau nilai konkret), lalu satu kalimat tentang yang belum terverifikasi; (5) bahasa Indonesia sederhana, tanpa penalaran mentah. Untuk mengubah gaya, edit kalimat bernomor itu.

## Baris siaga di akhir jawaban dimatikan
`Standby.showStandby()` tidak lagi menambahkan baris logo bintang + kata bergantian ("Menunggu…", "Merenung…", "Melamun…", "Memimpikan…", "Menghayal…", "Mentriangulasi…") di bawah jawaban. Layanan latar siaga (`enterStandbyService`, notifikasi "MYTHOS siaga") tetap berjalan. Untuk mengaktifkannya lagi, kembalikan isi `showStandby()` dari arsip 2.22.

## Teks status live mengikuti keadaan nyata
Sebelumnya teks status kerja ditentukan sekali saat alat dimulai ("Sedang menjalankan perintah") dan tetap tampil walau perintahnya sudah selesai. Kini `CodexActivityUi` melacak alat yang benar-benar berjalan (`running`) dan memperbarui teks lewat `ActivityZone.setCodexIdle` -> `LiveStatusLine.setIdle`:
- Alat dimulai: narasi spesifik ("Membaca README.md"), lalu status "Sedang membaca file / menjalankan perintah / mengubah file / mencari di web" selama alat itu masih berjalan.
- Semua alat selesai: "Proses berpikir" (model memproses hasil).
- Model mulai menulis pesan (`message_start`) dan tidak ada alat berjalan: "Menulis jawaban".
- Item penalaran dimulai atau ringkasan penalaran tiba: "Proses berpikir".
Narasi yang sedang tampil tidak dipotong; teks status baru dipakai begitu narasi selesai.

## Penyesuaian tempo ("terlalu cepat") berdasarkan perbandingan dua video
Diukur dari video acuan (4 frame/detik): kalimat ±80 karakter mengalir ±0,75-1 dtk (≈80-100 karakter/dtk); status kerja "Sedang menjalankan perintah" tampil ±1,2 dtk; narasi ±2 dtk; baris status tidak tampil selama teks diketik; tidak ada baris jam yang tertinggal, hanya satu ringkasan abu-abu per rangkaian alat. Rekaman 2.23 sebelumnya: teks dikejar nyaris seketika, status saling menimpa cepat, baris jam "Menyiapkan langkah berikutnya…" menumpuk.
- `StreamSmoother.Pacer`: kecepatan dasar 45 karakter/dtk + 0,6 x backlog (maks 260), dengan sisa pecahan agar halus. 80 karakter ≈ 1,2 dtk, 300 ≈ 2,7 dtk, 1.500 ≈ 7 dtk. Konstanta `MIN_CPS`, `GAIN`, `MAX_CPS` di `StreamSmoother.kt`.
- `LiveStatusLine`: narasi tertahan 2,4 dtk (`NARRATION_HOLD_MS`); status kerja tampil minimal 1,2 dtk sebelum diganti (`MIN_DWELL_MS`), termasuk untuk narasi berikutnya; antrean narasi dibatasi 2 terbaru agar tidak tertinggal dari pekerjaan nyata.
- Baris status live disembunyikan selama teks jawaban diketik (`message_start`) dan muncul lagi saat alat berikutnya mulai; dipulihkan di `showReport` dan `reset`.
- Penalaran tidak lagi meninggalkan baris jam; cukup status "Proses berpikir" di baris live.

## 2.25 - paragraf menumpuk saat menunggu proses panjang
Temuan dari rekaman 2.24: saat agen hanya menunggu perintah yang masih berjalan (mis. build Gradle), tidak ada item baru dari server, jadi baris status yang disembunyikan selama teks diketik tidak pernah muncul lagi dan beberapa paragraf menumpuk tanpa petunjuk apa pun.
- `CodexActivityUi.onTextActive/onTextSettled`: baris status disembunyikan saat delta teks datang, dan ditampilkan lagi 0,7 dtk setelah teks selesai diketik (dibatalkan bila giliran berakhir, agar tidak berkedip sebelum ringkasan hasil).
- Teks status saat itu mengikuti alat yang benar-benar masih berjalan ("Sedang menjalankan perintah"), atau "Proses berpikir" bila tidak ada.
- Perbaikan bug 2.24: daftar alat berjalan (`running`) tidak pernah terisi sehingga status sering salah jatuh ke "Proses berpikir". Kini berupa peta id -> teks status dan diisi saat `item_started`.

## 2.26 - baris ringkasan alat hanya muncul setelah agen pindah ke bawah
Sebelumnya baris abu-abu "Menjalankan 1 perintah ✓ >" dipasang saat rangkaian alat baru mulai dan isinya terus berubah ("1 perintah" -> "2 perintah") selagi agen masih bekerja, sehingga tampil ganda bersama baris live. Di video acuan, selama bekerja hanya baris live (narasi/status) yang tampil; ringkasan baru muncul di atas teks berikutnya.
- `CodexActivityUi.createGroup` tidak lagi memasang baris ke layar. `finishGroup()` memasangnya sekali, saat agen memulai pesan berikutnya (`message_start`), saat giliran selesai, atau lewat `finishPending()` (dipanggil dari `ModelCall` di akhir giliran, termasuk bila dihentikan). Setelah dipasang, baris ditinggal diam: grup berikutnya selalu baris baru.
- Item penalaran tidak lagi menutup rangkaian alat, sehingga satu ringkasan mencakup beberapa langkah.
- Label live khusus di dalam baris ringkasan dihapus (tidak diperlukan lagi).

## 2.27 - teks live ditinggal di tempat agen
Yang ditinggalkan agen saat pindah ke bawah kini adalah teks live terakhirnya sendiri (narasi alat atau ringkasan penalaran, mis. "Membaca README.md"), bukan ringkasan generik ("Menjalankan 1 perintah"). Baris itu dipasang di posisi agen berada (di atas teks penjelasan berikutnya), dalam keadaan mati (abu-abu, tidak berkilau), tetap bisa diketuk untuk membuka detail langkah. Bila tidak ada teks live yang pernah tampil di rangkaian itu, dipakai ringkasan jumlah langkah sebagai cadangan. Lihat `CodexActivityUi.lastLive` dan `finishGroup()`.

## 2.28 - tempo "mengalir santai": live menunggu teks ringkasan selesai
Penyebab terlalu cepat: event alat (`item_started`) tiba saat teks penjelasan masih diketik oleh penghalus; `streamSegmentEnd` lalu menuntaskan sisa teks seketika dan baris live langsung muncul. Pada akhir giliran, `streamEnd` juga memotong sisa teks.
- `CodexActivityUi` kini punya antrean berurutan (`script`) berisi potongan teks dan event alat. `ModelCall` mengirim potongan teks lewat `codexActivityUi.delta()` (bukan langsung `streamAppend`) sehingga urutannya terhadap event terjaga.
- Event alat tidak dijalankan selama teks masih diketik; baru diproses setelah teks selesai + jeda `SETTLE_MS` (600 ms). Dengan begitu teks ringkasan selesai dulu, baru teks live/ baris tinggalan muncul, lalu agen turun ke bawah.
- Potongan teks yang tertahan digabung agar tidak ada jeda buatan di tengah satu pesan.
- Akhir giliran: `ModelCall` menunggu (maks. 90 dtk, berhenti bila dibatalkan) sampai teks selesai diketik dan antrean kosong sebelum `flushAll()`/`streamEnd()`.
- Baris live tidak berkedip muncul sebelum teks berikutnya (`awaitingText`) dan tidak muncul lagi setelah giliran selesai (`turnDone`).
Penyetel: `SETTLE_MS` di `CodexActivityUi`; kecepatan ketik di `StreamSmoother`.

## 2.29 - teks uji bawaan untuk tes kirim email/WhatsApp/Telegram
Bila pengguna hanya menyuruh agen "tes kirim" tanpa menyebut isinya, agen mengirim teks bawaan:
"Halo,\nSaya Mythos AI, asisten pribadi <nama tampil>.\nPesan ini adalah uji coba pengiriman otomatis; tidak perlu dibalas." (subjek email: "Uji coba pesan dari Mythos AI"). Nama diambil dari preferensi `nama_tampil` (bawaan "Jefri Nicholee"). Bila pengguna menyebut isi sendiri, isi itu yang dipakai; tujuan tetap harus jelas dan dialog persetujuan tetap berlaku. Lihat `pesanTesBawaan` (Sapaan.kt), aturan (6) di `connectorsPrompt` (ConnectorsIntegration.kt), tes `PesanTesTest`. Berlaku untuk jalur agen umum yang memakai alat konektor (bukan jalur Codex).
