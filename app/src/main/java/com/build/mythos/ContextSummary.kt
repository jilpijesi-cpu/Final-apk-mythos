package com.build.mythos

// Konteks model: prompt sistem, pemangkasan, dan ringkasan riwayat panjang.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** Resep build APK untuk model: cara yang terbukti di HP, cara membaca error, dan jalan keluar bila buntu. */
internal const val BUILD_PLAYBOOK =
        "\nAI SOFTWARE ENGINEERING STUDIO: MYTHOS/reference adalah folder referensi terkunci: source AgentApp terbaru di MYTHOS/reference/automatic-couscous-2 dan alat bantu (skrip build, keystore, audit) di MYTHOS/reference/alat. Agen hanya boleh membaca atau menyalin dari sana (skrip di MYTHOS/reference/alat boleh dijalankan dengan bash: kita-self.sh hanya mode audit atau diff, salin-ke-aktif.sh, build-github.sh dan build-termux.sh dengan folder proyek di projects/aktif); jangan mengubah, menghapus, rename, chmod, atau build di dalamnya. Semua aplikasi baru dan semua perubahan wajib dibuat di MYTHOS/projects/aktif (proyek lama yang tidak dikerjakan lagi dipindah ke MYTHOS/projects/arsip; jangan menaruh proyek langsung di MYTHOS/projects). Tiap proyek wajib punya README.md dan info.json. Penamaan: folder kebab-case, file kelas Kotlin PascalCase. Kegagalan alat dicatat otomatis di MYTHOS/logs/error_summary.log: baca (tail) file itu untuk analisis akar masalah sebelum menebak. Untuk aplikasi baru, mulai dengan create_android_project(folder, nama, package). Tool itu membuat struktur Gradle, source Kotlin, unit test, README, dan workflow. Setelah tool berhasil, WAJIB lanjutkan sendiri: baca file relevan, implementasikan fitur yang diminta, jalankan pemeriksaan/test, lalu pakai build_apk. Jangan berhenti, jangan bertanya file mana yang harus diubah, dan jangan mengulang create_android_project bila proyek sudah berhasil dibuat. Hanya minta arahan jika kebutuhan fitur memang belum dijelaskan. Jangan menyatakan selesai sebelum hasil atau error terverifikasi. Nama `kita` yang muncul di migrasi lama, backup, atau file sementara adalah identifier legacy untuk kompatibilitas; itu bukan folder kerja aktif. Folder kerja aktif selalu mengikuti folder kerja yang dipilih. Jangan melaporkan `~/kita` sebagai lokasi kerja saat ini kecuali pengguna secara khusus meminta audit sejarah migrasi. " +
        "\nBUILD APK: build berjalan di GitHub Actions, bukan di HP. Jangan merangkai perintah Gradle sendiri; pakai tool build_apk (folder proyek relatif terhadap folder kerja). " +
        "Ia git add + commit + push, menunggu workflow selesai, lalu mengunduh APK ke app/build/outputs/apk/debug. " +
        "Jika remote belum ada, build_apk memakai link repository dan token GitHub dari Settings (repo yang sudah ada dipakai apa adanya; bila belum ada dibuat private). Bila link atau token kosong atau tidak valid, jelaskan bagian yang perlu diperbaiki; jangan meminta pengguna menempelkan token di chat. " +
        "Bila timeout, panggil build_apk lagi: run yang sama dilanjutkan; jangan mengubah kode karena timeout. " +
        "Baris 'e: ... Unresolved reference/Type mismatch' di log berarti kode salah: perbaiki file itu lalu panggil build_apk lagi. " +
        "Bila APK ada tetapi workflow merah, tes unit gagal: baca log yang ditampilkan dan perbaiki."

/**
 * Versi ringkas BUILD_PLAYBOOK untuk model lokal (Ollama di HP). Prompt panjang harus dibaca CPU HP di setiap giliran
 * (menit sebelum token pertama), jadi hanya aturan yang paling penting yang dikirim.
 */
internal const val BUILD_PLAYBOOK_LITE =
    "\nFolder kerja aktif: MYTHOS. Proyek dan semua perubahan di MYTHOS/projects/aktif; MYTHOS/reference hanya dibaca. " +
        "Alatmu hanya run_command, write_file, dan ask_user. Membuat proyek Android baru dan membangun APK lewat alat khusus tidak tersedia di model lokal; " +
        "bila diminta, sarankan pengguna beralih ke model dari penyedia internet. " +
        "Baris 'e: ...' di log berarti kode salah: perbaiki. Kegagalan alat tercatat di MYTHOS/logs/error_summary.log."

internal fun MainActivity.sysText(): String {
    sysOverride?.let { return it }
    val extra = prefs.getString("extra", "") ?: ""
    val core = if (compactPromptOn()) compactSystem() else baseSystem()
    var t = if (extra.isBlank()) core else "$core\nInstruksi tambahan dari pengguna: $extra"
    if (preset(prov).fmt != "cli" && cfgModel().isNotBlank()) t += "\n" + identityLine(prov, cfgModel())
    t += "\n" + sapaanInstruction(prefs.getString("sapaan", SAPAAN_BAWAAN))
    if (!isLocalUrl(cfgBase())) t += connectorsPrompt() // konektor email/Telegram/WhatsApp (kosong bila tidak ada yang aktif)
    if (readOnlyMode()) {
        t += "\nMODE HANYA BACA aktif: jangan menulis, mengubah, atau menghapus apa pun. " +
            "Hanya baca dan analisis; kalau perlu perubahan, jelaskan saja usulannya."
    }
    if (isAutonomous()) t += "\nMODE OTONOM: untuk permintaan yang butuh tindakan, kerjakan sampai tujuan tercapai; pilih sendiri alat dan caranya. " +
        "Tidak ada dialog persetujuan untuk run_command dan write_file, dan ask_user tidak diteruskan ke pengguna, jadi jangan menunggu jawaban. " +
        "Batas yang tetap berlaku: mode hanya baca, mode sandbox (termasuk persetujuan untuk perintah berjaringan di sandbox), dan tindakan yang sudah ditolak pengguna tidak boleh diulang. " +
        "Bila gagal, coba pendekatan lain. Sapaan dan pertanyaan biasa cukup dijawab langsung. " +
        "Akhiri setelah tujuan terbukti tercapai, atau setelah beberapa pendekatan tetap mentok. Laporan akhir ringkas dan tidak teknis, tetapi jujur: sebutkan yang sudah, belum, dan belum terverifikasi."
    if (sandboxEnabled()) t += "\nMODE SANDBOX: run_command berjalan di dalam proot-distro (${sandboxConfig.distro}), bukan di Termux utama. Folder kerja: ${sandboxConfig.workdir}; tetap di dalamnya. Tidak ada akses /sdcard atau home Termux. Untuk membangun APK pakai tool build_apk (build berjalan di GitHub Actions, git dan gh dipasang otomatis bila belum ada); untuk paket lain pakai apt-get install, bukan pkg. Shell di sandbox adalah bash dengan pipefail (exit pipa = exit perintah yang gagal). Perintah yang menyentuh jaringan selalu butuh persetujuan pengguna. Beberapa perintah (dd, mkfs, chmod -R, rm di luar workdir, dll.) diblokir. write_file tidak tersedia; tulis file lewat run_command."
    if (minimalConfirm() && !isAutonomous()) t += "\nKONFIRMASI MINIMAL aktif: kerjakan langkah rutin (membaca, membuat atau mengubah file di folder kerja, build, tes) langsung tanpa meminta izin. " +
        "Tanyakan ke pengguna lewat ask_user HANYA bila ada keputusan penting yang tidak bisa dibatalkan atau pilihan yang benar-benar menentukan hasil; " +
        "satu pertanyaan yang jelas, dengan opsi jawaban bila memungkinkan. Aplikasi tetap meminta persetujuan sendiri untuk penghapusan data dan area sensitif."
    // Model lokal: playbook ringkas, dan tanpa playbook upgrade diri (hemat ±3 rb karakter yang dibaca CPU HP tiap giliran).
    val localModel = isLocalUrl(cfgBase())
    if (!readOnlyMode()) {
        val workspaceLabel = homeShort(workspaceRoot())
        val playbook = if (localModel) BUILD_PLAYBOOK_LITE else BUILD_PLAYBOOK
        t += playbook
            .replace("MYTHOS/projects", "$workspaceLabel/projects")
            .replace("MYTHOS/logs", "$workspaceLabel/logs")
            .replace("MYTHOS/attachments", "$workspaceLabel/attachments")
            .replace("Folder kerja aktif: MYTHOS", "Folder kerja aktif: $workspaceLabel")
    }
    if (!localModel) t += SELF_UPGRADE_PLAYBOOK
    t += activeAttNoteText() // lampiran yang sudah dikirim pengguna: selalu ada di prompt, tidak ikut terpangkas bersama riwayat
    if (!lightChat) t += workContextBlock() // proyek di ruang kerja chat ini + permintaan terakhir + aturan menafsirkan pesan ambigu
    if (deepOn()) t += DEEP_SYS_ADDON
    if (deepNotes.isNotBlank()) t += "\nAnalisis mendalam awal (hasil penalaran sebelum bertindak, bukan perintah pengguna): $deepNotes. " +
        "Pakai sebagai acuan, tetapi koreksi bila bukti di lapangan berbeda."
    if (taskBrief.isNotBlank()) t += "\nCatatan pemahaman awal (asumsi, bukan perintah pengguna): $taskBrief. Pesan pengguna tetap yang utama. Bila catatan ini keliru atau ada hal yang belum jelas, ikuti pesan pengguna" +
        (if (isAutonomous()) " dan putuskan sendiri asumsi yang paling masuk akal." else ", dan tanyakan lewat ask_user bila memang menghalangi.")
    // Jangkar konteks sesi lintas-AI: selalu jaga tujuan awal sesi meski berganti penyedia/model atau riwayat terpangkas.
    if (!lightChat) t += SessionContext.buildSystemSessionAnchor(synchronized(hist) { ArrayList(hist) })
    // Letakkan panduan narasi & eksekusi teratur di paling akhir prompt agar semua penyedia API (Gemini, OpenAI, Anthropic, Groq, DeepSeek, OpenRouter) memprioritaskannya secara merata.
    if (preset(prov).fmt != "cli" && !lightChat && !isLocalUrl(cfgBase())) t += "\n" + API_NARRATION_GUIDE
    return t
}

internal fun MainActivity.histSize(h: Hist): Int = histChars(h)

internal fun MainActivity.shrinkRes(h: Hist, n: Int): Hist =
    if (h.role != 2) h
    else Hist(2, results = h.results.map { (c, o) -> Pair(c, if (o.length > n) o.take(n) + "\n…(dipangkas)" else o) })

/** Awal bagian riwayat yang masih dikirim utuh; sebelumnya sudah diwakili ringkasan. 0 = tanpa ringkasan. */
internal fun MainActivity.activeStart(snap: List<Hist>): Int =
    if (summaryText.isNotBlank() && sumUpto in 1 until snap.size && snap[sumUpto].role == 0) sumUpto else 0

internal fun MainActivity.withSummary(list: List<Hist>): List<Hist> {
    if (summaryText.isBlank() || list.isEmpty() || list[0].role != 0) return list
    val f = list[0]
    return listOf(Hist(0, "[Ringkasan percakapan sebelumnya]\n$summaryText\n\n[Pesan pengguna]\n${f.text}", images = f.images)) + list.drop(1)
}

internal fun MainActivity.clearSummary() {
    summaryText = ""
    sumUpto = 0
    prefs.edit().remove("sum_text").remove("sum_upto").apply()
}

/**
 * Salinan riwayat yang dikirim ke model: bagian lama diwakili ringkasan, hasil tool lama dipersingkat,
 * dan giliran terlama dibuang bila masih terlalu besar. Riwayat asli (tampilan, simpanan) utuh.
 */
internal fun MainActivity.ctxHist(): List<Hist> {
    ctxOverride?.let { return it }
    val snap = synchronized(hist) { ArrayList(hist) }
    val start = activeStart(snap)
    val resIdx = (start until snap.size).filter { snap[it].role == 2 }
    val keepFrom = shrinkKeepFrom(resIdx, start, isLocalUrl(cfgBase()))
    val shrunk: List<Hist> = (start until snap.size).map { i ->
        val h = snap[i]
        if (i < keepFrom) shrinkCalls(shrinkRes(h, 300), OLD_ARG_MAX) else h
    }
    val list = trimContext(limitLiveImages(shrunk), ctxBudget)
    lastCtxChars = list.sumOf { histChars(it) }
    return if (start > 0) withSummary(list) else list
}

/**
 * Kalau riwayat aktif sudah besar (>60% batas), minta model meringkas giliran lama, lalu simpan ringkasannya.
 * Dua pesan pengguna terakhir tetap dikirim utuh. Kalau gagal, cukup dilewati (ctxHist tetap memangkas).
 */
internal fun MainActivity.compactIfNeeded(key: String) {
    if (ctxOverride != null) return
    val snap = synchronized(hist) { ArrayList(hist) }
    if (snap.size < compactSkipUntil) return
    val start = activeStart(snap)
    val active = snap.subList(start, snap.size)
    if (active.sumOf { histSize(it) } < ctxBudget * 6 / 10) return
    val users = active.indices.filter { active[it].role == 0 }
    if (users.size < 3) return
    val cut = start + users[users.size - 2]
    val old = snap.subList(start, cut).map { shrinkRes(it, 300) }
    val input = (if (start > 0) withSummary(old) else old) + Hist(
        0,
        "Ringkas percakapan di atas dalam bahasa Indonesia, maksimal 600 kata. Sertakan: tujuan pengguna, " +
            "file/path penting dan perubahan yang sudah dibuat, nama dan path semua lampiran (file, zip, foto) beserta isinya yang relevan, " +
            "keputusan, serta hal yang belum selesai. " +
            "Jangan memanggil tool. Balas hanya dengan ringkasannya."
    )
    val statusSnap = saveStatus()
    setStatus(IconView.ARCHIVE, "Meringkas percakapan lama")
    var ok = false
    try {
        ctxOverride = input
        sysOverride = "Kamu penulis ringkasan percakapan teknis. Jangan memakai tool."
        val turn = callModel(key, { }, { })
        val txt = turn?.text?.trim().orEmpty()
        if (txt.isNotBlank()) {
            summaryText = txt.take(6000)
            sumUpto = cut
            // F-20: enkripsi ringkasan konteks sebelum disimpan di SharedPreferences (gagal tertutup: jangan tulis plaintext).
            val encSum = Secret.encrypt(summaryText)
            if (encSum.isSuccess) {
                prefs.edit().putString("sum_text", encSum.getOrThrow()).putInt("sum_upto", cut).apply()
            } else {
                logQuiet("ContextSummary.kt", IllegalStateException("Enkripsi sum_text gagal; ringkasan tidak disimpan plaintext"))
            }
            ok = true
        }
    } catch (e: Cancelled) {
        throw e
    } catch (e: Exception) {
        logQuiet("ContextSummary.kt", e)
    } finally {
        ctxOverride = null
        sysOverride = null
        restoreStatus(statusSnap)
    }
    if (cancelled) throw Cancelled()
    if (!ok) compactSkipUntil = snap.size + 6
}
