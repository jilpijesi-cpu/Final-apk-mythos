# Mythos 2.18 — Penyesuaian alur live streaming terhadap video referensi

## Perubahan

- Respons Codex App Server kini diteruskan sebagai satu stream JSONL terurut berisi delta teks dan event aktivitas. Dengan satu saluran, UI memproses segmen teks dan aktivitas dengan urutan yang sama seperti event yang diterima.
- Delta teks dirender langsung saat diterima, bukan diketik ulang melalui animasi sintetis.
- Pada batas item tool, bagian jawaban sebelumnya difinalisasi; baris aktivitas dimasukkan tepat setelahnya; pesan AI selanjutnya tampil setelah baris tersebut.
- Aktivitas shell, perubahan file, pencarian web, dan pemanggilan alat ditampilkan dalam baris ringkas tanpa kartu latar, sesuai pola pada video. Detail tindakan dibuka saat baris diketuk.
- Status awal Codex diubah menjadi “Sedang dikerjakan…”. Detail aktivitas hanya dibuat dari event App Server nyata; isi penalaran internal mentah tidak ditampilkan.
- File live dibaca inkremental dan mempertahankan chunk yang sudah dibaca ketika proses selesai, untuk mencegah hilangnya delta tertunda.
- Version metadata dinaikkan menjadi `versionCode 31` / `versionName 2.18`.

## Verifikasi yang dilakukan

- Node.js `--check` pada wrapper App Server: lulus.
- Uji protokol yang dapat dijalankan ulang (`python3 tools/test_codex_stream_protocol.py`): lulus dengan server App Server tiruan. Dua segmen teks muncul sebagai event delta, aktivitas perintah berada di antaranya, dan gabungan teks tepat sama dengan respons yang diharapkan; tidak ada teks ganda.
- Pemeriksaan sintaks Kotlin `CodexActivityUi.kt` dengan compiler/stub lokal: lulus.
- Arsip proyek akan diperiksa integritasnya setelah dikemas.

## Belum diverifikasi

Build Android dan unit test Gradle belum dapat dijalankan: Gradle Wrapper gagal mengunduh Gradle 8.9 dari `services.gradle.org` karena `UnknownHostException`. APK belum dihasilkan atau diuji di perangkat. Uji video pada HP tetap diperlukan untuk membuktikan kecocokan 1:1 di runtime.
