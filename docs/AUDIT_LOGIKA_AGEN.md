# Audit logika agen (v2.3-audit-logika-agen)

Cakupan: `AgentLoop`, `ToolExecution`, `AskUser`, `AgentGuard`, `AgentRuntime`, `ContextSummary` (prompt sistem),
`MainActivity` (prompt dasar + deklarasi tool), `TaskBrief`, `ActivityModel` (laporan akhir), dan dokumentasi.
Metode: pembacaan kode statis dan pelacakan alur. **Belum dikompilasi dan tes belum dijalankan** (tidak ada
Kotlin/Android SDK di lingkungan audit). Jalankan `./gradlew testDebugUnitTest assembleDebug`.

## A. Kontradiksi dan logika keliru yang diperbaiki

| # | Masalah | Dampak | Perbaikan |
|---|---------|--------|-----------|
| 1 | Hasil `ask_user` yang sukses dan hasil penolakan ("Ditolak...", "Diblokir...") tidak berawalan `exit=`, jadi `parseToolResult` menganggapnya gagal biasa | Model menerima `[Kategori error: COMMAND_FAILED. Jangan mengulang...]` setelah pengguna menjawab pertanyaan; `ErrorKind.USER_DENIED` ada tetapi tidak pernah dihasilkan | `ToolResult.denied` + `USER_DENIED`; `ask_user` dianggap sukses; petunjuk khusus untuk penolakan |
| 2 | Deteksi "panggilan sama berulang" membandingkan hasil dua putaran SEBELUM putaran ini selesai, padahal konstanta `MAX_SAME_CALLS = 3` | Baru berhenti di panggilan ke-5 | `RepeatDetector` dipanggil sesudah hasil ada; berhenti di kemunculan ke-3 (panggilan sama + hasil sama) |
| 3 | Penolakan eksplisit, dialog ditutup, layar diputar, dan 10 menit tanpa jawaban semuanya dilaporkan "Ditolak pengguna. Tanyakan alasannya lewat ask_user" | Model mengira pengguna menolak padahal tidak ada jawaban; di mode otonom ask_user tidak menunggu sehingga sarannya tidak berguna | `lastDenyUnanswered` + `deniedResult()` membedakan keduanya; saran ask_user hanya bila tidak otonom |
| 4 | "Ya, semua" melewati peringatan area sensitif ("hanya diberi peringatan merah") | Setelah satu klik, perintah di area sensitif jalan tanpa dialog sama sekali | `allowAll` tidak berlaku bila ada `warning`; hanya mode otonom (opt-in) yang melewatinya |
| 5 | Tombol Berhenti tidak menghapus `pendingInterrupt` | Menekan Berhenti sesudah mengantre pesan malah menjalankan agen lagi dengan pesan itu | `stopAgent(keepPending)`; Berhenti membatalkan pesan yang menunggu, `send()` tetap melanjutkan |
| 6 | "Lanjutkan" memakai `addUser = false` sementara riwayat biasanya berakhir di giliran model (catatan berhenti / dihentikan) | Permintaan berakhir di giliran model: berisiko ditolak API | Pesan sistem lanjutan ditambahkan (tidak tampil sebagai gelembung) |
| 7 | Checkpoint tidak dihapus saat chat dihapus | "lanjutkan" menghidupkan tugas lama tanpa riwayat | `newChat()` menghapus checkpoint dan pesan tertunda |
| 8 | Tanda tangan error mengambil baris pertama yang mengandung `fail`/`error`, dan galat Kotlin seperti `e: ... Type mismatch` tidak memuat kata itu | Semua kegagalan Gradle bisa bertanda tangan sama; agen yang memperbaiki error BERBEDA dihentikan pada kegagalan ke-4 | Baris generik Gradle dilewati; pola error ditambah `^e:`, `mismatch`, `ambiguity`, `unexpected`, `missing` |
| 9 | Penulisan file diakhiri `rm -f`, jadi exit-nya selalu 0 walau `mv`/`base64` gagal | Kartu menulis "gagal", tetapi model membaca `exit=0` | Tanpa "OK" hasilnya diubah menjadi `exit=1 Gagal menulis file...` |
| 10 | "Build" didefinisikan dua kali: `buildRe` (hanya `assemble`) untuk batas waktu, `activityKindOf` (termasuk test/check/lint/make) untuk verifikasi | `./gradlew build` atau `testDebugUnitTest` dihitung build untuk verifikasi tetapi hanya diberi 600 dtk | Batas waktu, batas keluaran, dan polling memakai `activityKindOf` (APK tetap dicari lewat `buildRe`) |
| 11 | `unverifiedChange` menghitung SEMUA `write_file`, sedangkan `verificationNudge` hanya file kode | `README.md` saja membuat laporan "Belum terverifikasi" padahal tidak ada yang bisa di-build | Predikat `isCodeFile` yang sama dipakai keduanya |
| 12 | Pengingat verifikasi menuntut build walau mode sandbox tidak punya alat build | Agen diminta hal yang mustahil | Pesan pengingat memberi jalan keluar: katakan jujur "belum terverifikasi" |
| 13 | `taskBrief` tidak direset saat tugas baru (terutama bila `understand` dimatikan lagi) | Asumsi tugas lama terbawa ke prompt tugas baru | Direset pada tiap pesan pengguna baru |
| 14 | Prompt: "Patuhi ini" untuk catatan yang bisa berisi "Hal yang belum jelas" | Memerintah patuh pada hal yang justru belum jelas | Dirumuskan sebagai asumsi; pesan pengguna tetap yang utama |
| 15 | Prompt MODE OTONOM: "jangan meminta izin" + "akhiri hanya setelah tujuan terbukti" + "pengguna tidak perlu tahu detail teknis" | Bertabrakan dengan: prompt dasar (sapaan dijawab langsung, laporan jujur), mode hanya baca, dan persetujuan perintah berjaringan di sandbox yang tetap muncul | Dirumuskan ulang: batas yang tetap berlaku disebut, sapaan dikecualikan, laporan "ringkas tetapi jujur" |
| 16 | Deskripsi tool statis: `ask_user` "untuk izin", `write_file` tanpa catatan mode, `run_command` hanya "di Termux" | Model meminta izin lewat ask_user DAN lalu muncul dialog persetujuan (izin ganda); mencoba write_file di sandbox/hanya baca | Deskripsi dan prompt dasar diperjelas |
| 17 | `stateOfResult` memberi status gagal pada hasil `ask_user` dan pada "Diblokir..." saat feed dipulihkan | Kartu pemulihan salah status | `Jawaban pengguna` = OK, `Diblokir` = DENIED |

## B. Dokumentasi yang bertentangan dengan kode (dikoreksi)

- `PERBAIKAN_LOGIKA_AGEN.md` menyebut mode otonom "default aktif" dan `understand` seolah default aktif. Kode: keduanya default MATI, otonom wajib opt-in (`autonomous_ack`). Temuan H-01 di `AUDIT_KODE_DAN_UNIT_TEST.md` sudah teratasi di kode, tetapi laporannya belum diperbarui.
- Bagian "Tugas berhenti terlalu cepat" menjelaskan kesempatan tambahan yang tidak ada lagi di `AgentLoop`. Diberi catatan bahwa itu riwayat.
- Versi: dokumen menyebut 1.1 (versionCode 2); kode 2.2 (13). Sekarang 2.3-audit-logika-agen (14).

## C. Sengaja TIDAK diubah (butuh keputusan Anda)

1. **Aturan "langkah terakhir gagal = laporan Gagal"** (`buildReport`). `grep` tanpa hasil (exit=1) sebagai langkah terakhir membuat laporan "Gagal" walau jawaban akhir benar. Mengubahnya melonggarkan kejujuran laporan; pilih sendiri.
2. **`ErrorKind.NETWORK_*` untuk exit 401/403/429/5xx** di `ToolResult`: exit code shell maksimal 255, jadi cabang ini tidak pernah aktif untuk perintah. Tidak merusak apa pun, hanya kode mati yang menyesatkan.
3. **Ringkasan riwayat dan tahap pemahaman** memanggil model dengan `tools` tetap dikirim sementara prompt berkata "jangan memakai tool". Aman (teks kosong diabaikan), tetapi menghapus `tools` akan membuat Anthropic menolak riwayat yang memuat blok tool_use.
4. **Folder lampiran `Download/ClaudeMythos`** masih bernama merek lama. Konsisten antara kode dan prompt, jadi bukan kontradiksi; mengganti nama perlu migrasi file yang sudah ada.
5. **Hasil `ERR: ...` dari RUN_COMMAND** (baris pertama bukan `exit=`) membuat `parseToolResult` gagal membaca exit code. Jarang terjadi; belum disentuh.
6. **Pola "polling"**: agen yang menunggu proses lama dengan perintah sama dan keluaran sama 3 kali kini dihentikan (dulu 5). Jika itu pola yang sah bagi Anda, naikkan `MAX_SAME_CALLS`.

## D. Tes baru

`AgentAuditTest.kt`: RepeatDetector, USER_DENIED, tanda tangan error Gradle, predikat verifikasi seragam, status feed.
Tes lama tidak diubah. Satu hal yang saya periksa manual: `ApprovalPolicyTest` dan `CommandPolicyTest` tetap valid karena
`bypassesApproval` tidak berubah (perbedaan "Ya, semua" ada di pemanggilnya, `confirm()`).
