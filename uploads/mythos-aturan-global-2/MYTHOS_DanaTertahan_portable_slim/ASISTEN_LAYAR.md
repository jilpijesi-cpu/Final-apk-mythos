# Asisten layar (panel di atas aplikasi lain)

MYTHOS bisa dipilih sebagai **Aplikasi asisten digital** Android. Saat dipanggil (tahan tombol power / gestur asisten),
panel lembar-bawah muncul di atas aplikasi yang sedang dibuka, membaca isi layarnya, lalu menjawab lewat model
yang sudah Anda pilih di MYTHOS.

## Memakai
1. MYTHOS > Pengaturan > **Asisten layar** > Buka pengaturan > pilih MYTHOS sebagai Aplikasi asisten digital.
2. Aktifkan "Gunakan teks layar" (dan "Gunakan tangkapan layar" bila ada) di halaman yang sama.
3. Buka aplikasi apa pun, panggil asisten, lalu ketik atau ketuk saran.

## Berkas baru
- `AssistService.kt`  — VoiceInteractionService, SessionService, dan pengenal suara cadangan (wajib oleh Android).
- `AssistSession.kt`  — membaca AssistStructure (teks layar) + tangkapan layar, menampung panel.
- `AssistPanel.kt`    — seluruh tampilan panel (tema Palette MYTHOS, ikon GlyphView).
- `AssistLogic.kt`    — logika murni: prompt, pembersihan teks, markdown ringan, deteksi kolom sandi.
- `AssistConfig.kt`   — membaca penyedia/model/API key yang sama dengan chat utama.
- `AssistSetup.kt`    — dialog penjelasan + tombol ke pengaturan sistem.
- `res/xml/mythos_assist.xml`, `mythos_recognition.xml`, entri `<service>` di AndroidManifest.
- Tes: `AssistLogicTest.kt`.

## Perubahan pada berkas lama
- `Models.kt` / `MainActivity.kt`: daftar preset dipindah ke `BASE_PRESETS` (top-level) agar dipakai bersama. Perilaku sama.
- `SettingsDialog.kt`: satu baris "Asisten layar".

## Batasan
- Penyedia CLI (Codex, Gemini CLI, CLI Termux) belum didukung panel; pakai penyedia API.
- Tangkapan layar hanya dikirim bila model mendukung gambar (`supportsVision`); selain itu hanya teks layar.
- "Sisipkan" menyalin jawaban lalu menutup panel (Android tidak mengizinkan asisten menulis ke kolom aplikasi lain tanpa AccessibilityService).
- Layar yang ditandai aman oleh aplikasi (FLAG_SECURE) dan kolom sandi tidak dibaca.

## Jawaban hidup (streaming) — alur sama dengan chat utama
- Panel memanggil `LlmClient.streamText` (jalur native OpenAI-kompatibel / Anthropic / Gemini, tanpa alat). Teks lengkap sejauh ini
  mengalir ke thread UI; bila penyedia menolak streaming (400/404/422) sebelum ada teks, otomatis kembali ke `complete()`.
- Penghalus ketik memakai kelas yang sama dengan chat: `StreamSmoother.Pacer` + profil `StreamTempo.current` (dibaca dari
  Pengaturan > tempo tampilan): kecepatan, jeda napas di tanda baca, huruf memudar per huruf, `hideOpenMarkup` untuk tanda markdown
  yang belum ditutup, ketuk teks untuk melihat sisanya, tombol berhenti mempercepat bila jawaban sudah tiba.
- Baris status hidup memakai `ClaudeDotsView` + `ShimmerText` yang sama dengan `LiveStatusLine` ("Berpikir…" lalu "Menyusun balasan…").
- Setelah ketikan tuntas, teks diganti tampilan akhir (markdown penuh, sumber, suka/tidak suka, Salin, Sisipkan).
- Beda dengan chat: panel tidak menjalankan alat, jadi tidak ada narasi alat, kartu aktivitas, atau terminal; baris status tanpa panah buka-tutup.
- Kode: `AssistTypewriter.kt` (mesin ketik murni), `AssistPanel.kt` (StatusRow, startTyping, finalizeAnswer), `cc/Llm.kt` + `cc/NativeClients.kt` (`streamText`, `plainStream`).
