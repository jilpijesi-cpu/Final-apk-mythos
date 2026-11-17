# Agen ala Claude Code (mesin dari Mythos-v31)

Mesin agen v31 ada di paket `com.build.mythos.cc`; penghubung ke aplikasi ada di `CcBridge.kt` dan satu baris di `AgentLoop.kt`.

## Alur
1. Pengguna kirim pesan -> `agentLoop` -> `ccAgentLoop` (bila penyedia bukan CLI dan bukan obrolan ringan).
2. Agen bekerja di folder proyek penyimpanan aplikasi: `filesDir/cc_projects/<id-chat>` (chat samaran: `cacheDir/cc_incognito`).
3. Alat: `Read`, `Write`, `Edit`, `Glob`, `Grep`, `LS`, `Delete`, `TodoWrite`, `AskUserQuestion`, `GithubBuild`.
4. `GithubBuild`: push proyek ke repo GitHub (token + URL repo dari Pengaturan), tunggu GitHub Actions, kembalikan hasil/log gagal.
5. Bila semua run sukses: APK diunduh dari artifact ke `cacheDir/apk/app.apk`, diperiksa `verifyApkFile`, lalu muncul tombol **Pasang APK**.

## Aturan izin
Write/Edit otomatis; Delete dan workflow GitHub yang baru/berubah selalu bertanya (dialog persetujuan yang sama).

## Kontinuitas chat
Tiap pesan memulai riwayat model baru; 8 pasangan (pesan, jawaban akhir) terakhir dari riwayat chat disisipkan ke tugas, seperti v31.

## Dua dunia kerja (terpisah)
- **Agen API** (Gemini, OpenAI-compatible, Anthropic) memakai mesin ini dan bekerja PENUH di dalam aplikasi: folder `filesDir/cc_projects/<id-chat>`. Tidak ada alat shell dan tidak ada akses ke Termux. Build lewat GitHub Actions.
- **Agen CLI** (Codex, Gemini CLI, Claude Code) bekerja di Termux, folder `~/MYTHOS/chats/<id-chat>`.
- Keduanya tidak berbagi folder maupun alat. Penyedia API tidak pernah dialihkan ke loop berbasis Termux; bila pengaturan penyedia belum lengkap (alamat dasar atau model kosong), agen berhenti dengan petunjuk.
- Saklar `cc_agent` sudah dihapus. Loop lama (`agentLoop`) kini hanya melayani obrolan ringan tanpa alat.

## Lampiran
- Agen API: file disalin ke `cc_projects/<id-chat>/uploads/` (tanpa Termux dan tanpa izin penyimpanan). Nama `uploads` sengaja, karena prompt agen menyebutnya dan `GithubBuild` TIDAK mengirim file yang langsung berada di `uploads/` ke GitHub (privasi lampiran); folder hasil ekstrak zip tetap ikut. Pesan menyebut path relatif (`uploads/nama.txt`) yang bisa dibaca alat `Read`. Teks dan docx dibaca langsung dan ikut di pesan; zip dipindai lalu diekstrak aman (anti zip-slip, batas jumlah dan ukuran) ke `uploads/<nama-zip>/`; PDF belum bisa diekstrak teksnya tanpa Termux; gambar dikirim ke model sebagai gambar.
- Agen CLI: file disalin ke `~/MYTHOS/chats/<id-chat>/attachments/` di Termux seperti sebelumnya.
- Menghapus chat menghapus kedua folder kerjanya. Saat aplikasi dibuka, folder agen API yang chat-nya sudah tidak ada juga disapu (hanya penyimpanan aplikasi).
- Pemindaian proyek via Termux sebelum tugas hanya berjalan untuk agen CLI.

## Catatan
- Workflow proyek harus ada di `.github/workflows/*.yml` dan memakai `actions/upload-artifact@v4` agar APK bisa diunduh.
- Kunci API TIDAK pernah tertanam di kode (`EmbeddedKeys` sengaja kosong); CI menolak bundel yang membawa pola kunci.
- Belum dikompilasi saat dibuat: build lalu laporkan error bila ada.

## Paket alat agen API (dalam-aplikasi)
Selain `TodoWrite`, `LS`, `Glob`, `Grep`, `Read`, `Edit`, `Write`, `Delete`, `AskUserQuestion`, `ExitPlanMode`, `GithubBuild`:

| Alat | Fungsi | Risiko |
|---|---|---|
| `Mkdir` | buat folder | tulis |
| `Move` | pindah/ganti nama file atau folder | tulis |
| `Copy` | salin file atau folder | tulis |
| `Zip` / `Unzip` | kemas / ekstrak zip (anti zip-slip, batas 2000 file dan 100 MB) | tulis |
| `Time` | tanggal dan waktu ponsel | aman |
| `Calc` | aritmetika presisi (tanpa eval) | aman |
| `WebSearch` / `WebFetch` | cari dan baca web (aturan keamanan sama dengan `cari_web`/`baca_web`) | aman |
| `Export` | salin SATU file ke `Download/MYTHOS/export` agar pengguna bisa membukanya | selalu minta persetujuan |

- Semua path lewat `ProjectSandbox`. Setiap file yang diubah, dipindah, atau dibuat dicatat di `TaskJournal` per file (jangan merekam folder: jurnal menganggap folder "tidak ada").
- Alat yang butuh jaringan atau Android (`WebSearch`, `WebFetch`, `Export`) disuntikkan dari `CcBridge` lewat `ToolRegistry.default(extra = ...)`; sisanya murni JVM di `cc/KitTools.kt`.
- Yang tidak ada, sengaja: shell dan eksekusi program (tidak ada Termux), pembacaan teks PDF, permintaan HTTP bebas (POST ke API sembarang).
