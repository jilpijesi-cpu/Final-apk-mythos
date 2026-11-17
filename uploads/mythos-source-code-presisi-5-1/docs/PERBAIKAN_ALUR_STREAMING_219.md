# Perbaikan Alur Streaming Mythos 2.19

## Temuan dari rekaman
- Isi jawaban terlihat muncul dalam blok besar dan bukan selalu mengikuti delta yang baru diterima.
- Ikon kursor bintang ditempel sebagai `ImageSpan` pada teks jawaban sehingga dapat tampak menempel pada kata terakhir.
- Polling file live 350 ms membuat pembaruan output terasa tertunda.
- Jalur fallback masih menjalankan typewriter sintetis walaupun tujuan integrasi adalah menampilkan data yang diterima langsung.

## Perubahan
- `ChatMessages.kt`: renderer stream kini menampilkan teks yang sudah diterima secara langsung, tanpa ticker typewriter buatan dan tanpa ikon yang disisipkan di dalam teks. Pesan AI yang sama dipertahankan selama delta masuk; ketika stream selesai, teks diformat final dan kontrol salin ditambahkan.
- `ModelCall.kt`: polling file live diturunkan dari 350 ms menjadi 100 ms agar latensi pembacaan lebih rendah.
- `app/build.gradle.kts`: versi dinaikkan menjadi `versionCode 32`, `versionName 2.19`.

## Verifikasi
- Tes protokol streaming lokal: PASS. Urutan event aktivitas/delta, penggabungan teks, UTF-8, dan pencegahan duplikasi lulus pada wrapper/test harness.
- Pemeriksaan sintaks wrapper Node.js: PASS.
- Build Android/tes Kotlin: BELUM DIVERIFIKASI di lingkungan ini karena akses distribusi Gradle tidak tersedia.
- Pengujian langsung di perangkat: masih perlu dilakukan dengan APK 2.19.

## Batasan
Perubahan ini memperbaiki kekakuan tampilan delta dan mengurangi jeda polling. Kesamaan 1:1 dengan video harus dinilai dari rekaman APK yang baru dibangun, terutama urutan aktivitas tool nyata dan transisi status. Tes protokol simulasi bukan bukti pengujian langsung terhadap sesi Codex pengguna.
