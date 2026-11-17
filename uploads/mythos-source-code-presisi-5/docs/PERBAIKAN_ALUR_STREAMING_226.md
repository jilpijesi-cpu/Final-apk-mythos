# 2.37 — Alur live streaming agen API sesuai rekaman acuan

Rekaman acuan (1000242092.mp4), urutan yang dipakai:
1. Bubble pengguna.
2. Kalimat pembuka model mengalir LIVE ("Saya lihat dulu kedua rekamannya.").
3. Di bawahnya satu baris aktivitas: ikon bola oranye + teks berkilau (alat yang sedang jalan) + panah.
4. Alat selesai -> baris menjadi ringkasan diam (ikon jam abu-abu) dan tetap di tempatnya.
5. Paragraf berikutnya mengalir LIVE di bawah ringkasan itu; baris aktivitas disembunyikan selama teks mengalir,
   muncul lagi sesudah teks selesai. Berulang sampai jawaban akhir.

## Yang diubah (jalur agen API: OpenRouter, OpenAI-compatible, Anthropic, Gemini API) — `AgentLoop.kt`
- `onText` kini menampilkan teks model secara live lewat `streamAppend` (selisih dari teks kumulatif). Bila percobaan
  ulang/ganti model menulis dari awal, kotak yang sama digambar ulang lewat `streamUpdate` (tanpa dobel).
- `onFlush` tidak lagi menutup kotak. Kotak ditutup sesudah giliran jelas:
  - giliran dengan alat -> `endLiveSegment()`: tunggu ketikan selesai (maks 15 dtk), `streamSegmentEnd()` (tanpa tombol
    Salin), lalu baris aktivitas ditampilkan lagi;
  - jawaban akhir -> `streamUpdate` + `streamEnd` seperti sebelumnya (tanpa dobel karena kotak yang sama masih terbuka);
  - giliran kosong / dorongan verifikasi -> segmen ditutup dulu.
- Teks pembuka giliran yang memanggil alat sebelumnya dibuang; kini tampil seperti rekaman.

## Belum sama persis (jujur)
- Baris ringkasan alat di chat (ikon jam + "Membaca sebuah file, menjalankan 2 perintah" + panah) saat ini hanya ada
  di jalur Codex/Gemini CLI (`CodexActivityUi`). Jalur API masih memakai baris live zona + kartu langkah agen. Agar
  identik, langkah berikutnya: kirim `item_started`/`item_completed` dari `runTool` ke `codexActivityUi.accept`.
- Belum dikompilasi/dites (tanpa Gradle di sini). Uji: kirim tugas yang memanggil alat dengan model OpenRouter;
  pastikan teks pembuka mengalir, baris aktivitas tampil setelahnya, dan jawaban akhir tidak muncul dua kali.
