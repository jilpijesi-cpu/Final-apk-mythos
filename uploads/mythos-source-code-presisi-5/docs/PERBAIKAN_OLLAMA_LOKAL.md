# Perbaikan Ollama lokal: lama tersambung

STATUS: BELUM DIKOMPILASI dan tes belum dijalankan (dibuat tanpa Gradle/Kotlin di lingkungan penyusun).
Jalankan `./gradlew testDebugUnitTest assembleDebug` (atau workflow GitHub) sebelum dipakai. versionCode dinaikkan 22 ke 24 (23 sudah dipasang dari ZIP sebelumnya).

## Penyebab yang ditemukan (dari membaca kode, belum diukur di HP)
1. Server mati: koneksi ditolak diulang 3 kali dengan jeda 2+4+8 dtk (±14 dtk) sebelum galat muncul.
2. Server tidak pernah dinyalakan otomatis; Android sering mematikan Termux, jadi masalah 1 berulang.
3. Tombol "Jalankan server" selalu `pkill` lalu hanya menunggu 4 dtk. Server HP sering butuh lebih lama, jadi langkahnya merah,
   dan menekan tombol lagi mematikan server yang masih bangun.
4. Tiap giliran mengirim prompt panjang (BUILD_PLAYBOOK ±2,8 rb + SELF_UPGRADE_PLAYBOOK ±1,2 rb karakter, selain ringkasan dan alat).
   CPU HP harus membaca semuanya sebelum token pertama (perkiraan: menit untuk model 3B), plus muat model saat pertama kali.
5. Pemeriksaan di Siapkan Termux memakai batas waktu bawaan 600 dtk untuk cek alat.

## Perubahan
- `OllamaSetup.kt`: `START` hanya menyalakan bila `/api/version` belum menjawab, tanpa `pkill`, dan menunggu sampai siap (maks 30 dtk).
  Tombol baru "Mulai ulang server" (`RESTART`, memakai `pkill -x`). Fungsi baru `ensureOllamaUp`, `isPortOpen`, `isOllamaUrl`,
  `shouldFailFast`, dan `LocalServerDownException`.
- `ModelCall.kt` (`openStream`): untuk alamat lokal, server dicek lewat soket (cepat) dan dinyalakan otomatis bila mati.
  Koneksi ditolak langsung gagal dengan pesan jelas (tanpa 3x ulang). Selama menunggu, baris status menampilkan
  "Model lokal membaca prompt". Galat ini tidak dialihkan ke AI lain.
- `ContextSummary.kt` (`sysText`): model lokal memakai `BUILD_PLAYBOOK_LITE` (±490 karakter) dan tanpa `SELF_UPGRADE_PLAYBOOK`.
  Penyedia lain tidak berubah. Definisi alat tidak dikurangi (hematnya kecil, risiko salah panggil alat lebih besar).
- `TermuxSetup.kt`: cek alat memakai batas 30 dtk.
- Tes: `OllamaSetupTest` diperbarui (START tidak lagi `pkill`) dan ditambah tes untuk perilaku baru.

## Tambahan (v2.20): sapaan di model lokal
Temuan dari tangkapan layar "Halo" yang berhenti di "Model lokal membaca prompt": jalur sapaan (`AgentLoop.kt`) memakai `baseSystem()` penuh
(±8 rb karakter) dan tetap mengirim semua definisi alat. Sekarang: model lokal memakai `compactSystem()` untuk sapaan, dan panggilan
terisolasi (sapaan, tahap memahami, penalaran mendalam) di model lokal dikirim tanpa definisi alat (`skipToolsForLocal` di `ProviderOpenAI.kt`).
Giliran agen biasa tetap membawa alat.

## Yang masih bisa lambat (bukan bug)
Model 3B di CPU HP memang lambat membaca prompt dan memuat model pertama kali. Bila masih terasa lama, pakai `qwen2.5:1.5b`.
