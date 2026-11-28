package com.build.mythos

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import org.json.JSONObject

// Loop agen: kirim pesan, jalankan worker, panggil model, jalankan alat, berhenti.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** Tutup keyboard dan lepas fokus dari kolom input setelah pesan dikirim, agar tata letak kembali normal. */
/** Mode otonom: agen menjalankan tindakan tanpa dialog persetujuan dan tanpa menunggu jawaban pengguna. Harus opt-in. */
internal fun MainActivity.isAutonomous(): Boolean = prefs.getBoolean("autonomous", false)

internal fun MainActivity.hideKeyboard() {
    val close = {
        try {
            input.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (e: Exception) {
        }
    }
    close()
    // Setelah dialog ditutup, Android sering mengembalikan fokus ke kolom input; tutup lagi sebentar kemudian.
    input.postDelayed({ close() }, 300)
}

/**
 * Hentikan agen. Tombol Berhenti ([keepPending] = false) juga membatalkan pesan yang menunggu giliran; kalau tidak,
 * agen berhenti lalu langsung berjalan lagi dengan pesan itu. send() memakai keepPending = true karena memang ingin melanjutkan.
 */
internal fun MainActivity.stopAgent(keepPending: Boolean = false) {
    if (!busy) return
    if (!keepPending && pendingInterrupt != null) {
        pendingInterrupt = null
        addNote("Pesan yang menunggu dibatalkan.")
    }
    cancelled = true
    try { activeConn?.disconnect() } catch (e: Exception) { logQuiet("AgentLoop.kt", e) }
    worker?.cancel()
    confirmDialog?.dismiss()
    setStatus(IconView.STOP, "Menghentikan...")
}

internal fun MainActivity.onCancelled() {
    runLog.outcome = RunOutcome.CANCELLED
    trimDangling()
    val p = lastPartial
    if (p.isNotBlank()) synchronized(hist) { hist.add(Hist(1, p)) }
    lastPartial = ""
    addNote("Dihentikan.")
}

internal fun MainActivity.send() {
    val typed = input.text.toString().trim()
    if (busy) {
        if (typed.isNotEmpty()) {
            pendingInterrupt = typed
            input.setText("")
            hideKeyboard()
            addNote("Pesan baru diterima. Menghentikan jawaban saat ini lalu melanjutkan dengan pesan tersebut...")
            stopAgent(keepPending = true)
        }
        return
    }
    if (typed.isEmpty() && attachments.isEmpty()) return
    if (attachments.isEmpty()) {
        val quickLeakQuery = LeakOsintEngine.extractCommandQuery(typed)
        if (quickLeakQuery != null) {
            runLeakOsintChatSearch(quickLeakQuery)
            return
        }
        if (leakOsintMode()) {
            runLeakOsintChatSearch(typed)
            return
        }
    }
    if (attachments.any { it.path == null }) { toast("Tunggu file selesai disalin"); return }
    val imageRefs = attachments.filter { isImageName(it.name) }.mapNotNull { a ->
        a.path?.let { HistImage(it, a.previewPath) }
    }
    val msg = typed.ifEmpty { "Periksa lampiran dan gunakan sebagai konteks tugas." } +
        attachments.joinToString("") {
            "\n${if (isImageName(it.name)) "Gambar" else "File"} terlampir di \"${attachmentRef(it.path)}\""
        }
    registerTaskCopies(attachments.mapNotNull { it.path })
    // Isi lampiran (teks, docx, pdf, manifest zip) dibaca saat file dipilih; di sini ditempel ke pesan di riwayat agar tidak hilang.
    val sentAtts = attachments.toList()
    val attBlock = buildAttachmentBlock(
        sentAtts.filter { it.digest.isNotBlank() }.map { AttDigest(it.name, it.kind, it.digest) },
        digestTotalLimit(ctxBudget, isLocalUrl(cfgBase()))
    )
    val checkpoint = loadAgentCheckpoint()
    val resume = typed.trim().lowercase() in setOf("lanjutkan", "lanjut", "teruskan", "continue") && checkpoint != null
    val key = cfgKey()
    if (needsKey() && key.isEmpty()) { showApiKeyDialog(); return }
    if (preset(prov).fmt != "cli" && cfgModel().isBlank()) {
        toast("Isi nama model dulu")
        pickModel()
        return
    }
    // Offline dengan AI internet: simpan pesan di antrean, kirim otomatis saat koneksi pulih (Chat samaran tidak menyimpan apa pun ke disk).
    if (shouldQueueOffline(NetState.online, isLocalUrl(cfgBase()), attachments.isNotEmpty(), typed)) {
        if (incognito) toast("Offline: Chat samaran tidak menyimpan antrean. Coba lagi saat online.") else queueOffline(typed)
        return
    }
    // Lampiran yang dikirim dicatat per chat; catatannya masuk prompt sistem di setiap giliran (lihat sysText).
    rememberActiveAttachments(sentAtts)
    // Sapaan baku, atau (model lokal saja) obrolan pendek tanpa kata kerja tindakan: jalur ringan tanpa alat dan tanpa riwayat tugas.
    val lastAiAsked = synchronized(hist) { endsWithQuestion(hist.lastOrNull { it.role == 1 }?.text) }
    // Mode obrolan (selalu aktif, hanya model lokal): semua pesan jadi obrolan murni tanpa alat. AI internet tidak terpengaruh.
    val localChat = isLocalUrl(cfgBase())
    val isolateGreeting = attachments.isEmpty() && !resume && (localChat || useLightPath(typed, isLocalUrl(cfgBase()), lastAiAsked))
    lightChat = isolateGreeting
    busy = true
    input.setText("")
    hideKeyboard()
    clearAttachments()
    if (resume) {
        clearErrorNotes() // pesan error lama dihapus supaya riwayat chat tetap rapi
        val resumeMessage = checkpoint?.userMessage ?: return
        startWorker(resumeMessage, key, addUser = false, images = emptyList())
    } else {
        // Riwayat sebelum pesan baru masuk: dipakai mode obrolan untuk konteks beberapa pesan terakhir.
        val chatPrev: List<Hist> = if (isolateGreeting && localChat) synchronized(hist) { ArrayList(hist) } else emptyList()
        addMsg(USER, msg, imageRefs)
        val extraNote = prefs.getString("extra", "")?.trim().orEmpty().let { if (it.isBlank()) "" else "\nInstruksi tambahan dari pengguna: $it" }
        if (isolateGreeting && localChat) {
            ctxOverride = localChatContext(chatPrev, typed, recallBlock(typed)) // memori obrolan lama (ChatMemory.kt)
            sysOverride = localChatSystem(cfgModel(), prefs.getString("sapaan", SAPAAN_BAWAAN)) + extraNote + activeAttNoteText(tools = false)
        } else if (isolateGreeting) {
            ctxOverride = listOf(Hist(0, typed))
            // Model lokal: prompt ringkas (baseSystem ±8 rb karakter harus dibaca CPU HP, menit untuk sekadar "Halo").
            sysOverride = apiChatSystem() + extraNote + activeAttNoteText(tools = false)
        }
        startWorker(msg, key, addUser = true, images = imageRefs, extra = attBlock)
    }
}

/** Prompt sistem sapaan penyedia API: obrolan biasa, tanpa alat dan tanpa aturan kerja jalur lama (Termux, build_apk, dst.). */
internal fun MainActivity.apiChatSystem(): String =
    "Kamu asisten AI berbahasa Indonesia di dalam aplikasi Mythos. Pesan ini sapaan atau obrolan singkat, bukan perintah kerja: " +
        "jawab langsung, ramah, dan singkat. Di giliran ini kamu tidak punya alat, jadi jangan menjalankan perintah, menulis file, atau membahas proyek lama.\n" +
        identityLine(prov, cfgModel()) + "\n" + sapaanInstruction(prefs.getString("sapaan", SAPAAN_BAWAAN))

internal fun isSimpleGreeting(message: String): Boolean =
    Regex("(?i)^(halo|hai|hi|hello|assalamualaikum|selamat pagi|selamat siang|selamat sore|selamat malam)[!. ]*$")
        .matches(message.trim())

/** Jalankan satu putaran agen di thread latar. `busy` sudah true saat dipanggil. */
internal fun MainActivity.startWorker(
    msg: String,
    key: String,
    addUser: Boolean,
    images: List<HistImage> = emptyList(),
    extra: String = ""
) {
    cancelled = false
    if (addUser) activeStudioProjectFolder = ""
    agentState = AgentState.Planning
    // Melanjutkan: pertahankan id tugas dan nomor putaran dari checkpoint, jangan direset ke 0.
    val prevCp = if (!addUser) loadAgentCheckpoint() else null
    if (addUser || agentTaskId.isBlank()) agentTaskId = prevCp?.taskId?.takeIf { it.isNotBlank() } ?: "task-${System.currentTimeMillis()}"
    saveAgentCheckpoint(AgentCheckpoint(agentTaskId, msg, prevCp?.turn ?: 0, prevCp?.lastTool.orEmpty(), "planning"))
    allowAll = false
    try { startForegroundService(Intent(this, AgentService::class.java)) } catch (e: Exception) { logQuiet("AgentLoop.kt", e) }
    sawBuild = false
    sawFail = false
    SourceLog.clear()   // sumber web hanya untuk giliran ini
    clearChips()
    clearStandby()
    lastPartial = ""
    resetFailover()
    runLog.reset(System.currentTimeMillis())
    agentSteps.clear()
    runOnUiThread {
        codexActivityUi.reset()
        zone.beginRun()
        zone.renderSteps(agentSteps.groups.value)
    }
    updateAction()
    setStatus(KIND_DOTS, THINK_TEXT)
    // Pembatalan lewat Job.cancel() (runInterruptible menginterupsi thread yang sedang memblokir, sama seperti
    // Thread.interrupt() sebelumnya). onFinally SELALU jalan, juga bila dibatalkan sebelum agentLoop sempat mulai.
    worker = bgCancellable(
        onError = { e ->
            try {
                if (cancelled || e is Cancelled || e is InterruptedException || e is kotlinx.coroutines.CancellationException) {
                    Thread.interrupted()
                    agentState = AgentState.Cancelled
                    onCancelled()
                } else {
                    // Giliran model yang memanggil alat tapi belum dapat hasil harus dibuang, kalau tidak
                    // permintaan berikutnya ditolak API ("function call tanpa response").
                    trimDangling()
                    runLog.note = e.message ?: e.javaClass.simpleName
                    runLog.outcome = RunOutcome.ERROR
                    agentState = AgentState.Failed(runLog.note)
                    val lastCp = loadAgentCheckpoint()
                    saveAgentCheckpoint(AgentCheckpoint(agentTaskId, msg, lastCp?.turn ?: 0, lastCp?.lastTool.orEmpty(), "failed", runLog.note))
                    addErrorNote(e.message ?: e.javaClass.simpleName)
                    synchronized(hist) { if (hist.lastOrNull()?.role != 1) hist.add(Hist(1, "$FAILED_HIST_PREFIX: ${friendlyErrorText(e.message)}")) }
                }
            } catch (e2: Exception) {
                logQuiet("AgentLoop.kt", e2)
            }
        },
        onFinally = {
            Thread.interrupted()
            // Sapaan memakai konteks satu pesan agar AI tetap menjawab sendiri tanpa terseret tugas lama.
            ctxOverride = null
            sysOverride = null
            lightChat = false
            try {
                // Tutup aliran aktivitas: selesaikan antrean yang tertahan dan pasang ringkasan langkah.
                runOnUiThread {
                    codexActivityUi.accept(JSONObject().put("type", "turn_completed").put("item", JSONObject().put("status", "completed")))
                    codexActivityUi.flushAll()
                }
                streamEnd()
                hideStatus()
                saveHist()
            } catch (e: Exception) {
                logQuiet("AgentLoop.kt", e)
            }
            try { rememberLastExchange() } catch (e: Throwable) { logQuiet("AgentLoop.kt", e) } // simpan tanya-jawab ini ke memori obrolan
            // Lepaskan payload base64 dari memori setelah tugas selesai; path tetap tersimpan untuk riwayat.
            synchronized(hist) { hist.forEach { h -> h.images.forEach { it.cachedPayload = null } } }
                runOnUiThread {
                sealFeed()
                // Laporan akhir hanya setelah semua langkah selesai dan riwayat tersimpan.
                try { showRunReport() } catch (e: Throwable) { logQuiet("AgentLoop.kt", e) }
                    busy = false
                    if (runLog.outcome == RunOutcome.COMPLETED) {
                        agentState = AgentState.Completed(buildReport(runLog, System.currentTimeMillis() - runLog.startedAt))
                        clearAgentCheckpoint()
                        // Lampiran TIDAK lagi dihapus di sini: dulu foto dilepas dan file dihapus begitu satu tugas selesai,
                        // sehingga pesan berikutnya ("lanjutkan", "perbaiki lagi") kehilangan file yang sudah dikirim.
                        // Salinan baru dibersihkan saat chat dihapus (lihat purgeChatAttachments / resetChatState).
                    }
                    if (runLog.outcome == RunOutcome.CANCELLED) agentState = AgentState.Cancelled
                    worker = null
                    val next = pendingInterrupt
                    pendingInterrupt = null
                    if (next != null && !isFinishing) {
                        busy = true
                        addMsg(USER, next)
                        startWorker(next, cfgKey(), addUser = true)
                    }
                    // Pesan yang diantre saat offline dikirim satu per satu setelah tugas ini selesai (bukan bila pengguna menekan Berhenti).
                    if (!busy && !isFinishing && runLog.outcome != RunOutcome.CANCELLED) drainOfflineQueue()
                    // Tugas berhenti sebelum selesai (gagal/terpotong/dihentikan): tawarkan lanjut. Pada tugas yang
                    // selesai normal, chip sudah ditampilkan sesuai isi jawaban AI (pilihan, ya/tidak, perbaikan).
                    if (!busy && runLog.outcome != RunOutcome.COMPLETED) showChips(runLog.finalText, unfinished = true)
                updateAction()
                // Setelah selesai AI tidak dimatikan: layanan tetap hidup dalam mode siaga dan indikator terus bergerak.
                // Jeda singkat agar tidak berebut dengan startForeground() bila pekerjaan selesai seketika.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!busy && !isFinishing && !isDestroyed) {
                        enterStandbyService()
                        showStandby()
                    }
                }, 1500)
            }
        }
    ) {
        // Pemindaian proyek lewat Termux hanya untuk dunia CLI; agen API bekerja di dalam aplikasi dan tidak memakainya.
        if (!lightChat && usesTermuxWorld(preset(prov).fmt)) refreshWorkContext() // proyek apa saja yang ada di ruang kerja chat ini, untuk blok Konteks kerja
        agentLoop(msg, key, addUser, images, extra)
    }
}

internal fun MainActivity.statusFor(name: String, args: JSONObject): String {
    // Kalimat yang model tulis sendiri untuk langkah ini (argumen deskripsi/description) paling informatif; dipakai bila spesifik.
    oneLineStepLabel(args.optString("deskripsi").ifBlank { args.optString("description") })?.let { return it }
    if (name == "build_apk") return "Membangun APK dari seluruh kode proyek"
    if (name == "cari_web") return "Mencari informasi terbaru di internet"
    if (name == "baca_web") return "Membaca isi halaman web untuk mengambil informasi"
    if (name == "buat_gambar") return "Membuat gambar"
    if (name == "edit_gambar") return "Mengedit gambar"
    if (name == "cari_leakosint") return "Mencari di MYTHOS LeakOSINT"
    if (name in DANA_TOOL_NAMES) return danaStatus(name)
    if (name in CONNECTOR_TOOL_NAMES) return connectorStatus(name)
    if (name == "validate_project") return "Memeriksa struktur dan kelengkapan file proyek"
    if (name == "create_android_project") return "Membuat kerangka proyek Android baru"
    if (name == "create_tool") return "Membuat alat baru untuk dipakai berulang"
    if (name == "delete_tool") return "Menghapus alat buatan yang tidak terpakai"
    if (name == "self_audit") return "Mengaudit kode aplikasi ini mencari masalah"
    if (name == "self_upgrade") return "Memproses pembaruan untuk aplikasi ini"
    if (name == "write_file") return describeWrite(args.optString("path"))
    if (name == "berangkas_baca") return "Membaca data dari berangkas aplikasi"
    if (name == "berangkas_salin") return "Menyalin berkas dari berangkas aplikasi"
    if (name == "ask_user") return "Menunggu jawaban Anda untuk melanjutkan pekerjaan"
    val c = args.optString("command")
    describeCommand(c)?.let { return it }
    return when {
        c.contains("gradlew") || c.contains("gradle ") -> "Menjalankan gradle untuk memeriksa dan membangun proyek"
        Regex("""\b(grep|find|rg|locate|which|whereis|pwd|tree)\b""").containsMatchIn(c) -> "Menelusuri struktur dan isi folder proyek"
        Regex("""\b(cat|head|tail|sed|wc|diff)\b""").containsMatchIn(c) -> "Membaca dan menganalisis kode yang berkaitan"
        Regex("""\b(test|check|lint|verify)\b""").containsMatchIn(c) -> "Memvalidasi hasil pekerjaan sebelum dilanjutkan"
        else -> "Menjalankan perintah untuk melanjutkan pekerjaan"
    }
}



/** Provider tertentu mengirim alasan berpikir sebagai tag teks; itu bukan jawaban untuk pengguna. */
internal fun stripModelThinking(text: String): String {
    var out = text.replace(Regex("(?is)<(thinking|think)>.*?</\\1>"), "")
    out = out.replace(Regex("(?is)<(thinking|think)>.*$"), "")
    out = out.replace(Regex("(?i)</(thinking|think)>"), "")
    return out.trim()
}

/** Model kadang menulis protokol tool sebagai chat biasa; protokol internal tidak boleh bocor ke pengguna. */
/**
 * Tutup segmen teks live giliran ini TANPA memblokir thread agen: alat berikutnya langsung berjalan sementara
 * ketikan teks pembuka selesai sendiri di layar. Urutan terhadap baris aktivitas dijaga antrean CodexActivityUi.
 */
internal fun MainActivity.endLiveSegment() {
    requestSegmentEnd()
}

/**
 * Sebelum jawaban akhir ditampilkan, tunggu antrean tampilan (baris aktivitas yang tertahan di belakang ketikan) habis
 * supaya urutan di layar sama dengan urutan kerja. Ini hanya terjadi di akhir tugas, jadi kerja alat tidak diperlambat.
 * Bila menunggu lama, ketikan dipercepat; batas keras [maxMs] mencegah macet.
 */
internal fun MainActivity.awaitVisualQueue(maxMs: Long = 45_000L) {
    val start = System.currentTimeMillis()
    while (!cancelled && codexActivityUi.hasQueuedEvents() && System.currentTimeMillis() - start < maxMs) {
        if (System.currentTimeMillis() - start > 12_000L) streamBoost = StreamTempo.current.fastForward
        Thread.sleep(40)
    }
}

/** Kirim event aktivitas ke UI bersama; urutannya dijaga karena dipost berurutan ke main thread. */
internal fun MainActivity.emitActivity(type: String, item: JSONObject) {
    runOnUiThread { codexActivityUi.accept(JSONObject().put("type", type).put("item", item)) }
}

internal fun sanitizeAssistantText(text: String): String {
    val clean = stripModelThinking(text).replace(Regex("(?i)</?think(?:ing)?>"), "").trim()
    val hasToolProtocol = Regex("(?is)[\\\"'](?:command|deskripsi|description)[\\\"']\\s*:").containsMatchIn(clean)
    val hasInternalPlan = Regex("(?im)^\\s*[-*]?\\s*(langkah selanjutnya|langkah rutin untuk verifikasi|rencana kerja)\\s*:?").containsMatchIn(clean)
    val looksLikeToolPayload = clean.trimStart().startsWith("{") || clean.contains("\"command\"")
    return if (hasToolProtocol && (hasInternalPlan || looksLikeToolPayload)) {
        "Agen belum menjalankan pemeriksaan karena format perintah internal tidak terbaca sebagai tool. Silakan kirim perintahnya lagi."
    } else clean
}

internal fun MainActivity.agentLoop(
    userMsg: String,
    key: String,
    addUser: Boolean = true,
    images: List<HistImage> = emptyList(),
    extra: String = ""
) {
    if (preset(prov).fmt == "cli") { cliChat(userMsg); return }
    // Dua dunia terpisah (lihat AppWorld.kt): penyedia API bekerja penuh di dalam aplikasi lewat mesin Mythos-v31 (CcBridge.kt) dan TIDAK
    // pernah memakai Termux; hanya penyedia CLI (cabang di atas) yang menyentuh Termux. Obrolan ringan tetap tanpa alat di jalur bawah.
    if (!lightChat) {
        if (ccAgentLoop(userMsg, addUser, images, extra)) return
        // Konfigurasi belum lengkap: berhenti dengan petunjuk, jangan dialihkan ke loop lama yang berbasis Termux.
        if (addUser) {
            synchronized(hist) { hist.add(Hist(0, userMsg + extra, images = images)) }
        }
        stopWithNote("Pengaturan penyedia belum lengkap: isi alamat dasar dan model di Pengaturan, lalu kirim pesan lagi.")
        return
    }
    // Obrolan ringan (sapaan) penyedia API: SATU panggilan model tanpa definisi alat. Tidak ada loop alat lama di sini;
    // semua pekerjaan beralat penyedia API lewat ccAgentLoop (cabang di atas), semua pekerjaan CLI lewat cliChat.
    if (addUser) {
        // [extra] = blok isi lampiran: ikut tersimpan di riwayat (disembunyikan dari gelembung chat).
        synchronized(hist) { hist.add(Hist(0, userMsg + extra, images = images)) }
        saveHist()
        taskBrief = "" // catatan pemahaman milik tugas sebelumnya tidak boleh terbawa
        deepNotes = ""
        deepStageDone = false
    }
    val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    agentState = AgentState.CallingModel(1)
    saveAgentCheckpoint(AgentCheckpoint(agentTaskId, userMsg, 1, "", "calling_model"))
    val phase = THINK_TEXT
    setStatus(KIND_DOTS, phase)
    compactIfNeeded(key)
    var wrote = false
    var liveShown = "" // bagian teks yang sudah ditampilkan live
    lastPartial = ""
    // Status tunggu yang jujur: hanya muncul bila model benar-benar belum mengirim token setelah 5 detik.
    val waitTask = Runnable {
        if (busy && !cancelled && !wrote && curTitle == phase) setStatus(KIND_DOTS, "Menunggu respons model AI")
    }
    uiHandler.postDelayed(waitTask, 5000)
    val rawTurn = try {
        // noToolsCall: benar-benar tanpa definisi alat, supaya model tidak bisa memanggil alat lama.
        noToolsCall = true
        try {
            callModelRetry(
                key,
                onText = { t ->
                    val visible = sanitizeAssistantText(t)
                    lastPartial = visible
                    if (!wrote) {
                        wrote = true
                        setStatus(IconView.NEW, "Menulis jawaban")
                    }
                    if (visible.startsWith(liveShown)) {
                        val delta = visible.substring(liveShown.length)
                        if (delta.isNotEmpty()) { liveShown = visible; codexActivityUi.delta(delta) }
                    } else if (visible.isNotBlank()) {
                        // Percobaan ulang/ganti model menulis dari awal: gambar ulang kotak yang sama (tanpa dobel).
                        liveShown = visible
                        streamUpdate(visible)
                    }
                },
                onFlush = { lastPartial = "" }
            )
        } catch (e: EmptyResponseException) {
            null // semua model menjawab kosong: ditangani di bawah
        }
    } finally {
        noToolsCall = false
        uiHandler.removeCallbacks(waitTask)
    }
    if (rawTurn == null) {
        if (liveShown.isNotEmpty()) endLiveSegment()
        if (cancelled) throw Cancelled()
        runLog.note = "Model tidak memberi jawaban"
        runLog.outcome = RunOutcome.INCOMPLETE
        addFailureNote("Model mengembalikan respons kosong dan tidak ada model cadangan yang menjawab. Silakan kirim ‘lanjutkan’ atau ganti model.")
        // Jaga selang-seling riwayat (pengguna/model) supaya permintaan berikutnya tidak ditolak API.
        synchronized(hist) { if (hist.lastOrNull()?.role != 1) hist.add(Hist(1, "(respons model kosong)")) }
        saveHist()
        return
    }
    // Tanpa alat: hanya teks jawaban yang disimpan.
    val turn = Hist(rawTurn.role, sanitizeAssistantText(rawTurn.text), raw = rawTurn.raw)
    synchronized(hist) { hist.add(turn) }
    saveHist()
    runLog.finalText = turn.text
    agentState = AgentState.Verifying
    if (turn.text.isNotBlank()) {
        // Jawaban akhir ditampilkan setelah antrean visual selesai, lalu dianimasikan seperti sedang mengetik.
        awaitVisualQueue()
        streamUpdate(turn.text)
        streamEnd()
        showChips(turn.text)
        if (voiceTurnPending) {
            voiceTurnPending = false
            speakReply(turn.text)
        }
    }
}

/** Akhiri putaran dengan catatan, dan pastikan riwayat berakhir di giliran model (bukan hasil alat yang menggantung). */
private fun MainActivity.stopWithNote(msg: String) {
    runLog.note = msg
    runLog.outcome = RunOutcome.INCOMPLETE
    synchronized(hist) { if (hist.lastOrNull()?.role != 1) hist.add(Hist(1, msg)) }
    saveHist()
    addFailureNote(msg)
}
