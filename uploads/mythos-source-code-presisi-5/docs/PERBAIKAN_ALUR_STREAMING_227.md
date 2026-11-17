# 2.38 — Langkah agen API terasa (tidak lagi lewat sekejap)

## Mengapa terasa terlalu cepat (dari kode, bukan karena agen "terlalu pintar")
- Alat seperti baca file / ls selesai dalam puluhan-ratusan milidetik; model gratis juga membalas cepat.
- Kalimat langkah yang spesifik baru dipasang setelah 1,4 dtk dan dibatalkan bila alat sudah selesai
  (`curTitle == RUNNING_TEXT`), sehingga yang tampak hanya teks umum "Proses berpikir"/"Sedang menjalankan perintah".
- `TextGate` hanya menyimpan status TERAKHIR yang menunggu; status di antaranya dibuang.
- Detail langkah masuk ke kartu aktivitas yang tidak memakan layar (`quiet = true`).

## Perubahan (`AgentLoop.kt`)
- Tiap panggilan alat dikirim sebagai event `item_started`/`item_completed` ke `CodexActivityUi` (pemetaan `agentToolItem`):
  run_command/build_apk -> commandExecution, write_file -> fileChange, cari_web/baca_web -> webSearch, lainnya -> dynamicToolCall.
- Antrean berurutan + jeda + narasi berjalan sama seperti Codex: tiap langkah ditampilkan berurutan, tidak dilewati,
  tanpa memperlambat agen (antrean tampilan terpisah dari eksekusi alat).
- Ringkasan alat di chat (ikon jam, "Membaca sebuah file, menjalankan 2 perintah", panah) kini juga muncul di jalur API.
- `codexActivityUi.reset()` di awal run; `turn_completed` + `flushAll()` di akhir run.

## Belum dilakukan / batas
- Belum dikompilasi/dites. Uji: tugas dengan beberapa alat cepat (mis. baca 3 file) dengan model OpenRouter.
- Bila ingin lebih lambat/terasa, atur `SETTLE_MS` di `CodexActivityUi.kt` dan tahan narasi di `LiveStatusLine.kt`.
- Kartu aktivitas lama dan persetujuan alat tidak diubah.

## 2.39 — Kalimat pembuka sebelum alat (dari model)
- Rekaman acuan adalah percakapan Claude itu sendiri: kalimat pembuka sebelum tiap rangkaian alat DITULIS MODEL, bukan
  dibuat oleh tampilan. Prompt sistem jalur API belum menyuruh itu (hanya wrapper CLI yang punya `CLI_NARRATION_GUIDE`),
  sehingga model sering langsung memanggil alat tanpa teks.
- Ditambah `API_NARRATION_GUIDE` (`CliGuide.kt`) ke `sysText()` untuk penyedia non-CLI, bukan obrolan ringan, bukan model lokal.
- Batas: model kecil/gratis bisa mengabaikan instruksi; hasilnya tidak dijamin seperti model besar.
