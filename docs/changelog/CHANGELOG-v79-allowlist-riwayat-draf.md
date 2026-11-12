# v79 — Allowlist domain Browser Agent + riwayat versi draf email

## Browser Agent: daftar situs tepercaya
- Baru: `agent/BrowserDomainPolicy.kt`. Tepercaya: `ojk.go.id` dan `bi.go.id` (termasuk subdomain). Hanya https.
- Host mirip ("evilojk.go.id", "ojk.go.id.evil.com", "ojk.go.id@evil.com") ditolak.
- `BrowserAgentScreen`: navigasi utama ke situs lain (tombol Buka, tautan, pengalihan) ditahan dan memunculkan dialog izin.
  Izin hanya untuk host persis itu dan hanya selama layar ini terbuka (tidak disimpan).
- Agen (baca halaman, isi, klik, rencana form) tidak berjalan di halaman yang hostnya belum tepercaya, termasuk bila halaman
  itu tercapai lewat pengalihan yang lolos dari pemeriksaan navigasi.
- Batas: hanya navigasi frame utama yang diperiksa; iframe dan sumber daya (CDN, iklan) tidak diblokir.

## Riwayat versi draf email
- Baru: `agent/DraftHistory.kt`. Setiap kartu draf (ComposeEmail) disimpan otomatis sebagai `draf/<penerima>/vNNN.json`
  dan ringkasan `draf/RIWAYAT.md`. Draf yang isinya sama persis tidak membuat versi baru.
- Ringkasan perubahan: subjek diubah, +/- baris isi, lampiran diubah.
- Saat pengguna mengetuk "Kirim email", versi yang cocok ditandai "dibuka di aplikasi email". Tidak pernah "terkirim",
  karena aplikasi tidak tahu apakah pengguna menekan Kirim.
- `Protocol.kt`: agen diminta membaca `draf/RIWAYAT.md` sebelum merevisi draf ke penerima yang sama dan tidak mengedit folder `draf/`.
- Catatan: folder `draf/` berada di dalam proyek, jadi ikut ekspor ZIP dan dorongan GitHub seperti `KASUS.md`.

## Riwayat versi isi form (rencana Browser Agent)
- Baru: `agent/FormPlanHistory.kt`. Saat pengguna menekan "Jalankan rencana", isi yang akan diisi (isi/pilih/tanggal/radio/centang)
  disimpan sebagai `form/<situs>/vNNN.json`. Rencana yang sama persis tidak membuat versi baru; OTP/PIN/CVV/token disamarkan.
- Lokasi: `draf/form/...` di proyek aktif; bila tidak ada proyek, di penyimpanan privat aplikasi (`draf-form`).
  Mode samaran tanpa proyek tidak menyimpan apa pun.

## Form: shadow DOM, iframe bersarang, modal (`assets/mythos_form.js`)
- Pencarian field kini menjangkau shadow root terbuka dan iframe sama-origin yang bersarang. Label dicari di akar elemen itu sendiri.
- Saat ada modal yang memblokir (`dialog:modal`, `aria-modal="true"`, `role=alertdialog`, `.modal.show/.in`), hanya isi modal yang bisa
  dibaca, diisi, atau diklik; halaman di belakangnya tidak tersentuh. `inspect` melaporkan judul modal. `role=dialog` tanpa
  `aria-modal` (banner cookie) tidak dianggap memblokir.
- Diuji di Chromium nyata: `python3 tools/test_form_js.py`. Batas: shadow root tertutup dan iframe lintas-origin tetap tidak terjangkau.

## Tampilan Browser Agent dirapikan (`ui/BrowserAgentScreen.kt`)
- Layar kini menghormati status bar dan bilah navigasi (`statusBarsPadding`, `navigationBarsPadding`, `imePadding`), jadi jam tidak
  menimpa judul dan teks status tidak tertimpa tombol navigasi; area web ikut mengecil saat keyboard muncul.
- Bilah atas ringkas (kembali, judul + "Login dan OTP Anda isi sendiri", muat ulang). Panel gembok terpisah dihapus.
- Alamat jadi satu kapsul tanpa label melayang: ikon gembok hijau untuk situs tepercaya, peringatan merah untuk yang belum,
  tombol panah di ujung, dan tombol Go di keyboard. Garis kemajuan tipis saat halaman memuat.
- Halaman web dipakai selebar mungkin (sudut membulat); status tinggal satu baris dengan titik warna (hijau/oranye/merah).
- Dok agen di bawah: kolom perintah dengan tombol kirim bulat (aktif bila ada teks), lalu chip aksi yang bisa digeser:
  Baca halaman, Rencanakan form, dan (setelah halaman dibaca) Analisis halaman serta Lihat/Sembunyikan teks.
- Memakai warna dan font tema Mythos (`MythosColors`, `MythosSans`). Belum dikompilasi; periksa tampilan nyata di perangkat.

## Versi
- `versionCode` 69 -> 70, `versionName` 1.0.70 (agar bisa dipasang sebagai pembaruan).

## Tes baru
`BrowserDomainPolicyTest` (5 tes), `DraftHistoryTest` (6 tes), `FormPlanHistoryTest` (4 tes). Belum dijalankan; jalankan `./gradlew testDebugUnitTest`.
