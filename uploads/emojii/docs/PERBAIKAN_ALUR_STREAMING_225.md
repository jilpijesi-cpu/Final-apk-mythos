# 2.36 — Teks live streaming sering tidak muncul

Dasar: pembacaan kode statis (belum direproduksi di perangkat, belum dikompilasi).

## Perbaikan
1. **Pembacaan macet tidak lagi diam** (`LiveTail.kt`, `ModelCall.kt`): bila 15 pembacaan Termux gagal berturut-turut
   (mis. aplikasi di latar belakang, Android 12+ melarang memulai service), muncul satu catatan di chat, bukan hening.
   Pulih otomatis; jawaban tetap tampil utuh di akhir. Parameter baru `onStall` (opsional, aman untuk pemanggil lama).
   Kasus "belum ada data baru" tidak dihitung kegagalan.
2. **Antrean event tidak lagi tertahan sampai akhir giliran** (`ChatMessages.kt`, `CodexActivityUi.kt`): `onTextSettled()`
   sebelumnya hanya dipanggil bila `smoothFromCodex` true; jalur `streamUpdate` membuatnya false sehingga event alat yang
   antre tidak pernah dilanjutkan. Kini juga dipanggil bila `hasQueuedEvents()`.
3. **Indikator live kembali bila teks tak kunjung tiba** (`CodexActivityUi.kt`): setelah `message_start` baris live
   disembunyikan; kini pengaman 5 detik menampilkannya lagi bila tidak ada teks.

## Belum diubah
- Kecepatan ketik (45-260 karakter/detik) masih bisa tertinggal pada jawaban panjang.
- Parser Gemini hanya menerima `role == "assistant"`.
- Race kecil pada `LiveTail.finish()` saat membatalkan job di tengah pembacaan.
- Belum dikompilasi/dites: jalankan `./gradlew testDebugUnitTest assembleDebug`, lalu uji dengan layar mati dan aplikasi dibuka lagi.

## Tambahan (dari rekaman layar, penyedia openrouter/free)
- Gejala: setelah mengirim pesan, tombol berubah jadi kotak (agen berjalan) tetapi layar kosong 17+ detik tanpa indikator.
- Penyebab (kode, cocok dengan rekaman): `ActivityZone.beginRun()` menampilkan zona tetapi tidak mengembalikan `live.visibility`.
  Run CLI (Codex/Gemini) menyembunyikannya (`setLiveVisible(false)`) dan tidak memulihkannya. Pada run agen API berikutnya
  `setStatus` menganggap zona tampil lalu menghapus status di chat, sementara baris live-nya GONE -> tidak ada indikator.
- Perbaikan: `beginRun()` kini memaksa `live.visibility = VISIBLE`.
- Catatan desain (belum diubah): jalur API (OpenRouter/OpenAI-compatible) TIDAK menampilkan teks model secara live.
  `onText` hanya menyimpan `lastPartial` dan mengganti status ke "Menulis jawaban"; jawaban akhir baru ditampilkan setelah
  giliran selesai lewat `streamUpdate()` (animasi ketik dari teks yang sudah lengkap).
