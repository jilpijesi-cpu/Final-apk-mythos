# MYTHOS: dokumentasi utama

> Perbaikan build agen (v2.5): lihat `PERBAIKAN_BUILD_AGEN.md`.
> Audit diri dan upgrade diri (v2.6): lihat `SELF_UPGRADE.md`.

Dokumen ini adalah sumber kebenaran untuk kondisi aplikasi saat ini. Laporan lama (perubahan UI dan
laporan sebelumnya) dipindahkan ke folder `arsip/` dan tidak lagi berlaku jika bertentangan dengan dokumen ini.
Riwayat perubahan terbaru ada di `PERBAIKAN_LOGIKA_AGEN.md`. Struktur kode ada di `STRUKTUR.md`. Panduan alat, peta path, dan template hasil uji ada di `PANDUAN_ALAT.md`; perbaikan terbaru di `PERBAIKAN_WAWANCARA_AGEN.md`.

## Build dan tes
- Jalankan `./gradlew testDebugUnitTest assembleDebug`.
- Status: belum pernah dikompilasi di lingkungan pengembangan. Build pertama wajib dilakukan sebelum fitur baru.

## Perilaku agen saat ini
- Tidak ada penolakan otomatis oleh kebijakan umum. Tindakan berisiko ditampilkan sebagai dialog persetujuan. Pengecualian: mode hanya baca dan perintah yang DIBLOKIR sandbox ditolak tanpa dialog.
- "Ya, semua" melewati dialog untuk sisa tugas, KECUALI tindakan yang bertanda peringatan merah (area sensitif, folder sistem Termux, file di luar folder kerja) dan perintah berjaringan di sandbox: keduanya tetap bertanya.
- Mode otonom default MATI (opt-in di Pengaturan). Saat aktif ia melewati dialog, tetapi tidak melewati mode hanya baca, blokir sandbox, atau persetujuan perintah berjaringan di sandbox.
- Agen bisa bertanya ke pengguna lewat tool `ask_user` (bukan untuk meminta izin menjalankan perintah; izin ditangani dialog aplikasi). Di mode otonom pertanyaan tidak diteruskan. Tanpa jawaban dalam 10 menit dianggap tidak menjawab, dan itu BUKAN persetujuan.
- Hasil "Ditolak pengguna" (eksplisit, jangan diulang) dibedakan dari "Ditolak: tidak ada jawaban" (dialog ditutup, layar diputar, atau 10 menit habis; boleh diajukan sekali lagi).
- Tombol Berhenti juga membatalkan pesan yang sedang menunggu giliran. Pesan baru yang diketik saat agen jalan tetap menghentikan agen lalu melanjutkan dengan pesan itu.
- Agen dianggap macet bila memanggil alat yang sama dengan hasil yang sama 3 kali berturut-turut; error yang sama (tanda tangan baris error spesifik, bukan baris generik Gradle) 4 kali juga menghentikan tugas.
- Dialog yang sedang ditunggu dilepas saat Activity ditutup atau diputar.
- Tahap pemahaman (`TaskBrief.kt`) mencatat tujuan dan asumsi. Default MATI (preferensi `understand`). Tidak menahan tugas, dan catatannya direset tiap tugas baru. Di prompt ia hanya asumsi; pesan pengguna tetap yang utama.
- Laporan akhir: "Berhasil", "Belum terverifikasi" (perubahan belum dibuktikan build atau verifikasi), "Gagal", atau "Dihentikan".
- Mode hanya baca dan opsi "autoread" tetap menjadi pilihan pengguna di Pengaturan.

## Koneksi
- Percobaan ulang saat gagal membuka koneksi: 5 kali, jeda 2, 4, 8, 16 detik (maks 20).
- Percobaan ulang saat stream terputus: 5 kali, jeda bertahap (maks 15 detik).
- Stream OpenAI yang berakhir tanpa `[DONE]` atau `finish_reason` dianggap terputus dan diulang.
- Belum ada: lanjut otomatis setelah percobaan habis. Pengguna perlu mengirim "Lanjutkan". Checkpoint dihapus saat tugas selesai dan saat chat dihapus. Saat melanjutkan, bila riwayat berakhir di giliran model, ditambahkan pesan sistem (tidak tampil sebagai gelembung) agar permintaan berikutnya berakhir di giliran pengguna.

## Audit logika agen
Temuan, perbaikan, dan hal yang sengaja tidak diubah: lihat `AUDIT_LOGIKA_AGEN.md`.

## Diketahui belum selesai
- Pemindahan ke ViewModel atau coroutine belum dilakukan. `MainActivity.kt` masih besar dan memakai status global.
- Dua sistem tampilan langkah masih berjalan berdampingan: `ActivityFeed` dan `InlineActivityBlock`. `InlineActivityBlock` belum dipasang ke chat.
- Belum diuji di perangkat nyata.
