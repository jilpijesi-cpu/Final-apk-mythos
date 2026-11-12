# patch13: WebFetch tidak lagi berputar
- **Masalah:** permintaan "cari layanan bantuan flip" membuat agen membuka puluhan alamat flip.id (/login, /daftar, /register, ...) selama belasan menit. Situs berbasis JavaScript itu mengembalikan beranda yang sama (judul sama, +-55.000 karakter) untuk alamat apa pun, dan penjaga duplikat hanya menangkap URL yang persis sama, jadi satu-satunya batas adalah 300 langkah.
- **`WebFetchGuard`** (baru, `agent/WebFetchGuard.kt`, satu instance per tugas di `RunState.web`):
  - halaman baru yang judulnya sama dan panjangnya hampir sama (selisih <= 1%, min. 50) dengan halaman yang sudah dibuka di situs yang sama diberi catatan sistem; setelah 3 kali, alamat baru di situs itu ditolak;
  - alamat berbeda per situs (domain induk, jadi help.flip.id dan flip.id dihitung satu): catatan lunak di alamat ke-5, penolakan sesudah 8 alamat;
  - mengulang/melanjutkan alamat yang sama (offset) tetap boleh; penolakan tidak dihitung sebagai kegagalan beruntun, jadi tugas tidak dibatalkan, model diarahkan menjawab dari yang sudah ada.
- **Label:** baris WebFetch kini "Membuka flip.id/login" (host + jalur), bukan hanya "Membuka flip.id" (`webLabel` di `Timeline.kt`).
- Tidak diubah: kata "flip" di `CASE_WORDS` masih memicu mode kasus/sengketa untuk permintaan sesederhana "cari layanan bantuan flip".
- Tes: `WebFetchGuardTest`. Nama APK kini `Mythos-v83-patch13-debug.apk`. Versi tidak berubah (83).
