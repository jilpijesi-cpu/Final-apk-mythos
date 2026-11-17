# Kecepatan model lokal: obrolan ringan, cadangan model, varian cepat

STATUS: BELUM DIKOMPILASI di Android (tidak ada Android SDK di lingkungan penyusun). Yang sudah dijalankan: logika murni
(`LightChat.kt`, `OllamaCmds`, `failoverModels`) dikompilasi dengan kompilator Kotlin 2.0.21 dan 12 tes `LightChatTest` lulus.
Bagian yang menyentuh `MainActivity` (AgentLoop, ModelCall, OllamaSetup, TermuxSetup) baru dibaca ulang, belum dikompilasi.
Jalankan `./gradlew testDebugUnitTest assembleDebug` sebelum dipakai. Kecepatan hasilnya belum diukur di HP.

## Item 1: obrolan ringan tanpa alat (model lokal)
Sebelumnya hanya sapaan baku ("Halo", "Assalamualaikum", ...) yang lewat jalur ringan. Pesan seperti "Waalaikum salam, sangat siap"
masuk jalur agen penuh (prompt ±2.000 token dengan semua definisi alat).
- `LightChat.kt` (baru): `isLightChat` mengenali pesan pendek (maks 48 karakter, 6 kata) tanpa kata kerja tindakan, tanpa angka/path/kode/URL,
  tanpa rujukan ke proyek atau giliran sebelumnya ("hasilnya", "tadi", "status", "ya", "oke", ...). Ragu = jalur penuh.
- `useLightPath`: sapaan baku di mana pun; obrolan ringan hanya di model lokal, dan tidak bila giliran AI terakhir berakhir dengan "?"
  (pesan pendek itu kemungkinan jawaban atas pertanyaannya).
- `AgentLoop.kt`: memakai `useLightPath`; jalur ringan = `compactSystem()` + tanpa definisi alat + konteks satu pesan (mekanisme lama).
  Bendera baru `lightChat` (MainActivity) membuat tahap "memahami" dan "penalaran mendalam" ikut dilewati untuk giliran ini.
- Risiko yang diterima: tugas yang kebetulan terdengar seperti obrolan kehilangan alat untuk giliran itu; pengguna cukup mengirim ulang
  dengan kata kerja ("tolong cek ..."). Daftar kata ada di `ACTION_STEMS` / `CONTEXT_WORDS` bila perlu ditambah.

## Item 3: model Ollama lain ikut termuat
Dugaan awal: `autoSetupModels` menguji sampai 30 model. Dari kode: untuk "Ollama lokal" cabang itu TIDAK memanggil `probe` sama sekali
(`probeBlocking` dilewati, baris 219 `ModelPicker.kt`); hanya `GET /v1/models`, yang tidak memuat model. Jadi dugaan itu tidak didukung kode.
Jalur yang memang bisa memuat model lain: failover. Bila model utama gagal (timeout, 5xx), `callModelRetry` mencoba "model lain di
penyedia yang sama" dari `poolFor`, dan untuk Ollama itu berarti mengirim permintaan ke model lain sehingga termuat dan berebut CPU.
- `OllamaSetup.kt`: `failoverModels` — di server lokal cadangannya hanya model terpilih (daftar penuh tidak diambil). `ModelCall.poolFor` memakainya.
- `autoSetupModels` tidak diubah (sudah aman). Daftar pilihan model tetap menampilkan semua model terpasang.
- BELUM TERBUKTI bahwa failover penyebab `qwen-t5` termuat. Cek di `~/ollama.log` (baris `loading model` / `llm server`) pada saat kejadian.

## Item 4: varian cepat otomatis
Dari uji: 6 thread ±2,7 kali lebih cepat daripada bawaan 8 thread (satu HP; HP lain bisa berbeda).
- `OllamaCmds.createFast(model)`: tulis `Modelfile` (`FROM model` + `PARAMETER num_thread n`), `ollama create model-cepat`, lalu `ollama stop model`
  agar dua salinan tidak berebut RAM. `n = min(nproc, 6)`. Nama model divalidasi (`[A-Za-z0-9._:/-]`) sebelum masuk shell.
- Langkah baru di "Siapkan Termux" (penyedia Ollama lokal): "Varian cepat (6 thread)" dengan tombol "Buat varian cepat dari <model>".
  Setelah berhasil, varian dipilih otomatis dan ditambahkan ke daftar pilihan model (`useFastModel`). Tidak memakai ruang tambahan (berbagi berkas model).
- `setupRun` mendapat parameter opsional `onDone`.

## Tes
`LightChatTest` (baru, 12 tes): klasifikasi obrolan, jalur ringan, cadangan model lokal, nama/perintah/model dasar varian cepat.

# Lanjutan: item 2 (daftar alat lokal) dan item 5 (awal prompt stabil)

STATUS sama: logika murni (`LocalTools.kt`) dikompilasi dan 19 tes (`LightChatTest` + `LocalToolsTest`) lulus; bagian `MainActivity`
(`PromptCompact.promptDecls`, `ProviderOpenAI`) belum dikompilasi di Android. Kecepatan belum diukur di HP.

## Item 2: alat model lokal dikecilkan
Hitungan dari kode (bukan dari HP; token ≈ karakter / 3, perkiraan):
- Definisi 10 alat setelah dipadatkan (`compactDecl`, aktif untuk model lokal): ±4.900 karakter. Hanya `run_command`, `write_file`, `ask_user`: ±1.300.
- Prompt sistem lokal (compactSystem + identitas + sapaan + playbook ringkas): ±1.750 karakter. Jadi total ±6.600 → ±3.050 karakter,
  kira-kira 2.200 → 1.000 token, dan definisi alat memang ±74% dari prompt (dugaan awal terbukti secara hitungan).
- `LocalTools.kt`: `LOCAL_TOOL_NAMES`, `toolNamesFor`. `promptDecls()` memakainya hanya bila alamat lokal; penyedia internet tidak berubah.
- `BUILD_PLAYBOOK_LITE` ditulis ulang: tidak lagi menyuruh memanggil `create_android_project` / `build_apk` (tidak ada di daftar). Untuk
  membuat proyek Android dan build APK, pengguna diminta beralih ke model internet. Panggilan teks yang dipulihkan (`recoverTextToolCalls`)
  tetap mengenali semua alat, jadi pelaksana tidak berubah.
- Konsekuensi yang diterima: model lokal tidak bisa lagi memanggil `build_apk`, `validate_project`, `self_audit`, `self_upgrade`, `create_tool`.
  Kembalikan dengan mengubah `LOCAL_TOOL_NAMES` bila perlu.

## Item 5: awal prompt stabil untuk cache Ollama
Audit dari kode (BELUM terbukti di HP; langkah pertama tetap membaca `~/ollama.log`):
- Sistem + alat per giliran agen stabil: tidak ada cap waktu, id acak, atau urutan yang berubah di `sysText()` dan `promptDecls()`.
- Yang bisa mengubah awal prompt di tengah tugas: `activeStudioProjectFolder`, `deepNotes`, `taskBrief`, alat buatan agen, perubahan pengaturan.
  Semuanya jarang.
- Giliran terisolasi (sapaan, ringkasan, penalaran, tahap memahami) memakai sistem berbeda dengan sengaja, dan karena `OLLAMA_NUM_PARALLEL=1` (satu slot)
  giliran itu menggeser cache giliran agen berikutnya. Obrolan ringan (item 1) menambah kejadian ini, tetapi prompt-nya pendek.
- Pemangkasan riwayat (`ctxHist`) hanya mengubah bagian sesudah sistem+alat, jadi awalan sistem+alat tetap terpakai.
- Tambahan: `PrefixWatch` (LocalTools.kt) membandingkan sistem+alat antar giliran agen di model lokal; bila berubah, muncul catatan
  "Awal prompt berubah (...): model lokal membaca ulang seluruh prompt giliran ini." Tidak ada perubahan perilaku lain.
- Cek manual: `grep -E "cached|n_tokens|prompt eval|loading model" ~/ollama.log | tail -40` setelah dua giliran berturut-turut.

# Lanjutan: item 6 (progres) dan item 7 (perbaikan kecil)

STATUS: logika murni dikompilasi dan 31 tes lulus (`LightChatTest`, `LocalToolsTest`, `LocalProgressTest`, `SapaanTest`), termasuk satu tes yang
menjalankan pembungkus LD_PRELOAD di bash sungguhan dengan `env` palsu. Bagian `MainActivity` (ModelCall, Report, CliLogin, TermuxShell, ProviderConfig)
belum dikompilasi di Android.

## Temuan dari ~/ollama.log HP uji (qwen2.5:1.5b, tangkapan layar)
- Giliran pertama: prompt 2.215 token, tanpa cache, diproses ±23-25 token/dtk, sekitar 95 dtk sebelum token pertama.
- Giliran lain: prompt 2.555 token, `cached n_tokens = 1330`: cache terpakai sebagian (awalan ±1.330 token sama, sisanya dibaca ulang).
  Penyebab terpotong di 1.330 BELUM diketahui; butuh log dua giliran berturut-turut dengan build ini (lihat perintah `grep` di bawah).
- Konteks 8.192 cukup (`truncated = 0`), satu slot.

## Item 6: progres membaca prompt
- `LocalProgress.kt`: `estimateTokens` (3 karakter/token), `promptWaitDetail`, `formatWait`, `learnTps`.
  Teks status: "Model lokal membaca prompt" + "±N token, perkiraan terlama ±M dtk/mnt sebelum jawaban pertama (lebih cepat bila cache terpakai)".
- Kecepatan awal 25 token/dtk (dari log di atas), lalu dipelajari dari waktu sampai jawaban pertama tiap permintaan
  (rata-rata bergerak bobot 0,3, disimpan di preferensi `local_tps`, dibatasi 3-300). Pengukuran < 3 dtk atau < 200 token diabaikan.
- `ModelCall.openStream` memakainya. "Terlama" karena cache Ollama bisa mempercepat; cache juga membuat kecepatan yang dipelajari sedikit optimistis.

## Item 7
- `TermuxWrap.kt`: `preloadWrapper()` (teks LD_PRELOAD dipecah dari `TermuxShell`, bisa diuji). `CliLogin.cliLogin` kini memakainya juga,
  jadi login gemini/codex tidak lagi gagal "bad interpreter".
- Laporan masalah (`Report.kt`): bila penyedia = Ollama lokal, ditambah bagian "--- Ollama ---": versi, jumlah inti, `ollama ps`, `ollama list`,
  dan 6 baris cache terakhir dari `~/ollama.log`. Semua perintah dibatasi `timeout 5`.
- `GREETING_RULE` (Sapaan.kt) di `baseSystem` dan `compactSystem`: "Halo" dibalas "Halo"; salam keagamaan hanya dibalas bila pengguna memakainya.

## Cara mengumpulkan data untuk item 2 dan 5
Pasang build ini, pilih `qwen-t6:latest`, kirim dua pesan kerja berturut-turut, lalu:
`grep -E "new prompt|cached n_tokens|prompt processing|stop processing" ~/ollama.log | tail -16` dan `grep threads ~/ollama.log | tail -3`.
Atau ketuk "Salin laporan masalah" (sekarang memuat info Ollama dan log cache).
