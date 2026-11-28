package com.build.mythos

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import com.build.mythos.cc.BusyKind
import com.build.mythos.cc.busyKindOf
import com.build.mythos.cc.busyLine

/** Percobaan ulang saat membuka koneksi (jaringan seluler sering goyah) dan saat stream putus di tengah jalan. Dipersingkat saat peralihan AI. */
private const val OPEN_RETRIES_NORMAL = 3

/** Server meminta tunggu lebih lama dari ini (Retry-After/retryDelay): jangan menunggu di sini, serahkan ke peralihan AI. */
internal const val MAX_RETRY_WAIT_SEC = 20

/** Jeda sebelum percobaan ulang ke-[attempt] (mulai 1): petunjuk server bila ada, kalau tidak 2, 4, 8, ... detik; maksimal [MAX_RETRY_WAIT_SEC]. */
internal fun retryWaitSeconds(hint: Int?, attempt: Int): Int =
    minOf(hint ?: (2 shl (attempt - 1)), MAX_RETRY_WAIT_SEC)

/** Server meminta tunggu lebih lama dari batas: jangan menunggu, alihkan ke AI lain. */
internal fun retryHintTooLong(hint: Int?): Boolean = hint != null && hint > MAX_RETRY_WAIT_SEC

private const val STREAM_RETRIES_NORMAL = 5
private fun MainActivity.openRetries() = if (fastFail) 1 else OPEN_RETRIES_NORMAL
private fun MainActivity.streamRetries() = if (fastFail) 1 else STREAM_RETRIES_NORMAL

// Pemanggilan model umum: pilih penyedia, ulangi saat gagal, stream, parsing argumen, CLI.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.callModel(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? =
    when (preset(effectiveProv()).fmt) {
        "openai" -> callOpenAI(key, onText, onFlush)
        "anthropic" -> callAnthropic(key, onText, onFlush)
        else -> callGemini(key, onText, onFlush)
    }

/**
 * Ulangi (maks 5 x) kalau koneksi putus/timeout di tengah stream. Teks parsial diganti oleh percobaan baru
 * karena kotak balasan digambar ulang dari awal. Tidak diulang kalau teks sudah di-flush (agar tidak dobel).
 */
internal fun MainActivity.callModelRetryOnce(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
    var attempt = 0
    while (true) {
        var flushed = false
        streamOpen = false
        try {
            return callModel(key, onText, { flushed = true; onFlush() })
        } catch (e: java.io.IOException) {
            if (cancelled) throw Cancelled()
            if (!streamOpen || flushed || attempt >= streamRetries()) {
                val part = lastPartial
                if (part.isNotBlank()) {
                    synchronized(hist) { hist.add(Hist(1, part)) }
                    lastPartial = ""
                }
                throw e
            }
            attempt++
            setStatus(IconView.CLOCK, busyLine(BusyKind.NETWORK, attempt - 1))
            for (sec in minOf(2 shl (attempt - 1), 15) downTo 1) {
                Thread.sleep(1000)
                if (cancelled) throw Cancelled()
            }
            lastPartial = ""
        }
    }
}

/** Jalankan [block] dengan penyedia [p] (kunci, URL, model milik p) secara terisolasi per thread tanpa memutasi [prov] global. */
internal fun <T> MainActivity.onProvider(p: String?, model: String?, block: () -> T): T {
    val curProv = effectiveProv()
    if (p == null || (p == curProv && model == null)) return block()
    val prev = activeProviderOverride.get()
    activeProviderOverride.set(p to model)
    return try {
        block()
    } finally {
        if (prev == null) activeProviderOverride.remove() else activeProviderOverride.set(prev)
    }
}

/** True bila alamat penyedia [name] adalah server lokal (Ollama di perangkat sendiri). */
private fun MainActivity.isLocalProvider(name: String): Boolean =
    try { onProvider(name, null) { isLocalUrl(cfgBase()) } } catch (e: Exception) { false }

/** Model pilihan pengguna untuk penyedia [p] (tanpa override peralihan). */
private fun MainActivity.savedModelOf(p: String): String = pvOf(p, "model").ifEmpty { preset(p).model }

/**
 * Daftar model satu penyedia untuk cadangan, atau null bila penyedia itu tidak siap (tanpa API key / URL / model).
 * Memakai hasil uji model bila ada; kalau belum pernah diuji, daftar diambil langsung dari penyedia. Hasil di-cache selama satu tugas.
 */
private fun MainActivity.poolFor(name: String): ProviderPool? {
    if (poolCache.containsKey(name)) return poolCache[name]
    val pool = try {
        onProvider(name, null) {
            val ready = preset(name).fmt != "cli" && cfgBase().isNotBlank() && (!needsKey() || cfgKey().isNotEmpty())
            if (!ready) return@onProvider null
            val saved = savedModelOf(name)
            val probed = loadProbed()
            // Server lokal: cadangan hanya model terpilih. Mengalihkan ke model lokal lain memuatnya ke RAM dan berebut CPU dengan model utama.
            val models = poolModelsFor(name) {
                failoverModels(isLocalUrl(cfgBase()), saved) {
                    if (probed != null) probed.first + probed.second else
                        try { probeCandidates(name, fetchModels().filter { n -> skipWords.none { w -> n.contains(w, true) } }, 40) } catch (e: Exception) { emptyList() }
                }
            }
            if (saved.isBlank() && models.isEmpty()) null else ProviderPool(name, saved, models)
        }
    } catch (e: Exception) {
        null
    }
    poolCache[name] = pool
    return pool
}

/** Simpan batas konteks baru untuk penyedia/model aktif. False bila sudah tidak bisa dikecilkan lagi. */
private fun MainActivity.registerCtxLimit(e: ContextTooLargeException): Boolean {
    val current = ctxBudget
    val next = shrunkBudget(current, lastCtxChars, e.limitTokens, e.requestedTokens)
    if (next >= current) return false
    ctxCaps["${effectiveProv()}/${cfgModel()}"] = next
    // Catatan pemangkasan konteks tidak ditampilkan; pemangkasan dan percobaan ulang berjalan diam-diam.
    return true
}

private const val MAX_ROUNDS = 3
private val ROUND_WAIT_SEC = intArrayOf(10, 25, 45)

/** Peralihan model berjalan diam-diam di latar belakang: kartunya tidak ditampilkan. Kegagalan akhir tetap muncul sebagai pesan error biasa. */
@Suppress("UNUSED_PARAMETER")
private fun MainActivity.showFailover(t: FailoverTracker, last: Boolean = false) {
    if (last) failoverView = null
}

private fun MainActivity.applyTarget(c: FoCandidate, mainProv: String) {
    if (c.provider == mainProv) {
        fallbackProv = null
        fallbackModel = null
        modelOverride = c.model.takeIf { it != savedModelOf(mainProv) }
    } else {
        fallbackProv = c.provider
        fallbackModel = c.model
        modelOverride = null
    }
    updateModelLabel()
}

/**
 * Panggil model dengan retry. Kalau tetap gagal karena server sibuk / kuota habis / tidak terhubung, sistem otomatis
 * mencari AI yang bisa dipakai: model lain di penyedia yang sama, lalu penyedia lain yang API key-nya terdaftar (semua model
 * yang diketahui, dari yang terbaik). Bila semua sibuk, menunggu lalu mengulang sampai [MAX_ROUNDS] putaran. Begitu ada yang
 * berhasil, giliran dilanjutkan dan AI itu dipakai sampai tugas selesai. Kemajuan tampil di kartu peralihan.
 */
internal fun MainActivity.callModelRetry(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
    val histSize = synchronized(hist) { hist.size }
    val mainProv = prov
    fun runTargetOnce(): Hist? {
        val fp = fallbackProv
        return if (fp == null) callModelRetryOnce(key, onText, onFlush)
        else onProvider(fp, fallbackModel) { callModelRetryOnce(cfgKey(), onText, onFlush) }
    }
    // Ditolak karena terlalu besar (413): kecilkan konteks untuk model ini lalu ulangi, tanpa menyentuh pengguna.
    fun runTarget(): Hist? {
        var shrinks = 0
        var empties = 0
        while (true) {
            try {
                val r = runTargetOnce()
                if (r == null && !cancelled) {
                    // Respons kosong (tanpa teks dan tanpa alat), sering terjadi pada model gratis yang sibuk. Ulangi sekali,
                    // lalu anggap model ini gagal supaya jalur failover memilih model lain, bukan berhenti diam-diam.
                    if (++empties > MAX_EMPTY_RETRIES) throw EmptyResponseException("Model mengembalikan respons kosong")
                    for (i in 1..2) {
                        Thread.sleep(1000)
                        if (cancelled) throw Cancelled()
                    }
                    continue
                }
                return r
            } catch (e: ContextTooLargeException) {
                if (cancelled) throw Cancelled()
                val fp = fallbackProv
                val ok = if (fp == null) registerCtxLimit(e) else onProvider(fp, fallbackModel) { registerCtxLimit(e) }
                if (!ok || ++shrinks > MAX_CTX_SHRINKS) throw e
            }
        }
    }

    val first = try {
        return runTarget()
    } catch (e: Exception) {
        // Penyedia internet yang tidak menjawab (koneksi gagal/putus/timeout) juga dialihkan; server lokal tidak (ApiErrors.isUnreachable).
        val activeLocal = fallbackProv?.let { fp -> onProvider(fp, fallbackModel) { isLocalUrl(cfgBase()) } } ?: isLocalUrl(cfgBase())
        if (cancelled || e is Cancelled || preset(prov).fmt == "cli" || !(isFallbackWorthy(e) || isUnreachable(e, activeLocal))) throw e
        e
    }

    // Teks parsial dari percobaan pertama (sudah masuk riwayat) disimpan; dikembalikan bila semua cadangan gagal.
    val kept = synchronized(hist) { hist.subList(histSize, hist.size).toList() }
    val tracker = FailoverTracker(MAX_ROUNDS)
    val soft = HashSet<String>() // gagal sementara (sibuk): dicoba lagi di putaran berikutnya
    fun stateOf(e: Throwable): FoState = when {
        e is QuotaExhaustedException -> FoState.QUOTA
        e is TransientApiException || e is EmptyResponseException -> FoState.BUSY
        e is java.io.IOException -> FoState.OFFLINE
        else -> FoState.REJECTED
    }
    fun mark(p: String, m: String, e: Throwable) {
        val st = stateOf(e)
        tracker.failed(p, m, st)
        if (e is ModelGoneException) try { forgetGoneModel(p, m) } catch (ex: Exception) { logQuiet("ModelCall.kt", ex) }
        val id = "$p/$m"
        if (st == FoState.BUSY || st == FoState.OFFLINE) soft += id else hardFailed += id
    }
    fun trimHist() {
        synchronized(hist) { while (hist.size > histSize) hist.removeAt(hist.size - 1) }
        lastPartial = ""
    }

    val failingProv = fallbackProv ?: mainProv
    val failingModel = if (fallbackProv != null) fallbackModel.orEmpty() else cfgModel()
    mark(failingProv, failingModel, first)
    setStatus(IconView.CLOCK, "Mengalihkan AI otomatis")
    tracker.searching = true
    showFailover(tracker)
    fastFail = true
    val crossProviderOptIn = prefs.getBoolean("cross_provider_failover", false)
    val failoverAllowlist = prefs.getStringSet("failover_provider_allowlist", emptySet()).orEmpty()
    val confirmedCrossProviders = HashSet<String>()
    val deniedCrossProviders = HashSet<String>()
    try {
        for (round in 1..MAX_ROUNDS) {
            tracker.round = round
            tracker.searching = true
            showFailover(tracker)
            // F-13: secara bawaan hanya failover dalam penyedia utama yang sama; lintas vendor bersifat opt-in + allowlist.
            val pools = presets.map { it.name }
                .filter { it == mainProv || preset(it).fmt != "cli" }
                .filter { autoPickableFor(it, mainProv, isLocalProvider(it), crossProviderOptIn, failoverAllowlist) }
                .mapNotNull { poolFor(it) }
            val chain = buildChain(mainProv, pools, hardFailed + soft)
            for (c in chain) {
                if (cancelled) throw Cancelled()
                if (c.provider != mainProv) {
                    if (c.provider in deniedCrossProviders) continue
                    if (c.provider !in confirmedCrossProviders) {
                        val okSwitch = confirm(
                            msg = "Alihkan percakapan ke penyedia AI lain (${c.provider} · ${c.model})?",
                            desc = "Penyedia utama ($mainProv) gagal. Jika disetujui, system prompt, riwayat percakapan, dan gambar terpilih pada konteks ini akan dikirim ke ${c.provider}.",
                            force = true,
                            routine = false
                        )
                        if (!okSwitch) {
                            deniedCrossProviders += c.provider
                            continue
                        }
                        confirmedCrossProviders += c.provider
                    }
                }
                trimHist()
                tracker.trying(c.provider, c.model)
                showFailover(tracker)
                applyTarget(c, mainProv)
                try {
                    val r = runTarget()
                    tracker.ok()
                    showFailover(tracker, last = true)
                    return r
                } catch (e: Exception) {
                    if (e is Cancelled) throw e
                    if (cancelled) throw Cancelled()
                    mark(c.provider, c.model, e)
                }
            }
            if (round == MAX_ROUNDS) break
            if (chain.isEmpty() && soft.isEmpty()) break // semua sudah ditolak / kuota habis; menunggu tidak akan membantu
            for (sec in ROUND_WAIT_SEC[round - 1] downTo 1) {
                tracker.countdown = sec
                showFailover(tracker)
                Thread.sleep(1000)
                if (cancelled) throw Cancelled()
            }
            tracker.countdown = 0
            soft.clear()
        }
        tracker.giveUp()
        showFailover(tracker, last = true)
        resetFailover()
        synchronized(hist) { while (hist.size > histSize) hist.removeAt(hist.size - 1); hist.addAll(kept) }
        throw first
    } catch (e: Cancelled) {
        showFailover(tracker, last = true)
        throw e
    } finally {
        fastFail = false
    }
}

/**
 * Buka koneksi POST streaming. Untuk error sementara (429, 502, 503, 504, 529, atau jaringan putus)
 * otomatis dicoba lagi sampai 3 kali dengan jeda 2, 4, 8 detik (atau sesuai Retry-After / retryDelay, maks 20 detik; bila server minta lebih lama, langsung dialihkan ke AI lain).
 * Kuota harian habis tidak diulang (QuotaExhaustedException).
 */
internal fun MainActivity.openStream(url: String, headers: Map<String, String>, body: JSONObject): HttpURLConnection {
    checkUrl(url)
    if (!isLocalUrl(url)) return openStreamInner(url, headers, body)
    // Model lokal: nyalakan server Ollama bila mati, dan beri tahu pengguna bahwa menunggu itu wajar (HP membaca prompt di CPU).
    if (!ensureOllamaUp(url)) throw LocalServerDownException(LOCAL_DOWN_MSG)
    val snap = saveStatus()
    // Perkiraan token dan waktu tunggu; kecepatan dipelajari dari permintaan sebelumnya (LocalProgress.kt).
    val tokens = estimateTokens(body.toString().length)
    val oldTps = prefs.getFloat("local_tps", DEFAULT_LOCAL_TPS.toFloat()).toDouble()
    setStatus(IconView.CLOCK, "Model lokal membaca prompt", promptWaitDetail(tokens, oldTps))
    val t0 = System.nanoTime()
    try {
        val conn = openStreamInner(url, headers, body)
        // Header jawaban tiba bersama token pertama: selisih waktunya = waktu membaca prompt.
        val newTps = learnTps(oldTps, tokens, (System.nanoTime() - t0) / 1e9)
        if (newTps != oldTps) prefs.edit().putFloat("local_tps", newTps.toFloat()).apply()
        return conn
    } finally {
        restoreStatus(snap)
    }
}

private fun MainActivity.openStreamInner(url: String, headers: Map<String, String>, body: JSONObject): HttpURLConnection {
    val payload = body.toString().toByteArray()
    // Model lokal: batas baca menyesuaikan ukuran prompt dan kecepatan HP yang dipelajari (LocalProgress.kt).
    val localTimeout = localReadTimeoutMs(estimateTokens(payload.size), prefs.getFloat("local_tps", DEFAULT_LOCAL_TPS.toFloat()).toDouble())
    var attempt = 0
    while (true) {
        if (cancelled) throw Cancelled()
        var code: Int
        var err: String
        var retryAfter: Int? = null
        val c = URL(url.trim()).openConnection() as HttpURLConnection
        try {
            activeConn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 30000
            // Model berpikir/konteks besar bisa lama sebelum token pertama; model lokal di HP jauh lebih lambat.
            c.readTimeout = if (isLocalUrl(url)) localTimeout else 180000
            c.useCaches = false
            // Jangan pakai ulang koneksi lama: setelah tool berjalan lama (mis. build) koneksi idle sering sudah
            // dimatikan server/NAT dan menghasilkan "unexpected end of stream".
            c.setRequestProperty("Connection", "close")
            c.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(payload) }
            code = c.responseCode
            if (code < 400) { streamOpen = true; return c }
            err = readLimitedText(c.errorStream, 256 * 1024)
            retryAfter = c.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
            c.disconnect()
        } catch (e: java.io.IOException) {
            c.disconnect()
            if (cancelled) throw Cancelled()
            // Server lokal menolak koneksi: belum jalan. Langsung beri tahu, jangan mengulang 3 kali dengan jeda.
            if (shouldFailFast(url, e)) throw LocalServerDownException(LOCAL_DOWN_MSG, e)
            // Offline: tunggu koneksi pulih (percobaan tidak dihitung) daripada menghabiskan 3 percobaan lalu beralih ke AI lain.
            if (!isLocalUrl(url) && !fastFail && !NetState.online && awaitNetwork()) continue
            if (attempt >= openRetries()) {
                throw java.io.IOException(
                    "Tidak bisa terhubung ke server AI setelah ${openRetries()} percobaan (${e.message ?: e.javaClass.simpleName}). " +
                        (if (isLocalUrl(url)) "Server model lokal belum jalan: buka Pengaturan, Siapkan Termux, lalu Jalankan server."
                        else "Periksa sinyal/VPN, lalu kirim \"Lanjutkan\"."), e
                )
            }
            err = e.message ?: "koneksi gagal"
            code = -1
        }
        // Permintaan terlalu besar (413 dll): mengulang tanpa mengecilkan konteks selalu gagal. Naikkan ke pemanggil.
        if (code >= 400 && isContextTooLarge(code, err)) throw contextTooLarge(err)
        // Kuota harian/kredit habis: mengulang hanya membuang waktu dan kuota. Langsung beri tahu (atau beralih model).
        if (code in retryable && classifyApiError(code, "", err) == ApiErrorKind.QUOTA) {
            throw QuotaExhaustedException(quotaMessage(code, err))
        }
        if (code != -1 && code !in retryable) {
            throw when (classifyApiError(code, "", err)) {
                ApiErrorKind.QUOTA -> QuotaExhaustedException(quotaMessage(code, err))
                ApiErrorKind.MODEL -> ModelRejectedException("API $code: ${err.take(300)}")
                ApiErrorKind.GONE -> ModelGoneException("API $code: ${err.take(300)}")
                else -> RuntimeException("API $code: ${err.take(300)}")
            }
        }
        if (attempt >= openRetries()) throw TransientApiException("API $code: ${err.take(300)}")
        val hint = retryAfter ?: parseRetryDelaySec(err)
        if (code in retryable && retryHintTooLong(hint)) {
            throw TransientApiException("API $code: server meminta menunggu $hint dtk, beralih ke AI lain. ${err.take(200)}")
        }
        attempt++
        val wait = retryWaitSeconds(hint, attempt)
        val snap = saveStatus()
        setStatus(IconView.CLOCK, busyLine(busyKindOf(code, err), attempt - 1))
        for (sec in wait downTo 1) {
            Thread.sleep(1000)
            if (cancelled) throw Cancelled()
        }
        restoreStatus(snap)
    }
}

/** Nilai string dari [o]; kunci yang hilang atau bernilai null menjadi string kosong. */
internal fun jsonString(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k, "")

internal fun MainActivity.str(o: JSONObject, k: String): String = jsonString(o, k)

internal fun MainActivity.declList(): List<JSONObject> {
    val decls = tools.getJSONObject(0).getJSONArray("functionDeclarations")
    return (0 until decls.length()).map { decls.getJSONObject(it) }
}

/** Argumen alat dari teks JSON. Kosong menjadi `{}`; JSON rusak menjadi objek berisi `__error`. */
internal fun parseToolArgs(s: String, errorMessage: String): JSONObject =
    try { JSONObject(if (s.isBlank()) "{}" else s) } catch (e: Exception) { JSONObject().put("__error", errorMessage) }

internal fun MainActivity.parseArgs(s: String): JSONObject = parseToolArgs(s, truncMsg)

/** Argumen kosong pada respons yang terpotong dianggap rusak, bukan "tanpa argumen". */
internal fun toolArgsOf(s: String, truncated: Boolean, errorMessage: String): JSONObject =
    if (truncated && s.isBlank()) JSONObject().put("__error", errorMessage) else parseToolArgs(s, errorMessage)

internal fun MainActivity.argsOf(s: String, truncated: Boolean): JSONObject = toolArgsOf(s, truncated, truncMsg)

internal fun MainActivity.cliChat(userMsg: String) {
    val lg = cliLoginOf(prov)
    val status = if (lg != null) cliStatus(lg) else CliStatus.OK
    if (lg != null && (status == CliStatus.NOT_READY || status == CliStatus.NOT_LOGGED_IN)) {
        val detail = if (status == CliStatus.NOT_READY) {
            "CLI ${lg.name} belum siap atau bukan build yang kompatibel. Ketuk \"Masuk dengan ${lg.account}\" pada dialog yang terbuka untuk memasang atau memperbaikinya."
        } else {
            "Belum masuk dengan ${lg.account}. Ketuk \"Masuk dengan ${lg.account}\" pada dialog yang terbuka, lalu kirim pesanmu lagi."
        }
        runOnUiThread { showApiKeyDialog() }
        addMsg(AI, detail)
        return
    }
    var t = cfgBase().ifBlank { lg?.run ?: "gemini -p {prompt}" }
    // Hapus redirect lama yang mungkin sudah tersimpan dari versi sebelumnya; stderr berisi
    // diagnostik penting dari Gemini dan tidak boleh disembunyikan.
    var geminiLegacyTemplate: String? = null
    if (prov == "Gemini CLI (Google)") {
        val baseT = t
        val chosenModel = normalizeGeminiCliModel(pvOf(prov, "model"))
        val modeled = geminiCliCommandWithModel(t, chosenModel, quote = { sh(it) }, streamJson = geminiStreamJsonOk != false)
        if (modeled == null) {
            val msg = "Konfigurasi perintah Gemini CLI tidak valid. Gunakan format gemini -p {prompt}; model harus auto atau ID model tanpa spasi."
            addMsg(AI, msg)
            synchronized(hist) { hist.add(Hist(1, msg)) }
            saveHist()
            return
        }
        t = modeled
        geminiLegacyTemplate = geminiCliCommandWithModel(baseT, chosenModel, quote = { sh(it) }, streamJson = false)
    }
    if (!t.contains("{prompt}")) t += " {prompt}"
    val cliContextMsg = SessionContext.buildCliMessageWithHistory(synchronized(hist) { ArrayList(hist) }, userMsg)
    val cmd = t.replace("{prompt}", sh(deepCliPrompt(userMsg, deepOn(), isSimpleGreeting(userMsg))))
    // Dialog izin menampilkan perintah ringkas ($cmd); yang benar-benar dijalankan membawa konteks sesi lintas-AI dan panduan alur.
    val runCmdText = t.replace("{prompt}", sh(cliPromptFor(prov, cliContextMsg, deepOn(), isSimpleGreeting(userMsg))))
    synchronized(hist) { hist.add(Hist(0, userMsg)) }
    if (prov == "Codex (ChatGPT)" || (prov == "Gemini CLI (Google)" && geminiStreamJsonOk != false)) {
        // Status awal ringkas seperti video referensi; detail alat ditambahkan saat event App Server diterima.
        setStatus(KIND_DOTS, "Sedang dikerjakan…")
    } else {
        setStatus(IconView.TERMINAL, "Menjalankan CLI di Termux", "$ " + cmd.take(300))
    }
    val statusBeforeApproval = saveStatus()
    val approved = cliAllowedTpl == t || confirm(
        "Jalankan di Termux:\n\n$cmd", cmd, "Mengirim pesan Anda ke CLI di Termux",
        onAll = { cliAllowedTpl = t }, allLabel = "Ya, selalu", routine = false
    )
    // Pulihkan status kerja sesudah dialog izin; tanpa ini layar kosong tanpa indikator sampai event CLI pertama tiba.
    if (approved) restoreStatus(statusBeforeApproval)
    if (!approved) {
        if (cancelled) throw Cancelled()
        val msg = "Ditolak pengguna. CLI tidak dijalankan."
        addMsg(AI, msg)
        synchronized(hist) { hist.add(Hist(1, msg)) }
        saveHist()
        return
    }
    // Codex app-server mengirim delta agent_message secara nyata; stderr disimpan terpisah dari teks jawaban.
    val isCodex = prov == "Codex (ChatGPT)"
    // Gemini CLI memakai jalur yang sama (stream-json, satu objek per baris); CLI lama otomatis kembali ke mode biasa.
    var geminiStream = prov == "Gemini CLI (Google)" && geminiStreamJsonOk != false
    val engineTag = if (isCodex) "codex" else "gemini"
    val liveFile = if (isCodex || geminiStream) "$termuxTmp/kita_${engineTag}_stream_${java.util.UUID.randomUUID()}.txt" else null
    val errorFile = if (isCodex || geminiStream) "$termuxTmp/kita_${engineTag}_error_${java.util.UUID.randomUUID()}.txt" else null
    codexStreamOutputStarted = false
    if (isCodex || geminiStream) codexActivityUi.reset()
    val gemParser = GeminiStreamParser()
    val codexTextLock = Any()
    val codexFullText = StringBuilder()
    val codexEventBuffer = StringBuilder()
    val codexEventLock = Any()
    // Pembacaan aliran dari Termux macet (mis. aplikasi di latar belakang): beri tahu sekali, jangan diam.
    var stallNoted = false
    val onStreamStall: (Boolean) -> Unit = { stalled ->
        if (stalled && !stallNoted) {
            stallNoted = true
            addNote("Aliran jawaban dari Termux tersendat. Biarkan aplikasi tetap terbuka; jawaban tetap tampil utuh setelah selesai.")
        }
    }
    val tail = if (isCodex && liveFile != null) LiveTail(
        this, liveFile, pollMs = 100L, maxChars = 100000, incremental = true,
        deliverPendingOnStop = true, callbackOnMainThread = false, onStall = onStreamStall
    ) { chunk ->
        synchronized(codexEventLock) {
            codexEventBuffer.append(chunk)
            while (true) {
                val nl = codexEventBuffer.indexOf("\n")
                if (nl < 0) break
                val line = codexEventBuffer.substring(0, nl).trim()
                codexEventBuffer.delete(0, nl + 1)
                if (line.isEmpty()) continue
                try {
                    val event = JSONObject(line)
                    when (event.optString("type")) {
                        "delta" -> {
                            val textDelta = event.optString("text")
                            if (textDelta.isNotEmpty()) {
                                synchronized(codexTextLock) { codexFullText.append(textDelta) }
                                codexActivityUi.delta(textDelta)
                            }
                        }
                        "activity" -> event.optJSONObject("event")?.let { activityEvent ->
                            runOnUiThread { codexActivityUi.accept(activityEvent) }
                        }
                        "error" -> event.optString("message").takeIf { it.isNotBlank() }?.let { message ->
                            runOnUiThread { addNote("Codex: $message") }
                        }
                    }
                } catch (e: Exception) { logQuiet("ModelCall.kt", e) }
            }
        }
    } else if (geminiStream && liveFile != null) LiveTail(
        this, liveFile, pollMs = 100L, maxChars = 100000, incremental = true,
        deliverPendingOnStop = true, callbackOnMainThread = false, onStall = onStreamStall
    ) { chunk -> applyGeminiEvents(gemParser.feed(chunk)) } else null
    val runCmd = if (isCodex) {
        val js = """
const cp = require('child_process');
const GUIDE = "${CLI_NARRATION_GUIDE}";
const prompt = GUIDE + (process.argv[1] || '');
const cwd = process.cwd();
const child = cp.spawn('codex', ['app-server', '--listen', 'stdio://'], {stdio: ['pipe', 'pipe', 'inherit']});
let inputBuffer = '';
let threadId = '';
let answer = '';
let currentMessageId = '';
let currentMessageText = '';
let failed = '';
let finished = false;
function send(obj) {
  if (!child.stdin.destroyed) child.stdin.write(JSON.stringify(obj) + '\n');
}
function out(type, payload) {
  process.stdout.write(JSON.stringify(Object.assign({type: type}, payload || {})) + '\n');
}
function safeItem(item) {
  if (!item || typeof item !== 'object') return {};
  const safe = {};
  ['id','type','command','cwd','query','tool','server','status','exitCode','aggregatedOutput','output','path'].forEach(function (key) {
    if (item[key] !== undefined && item[key] !== null) safe[key] = item[key];
  });
  if (item.type === 'reasoning' && Array.isArray(item.summary)) {
    safe.summary = item.summary.map(function (part) {
      return typeof part === 'string' ? part : (part && typeof part.text === 'string' ? part.text : '');
    }).join(' ').slice(0, 240);
  }
  if (typeof safe.command === 'string') safe.command = safe.command.slice(0, 2200);
  if (typeof safe.aggregatedOutput === 'string') safe.aggregatedOutput = safe.aggregatedOutput.slice(0, 2400);
  if (typeof safe.output === 'string') safe.output = safe.output.slice(0, 2400);
  if (Array.isArray(item.changes)) safe.changes = item.changes.slice(0, 12).map(function (c) {
    return {path: c && c.path || '', kind: c && c.kind || ''};
  });
  return safe;
}
function activity(type, item) {
  out('activity', {event: {type: type, item: safeItem(item)}});
}
function emit(text) {
  if (typeof text !== 'string' || text.length === 0) return;
  answer += text;
  currentMessageText += text;
  out('delta', {text: text});
}
function textOf(item) {
  if (!item) return '';
  if (typeof item.text === 'string') return item.text;
  if (typeof item.message === 'string') return item.message;
  if (Array.isArray(item.content)) return item.content.map(function (part) {
    return part && typeof part.text === 'string' ? part.text : '';
  }).join('');
  return '';
}
function finish(message) {
  if (message && !failed) failed = String(message);
  if (finished) return;
  finished = true;
  try { child.stdin.end(); } catch (_) {}
}
function handle(msg) {
  if (msg.id === 1) {
    if (msg.error) { finish('Codex app-server gagal inisialisasi: ' + (msg.error.message || JSON.stringify(msg.error))); return; }
    send({method: 'initialized'});
    send({method: 'thread/start', id: 2, params: {cwd: cwd, approvalPolicy: 'never', sandbox: 'workspace-write'}});
    return;
  }
  if (msg.id === 2) {
    if (msg.error) { finish('Gagal memulai sesi Codex: ' + (msg.error.message || JSON.stringify(msg.error))); return; }
    threadId = msg.result && msg.result.thread && msg.result.thread.id || '';
    if (!threadId) { finish('Codex tidak mengembalikan ID sesi.'); return; }
    send({method: 'turn/start', id: 3, params: {threadId: threadId, cwd: cwd, approvalPolicy: 'never', input: [{type: 'text', text: prompt}]}});
    return;
  }
  if (msg.id === 3 && msg.error) {
    finish('Permintaan Codex ditolak: ' + (msg.error.message || JSON.stringify(msg.error)));
    return;
  }
  if (msg.method === 'item/started') {
    const params = msg.params || {};
    if (params.threadId && threadId && params.threadId !== threadId) return;
    const item = params.item || {};
    if (item.type === 'agentMessage' || item.type === 'agent_message') {
      currentMessageId = String(item.id || '');
      currentMessageText = '';
      activity('message_start', {id: currentMessageId});
    } else {
      activity('item_started', item);
    }
    return;
  }
  if (msg.method === 'item/agentMessage/delta') {
    const params = msg.params || {};
    if (!params.threadId || !threadId || params.threadId === threadId) emit(params.delta || '');
    return;
  }
  if (msg.method === 'item/completed') {
    const params = msg.params || {};
    if (params.threadId && threadId && params.threadId !== threadId) return;
    const item = params.item;
    if (item && (item.type === 'agentMessage' || item.type === 'agent_message')) {
      const full = textOf(item);
      // Cadangan hanya untuk item pesan ini; beberapa item agentMessage dapat muncul dalam satu giliran.
      if (currentMessageText.length === 0 && full) emit(full);
      else if (full && full.startsWith(currentMessageText)) emit(full.slice(currentMessageText.length));
    } else if (item) {
      activity('item_completed', item);
    }
    return;
  }
  if (msg.method === 'error') {
    const params = msg.params || {};
    const detail = params.error && params.error.message || params.message;
    if (detail) failed = String(detail);
    return;
  }
  if (msg.method === 'turn/completed') {
    const turn = msg.params && msg.params.turn || {};
    activity('turn_completed', {status: turn.status || ''});
    const status = turn.status || '';
    if (status && status !== 'completed' && status !== 'complete') {
      const detail = turn.error && turn.error.message || '';
      if (!failed) failed = detail || ('Giliran Codex berakhir dengan status: ' + status);
      else if (detail && failed.indexOf(detail) < 0) failed += ' — ' + detail;
    }
    finish(failed);
  }
}
child.stdout.setEncoding('utf8');
child.stdout.on('data', function (chunk) {
  inputBuffer += chunk;
  let nl;
  while ((nl = inputBuffer.indexOf('\n')) >= 0) {
    const line = inputBuffer.slice(0, nl);
    inputBuffer = inputBuffer.slice(nl + 1);
    if (!line.trim()) continue;
    try { handle(JSON.parse(line)); }
    catch (err) { failed = failed || ('Respons protokol Codex tidak valid: ' + err.message); }
  }
});
child.on('error', function (err) { failed = 'Tidak dapat menjalankan Codex app-server: ' + err.message; finish(failed); });
child.on('close', function (code) {
  if (inputBuffer.trim()) { try { handle(JSON.parse(inputBuffer)); } catch (_) {} }
  if (code !== 0) failed = failed || ('Codex app-server keluar dengan kode ' + code);
  if (!answer && !failed) failed = 'Codex tidak mengeluarkan teks jawaban.';
  if (failed) process.stderr.write(failed + '\n');
  process.exitCode = failed ? 1 : (code === null ? 1 : code);
});
send({method: 'initialize', id: 1, params: {clientInfo: {name: 'mythos_android', title: 'Mythos Android', version: '2.18'}}});
""".trimIndent()
        "node -e ${sh(js)} -- ${sh(deepCliPrompt(userMsg, deepOn(), isSimpleGreeting(userMsg)))}"
    } else if (prov == "Gemini CLI (Google)") {
        // RUN_COMMAND membuka bash non-interaktif; muat environment Termux agar API key yang
        // tersimpan di ~/.gemini/.env, ~/.bashrc, atau ~/.profile ikut terbaca.
        geminiEnvironmentSetupCommand() + "; " + runCmdText + " < /dev/null"
    } else "$cmd < /dev/null"
    if (liveFile != null && errorFile != null) {
        termux("rm -f ${sh(liveFile)} ${sh(errorFile)} ${sh("$liveFile.readchunk")}", limit = 100, timeoutSec = 10, useWorkdir = false, killable = false)
        tail?.start()
    }
    var out = try {
        termux(
            runCmd, limit = 100000, timeoutSec = if (isCodex || geminiStream) 900 else 300,
            liveFile = liveFile, liveStderrFile = errorFile
        )
    } finally {
        if (isCodex || geminiStream) tail?.finish() else tail?.stop()
    }
    if (geminiStream) applyGeminiEvents(gemParser.finish())
    // stderr aliran dibaca sekali di sini: dipakai untuk mendeteksi CLI lama dan untuk pesan galat.
    var errText = if ((isCodex || geminiStream) && errorFile != null) {
        termux(
            "if [ -f ${sh(errorFile)} ]; then cat ${sh(errorFile)}; fi",
            limit = 6000, timeoutSec = 10, useWorkdir = false, killable = false, silentRetry = true
        ).substringAfter("\n", "").trim().take(2500)
    } else ""
    if (geminiStream && geminiStreamUnsupported(errText, gemParser.text().isNotEmpty())) {
        // Gemini CLI lama tidak mengenal --output-format stream-json: ingat, lalu ulangi dengan mode biasa.
        geminiStreamJsonOk = false
        addNote("Gemini CLI di perangkat ini belum mendukung streaming; memakai mode biasa.")
        val legacy = geminiLegacyTemplate?.let { tpl ->
            (if (tpl.contains("{prompt}")) tpl else "$tpl {prompt}")
                .replace("{prompt}", sh(cliPromptFor(prov, cliContextMsg, deepOn(), isSimpleGreeting(userMsg))))
        }
        if (legacy != null) {
            codexActivityUi.reset()
            out = termux(geminiEnvironmentSetupCommand() + "; " + legacy + " < /dev/null", limit = 100000, timeoutSec = 300)
        }
        geminiStream = false
        errText = ""
    } else if (geminiStream && gemParser.sawJson) geminiStreamJsonOk = true
    val lines = out.lines()
    val exitLine = lines.firstOrNull { it.startsWith("exit=") }
    val exitCode = exitLine?.substringAfter("exit=")?.toIntOrNull()
    val ei = lines.indexOfFirst { it.startsWith("exit=") }
    val cliBody = (if (ei >= 0) lines.take(ei) + lines.drop(ei + 1) else lines).joinToString("\n").trim()
    if (exitCode != 0 || cliBody.isEmpty()) cliAuthCache.invalidate(prov)   // gagal: periksa ulang login pada pesan berikutnya
    if (isCodex || geminiStream) {
        // Biarkan teks yang masih diketik dan kejadian yang tertahan selesai mengalir dulu (maks. 90 dtk),
        // supaya penutup jawaban tidak terpotong dan melompat. Dihentikan segera bila pengguna membatalkan.
        val waitStart = System.currentTimeMillis()
        while (!cancelled && codexActivityUi.hasPendingWork() && System.currentTimeMillis() - waitStart < 90_000L) {
            Thread.sleep(120)
        }
        runOnUiThread { codexActivityUi.flushAll() }
    }
    val body = when {
        isCodex -> synchronized(codexTextLock) { codexFullText.toString() }
        geminiStream -> gemParser.text().ifEmpty { if (!gemParser.sawJson) cliBody else "" }
        else -> cliBody
    }
    val streamed = isCodex || geminiStream
    val engineName = if (isCodex) "Codex" else "Gemini"
    val shown = when {
        body.isNotEmpty() -> body
        geminiStream && gemParser.failed != null -> "Gemini gagal: ${gemParser.failed}".take(700)
        streamed && errText.isNotBlank() -> "$engineName gagal: ${errText.lineSequence().filter { it.isNotBlank() }.take(4).joinToString(" ").take(700)}"
        streamed && exitCode != null && exitCode != 0 -> "$engineName berhenti dengan kode $exitCode. Periksa status login dan koneksi di Pengaturan."
        cliLoginOf(prov) != null -> "CLI tidak menjawab. Mungkin belum masuk: buka Pengaturan lalu ketuk \"Masuk dengan ${cliLoginOf(prov)?.account}\"."
        else -> "(CLI tidak mengeluarkan teks)"
    }
    if (streamed && body.isNotEmpty()) {
        // Delta sudah ditampilkan sepanjang proses. Jangan menambahkan ulang seluruh jawaban setelah
        // item/tool dipisah menjadi beberapa pesan; gunakan body hanya sebagai fallback jika tidak ada delta.
        if (!codexStreamOutputStarted) streamUpdate(body)
        streamEnd()
        if (exitCode != null && exitCode != 0) addNote("$engineName berhenti dengan kode $exitCode; sebagian jawaban mungkin belum selesai.")
    } else addMsg(AI, shown)
    if (streamed && errorFile != null) {
        bg { try { termux("rm -f ${sh(errorFile)}", limit = 100, timeoutSec = 10, useWorkdir = false, killable = false) } catch (e: Exception) { logQuiet("ModelCall.kt", e) } }
    }
    showChips(shown)
    synchronized(hist) { hist.add(Hist(1, shown)) }
    saveHist()
}
