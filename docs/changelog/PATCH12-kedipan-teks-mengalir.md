# patch12: kedipan saat teks mengalir
- **Penanda yang belum lengkap disembunyikan** (`CleanText.kt`, `dropPartialTail`): baris terakhir yang baru berisi `#`, `-`, `1.`, `|`, atau ``` `` ``` tidak ditampilkan sampai isinya datang, jadi baris tidak berganti jenis blok (paragraf -> judul/butir/tabel/kode) di tengah aliran.
- **Huruf tidak diketik ulang** (`rememberRevealed` di `Design.kt`): jumlah huruf tampil tidak diturunkan saat ujung teks menyusut sebentar.
- **Koreksi gulir** (`ChatScreen.kt`): koreksi `rawTail` menunggu animasi penyematan selesai (`pinAnimating`), lalu satu koreksi akhir dilakukan tepat sesudahnya.
- Belum diubah: baris "sedang berpikir" dicabut di bingkai yang sama saat teks pertama muncul, dan spacer di bawah jawaban menyusut telat satu bingkai.
- Tes: `StreamingStabilityTest`. Nama APK kini `Mythos-v83-patch12-debug.apk`. Versi tidak berubah (83).
