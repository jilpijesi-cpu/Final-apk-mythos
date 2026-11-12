# v80 - Koneksi Codex terkunci + teks panjang jadi .txt

## Masalah
- Jembatan Codex dimatikan lalu dinyalakan ulang berulang kali (tiap cek koneksi gagal sesaat, tiap aplikasi dibuka, dan oleh pemantau Pengaturan tiap 2 detik). Permintaan yang sedang berjalan ikut putus: "unexpected end of stream".
- Teks panjang tampil sebagai dinding teks di chat.

## Perbaikan
- Satu tombol **Sambungkan**. Setelah berhasil, status `codex_locked` disimpan permanen. Jembatan yang hidup tidak pernah dimatikan/dinyalakan ulang otomatis lagi (hanya ketukan tombol yang boleh restart).
- Bila jembatan benar-benar mati (3x cek gagal) dan sudah terkunci: hanya dinyalakan (mode aman: skrip keluar bila sudah ada proses hidup, token tidak ditimpa).
- Jembatan Python: header SSE dikirim langsung + denyut `: ping` tiap 8 dtk selama codex berpikir, isi permintaan besar dibuang tuntas sebelum membalas 413, klien putus tidak mematikan jembatan, port bisa dipakai ulang.
- Teks > 1200 karakter atau > 20 baris otomatis dikirim sebagai lampiran `teks_YYYYMMDD_HHMMSS.txt`.

## Kecepatan (tambahan)
- Jembatan memakai `model_reasoning_effort="low"` (ubah lewat env `CODEX_EFFORT`; kosongkan untuk bawaan). Bila Codex menolak opsi itu, otomatis diulang tanpa opsi.
- Prompt ringkas (LightChat) kini juga dipakai untuk pertanyaan pendek seperti "kamu model apa".

## Diagnosa (tambahan)
- Toast kini membedakan "tersambung dan dikunci" (tombol manual) dari "jembatan sempat mati sendiri, dinyalakan lagi" (Android mematikan Termux).
- `/ping` kini memuat `uptime` (detik). Bila jembatan sudah hidup >30 dtk saat aplikasi memverifikasi ulang, tidak ada toast (cek gagal sesaat, bukan restart).
