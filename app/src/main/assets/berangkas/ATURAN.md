# Aturan kerja agen (bagian dari berangkas, hanya-baca)

Berkas ini ikut di dalam aplikasi. Isinya berubah hanya saat aplikasi di-upgrade. Agen TIDAK boleh mengubahnya.

## 1. Pertanyaan kesiapan ("apakah aplikasi ready?")
- Salin skrip dengan `berangkas_salin` (sumber: `skrip/kita-cek.sh`), lalu jalankan dengan `run_command`: `bash ~/.mythos/berangkas/skrip/kita-cek.sh` (tambahkan folder proyek di belakangnya bila ada proyek aktif).
- Laporkan hasilnya APA ADANYA, termasuk baris ❌ dan ⚠️ beserta petunjuknya.
- DILARANG menilai kesiapan dari isi folder kerja. Folder kerja boleh kosong dan itu normal.
- "Aplikasi siap" (alat di Termux) dan "Proyek siap" (gradlew, workflow) adalah dua hal terpisah. Proyek hanya dicek bila ada proyek aktif: `kita-cek.sh <folder-proyek>`.
- Jangan menyimpulkan "tidak ada" sebelum benar-benar menjalankan pemeriksaannya. Kalau pemeriksaan gagal dijalankan, katakan gagal, bukan "tidak ada".

## 2. Cara memakai berangkas
- Baca `INDEKS.md` lebih dulu, lalu hanya berkas yang dibutuhkan. Jangan membaca semuanya sekaligus (memperlambat respons).
- Berangkas hanya-baca. Baca dengan `berangkas_baca`; untuk memakai skrip atau templat, SALIN dengan `berangkas_salin` ke `~/.mythos/berangkas` di Termux, lalu jalankan atau ubah salinannya (mis. `cp` ke proyek).
- Salinan sementara dibuat ulang setiap dipakai. Jangan mengandalkan salinan lama.

## 3. Bekerja dan membangun (build)
- Rumah proyek yang asli adalah penyimpanan aplikasi. Termux hanya tempat menjalankan `git`, `gradle`, `gh`.
- Sebelum build: salin proyek ke `~/.mythos/build/<nama-proyek>`, jalankan perintah di sana, ambil hasilnya (APK, log, hasil tes), lalu bersihkan folder itu.
- Jangan menyalin balik kode sumber dari Termux ke aplikasi kecuali pengguna memintanya, agar tidak ada dua versi yang bertabrakan.
- Jangan membiarkan berkas sementara menumpuk di folder kerja pengguna.

## 4. Rahasia
- Berangkas TIDAK berisi API key, token GitHub, keystore, atau sandi. Jangan mencarinya di sana.
- Rahasia hanya ada di Pengaturan aplikasi dan dipakai lewat alat, tanpa pernah ditampilkan ke model.
- Jangan mencetak token atau sandi ke chat, log, atau berkas.

## 5. Sebelum menyatakan selesai
- Buktikan dengan hasil perintah (keluaran, kode keluar), bukan dugaan.
- Bila ragu atau perintah gagal, laporkan jujur apa yang terjadi.
