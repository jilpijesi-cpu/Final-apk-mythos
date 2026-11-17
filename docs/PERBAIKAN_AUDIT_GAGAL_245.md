# Perbaikan: audit gagal karena folder chat kosong (2.45)

Gejala: "Audit aplikasi ini" di chat baru -> agen menemukan `mythos-audit` dan `mythos-2.44`, tetapi `find *.kt` dan `ls mythos-2.44` gagal,
`ls null` muncul di baris status, lalu agen menjawab "belum memiliki proyek Android".

Penyebab: satu chat = satu folder `~/MYTHOS/chats/<id>` (kosong di chat baru), chat lain tidak boleh disentuh, source ada di `MYTHOS/reference`
(boleh dibaca, tidak boleh diubah), dan model lemah mengirim path null/relatif yang salah.

Perbaikan: lihat CHANGELOG.md bagian 2.45. File: `WorkContext.kt`, `ToolGuidance.kt`, `ToolExecution.kt`, `SelfUpgradeIntegration.kt`, tes `EmptyWorkspaceAndNullArgTest.kt`.

Catatan jujur: tidak dikompilasi dan tes belum dijalankan di sini (tidak ada Android SDK/Kotlin). Logika regex `nullArgProblem` diperiksa manual
terhadap contoh di tes. Model `openrouter/free` yang lemah tetap bisa salah menafsirkan; perbaikan ini mempersempit celahnya, bukan menggantikan model yang lebih kuat.
