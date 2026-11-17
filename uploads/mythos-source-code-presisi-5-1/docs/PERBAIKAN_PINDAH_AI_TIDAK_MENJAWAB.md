# Pindah ke AI lain saat penyedia tidak menjawab

STATUS: BELUM DIKOMPILASI di Android. `isUnreachable` (ApiErrors.kt) dikompilasi bersama tes `UnreachableTest` (53 tes lulus bersama tes lain);
perubahan di `ModelCall.callModelRetry` hanya dibaca ulang.

## Masalah
`callModelRetry` hanya memindahkan AI bila galatnya `isFallbackWorthy` (server sibuk, kuota, model ditolak, permintaan terlalu besar). Bila server penyedia
internet tidak menjawab sama sekali (koneksi gagal, terputus, lewat batas waktu 3 menit; setelah 3 percobaan ulang dilempar `IOException` biasa), aplikasi berhenti
dengan "Tidak bisa terhubung ke server AI…", tidak pindah ke penyedia lain, padahal yang macet bisa hanya penyedia itu (mis. OpenRouter).

## Perbaikan
- `ApiErrors.isUnreachable(e, local)`: `IOException` pada penyedia internet (bukan server lokal, bukan `LocalServerDownException`) layak pindah.
- `ModelCall.callModelRetry`: gerbang pertama kini `isFallbackWorthy(e) || isUnreachable(e, activeLocal)`. `activeLocal` memperhitungkan penyedia cadangan yang sedang dipakai.
- `isFallbackWorthy` TIDAK diubah (tes lama `ApiErrorsTest` tetap berlaku). Server lokal tetap tidak dialihkan.

## Perilaku yang perlu diketahui
- Pindah hanya ke penyedia yang punya API key tersimpan (atau Ollama lokal dengan model terpilih). Tanpa tujuan, galat tampil seperti biasa.
- Bila internet HP mati total, semua penyedia internet gagal sama; rantai cadangan tetap dicoba (tiap penyedia 1 percobaan) dan menunggu putaran 10 dan 25 detik
  sebelum menyerah, jadi galat akhir muncul sekitar 35 detik lebih lambat daripada sebelumnya.
