# patch8: Codex tidak lagi gagal total saat mencoba tool bawaan
- GEJALA: obrolan menampilkan "Codex mencoba memakai tool bawaan (mcpToolCall); Mythos membatalkannya. Coba kirim ulang.", lalu agen hanya berjanji ("Saya akan memeriksa lampiran...") tanpa pernah membaca file, atau mengaku alat tidak tersedia.
- PENYEBAB: `codex_app_server.js` memutus SELURUH giliran begitu Codex memulai item bawaan (`mcpToolCall`, dst.). Model Codex cenderung memanggil alat lewat mekanisme bawaannya, jadi kirim ulang menghasilkan galat yang sama.
- PERBAIKAN (tripwire keamanan tetap aktif):
  1. Giliran Codex dihentikan seketika lewat `turn/interrupt` dan thread lamanya diabaikan (`deadThreads`).
  2. Bila belum ada teks jawaban, giliran DIULANG SEKALI di thread baru dengan pengingat keras (`NATIVE_REMINDER`): tidak ada tool bawaan, tulis `<tool_use>` sebagai teks biasa, mulai langsung dengan pemanggilan alat.
  3. Bila teks yang sudah keluar memuat `</tool_use>` lengkap, teks itu dipakai alih-alih dibuang.
  4. Bila percobaan kedua tetap memakai tool bawaan, baru galat (kini menyebut "dua kali" dan menyarankan ganti model).
- Tes: CodexHardeningTest memeriksa keberadaan retry + interrupt.

## Perbaikan build/tes (digabung dari hasil build Manus)
- `EmailDraftCard.kt`: hapus `prettyFileName` ganda (dipakai versi `FileCard.kt`).
- `Settings.kt`: izin Google (`gmailGranted`/`calendarGranted`/`driveGranted`) dipertahankan saat login ulang akun yang sama.
- `Agent.kt`: pesan persetujuan menyebut konteks eksternal bila konten eksternal sudah masuk.
- `GoogleWorkspace.kt`: cek identitas akun Calendar-only mendukung base URL maupun endpoint lengkap.

## patch8: Browser Agent langsung bekerja + pemantau live
- **Tombol kirim cerdas** (`BrowserAutopilot.route`): perintah tunggal yang jelas ("isi kolom Nama dengan Budi", "klik Lanjut") jalan langsung; kalimat bebas, catatan kasus, atau tujuan majemuk ("... lalu klik ...") langsung diserahkan ke agen otonom. Sebelumnya kalimat bebas hanya dijawab "Instruksi belum dikenali" dan pengguna harus tahu menekan chip "Otonom". Label yang tampak seperti kalimat ("isikan formulir ini dengan ...") tidak lagi disalahartikan sebagai satu isian.
- **Pemantau live** (`LiveMonitor`): langkah n/25 + bilah kemajuan, fase kerja (membaca halaman / model memilih / mengisi / menunggu konfirmasi Anda / menunggu halaman), aksi yang sedang dikerjakan, tujuan, dan riwayat bergulir (✓ berhasil, ✗ gagal), plus tombol Hentikan. Muncul otomatis selama agen bekerja atau dijeda.
- **Sorotan di halaman** (`mythos_form.js` `mark`): elemen yang sedang diisi/diklik digulirkan ke tengah dan diberi garis oranye ±1,6 dtk.
- **Agen melihat apa yang sudah terisi**: `inspect` kini memuat `value`, `checked`, `selected`, `disabled`, dan teks tombol asli. Kolom sensitif (sandi, PIN, OTP, CVV, token, kartu, rekening, NIK, KTP, NPWP) hanya ditandai `filled`, nilainya tidak dikirim ke model. Ini mengurangi pengisian ulang yang berputar.
- **Menunggu halaman selesai dimuat** setelah klik/isian (bukan jeda tetap 0,9 dtk), maksimal 9 dtk, sehingga agen tidak membaca halaman lama/kosong.
- **Jeda yang bisa dilanjutkan**: Batal pada dialog konfirmasi tidak lagi mematikan agen; agen dijeda, penolakan dicatat di riwayat (model tidak mengusulkan aksi yang sama), lalu Lanjutkan/Mulai ulang. Tujuan asli disimpan (`autoGoal`); teks yang diketik saat dijeda digabung sebagai jawaban (`mergeGoal`). "Mulai ulang" kini tersedia setelah agen berhenti.
- **Layar tetap menyala** selama agen bekerja.
- **Halaman kosong tidak lagi diam**: galat muat, HTTP ≥ 400, dan galat sertifikat SSL kini tampil di baris status (sebelumnya WebView hanya putih tanpa penjelasan).
- Keamanan tidak dilonggarkan: klik/isian final tetap minta konfirmasi, kredensial tidak pernah diisi, kebijakan domain tetap berlaku.
- Tes: `BrowserAutopilotTest` (route, mergeGoal, formatLogLine), `jstest/form.test.js` (inspect nilai/masking, sorotan; 19/19 lulus di Chromium).

## patch8: tanya-jawab ala chat di Browser Agent
- Kolom perintah kini juga menerima pertanyaan biasa ("bagaimana cara isi kolom ini?", "apa maksud Usulan Penyelesaian?", "kolom ini wajib diisi?"). `BrowserAutopilot.isQuestion` mengenalinya, `TaskRoute.QUESTION` menjawabnya dari isi halaman + peta kolom di kartu "Jawaban Mythos", TANPA mengubah formulir. Kalimat yang meminta aksi ("isikan form ini...", "bisa kamu isi ...?") tetap menjalankan agen.
- Pertanyaan tetap bisa diajukan saat agen dijeda (mis. untuk memahami pertanyaan agen); saat agen bekerja, tombol kirim menolak dengan pesan Hentikan dulu.
- Tes: `BrowserAutopilotTest.questionsAreAnsweredNotExecuted`, `askPromptsCarryQuestionAndTreatPageAsData`.
