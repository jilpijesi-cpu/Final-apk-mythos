# Perbaikan: agen mengulang terus (error 413 / 429)

Penyebab: anggaran konteks tetap 100.000 karakter untuk semua penyedia. Groq (gpt-oss, tier on_demand) membatasi 8.000 token
per menit, jadi permintaan ±23.000 token ditolak HTTP 413. Error 413 dianggap fatal, agen berhenti, dan "Lanjutkan" mengirim
riwayat yang sama sehingga gagal lagi. Putaran 429 menunggu sampai 5 x 45 detik sehingga tampak macet.

Perubahan:
- ContextLimit.kt (baru): deteksi 413 / "request too large" / "context length", baca `Limit` dan `Requested` dari pesan, hitung anggaran baru.
- MainActivity.kt: `ctxBudget` kini per "penyedia/model" (`ctxCaps`), default 100.000, dipelajari dari error 413 (disimpan di memori sampai aplikasi ditutup).
- ModelCall.kt: 413 memangkas konteks lalu mengulang model yang sama otomatis (maks 4 kali). Bila tetap gagal, dialihkan ke model/penyedia lain.
  Percobaan ulang koneksi 5 -> 3, tunggu maks 20 dtk; bila server minta tunggu lebih lama langsung beralih ke AI lain.
- ApiErrors.kt: ContextTooLargeException ikut `isFallbackWorthy`; error dalam stream juga dikenali.
- AgentLoop.kt: "Lanjutkan" mempertahankan id tugas dan nomor putaran dari checkpoint (tidak reset ke 0).
- Tes baru: ContextLimitTest.kt.

## Pengaturan GitHub disederhanakan: cukup link repository + token
- SettingsDialog.kt: kolom Pemilik, Nama repository, dan sakelar private dihapus; diganti satu kolom "Link repository GitHub" (disimpan sebagai `github_repo_url`). Link yang salah ditolak dengan pesan, bukan disimpan. Pengaturan lama otomatis tampil sebagai link.
- GithubLink.kt (baru): `parseRepoLink` membaca https://github.com/o/r, .git, git@github.com:o/r, "o/r".
- ToolExecution.kt: owner dan nama repo diambil dari link; pengaturan lama masih terbaca sebagai cadangan.
- tools/build-github.sh + BuildApk.kt (identik): bila proyek belum punya `origin`, dan repo di link sudah ada, dipasang sebagai origin lalu di-push; bila belum ada, dibuat private. `gh auth setup-git` dijalankan agar push memakai token.
- Batas: satu repo untuk satu proyek. Dua proyek ke link yang sama akan ditolak push-nya (riwayat berbeda).
- Tes baru: GithubLinkTest.kt.

## Agen boleh membuat alat sendiri
- CustomTools.kt (baru): `create_tool` dan `delete_tool`, penyimpanan di pengaturan aplikasi, dan eksekusi lewat `run_command` (semua pengaman jalur itu tetap berlaku).
- MainActivity.kt: `tools` kini bawaan + alat buatan, dibaca ulang tiap permintaan. ToolGuidance.kt: daftar alat dan aturan di prompt.
- Tes baru: CustomToolsTest.kt. Lihat PANDUAN_ALAT.md.
