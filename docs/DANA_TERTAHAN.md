# Pendamping dana tertahan (khusus Indonesia)

Berkas: `DanaTertahan.kt` (logika murni), `DanaTertahanIntegration.kt` (alat, penyimpanan, ekstensi `MainActivity`).
Tes: `DanaTertahanTest.kt`. Status: **belum dikompilasi**; jalankan `./gradlew testDebugUnitTest`.

## Apa yang dilakukan
Agen MENDAMPINGI pengguna mengurus saldo atau uang yang pending/ditahan aplikasi atau perusahaan. Agen tidak bisa membuka
atau mencairkan dana. Ia mencatat kasus, menyusun draf surat, menunjukkan langkah dan jenjang eskalasi, dan menandai tenggat.

| Alat | Fungsi |
|---|---|
| `kasus_dana_buat` | Catat kasus: platform, jenis (kyc/fraud/sengketa/bekukan/tunda/lainnya), nominal, tanggal tahan, referensi, alasan |
| `kasus_dana_catat` | Tambah catatan (bukti/komunikasi/langkah) dan/atau ubah status (baru, cs, tiket, regulator, selesai, tutup) |
| `kasus_dana_lihat` | Daftar kasus, atau satu kasus + linimasa + langkah berikutnya + jenjang regulator |
| `kasus_dana_draf` | Draf surat penjelasan / komplain / regulator dari fakta tersimpan; TIDAK mengirim |

## Penyimpanan
`filesDir/dana_kasus.json`, terenkripsi Keystore (`writeChatText`/`readChatText`), atomik, hanya di perangkat. Bila berkas ada tetapi
tidak terbaca, alat menolak menulis (tidak menimpa) dan melaporkannya. Maks 50 kasus, 100 catatan per kasus.

## Pengaman (ditegakkan di kode, diuji)
- PIN, OTP, kode verifikasi, kata sandi, nomor kartu valid (Luhn), dan token/kunci disamarkan SEBELUM disimpan; pengguna diberi peringatan.
- Tanda penipuan ("biaya pencairan", jasa pencairan/recovery/hacker, diminta OTP, "CS" lewat jalur pribadi) memunculkan peringatan.
- Surat dibuat hanya dari fakta tersimpan; yang belum ada diberi penanda `[ISI: ...]`, tidak dikarang.
- Tidak ada nomor telepon, tautan, atau tenggat hukum yang ditanam (bisa basi): agen wajib memverifikasi lewat `cari_web` ke situs resmi.
- Tenggat tindak lanjut 7 hari adalah saran aplikasi, bukan ketentuan hukum.
- Mode hanya baca menolak `kasus_dana_buat` dan `kasus_dana_catat`.
- Pengiriman surat hanya lewat `email_kirim` (gerbang persetujuan yang sudah ada).

## Aturan agen (di prompt, sama untuk semua alat)
`DANA_RULE` tidak menyebut alat tertentu. Semua alat (termasuk `cari_web` dan `cari_leakosint`) tersedia setara untuk kasus ini. Satu prinsip berlaku
untuk semuanya: bertindak atas akun dan data milik pengguna sendiri, bukan mencari atau mengakses data pihak lain. Hasil alat apa pun yang
bukan dari platform resmi adalah petunjuk untuk menilai kasus lebih tepat (mencocokkan, menemukan data janggal), bukan bukti, dan tidak masuk ke surat
sebagai fakta sebelum diverifikasi lewat kanal resmi. Ini aturan perilaku model, bukan pembatas di kode.

## Pemetaan alat
`ToolMappingTest.kt` membaca sumber dan memastikan setiap alat yang dideklarasikan terdaftar di daftar alat prompt (`NAMES`, `SIGNATURES`, `REFERENCE`),
`CustomTools.BUILTIN`, pelaksana `ToolExecution`, status `AgentLoop`, dan jenis langkah `newStepCard`. Alat baru yang lupa didaftarkan gagal di tes itu.

## Belum ada
- Pengingat dorong (notifikasi) saat tenggat lewat: saat ini hanya ditandai ketika `kasus_dana_lihat` dipanggil.
- Lampiran berkas bukti: catatan bukti berupa teks; berkas tidak disalin ke kasus.
- Penghapusan kasus: kasus ditutup lewat status `tutup`/`selesai`, belum ada alat hapus.
