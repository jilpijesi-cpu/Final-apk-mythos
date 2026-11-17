# MYTHOS 2.31: agen serba bisa

Perubahan dari 2.30:

1. Web Search Tavily (web_search) DIGANTI alat bawaan tanpa kunci API: cari_web (kueri, DuckDuckGo) dan baca_web (url).
   - Kueri berisi email atau deretan angka 9+ digit (mirip NIK, rekening, telepon) ditolak.
   - Hanya http/https port 80/443; alamat lokal dan jaringan pribadi ditolak, juga setelah DNS dan pada tiap pengalihan.
   - Hasil dibungkus Cx.untrusted (data tidak tepercaya). Tidak dikirim ke model lokal.
   - Dihapus: WebSearch.kt, WebSearchSettings.kt, WebSearchTest.kt, tombol "Web Search: atur kunci Tavily".
2. Video terlampir otomatis diubah jadi maksimal 6 gambar (frame) lewat MediaMetadataRetriever (VideoFrames.kt).
3. GeneralAssist.kt: aturan memakai cari_web dan frame video.

Tes baru: WebToolsTest, VideoFramesTest; ToolDeclarationsTest ditulis ulang.
BELUM dibangun atau dijalankan (tidak ada Gradle di lingkungan penyusun). Jalankan testDebugUnitTest dulu.
Parser hasil pencarian diuji dengan contoh HTML buatan sendiri, bukan halaman DuckDuckGo asli: uji sekali di HP.

## 2.32: perbaikan koneksi Gemini CLI
- Sebelum tiap pesan, aplikasi dulu memanggil Termux DUA kali (cek terpasang, cek login) dengan batas 15 detik. Termux yang baru
  dibangunkan sering lebih lambat dari itu, jadi timeout dibaca "belum masuk" lalu Pengaturan terbuka: itu gejala
  "harus buka Termux dulu, kadang bisa kadang tidak".
- Sekarang: satu panggilan (batas 45 detik), hasil sukses di-cache 10 menit, dan timeout/proses dibunuh (exit 124/137/143)
  dianggap "belum pasti" sehingga chat tetap dijalankan. Status di Pengaturan juga tidak lagi salah menulis "Belum masuk".
- Tes baru: CliPreflightTest.

## 2.33: Gemini CLI streaming seperti Codex
- Gemini dijalankan dengan `--output-format stream-json`; jawaban dibaca per baris selagi berjalan dan tampil mengalir lewat
  CodexActivityUi yang sama. Panggilan alat Gemini tampil sebagai baris aktivitas (GeminiStream.kt, GeminiStreamUi.kt).
- CLI lama yang menolak flag itu terdeteksi dari stderr; aplikasi memberi catatan, mengulang dengan mode biasa, dan mengingatnya.
- Batas waktu mode stream 900 detik (mode biasa tetap 300).
- Tes baru: GeminiStreamTest. BELUM dicoba pada Gemini CLI asli: format kejadian mengikuti dokumentasi, uji sekali di HP.
