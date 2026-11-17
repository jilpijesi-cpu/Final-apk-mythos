# Audit kedua logika agen (v2.4-perbaikan-audit-2)

Metode: baca kode statis. **Belum dikompilasi dan tes belum dijalankan** (tidak ada kotlinc di lingkungan audit).
Jalankan: `./gradlew testDebugUnitTest assembleDebug`. Kurung kurawal/kurung tiap file yang diubah sudah dicek seimbang secara mekanis.

## Diperbaiki

| # | Masalah | Perbaikan | File |
|---|---------|-----------|------|
| 1 | Tugas dengan satu pesan pengguna tidak pernah bisa dipangkas/diringkas; argumen `write_file` tak pernah dipangkas; pemotongan berhenti di nudge `[SISTEM]` sehingga permintaan asli bisa hilang | `shrinkCalls` memangkas argumen panjang di giliran lama (termasuk `raw` Gemini); `trimContext` memotong hanya di pesan pengguna sungguhan, lalu membuang pasangan model+hasil tertua sambil menjaga pesan pertama dan selang-seling | `ContextTrim.kt`, `ContextSummary.kt` |
| 2 | `exit=1` tanpa keluaran (grep tanpa hasil, diff, test -f) semuanya bertanda tangan `exit=#`, dihitung kumulatif, dan menghentikan agen di kemunculan ke-4 | Tidak dihitung sebagai error; juga tidak menambah `failStreak` | `AgentGuard.kt`, `AgentLoop.kt` |
| 3 | Konfirmasi minimal menjalankan semua WRITE tanpa dialog, termasuk `curl \| sh`, `python -c`, `git reset --hard`, `git clean`, `git push`, `chmod -R`, redirect `>` ke file kode | `needsExtraCare()` menjadi peringatan sehingga dialog tetap muncul dan "Ya, semua" tidak melewatinya | `CommandPolicy.kt`, `ToolExecution.kt` |
| 4a | `write_file` dengan `..` dianggap di dalam folder kerja (juga melewati peringatan folder sistem Termux) | `absPath` memakai `collapsePath` | `TermuxFiles.kt` |
| 4b | Cadangan yang gagal dibuat diabaikan diam-diam padahal file lama ada | Penulisan dibatalkan dengan pesan jelas; file tidak diubah | `ToolExecution.kt` |
| 4c | Hanya 50 cadangan terakhir disimpan | 200 | `TermuxFiles.kt` |

Tes baru: `AgentFixesTest.kt`. Ditambah `testImplementation("org.json:json:20231013")` karena stub android.jar
melempar "not mocked" pada `JSONObject` (tes lama tidak memakainya).

## Batas yang jujur (belum tuntas)

- **Temuan 3 adalah heuristik teks.** Skrip yang ditulis lewat `write_file` lalu dijalankan (`bash skrip.sh`) tetap tidak diperiksa isinya.
  Penjaga sebenarnya hanyalah mode sandbox dan persetujuan pengguna. Pola yang belum ditandai: `> file` ke ekstensi di luar daftar.
- **Temuan 1:** memangkas argumen pada `raw` Gemini belum diuji terhadap API (tanda tangan pikiran/`thoughtSignature` dipertahankan,
  tetapi validasi sisi server tidak saya ketahui). Bila Gemini menolak dengan 400 di tugas panjang, nonaktifkan cabang `raw` di `shrinkCalls`.
- **Backup (4b):** `readOld` yang gagal sementara membuat file BARU dianggap "ada"; penulisan dibatalkan dan model bisa mencoba lagi.
- Pemulihan otomatis saat API menolak karena konteks terlalu besar belum ada; pemangkasan hanya mencegahnya.

## Diperbaiki pada putaran kedua (temuan 5 sampai 9)

| # | Perbaikan | File |
|---|-----------|------|
| 5 | Keluaran panjang dipotong di TENGAH (seperempat awal + sisanya akhir), bukan hanya awal yang dibuang; awalan `…(dipotong` tetap sama untuk `readOld` | `TermuxShell.kt` |
| 6 | Pesan lanjutan memuat perintah yang tercatat berjalan 30 menit terakhir (CommandLog), supaya aksi tidak idempoten tidak diulang | `AgentLoop.kt` |
| 7 | Stream Anthropic tanpa `message_stop` dianggap putus dan diulang. **Risiko:** proxy yang tidak mengirim `message_stop` akan memicu ulang terus; hapus pengecekan itu bila terjadi | `ProviderAnthropic.kt` |
| 8 | `deskripsi` tidak ikut tanda tangan pengulangan | `AgentLoop.kt` |
| 9 | Dialog `ask_user` ditutup saat 10 menit habis | `AskUser.kt` |

## Belum dikerjakan

- 10 heredoc dianalisis sebagai perintah di sandbox (butuh parser heredoc; sandbox default mati).
- Batas biaya/token per tugas (selain 300 putaran) dan pemulihan otomatis saat API menolak karena konteks terlalu besar.
- `thoughtSignature` saat berpindah provider dari non-Gemini ke Gemini (perlu diuji dengan API).
