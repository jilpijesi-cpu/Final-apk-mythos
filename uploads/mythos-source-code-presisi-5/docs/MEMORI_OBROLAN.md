# Memori obrolan

Agen menyimpan catatan tanya-jawab dari riwayat chat dan memakainya sebagai bahan untuk **mode obrolan model lokal**. Makin sering aplikasi dipakai
(termasuk lewat AI internet), makin banyak bahan yang bisa dipanggil kembali oleh model lokal.

- **Menyimpan**: setelah tiap tugas/obrolan selesai (penyedia apa pun), pertanyaan pengguna dan jawaban akhir AI dicatat (`rememberLastExchange`).
  Tidak disimpan: Chat samaran, sapaan, galat, dan teks yang tampak rahasia (kunci API, sandi, token).
- **Memanggil**: saat mode obrolan lokal menerima pesan, 1-3 catatan paling cocok (kecocokan kata kunci + bonus sering dipakai + masih baru) disisipkan di
  pesan pengguna sebagai blok `[Ingatan dari obrolan lama]`, maksimal 900 karakter. Catatan yang dipanggil bertambah skornya.
- **Isi awal**: sekali saja, semua chat lama di perangkat diimpor.
- **Batas**: 400 catatan; yang lama dan jarang dipakai dibuang lebih dulu.
- **Memanggil, makin tajam**: kata yang jarang muncul di memori (mis. `proot`) bernilai lebih tinggi daripada kata yang ada di mana-mana (mis. `install`), jadi makin banyak catatan, makin tepat yang dipilih.
- **Lokasi**: `filesDir/memory/chat_memory.json` (di perangkat; hanya terbaca oleh model lokal di HP, tidak dikirim ke AI internet).
  File berversi (`{"v":2,"entries":[...]}`); format lama (larik polos) tetap terbaca, jadi memori ikut terbawa setiap upgrade kode.
- **Cadangan otomatis**: setiap ada catatan baru (paling cepat sekali per menit, dilewati bila tidak berubah) memori disalin ke Termux di
  `~/MYTHOS/memory/chat_memory.json`. Saat aplikasi dibuka, cadangan itu digabung kembali ke memori perangkat (pertanyaan sama disatukan, jawaban
  terbaru menang, jumlah panggilan terbesar dipakai). Jadi memori tetap ada walau aplikasi dihapus dan dipasang ulang (mis. tanda tangan APK berbeda).
  Cadangan baru ditimpa setelah isi lamanya berhasil dibaca; bila Termux tidak menjawab, dicoba lagi saat aplikasi dibuka berikutnya.
- **Hapus**: Pengaturan > Lanjutan > Opsi lain > Hapus memori obrolan (cadangan di Termux ikut dihapus). Riwayat chat tidak ikut terhapus.
- Kode: `ChatMemory.kt` (logika murni, tes `ChatMemoryTest`), `ChatMemoryStore.kt` (disk).
