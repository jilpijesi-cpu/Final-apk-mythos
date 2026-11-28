package com.build.mythos

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.build.mythos.cc.Agent
import com.build.mythos.cc.AgentConfig
import com.build.mythos.cc.AgentEvent
import com.build.mythos.cc.Approver
import com.build.mythos.cc.GitHubBuildService
import com.build.mythos.cc.GitHubConfig
import com.build.mythos.cc.ImagePart
import com.build.mythos.cc.ProjectSandbox
import com.build.mythos.cc.ProviderConfig
import com.build.mythos.cc.Export
import com.build.mythos.cc.Present
import com.build.mythos.cc.Tool
import com.build.mythos.cc.ToolRegistry
import com.build.mythos.cc.BuatGambar
import com.build.mythos.cc.EditGambar
import com.build.mythos.cc.AppBridgeTool
import com.build.mythos.cc.CariLeakOsint
import com.build.mythos.cc.Effect
import com.build.mythos.cc.WebFetch
import com.build.mythos.cc.WebSearch
import com.build.mythos.cc.BusyKind
import com.build.mythos.cc.FailoverClient
import com.build.mythos.cc.KeyPool
import com.build.mythos.cc.KeySpec
import com.build.mythos.cc.LlmClient
import com.build.mythos.cc.busyLine
import com.build.mythos.cc.createClient
import com.build.mythos.cc.createRawClient
import com.build.mythos.cc.defaultMaxOutputTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.build.mythos.cc.ProviderKind as CcKind

// Agen ala Claude Code (mesin dari Mythos-v31, paket cc): Read/Write/Edit/Glob/Grep/LS/Delete/TodoWrite/AskUserQuestion/GithubBuild.
// Berkas kerja ada di penyimpanan aplikasi (bukan Termux), build lewat GitHub Actions, APK hasil build diunduh dan dipasang dari sini.
// Penyedia CLI (Codex, Gemini CLI) dan obrolan ringan tetap memakai jalur lama.

/** Konfigurasi penyedia dari Pengaturan aplikasi; null bila penyedia tidak didukung mesin ini (mis. CLI) atau belum lengkap. */
internal fun MainActivity.ccProvider(): ProviderConfig? {
    val kind = when (preset(prov).fmt) {
        "gemini" -> CcKind.GEMINI
        "openai" -> CcKind.OPENAI_COMPATIBLE
        "anthropic" -> CcKind.ANTHROPIC
        else -> return null
    }
    var base = cfgBase().trim().trimEnd('/')
    if (base.isEmpty()) return null
    // Preset 2.46 memuat versi di alamat dasar; mesin v31 menambahkannya sendiri untuk Gemini dan Anthropic.
    when (kind) {
        CcKind.GEMINI -> base = base.removeSuffix("/v1beta").removeSuffix("/v1")
        CcKind.ANTHROPIC -> base = base.removeSuffix("/v1")
        CcKind.OPENAI_COMPATIBLE -> {}
    }
    val model = cfgModel().trim()
    if (model.isEmpty()) return null
    return ProviderConfig(kind, base, cfgKey(), model, maxOutputTokens = defaultMaxOutputTokens(kind, base, model))
}

/** Cache KeyPool agar status cooldown & rotasi antar-kunci maupun antar-model aktif tetap terjaga sepanjang sesi. */
internal object CcKeyPoolCache {
    private var sig: String = ""
    private var pool: KeyPool? = null

    @Synchronized
    fun getOrCreate(cfg: ProviderConfig, keys: List<String>, models: List<String>): KeyPool {
        val effKeys = keys.ifEmpty { listOf(cfg.apiKey) }
        val effModels = models.ifEmpty { listOf(cfg.model) }
        val nextSig = "${cfg.kind}|${cfg.baseUrl}|${effModels.joinToString(",")}|${effKeys.joinToString("\u0000")}"
        val existing = pool
        if (existing != null && sig == nextSig) return existing
        // Urutan cepat per kunci: coba model aktif di kunci ini satu per satu; bila semua model di kunci ini kena batas, lanjut ke kunci berikutnya.
        val specs = ArrayList<KeySpec>(effModels.size * effKeys.size)
        effKeys.forEachIndexed { idx, k ->
            for (m in effModels) {
                specs.add(
                    KeySpec(
                        label = "#${idx + 1}",
                        kind = cfg.kind,
                        baseUrl = cfg.baseUrl,
                        apiKey = k,
                        model = m,
                        tokenParam = cfg.tokenParam,
                        temperature = cfg.temperature,
                    )
                )
            }
        }
        return KeyPool(specs, rotateEvery = 0, sticky = true).also {
            sig = nextSig
            pool = it
        }
    }

    @Synchronized
    fun clear() {
        sig = ""
        pool = null
    }
}

/** Membangun LlmClient dengan rotasi multi-kunci dan perpindahan otomatis ke model aktif hasil probe saat kena batas limit. */
internal fun MainActivity.ccClient(cfg: ProviderConfig, keys: List<String>, onBusy: (BusyKind, Int) -> Unit): LlmClient {
    val providerName = effectiveProv()
    val liveKeys = keys.filter { DeadKeyStore.reasonOf(this, it) == null }.ifEmpty { keys }
    val activeModels = activeModelsForFailover(cfg.model, loadProbed()?.first.orEmpty(), providerName)
    if (liveKeys.size <= 1 && activeModels.size <= 1) {
        val singleCfg = if (liveKeys.isNotEmpty() && liveKeys[0] != cfg.apiKey) cfg.copy(apiKey = liveKeys[0]) else cfg
        return FailoverClient(
            pool = CcKeyPoolCache.getOrCreate(singleCfg, liveKeys.ifEmpty { listOf(singleCfg.apiKey) }, activeModels),
            factory = ::createRawClient,
            onNotice = { msg -> setStatus(KIND_DOTS, msg, quiet = true) },
            noun = "kunci",
            onDeadKey = { deadKey, label, reason ->
                val left = removeDeadApiKeysFromPrefs(this, providerName, setOf(deadKey))
                CcKeyPoolCache.clear()
                addNote("⚠️ Kunci $label (${maskApiKey(deadKey)}) terdeteksi mati ($reason) dan langsung dihapus. " +
                    if (left > 0) "Tersisa $left kunci." else "Tidak ada kunci tersisa — tambahkan kunci baru di Hubungkan AI Anda.")
            },
        )
    }
    val pool = CcKeyPoolCache.getOrCreate(cfg, liveKeys, activeModels)
    return FailoverClient(
        pool = pool,
        factory = ::createRawClient,
        onNotice = { msg -> setStatus(KIND_DOTS, msg, quiet = true) },
        noun = if (activeModels.size > 1) "kunci/model" else "kunci",
        onDeadKey = { deadKey, label, reason ->
            val left = removeDeadApiKeysFromPrefs(this, providerName, setOf(deadKey))
            addNote("⚠️ Kunci $label (${maskApiKey(deadKey)}) terdeteksi mati ($reason) dan langsung dihapus. " +
                if (left > 0) "Tersisa $left kunci." else "Tidak ada kunci tersisa — tambahkan kunci baru di Hubungkan AI Anda.")
        },
    )
}

/** Layanan build GitHub dari token terenkripsi dan URL repo di Pengaturan; null bila belum diisi. */
internal fun MainActivity.ccBuilder(): GitHubBuildService? {
    val token = (Secret.decrypt(prefs.getString("github_token", "") ?: "") as? SecretDecryptResult.Success)?.value.orEmpty().trim()
    val ref = parseRepoLink(prefs.getString("github_repo_url", "").orEmpty())
        ?: parseRepoLink(prefs.getString("github_owner", "").orEmpty() + "/" + prefs.getString("github_repo", "").orEmpty())
    if (token.isEmpty() || ref == null) return null
    val cfg = GitHubConfig.parse(token, ref.owner + "/" + ref.repo) ?: return null
    return GitHubBuildService(cfg)
}

/** Folder proyek agen untuk chat ini, di penyimpanan aplikasi (chat samaran: cache, hilang bersama cache). */
internal fun MainActivity.ccProjectDir(): File = ccProjectDirFor(currentChatId(), incognito)

internal fun MainActivity.ccProjectDirFor(chatId: String, incognitoMode: Boolean): File {
    val id = sanitizeChatId(chatId)
    val base = if (incognitoMode) File(cacheDir, "cc_incognito") else File(filesDir, "cc_projects")
    return File(base, id)
}

/** Sapu folder proyek agen API yang chat-nya sudah tidak ada. Dilewati bila agen sibuk atau daftar chat kosong/tak terbaca. Hanya penyimpanan aplikasi, tanpa Termux. */
internal fun MainActivity.sweepOrphanCcProjects() {
    if (busy) return
    val known = try { listChats().map { it.id }.toSet() } catch (e: Exception) { return }
    if (known.isEmpty()) return
    val keep = known + prefs.getString("chat_id", "").orEmpty()
    val base = File(filesDir, "cc_projects")
    sweepOrphanDelivered(keep) // folder serah-terima chat yang sudah tidak ada
    val names = base.list()?.toList() ?: return
    for (n in orphanProjectDirs(names, keep)) {
        try { File(base, n).deleteRecursively() } catch (e: Exception) { logQuiet("CcBridge.kt", e) }
    }
}

/** Hapus folder proyek (termasuk lampiran) milik chat yang dihapus, setara penghapusan folder chat di Termux untuk agen CLI. */
internal fun MainActivity.ccDeleteProject(chatId: String) {
    try { ccProjectDirFor(chatId, false).deleteRecursively() } catch (e: Exception) { logQuiet("CcBridge.kt", e) }
    deleteDeliveredFor(chatId) // file yang diserahkan agen ke chat ini ikut dihapus
}

/** Pasangan (pesan pengguna, jawaban akhir) dari riwayat chat, paling lama di atas. Pesan sistem dan langkah alat dilewati. */
private fun MainActivity.ccPairs(): List<Pair<String, String>> =
    synchronized(hist) { SessionContext.extractPairs(hist) }

private fun ccBuildTask(pairs: List<Pair<String, String>>, text: String): String =
    SessionContext.buildTaskWithHistory(pairs, text)

/** Judul singkat lama: hanya dipakai memilih ikon langkah (kata kuncinya dicocokkan di ActionIcons). */
/** Saring kalimat pengantar basa-basi ("Saya akan...", "Baik, saya akan...", "I will...") sebelum alat, tanpa membuang temuan/error penting. */
private fun isFillerLeadIn(text: String): Boolean {
    val clean = text.trim()
    if (clean.length > 260 || clean.contains('\n')) return false
    val lower = clean.lowercase()
    val hasFinding = listOf("error", "gagal", "masalah", "ditemukan", "ternyata", "peringatan", "namun", "tetapi", "karena", "failed", "found", "issue", "however", "warning")
        .any { lower.contains(it) }
    if (hasFinding) return false
    return Regex("^(?:(?:baik|oke|tentu|siap|halo(?:\\s+[^,!.:]+)?)[,!.:\\s]+)?(?:saya\\s+(?:akan|sedang|mau)|aku\\s+(?:akan|sedang|mau)|mari\\s+(?:kita|saya)|i\\s+will|i'll|let\\s+me|i\\s+am\\s+going\\s+to)\\b").containsMatchIn(lower)
}

private fun ccIconDesc(tool: String, target: String): String = when (tool) {
    "Read" -> "Membaca $target"
    "Write" -> "Menulis $target"
    "Edit" -> "Mengubah $target"
    "Delete" -> "Menghapus $target"
    "Glob" -> "Mencari file"
    "Grep" -> "Mencari di dalam kode"
    "LS" -> "Melihat isi folder"
    "TodoWrite" -> "Memperbarui daftar tugas"
    "ExitPlanMode" -> "Mengajukan rencana untuk disetujui"
    "AskUserQuestion" -> "Bertanya kepada Anda"
    "GithubBuild" -> "Membangun APK di GitHub Actions"
    "Mkdir" -> "Membuat folder $target"
    "Move" -> "Memindahkan file"
    "Copy" -> "Menyalin file"
    "Zip" -> "Membuat file zip"
    "Unzip" -> "Mengekstrak file zip"
    "Time" -> "Memeriksa waktu"
    "Calc" -> "Menghitung"
    "WebSearch" -> "Mencari di web"
    "cari_leakosint" -> "Mencari di MYTHOS LeakOSINT"
    "WebFetch" -> "Membaca halaman web"
    "buat_gambar" -> "Membuat gambar"
    "edit_gambar" -> "Mengedit gambar"
    "Export" -> "Menyimpan file ke Download"
    "MakeDocument" -> "Membuat dokumen $target"
    "Present" -> "Menampilkan file di chat"
    in CONNECTOR_TOOL_NAMES -> connectorStatus(tool)
    in DANA_TOOL_NAMES -> danaStatus(tool)
    "berangkas_baca" -> "Membaca data dari berangkas aplikasi"
    "berangkas_salin" -> "Menyalin berkas dari berangkas aplikasi"
    else -> "Menjalankan $tool"
}

/** Nama/argumen setara di jalur lama, hanya agar ikon langkah (iconFor) dipilih sama seperti biasa. */
private fun ccIconArgs(tool: String): Pair<String, JSONObject> = when (tool) {
    "Write", "Edit", "MakeDocument" -> "write_file" to JSONObject()
    "GithubBuild" -> "build_apk" to JSONObject()
    "Time" -> "run_command" to JSONObject().put("command", "date")
    "Delete" -> "run_command" to JSONObject().put("command", "rm file")
    "Read" -> "run_command" to JSONObject().put("command", "cat file")
    "Grep" -> "run_command" to JSONObject().put("command", "grep pola")
    "Glob", "LS" -> "run_command" to JSONObject().put("command", "ls")
    "WebSearch" -> "cari_web" to JSONObject()
    "WebFetch" -> "baca_web" to JSONObject()
    "buat_gambar" -> "buat_gambar" to JSONObject()
    "edit_gambar" -> "edit_gambar" to JSONObject()
    in CC_BRIDGE_TOOL_NAMES -> tool to JSONObject()
    else -> "run_command" to JSONObject().put("command", tool)
}

private fun ccTarget(tool: String, summary: String): String {
    Regex("\"(?:file_path|path)\"\\s*:\\s*\"([^\"]*)\"").find(summary)?.let { return it.groupValues[1] }
    if (tool == "Write" || tool == "Edit" || tool == "Delete") return summary.removePrefix("$tool ").substringBefore(" (").trim()
    return ""
}

private fun ccActivityItem(id: String, tool: String, summary: String, target: String): JSONObject {
    val item = JSONObject().put("id", id).put("ccTitle", ccToolTitle(tool, target, summary))
    return when (tool) {
        "GithubBuild" -> item.put("type", "commandExecution").put("command", "GitHub Actions build")
        "Write", "Edit", "Delete" -> item.put("type", "fileChange").put(
            "changes", JSONArray().put(JSONObject().put("path", target.ifBlank { summary.take(80) }).put("kind", "update"))
        )
        else -> item.put("type", "dynamicToolCall").put("tool", tool)
    }
}

/**
 * Jalankan satu tugas dengan agen ala Claude Code. Mengembalikan false (tanpa efek samping) bila penyedia tidak didukung,
 * supaya pemanggil memakai jalur lama. Dipanggil di thread latar milik startWorker; pembatalan lewat interupsi thread.
 */
internal fun MainActivity.ccAgentLoop(userMsg: String, addUser: Boolean, images: List<HistImage>, extra: String): Boolean {
    val cfg = ccProvider() ?: return false
    val dir = ccProjectDir().also { it.mkdirs() }
    val builder = ccBuilder()
    val pairs = ccPairs() // dibaca SEBELUM pesan baru masuk riwayat
    clearDeliveredFiles() // file serah-terima milik tugas yang dibatalkan jangan ikut menempel ke tugas ini

    val attNote = activeAttNoteText()
    // PDF lampiran dibaca otomatis saat dipilih, bukan lewat alat: tanpa ini logo PDF tidak pernah tampil untuk PDF yang diunggah.
    val pdfNames = pdfNamesInAttachmentBlock(extra)
    val pdfTitle = pdfReadingTitle(pdfNames)
    val task: String
    if (addUser) {
        task = ccBuildTask(pairs, userMsg) + extra + attNote
        synchronized(hist) { hist.add(Hist(0, userMsg + extra, images = images)) }
    } else {
        task = ccBuildTask(
            pairs,
            "Lanjutkan tugas sebelumnya dari titik terakhir. Permintaan awal: $userMsg. " +
                "Periksa dulu isi folder proyek, jangan mengulang langkah yang sudah berhasil."
        ) + attNote
        synchronized(hist) {
            if (hist.lastOrNull()?.role != 0) hist.add(Hist(0, SYS_PREFIX + "Lanjutkan tugas sebelumnya: $userMsg"))
        }
    }
    saveHist()
    if (builder == null && pairs.isEmpty()) {
        addNote("Build GitHub Actions belum diatur (Pengaturan: token dan URL repo GitHub): agen hanya bisa menulis kode, tanpa membangun APK.")
    }

    val parts = images.mapNotNull { img ->
        try {
            val e = encodedImage(img)
            ImagePart(e.mimeType, e.base64)
        } catch (e: Exception) {
            null
        }
    }

    // Izin: Write/Edit otomatis (mode ACCEPT_EDITS ala Claude Code); hapus file selalu bertanya;
    // GithubBuild dan workflow baru/berubah memakai force=true agar TIDAK bisa dilewati mode otonom (F-01).
    val approver = object : Approver {
        override suspend fun approve(tool: String, summary: String): Boolean = approve(tool, summary, force = false)
        override suspend fun approve(tool: String, summary: String, force: Boolean): Boolean =
            runInterruptible(Dispatchers.IO) { confirm("$tool: $summary", desc = "", routine = false, force = force) }
    }
    val ask: suspend (String) -> String = { text ->
        runInterruptible(Dispatchers.IO) {
            val lines = text.lines()
            val opts = lines.drop(1).mapNotNull { Regex("^\\d+\\. (.+)$").find(it)?.groupValues?.get(1) }
            askUser(if (opts.isEmpty()) text else lines.first(), opts)
        }
    }
    // Paket alat lengkap dalam-aplikasi: alat file/arsip/hitung/waktu ada di ToolRegistry.default; yang butuh jaringan atau Android disuntikkan di sini.
    val extraTools = listOf<Tool>(
        WebSearch { q -> ccWebSearch(q) },
        WebFetch { u -> ccWebFetch(u) },
        BuatGambar { d, p, r, root -> ccBuatGambar(d, p, r, root) },
        EditGambar { d, p, i, root -> ccEditGambar(d, p, i, root) },
        CariLeakOsint { q, lim, lng -> ccCariLeakOsint(q, lim, lng) },
        Export { f, n -> exportToDownloads(f, n) },
        Present { f, n -> deliverFile(f, n) },
    ) + ccBridgeTools()
    val sapaan = prefs.getString("sapaan", SAPAAN_BAWAAN)?.trim().takeUnless { it.isNullOrEmpty() }
    val extra = prefs.getString("extra", "")?.trim().takeUnless { it.isNullOrEmpty() }
    val agent = Agent(ccClient(cfg, cfgKeys()) { kind, n -> setStatus(KIND_DOTS, busyLine(kind, n), quiet = true) }, ToolRegistry.default(builder != null, extra = extraTools), ProjectSandbox(dir), approver, ask, builder, AgentConfig(userAddress = sapaan, extraInstructions = listOfNotNull(extra, ccBridgeRules().takeIf { it.isNotBlank() }).joinToString("\n").ifBlank { null }, readOnly = readOnlyMode()), null)

    var seq = 0
    // Selama model belum menulis teks atau memanggil alat, ia sedang membaca isi PDF lampiran: tampilkan logo PDF.
    var pdfPhase = pdfNames.isNotEmpty()
    // Jejak tampilan tugas ini (narasi + rangkaian langkah): disimpan bersama jawaban akhir agar pulih saat aplikasi dibuka ulang.
    val trail = JSONArray()
    var grpTitle = ""
    var grpIcon = -1
    var grpCount = 0
    val grpDetail = StringBuilder()
    fun closeGroup() {
        if (grpCount > 0) trail.put(JSONObject().put("k", "steps").put("title", grpTitle).put("icon", grpIcon).put("detail", grpDetail.toString().trim()))
        grpTitle = ""; grpIcon = -1; grpCount = 0; grpDetail.setLength(0)
    }
    var card: MainActivity.StepCard? = null
    var item: JSONObject? = null
    /** Kalimat pengantar terakhir yang model tulis sebelum alat; jadi label langkah bila pendek dan spesifik (pickStepTitle). */
    var lastThought: String? = null

    suspend fun handle(e: AgentEvent) {
        when (e) {
            is AgentEvent.Streaming ->
                if (pdfPhase) setStatus(KIND_LOGO_PDF, pdfTitle, quiet = true) else setStatus(KIND_DOTS, e.label, quiet = true)
            is AgentEvent.Thought -> {
                pdfPhase = false
                val t = sanitizeAssistantText(e.text)
                if (t.isNotBlank() && (seq == 0 || !isFillerLeadIn(t))) {
                    // Rangkaian alat sebelumnya selesai di sini: pasang barisnya DI ATAS narasi baru (urutan seperti aplikasi Claude).
                    emitActivity("group_end", JSONObject())
                    closeGroup()
                    trail.put(JSONObject().put("k", "text").put("t", t))
                    codexActivityUi.delta(t); endLiveSegment()
                }
                lastThought = t.takeIf { it.isNotBlank() }
            }
            is AgentEvent.PlanChanged -> {}
            is AgentEvent.ToolStart -> {
                pdfPhase = false
                val target = ccTarget(e.tool, e.summary)
                val modelTitle = pickStepTitle(e.summary, lastThought, "")
                val title = modelTitle.ifEmpty { ccToolTitle(e.tool, target, e.summary) }
                lastThought = null
                // Ikon mengikuti judul yang dibaca pengguna: teks model bila ada, selain itu deskripsi alat (tanpa nama berkas).
                val iconDesc = modelTitle.ifEmpty { ccIconDesc(e.tool, "") }
                val (iconName, iconArgs) = ccIconArgs(e.tool)
                val stepIcon = iconFor(iconName, iconArgs, iconDesc)
                // Judul baris langkah = judul yang sama dengan baris live (teks model bila ada); ikonnya ikut dikirim.
                val logoKind = logoKindFor(e.tool, logoTargetOf(e.tool, e.summary), e.summary)
                val started = ccActivityItem("cc-${++seq}", e.tool, e.summary, target)
                    .put("ccTitle", title).put("ccIcon", stepIcon)
                    .also { if (logoKind != null) it.put("ccLogo", logoKind) }
                item = started
                grpCount++; grpTitle = title; grpIcon = stepIcon
                grpDetail.append("$grpCount. $title")
                emitActivity("item_started", started)
                card = onUi {
                    val icon = iconFor(iconName, iconArgs, iconDesc)
                    // Baris status live: bila alat ini punya logo (Gmail/Telegram/WhatsApp/PDF/GitHub), titik berganti ke logonya.
                    setStatus(logoKind ?: icon, title, quiet = true)
                    newStepCard(title, e.summary, icon, iconName, target)
                }
            }
            is AgentEvent.ToolEnd -> {
                card?.finish(e.ok, e.preview.take(30), e.preview)
                card = null
                if (grpCount > 0) {
                    grpDetail.append(if (e.ok) " — Selesai" else " — Gagal")
                    val out = redactSecrets(e.preview.removePrefix(Cx.UNTRUSTED_OPEN).trimStart()).take(1200)
                    if (out.isNotBlank()) grpDetail.append("\n").append(out)
                    grpDetail.append("\n\n")
                }
                item?.let { started ->
                    emitActivity(
                        "item_completed",
                        JSONObject(started.toString())
                            .put("status", if (e.ok) "completed" else "failed")
                            .put("exitCode", if (e.ok) 0 else 1)
                            .put("output", redactSecrets(e.preview.removePrefix(Cx.UNTRUSTED_OPEN).trimStart()).take(2400))
                    )
                }
                item = null
                if (e.tool == "GithubBuild" && e.ok && builder != null) ccFetchAndOfferApk(builder)
            }
            is AgentEvent.Notice -> addNote(e.text)
            is AgentEvent.Final -> {
                runLog.finalText = e.summary
                endLiveSegment()
                val delivered = drainDeliveredFiles()
                closeGroup()
                synchronized(hist) { hist.add(Hist(1, e.summary, files = delivered, trail = trail.takeIf { it.length() > 0 })) }
                saveHist()
                clearAgentCheckpoint()
                // Rangkaian alat terakhir dipasang SEBELUM jawaban akhir (bukan sesudahnya).
                emitActivity("group_end", JSONObject())
                Thread.sleep(50)
                awaitVisualQueue()
                streamUpdate(e.summary)
                streamEnd()
                showFileCards(delivered) // file yang diserahkan agen muncul tepat di bawah jawabannya
                showChips(e.summary)
                if (!e.verified) addNote("Perubahan terakhir belum diverifikasi dengan build.")
                if (voiceTurnPending) {
                    voiceTurnPending = false
                    speakReply(e.summary)
                }
            }
            is AgentEvent.Failed -> {
                runLog.note = e.reason
                runLog.outcome = RunOutcome.ERROR
                endLiveSegment()
                addErrorNote(e.reason)
                val errText = friendlyErrorText(e.reason)
                awaitVisualQueue()
                streamUpdate("⚠️ $errText")
                streamEnd()
                val deliveredOnFail = drainDeliveredFiles()
                closeGroup()
                synchronized(hist) { if (hist.lastOrNull()?.role != 1) hist.add(Hist(1, "$FAILED_HIST_PREFIX: $errText", files = deliveredOnFail, trail = trail.takeIf { it.length() > 0 })) }
                val lastCp = loadAgentCheckpoint()
                saveAgentCheckpoint(AgentCheckpoint(agentTaskId, lastCp?.userMessage?.ifBlank { userMsg } ?: userMsg, lastCp?.turn ?: 0, lastCp?.lastTool.orEmpty(), "failed", e.reason))
                saveHist()
                showFileCards(deliveredOnFail) // file yang sempat diserahkan tetap bisa diunduh
            }
        }
    }

    if (pdfPhase) setStatus(KIND_LOGO_PDF, pdfTitle, quiet = true)
    runBlocking { agent.execute(task, parts) { ev -> handle(ev) } }
    if (cancelled) throw Cancelled()
    return true
}

/** Unduh APK dari artifact build yang sukses, periksa, lalu tampilkan tombol Pasang APK. */
private suspend fun MainActivity.ccFetchAndOfferApk(builder: GitHubBuildService) {
    setStatus(KIND_LOGO_GITHUB, "Mengunduh APK dari GitHub", quiet = true)
    val dir = File(cacheDir, "apk")
    dir.mkdirs()
    dir.listFiles()?.forEach { it.delete() }
    val f = File(dir, "app.apk")
    val err = builder.downloadApk(f)
    if (err != null) { addNote(err); return }
    val check = verifyApkFile(f)
    if (check.ok) addInstallCardFile(f)
    else addNote("APK berhasil diunduh tetapi tidak lolos pemeriksaan:\n" + apkCheckText(check))
}

/** Tombol Pasang APK untuk APK yang sudah ada di penyimpanan aplikasi (hasil unduhan GitHub, bukan dari Termux). */
internal fun MainActivity.addInstallCardFile(f: File) = runOnUiThread {
    val btn = TextView(this).apply {
        text = "Pasang APK"
        textSize = Typo.BODY
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = pressable(rounded(Palette.BRAND, 24))
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }
    btn.setOnClickListener {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Izinkan aplikasi ini memasang aplikasi, lalu ketuk Pasang APK lagi")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } else if (!f.isFile) {
            toast("Berkas APK sudah tidak ada. Bangun ulang.")
        } else {
            launchInstaller(f)
        }
    }
    insertBeforeStatus(btn, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(14) })
    scrollDown()
}

/** Jalankan pencarian MYTHOS LeakOSINT dari agen cc/ dan tampilkan hasilnya langsung di chat. */
private fun MainActivity.ccCariLeakOsint(query: String, limit: Int?, lang: String): Pair<Boolean, String> {
    val q = query.trim()
    if (q.isEmpty()) return false to "Parameter 'kueri' wajib diisi."
    val baseCfg = leakOsintConfig()
    if (baseCfg.token.isEmpty()) {
        return false to "Token API LeakOSINT belum diisi di Pengaturan > Konektor > LeakOSINT."
    }
    val cfg = baseCfg.copy(
        limit = LeakOsintEngine.clampLimit(limit ?: baseCfg.limit),
        lang = lang.trim().ifBlank { baseCfg.lang }
    )
    var openedConn: java.net.HttpURLConnection? = null
    val outcome = try {
        LeakOsintEngine.search(
            query = q,
            config = cfg,
            onConnectionOpen = { conn ->
                openedConn = conn
                activeConn = conn
            }
        )
    } finally {
        if (openedConn != null && activeConn === openedConn) activeConn = null
    }
    return when (outcome) {
        is LeakOsintOutcome.Error -> false to "Gagal mencari di LeakOSINT (${outcome.code}): ${outcome.message}"
        is LeakOsintOutcome.Success -> {
            val report = outcome.report
            addLeakOsintReportMsg(report)
            val summary = "${report.numOfDatabase} sumber · ${report.numOfResults} data"
            val aiBody = buildString {
                if (report.targetGroups.isNotEmpty()) {
                    append("Ringkasan hasil nyata per nama:\n")
                    for (g in report.targetGroups) {
                        append("- ${g.orderIndex}. ${g.label}: ${g.records.size} cocok\n")
                        for (rec in g.records.take(3)) {
                            append("  • ${rec.nama} | HP: ${rec.nomor} | Email: ${rec.email} | Sumber: ${rec.sourceName}\n")
                        }
                        if (g.records.size > 3) append("  • (+${g.records.size - 3} data lainnya sudah tampil di layar chat)\n")
                    }
                } else {
                    append(LeakOsintEngine.formatAllPagesMarkdown(report).take(6000))
                }
            }
            true to Cx.untrusted(
                "hasil pencarian LeakOSINT untuk \"$q\" ($summary)",
                aiBody.take(12_000)
            )
        }
    }
}


// ---------------------------------------------------------------- jembatan alat aplikasi untuk agen cc

/** Alat jalur lama yang dipakai ulang apa adanya oleh agen cc: konektor, kasus dana tertahan, berangkas. */
internal val CC_BRIDGE_TOOL_NAMES: Set<String> =
    (CONNECTOR_TOOL_NAMES + DANA_TOOL_NAMES + listOf("berangkas_baca", "berangkas_salin")).toSet()

/** Alat bridge yang hanya membaca; sisanya menulis sesuatu (kirim pesan, simpan kasus, salin berkas). */
private val CC_BRIDGE_READ_ONLY = setOf(
    "email_daftar", "email_baca", "telegram_baca", "whatsapp_baca", "kasus_dana_lihat", "kasus_dana_draf", "berangkas_baca"
)

/** Alat kirim menunggu dialog persetujuan pengguna di dalam alatnya, jadi diberi waktu lama. */
private const val CC_BRIDGE_SEND_TIMEOUT_MS = 10 * 60_000L
private const val CC_BRIDGE_TIMEOUT_MS = 90_000L
/** Unduh lampiran email (sampai 10 berkas, 15 MB masing-masing) butuh waktu lebih lama dari alat baca biasa. */
private const val CC_BRIDGE_DOWNLOAD_TIMEOUT_MS = 5 * 60_000L

/**
 * Satu alat cc untuk tiap deklarasi alat lama yang sedang aktif. [declList] sudah menyaring konektor yang belum dinyalakan
 * (alat email/Telegram/WhatsApp hanya ada bila konektornya terhubung), jadi daftar ini selalu cocok dengan kartu Hub Koneksi.
 */
internal fun MainActivity.ccBridgeTools(): List<com.build.mythos.cc.Tool> =
    declList().filter { it.getString("name") in CC_BRIDGE_TOOL_NAMES }.map { d ->
        val name = d.getString("name")
        val readOnly = name in CC_BRIDGE_READ_ONLY
        lateinit var tool: AppBridgeTool
        tool = AppBridgeTool(
            name = name,
            legacyDescription = d.optString("description"),
            schema = d.getJSONObject("parameters"),
            effect = if (readOnly) Effect.READ else Effect.WRITE,
            timeoutMs = when {
                name.endsWith("_kirim") || name == "whatsapp_balas" -> CC_BRIDGE_SEND_TIMEOUT_MS
                name == "email_lampiran" -> CC_BRIDGE_DOWNLOAD_TIMEOUT_MS
                else -> CC_BRIDGE_TIMEOUT_MS
            },
        ) { args -> ccRunBridged(name, args, tool.journal) }
        tool
    }

private fun MainActivity.ccRunBridged(name: String, args: JSONObject, journal: com.build.mythos.cc.TaskJournal? = null): Pair<Boolean, String> {
    val card = silentStepCard(name)
    val out = try {
        when (name) {
            in CONNECTOR_TOOL_NAMES -> runConnectorTool(name, args, card, journal)
            in DANA_TOOL_NAMES -> runDanaTool(name, args, card)
            "berangkas_baca" -> toolBerangkasBaca(args, card)
            "berangkas_salin" -> toolBerangkasSalinInApp(args, card, ccProjectDir())
            else -> null
        } ?: "exit=1 Alat tidak dikenal: $name"
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        "exit=1 ${e.message ?: e.javaClass.simpleName}"
    }
    // Pelaksana lama mengawali hasil sukses dengan "exit=0" dan gagal dengan "exit=1"; penolakan dimulai "Ditolak".
    val ok = out.startsWith("exit=0")
    return ok to out.removePrefix("exit=0").removePrefix("\n").ifBlank { "OK" }
}

/** Aturan prompt konektor dan dana tertahan yang sama dengan jalur lama (data tidak tepercaya, kirim hanya atas permintaan, dst.). */
internal fun MainActivity.ccBridgeRules(): String {
    val names = declList().map { it.getString("name") }.toSet()
    val sb = StringBuilder(connectorsPrompt().trim())
    if (names.any { it in DANA_TOOL_NAMES }) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(DANA_RULE) }
    if (sb.isNotEmpty()) sb.append('\n')
    sb.append(ToolDocs.ATURAN_KERJA_GLOBAL)
    return sb.toString().replace("ask_user", "AskUserQuestion").replace("cari_web", "WebSearch")
}
