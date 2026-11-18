# Perbaikan alur streaming Mythos 2.22

## Temuan dari dua video acuan
1. Narasi di baris status masuk sebagai pergantian teks utuh (crossfade, teks berkilau, lebar dan panah bergeser), bukan diketik per karakter. Ini yang berbeda di 2.20 dan 2.21.
2. Teks narasi menyebut pekerjaan nyata (file, perintah) dan singkat, tanpa awalan "Saya sedang" dan tanpa titik tiga di akhir.
3. Dua jenis baris selesai dalam satu alur: ringkasan alat (teks abu-abu + centang kecil + panah, tanpa ikon kiri) dan baris penalaran (ikon jam + teks abu-abu + panah). Hanya satu baris aktif (ikon titik oranye, teks berkilau) di bagian bawah.
4. Saat pekerjaan berjalan lama, muncul sub-baris kecil "Masih diproses..." di bawah baris status, sejajar dengan awal teks.

## Perubahan
- `LiveStatusLine.kt`: `startNarration` memakai `swapText` (tanpa typewriter), tertahan 2,6 dtk lalu kembali ke status kerja; ditambah sub-baris "Masih diproses..." (6 dtk tanpa aktivitas).
- `CodexActivityUi.kt`: narasi dari nama file/perintah/kueri nyata; ringkasan alat memakai IconView.CHECK + CHEVRON; item penalaran memakai baris terpisah IconView.CLOCK + CHEVRON (isi penalaran mentah tetap tidak ditampilkan).
- `app/build.gradle.kts`: versionCode 35, versionName 2.22.

## Tempo (hitungan dari video A, per detik)
Narasi tampil 2-6 detik, status umum 1-2 detik di antaranya, lalu narasi berikutnya. 2.22: `NARRATION_HOLD_MS = 3600`, `NARRATION_GAP_MS = 1500` di `LiveStatusLine.kt`. Ubah dua angka ini bila hasil rekaman terasa lebih cepat atau lambat.

## Panjang penjelasan
- Narasi baris status: kalimat penjelasan utuh (contoh: "Membaca ActivityZone.kt dan LiveStatusLine.kt untuk memahami bagian yang berkaitan"), dipotong "…" bila melebihi lebar layar seperti di video.
- Teks jawaban di antara alat: `ModelCall.kt` menambahkan panduan ke prompt Codex agar menulis 2-3 kalimat sebelum tiap rangkaian alat dan satu paragraf temuan sesudahnya. Kepatuhan model terhadap panduan ini tidak dijamin.

## Ikon bola oranye
Hasil ukur video A+B (video B lanjutan A, total ±89 dtk): grid 4 titik pada 16,5-23,5 dtk, 27-32,5 dtk, dan 54-62 dtk (awal rangkaian alat); orbit 3 titik di luar itu, termasuk seluruh video B dan 33-53 dtk. 2.22: `LiveStatusLine.gridBurst()` menyalakan grid `GRID_BURST_MS = 7000` saat rangkaian alat baru mulai (`CodexActivityUi.upsert`), lalu kembali ke orbit. Pemicu pastinya di video tidak bisa dipastikan dari rekaman saja; satu kemunculan (24-26 dtk, membaca gambar) tetap orbit di video tetapi grid di 2.22.

## Scroll otomatis
Video: pesan pengguna naik ke atas layar saat terkirim, lalu layar diam selama jawaban dan baris aktivitas bertambah (posisi teks tidak bergeser selama 25 dtk di video B), dengan ruang kosong di bawah isi. 2.22: `chatBox` diberi padding bawah sebesar tinggi layar chat dikurangi 96dp (`MainLayout.kt`); `pinToTop()` menaikkan pesan pengguna (`StatusBar.kt`, dipanggil dari `addMsg`, kecuali saat memuat riwayat); `scrollDown()` hanya menggulir bila ujung isi melewati tepi bawah layar. Yang tidak terlihat di video dan karenanya tebakan: perilaku setelah isi melewati tepi bawah (2.22 mengikuti ujung isi dengan jarak 24dp).

## Belum diverifikasi
- Build Android dan tes Gradle tidak dijalankan (tidak ada kompiler Kotlin/Gradle di lingkungan ini). Hanya pemeriksaan keseimbangan kurung.
- Kesamaan 1:1 harus dinilai dari rekaman APK 2.22 dengan prompt uji yang sama.
