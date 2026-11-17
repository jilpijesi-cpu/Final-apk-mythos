# Dukungan AI open source otomatis

Cukup tempel satu API key (Groq `gsk_...` atau OpenRouter `sk-or-...`) di Pengaturan. Penyedia dikenali dari bentuk key,
lalu model open source terbaik diuji dan dipilih otomatis (`OpenSource.kt`, `ModelPicker.autoSetupModels`).

## Perbaikan hasil audit
| # | Masalah | Perbaikan | File |
|---|---|---|---|
| 1 | Error khas model (Groq `tool_use_failed`, OpenRouter 404 tanpa dukungan alat, 402 kredit habis) menghentikan agen | Diklasifikasikan `ModelRejectedException` / kuota; layak dialihkan ke model lain | `ApiErrors.kt`, `ModelCall.kt` |
| 2 | Gambar dikirim ke model teks (HTTP 400) | `modelSeesImages`: model teks hanya menerima teks + catatan | `OpenSource.kt`, `ProviderOpenAI.kt` |
| 3 | Uji model menghabiskan jatah harian model gratis OpenRouter | Maks 8 model diuji di OpenRouter (`OPENROUTER_PROBE_MAX`) | `OpenSource.kt` |
| 4 | Prompt sistem + alat (±21 rb karakter) terlalu besar untuk batas token kecil | Setelah anggaran konteks mengecil (< 40.000), pakai prompt ringkas dan deskripsi alat dipendekkan; uji model juga pakai alat ringkas | `MainActivity.compactSystem`, `PromptCompact.kt`, `ContextLimit.kt` |
| 5 | Tool-call yang ditulis sebagai teks hanya disembunyikan | `recoverTextToolCalls` memulihkan `<tool_call>`, `<function=...>`, dan JSON polos (hanya nama alat yang dikenal) | `TextToolCalls.kt` |
| 6 | Panggilan alat paralel menyatu bila penyedia tidak mengirim `index` | Nomor panggilan ditentukan dari `id` | `ProviderOpenAI.kt` |
| 7 | Batas harian OpenRouter tidak dikenali; `phi3`/`phi4` tidak dikenali; Ollama/Kustom kehilangan model non-open-source | Penanda `per-day`; keluarga model ditambah; Ollama/Kustom hanya diurutkan, tidak disaring | `ApiErrors.kt`, `OpenSource.kt` |

Tes baru: `OpenSourceTest`, `OpenSourceFixesTest`, `TextToolCallsTest`, `ApiErrorsModelTest`.
Catatan: perubahan ini ditulis tanpa bisa dikompilasi di lingkungan pembuatnya; jalankan build GitHub Actions untuk memastikan.

## Model lokal di HP (Ollama lewat Termux)
Pilih penyedia **Ollama lokal** di Pengaturan, lalu buka **Siapkan Termux**. Tiga langkah baru muncul (`OllamaSetup.kt`):
1. **Ollama terpasang**: `pkg install ollama`.
2. **Server Ollama berjalan**: tombol menjalankan server di latar belakang dengan `OLLAMA_CONTEXT_LENGTH=8192` dan `termux-wake-lock`.
3. **Model terunduh**: `qwen2.5:3b` (±1,9 GB) atau `qwen2.5:1.5b` (±1 GB, lebih ringan).

Penyesuaian agar cocok dengan perilaku Ollama:
- Ollama tidak menolak permintaan yang terlalu besar (tanpa HTTP 413), ia memotong diam-diam. Maka anggaran riwayat model lokal ditetapkan dari awal (`LOCAL_CTX_BUDGET` = 12.000 karakter), yang otomatis mengaktifkan prompt ringkas.
- Model lokal tidak diuji paralel saat penyiapan (menghemat RAM); semua yang terpasang dianggap bisa dipakai.
- Batas tunggu token pertama dinaikkan ke 15 menit untuk alamat lokal, dan pesan galat koneksi menuntun ke Siapkan Termux.
- Bila server belum jalan atau belum ada model, layar Siapkan Termux terbuka otomatis setelah menyimpan Pengaturan.

Tes baru: `OllamaSetupTest`.
