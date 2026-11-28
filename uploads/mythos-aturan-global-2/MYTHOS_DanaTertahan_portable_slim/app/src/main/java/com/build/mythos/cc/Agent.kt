package com.build.mythos.cc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

fun interface Approver {
    suspend fun approve(tool: String, summary: String): Boolean
    /** Persetujuan dengan [force] = true tidak boleh dilewati oleh mode otonom atau "Ya, semua". */
    suspend fun approve(tool: String, summary: String, force: Boolean): Boolean = approve(tool, summary)
}

/**
 * Mode izin ala Claude Code:
 *  DEFAULT      : Write/Edit/GithubBuild/Delete meminta persetujuan.
 *  ACCEPT_EDITS : Write/Edit otomatis; GithubBuild otomatis kecuali workflow baru/berubah; Delete tetap meminta.
 *  PLAN         : hanya alat baca; rencana diajukan lewat ExitPlanMode.
 */
enum class PermissionMode { DEFAULT, ACCEPT_EDITS, PLAN }

data class AgentConfig(
    val maxSteps: Int = 60,              // maksimum giliran model
    val maxFormatRepairs: Int = 3,
    val maxConsecutiveErrors: Int = 3,   // mulai memaksa refleksi
    val abortAfterErrors: Int = 7,       // berhenti agar tidak membakar token
    val contextBudgetChars: Int = 400_000,
    val keepRecentMessages: Int = 44,
    val maxObservationChars: Int = 24_000,
    val permissionMode: PermissionMode = PermissionMode.ACCEPT_EDITS,
    /** Sapaan untuk pengguna. Bawaan "BOS"; isi null untuk tanpa sapaan khusus seperti Claude Code. */
    val userAddress: String? = "BOS",
    /** Instruksi tambahan dari Pengaturan pengguna (opsional). */
    val extraInstructions: String? = null,
    /** Ingatkan memperbarui TodoWrite setelah sekian giliran alat tanpa pembaruan. */
    val todoReminderEvery: Int = 6,
    /** Izinkan tool calling native + streaming bila penyedia mendukung (dengan protokol teks sebagai cadangan otomatis). */
    val nativeTools: Boolean = true,
    /** Mode hanya baca: memblokir semua alat dengan efek WRITE/EXEC atau risiko WRITE/DANGEROUS di gerbang terpusat. */
    val readOnly: Boolean = false,
    /** Batas maksimum pemanggilan alat dalam satu balasan model (F-09). */
    val maxToolCallsPerTurn: Int = 8,
    /** Anggaran kumulatif maksimum pemanggilan alat per tugas (F-09). */
    val maxTotalToolCalls: Int = 120,
    /** Anggaran kumulatif maksimum aksi tulis/eksekusi per tugas (F-09). */
    val maxWriteActions: Int = 40,
)

/**
 * Siklus agen ala Claude Code:
 *   kumpulkan konteks -> bertindak (alat, boleh beberapa sekaligus) -> lihat hasil -> ulangi,
 * dan berhenti ketika model membalas TANPA pemanggilan alat (balasan itu adalah jawaban akhir).
 * Tidak ada gerbang yang menolak jawaban; kejujuran soal status pemeriksaan diatur lewat prompt, dan
 * flag `verified` pada Final hanya menandakan berkas sudah dibaca ulang dan/atau di-build (belum diuji di perangkat).
 */
class Agent(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val sandbox: ProjectSandbox,
    private val approver: Approver,
    private val ask: suspend (String) -> String,
    private val builder: BuildService? = null,
    private val cfg: AgentConfig = AgentConfig(),
    private val journal: TaskJournal? = null,
) {
    private class RunState(var mode: PermissionMode, val wantsDelivery: Boolean) {
        var step = 0
        var consecutiveErrors = 0
        var stateVersion = 0
        var compacted = false
        var calls = 0
        var idSeq = 0
        var sinceTodo = 0
        var nudges = 0
        var verifyNudges = 0
        var anyEdited = false
        var buildSucceeded = false
        var delivered = false
        var deliverNudges = 0
        var intactSteps = Int.MAX_VALUE          // langkah terbaru yang hasilnya masih utuh di konteks yang dikirim
        var approvedWorkflow: String? = null     // isi workflow yang sudah disetujui pengguna untuk dijalankan di GitHub
        var approvedWorkflowSha256: String? = null // SHA-256 digest workflow yang sudah disetujui (F-01/F-08)
        var totalToolCalls = 0                   // jumlah kumulatif pemanggilan alat pada tugas ini (F-09)
        var writeActions = 0                     // jumlah kumulatif aksi WRITE/EXEC pada tugas ini (F-09)
        var lastPlan: List<PlanStep> = emptyList()
        val dirty = linkedSetOf<String>()
        val findings = ArrayList<String>()       // catatan temuan yang tidak pernah dipadatkan
        val seen = HashMap<String, IntArray>()   // signature -> [stateVersion, step]
        val denied = HashSet<String>()
        val sigs = HashMap<String, String>()     // id panggilan -> thoughtSignature asli (Gemini), hanya untuk tugas ini
        val nativeSupport = NativeSupportState() // status penolakan native per tugas agar agen konkuren tidak saling menimpa
    }

    private data class Outcome(val ok: Boolean, val text: String, val countsAsError: Boolean)

    fun events(task: String, images: List<ImagePart> = emptyList()): Flow<AgentEvent> =
        channelFlow { execute(task, images) { send(it) } }

    suspend fun execute(task: String, emit: suspend (AgentEvent) -> Unit): AgentResult = execute(task, emptyList(), emit)

    suspend fun execute(task: String, images: List<ImagePart>, emit: suspend (AgentEvent) -> Unit): AgentResult {
        val st = RunState(cfg.permissionMode, wantsDelivery = userRequestedFileDelivery(task))
        val session = SessionState()
        return try {
            loop(task, images, st, session, emit)
        } catch (e: AgentAbort) {
            val msg = e.message ?: "Dihentikan"
            emit(AgentEvent.Failed(msg))
            AgentResult(false, msg, false, st.step)
        }
    }

    private fun loadMemory(): String {
        for (name in listOf("CLAUDE.md", "MYTHOS.md")) {
            val f = File(sandbox.root, name)
            if (f.isFile && f.length() < 200_000) return "Contents of $name (project instructions):\n" + clip(f.readText(), 6_000)
        }
        return ""
    }

    private fun recordFinding(st: RunState, entry: String) {
        val clean = oneLine(entry.trim(), MAX_FINDING_ENTRY_CHARS)
        if (clean.length < 12) return
        if (st.findings.lastOrNull() == clean || clean in st.findings) return
        st.findings.add(clean)
        while (st.findings.size > MAX_FINDINGS_COUNT || st.findings.sumOf { it.length } > MAX_FINDINGS_CHARS) {
            st.findings.removeAt(0)
        }
    }

    private fun statusNote(st: RunState): String = when {
        st.dirty.isEmpty() && st.buildSucceeded -> "sudah dibaca ulang dan build berhasil, belum diuji di perangkat"
        st.dirty.isEmpty() -> "sudah dibaca ulang, belum di-build dan belum diuji di perangkat"
        else -> "perubahan belum dibaca ulang (${st.dirty.joinToString(", ")}), belum diuji di perangkat"
    }

    private suspend fun loop(task: String, images: List<ImagePart>, st: RunState, session: SessionState, emit: suspend (AgentEvent) -> Unit): AgentResult {
        val env = ToolEnv(sandbox, session, ask, builder, journal)
        val planMode = cfg.permissionMode == PermissionMode.PLAN
        val system = Protocol.systemPrompt(tools, builder != null, builder?.hint.orEmpty(), cfg.userAddress, planMode = planMode, extraInstructions = cfg.extraInstructions)
        val nativeSystem = if (cfg.nativeTools) {
            Protocol.systemPrompt(tools, builder != null, builder?.hint.orEmpty(), cfg.userAddress, planMode = planMode, native = true, extraInstructions = cfg.extraInstructions)
        } else system
        val nativeSpecs = if (cfg.nativeTools) ToolSchemas.specs(tools) else emptyList()
        val hasLogcat = tools.get("Logcat") != null
        val crashHint = if (looksLikeCrashWithoutLog(task)) {
            if (hasLogcat) {
                "Pengguna melaporkan crash tanpa stack trace. Panggil alat Logcat terlebih dahulu untuk mengambil log crash asli sebelum menebak atau mengubah kode."
            } else {
                "Pengguna melaporkan crash/force-close TANPA logcat atau stack trace, dan alat Logcat belum tersedia di sesi ini. " +
                    "JANGAN menebak penyebab crash (misalnya langsung menebak OOM atau mengubah kode secara spekulatif). " +
                    "Segera minta pengguna menempelkan stack trace / logcat (bagian 'Tempel stack trace di sini', misalnya dari Termux: `logcat -d -b crash` atau `logcat -d *:E | tail -n 80`) sebelum mengusulkan perbaikan."
            }
        } else ""
        val history = mutableListOf<Msg>()
        history.add(
            Msg(
                Role.USER,
                Protocol.firstMessage(task, clip(renderTree(sandbox.root, 3), 6_000), loadMemory(), sandbox.root.path, crashHint),
                images
            )
        )

        while (true) {
            currentCoroutineContext().ensureActive()
            if (st.step >= cfg.maxSteps) {
                throw AgentAbort("Batas ${cfg.maxSteps} langkah tercapai sebelum tugas selesai. ${progress(st, session)}")
            }

            val reply = decide(TurnTemplate(system, nativeSystem, nativeSpecs), history, st, emit)
            st.step++

            // Model lemah sering menulis niat ("Saya akan mencari ...") lalu berhenti tanpa memanggil alat.
            // Itu bukan jawaban: tampilkan sebagai narasi dan minta ia benar-benar bertindak (maks. 2 kali per tugas).
            if (reply.calls.isEmpty() && st.nudges < MAX_NUDGES && looksLikeUnfulfilledIntent(reply.text)) {
                st.nudges++
                emit(AgentEvent.Thought(st.step, reply.text))
                history.add(Msg(Role.ASSISTANT, reply.text))
                history.add(
                    Msg(
                        Role.USER,
                        Protocol.reminder(
                            "You described an action but made no tool call, so your turn would end here. If you still need to act, make the tool call now, " +
                                "without announcing it again. If the task is complete, reply with the actual result instead of a plan."
                        )
                    )
                )
                continue
            }

            // Penjaga presisi: bila ada file yang baru ditulis/diubah (st.dirty) dan belum dibaca ulang,
            // minta agen membaca ulang isinya dulu (maks 1 kali) agar tidak berhenti dengan file terpotong/setengah jadi.
            if (reply.calls.isEmpty() && st.dirty.isNotEmpty() && st.verifyNudges < 1 && st.step < cfg.maxSteps - 1) {
                st.verifyNudges++
                if (reply.text.isNotBlank()) emit(AgentEvent.Thought(st.step, reply.text))
                history.add(Msg(Role.ASSISTANT, reply.text))
                history.add(
                    Msg(
                        Role.USER,
                        Protocol.reminder(
                            "File berikut baru ditulis/diubah tetapi belum dibaca ulang: ${st.dirty.joinToString(", ")}. " +
                                "Panggil Read pada file tersebut untuk memastikan isinya lengkap, tidak terpotong, dan sesuai permintaan pengguna. " +
                                "Jika perlu menyalin file utuh dari uploads/, gunakan alat Copy alih-alih mengetik ulang. Perbaiki bila kurang, lalu berikan jawaban akhir dengan status jujur (\"sudah dibaca ulang dan build berhasil, belum diuji di perangkat\" atau \"sudah dibaca ulang, belum di-build dan belum diuji di perangkat\")."
                        )
                    )
                )
                continue
            }

            // Penjaga serah-terima: HANYA aktif bila pengguna memang meminta file/arsip/paket hasil,
            // agen sudah mengubah berkas di folder privat, tetapi belum menyerahkan file (Present/Export/GithubBuild).
            if (reply.calls.isEmpty() && st.wantsDelivery && st.anyEdited && !st.delivered && st.deliverNudges < 1 && tools.get("Present") != null && st.step < cfg.maxSteps - 1) {
                st.deliverNudges++
                if (reply.text.isNotBlank()) emit(AgentEvent.Thought(st.step, reply.text))
                history.add(Msg(Role.ASSISTANT, reply.text))
                history.add(
                    Msg(
                        Role.USER,
                        Protocol.reminder(
                            "Pengguna meminta file/arsip hasil dan kamu sudah mengubah berkas proyek di folder privat aplikasi, tetapi belum menyerahkan file hasilnya. " +
                                "Kemas folder proyek yang sudah diperbaiki menjadi file .zip dengan alat Zip (dari folder proyek utuh, bukan salinan parsial), " +
                                "lalu panggil alat Present dengan path file .zip tersebut agar muncul kartu Buka / Unduh / Bagikan di chat, baru tutup dengan ringkasan singkat."
                        )
                    )
                )
                continue
            }

            // Tanpa pemanggilan alat = giliran selesai; teksnya adalah jawaban.
            if (reply.calls.isEmpty()) {
                val checked = st.dirty.isEmpty()
                if (st.anyEdited) {
                    emit(AgentEvent.Notice("Status perubahan: ${statusNote(st)}."))
                }
                emit(AgentEvent.Final(reply.text, checked, st.step))
                return AgentResult(true, reply.text, checked, st.step)
            }

            if (reply.text.isNotBlank()) {
                emit(AgentEvent.Thought(st.step, reply.text))
                if (reply.text.length >= 40 && !looksLikeUnfulfilledIntent(reply.text)) {
                    recordFinding(st, "[Langkah ${st.step}] ${reply.text}")
                }
            }
            history.add(Msg(Role.ASSISTANT, Protocol.render(reply.text, reply.calls)))

            val results = ArrayList<String>()
            var failed = 0
            var succeeded = 0
            var usedTodo = false
            val maxPerTurn = cfg.maxToolCallsPerTurn.coerceAtLeast(1)
            if (reply.calls.size > maxPerTurn) {
                emit(AgentEvent.Notice("Balasan model meminta ${reply.calls.size} alat sekaligus; dibatasi maksimal $maxPerTurn per giliran."))
            }
            for ((idx, call) in reply.calls.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (idx >= maxPerTurn) {
                    results.add(
                        Protocol.result(
                            call,
                            false,
                            "Tool call rejected: per-response limit ($maxPerTurn) exceeded. Split remaining actions into the next turn."
                        )
                    )
                    continue
                }
                if (st.totalToolCalls >= cfg.maxTotalToolCalls) {
                    throw AgentAbort("Batas total ${cfg.maxTotalToolCalls} pemanggilan alat tercapai sebelum tugas selesai. ${progress(st, session)}")
                }
                val o = runTool(call, st, env, emit)
                results.add(Protocol.result(call, o.ok, clip(o.text, cfg.maxObservationChars)))
                if (o.ok) succeeded++ else if (o.countsAsError) failed++
                if (call.name.equals("TodoWrite", ignoreCase = true)) usedTodo = true
            }

            if (succeeded > 0) st.consecutiveErrors = 0 else if (failed > 0) st.consecutiveErrors++
            if (st.consecutiveErrors >= cfg.abortAfterErrors) {
                throw AgentAbort("Terjebak: ${st.consecutiveErrors} kegagalan berturut-turut. ${progress(st, session)}")
            }
            if (session.plan != st.lastPlan) {
                st.lastPlan = session.plan
                emit(AgentEvent.PlanChanged(session.plan))
            }

            val notes = mutableListOf<String>()
            if (st.consecutiveErrors == 2) {
                notes.add("2 consecutive failures. Re-check your assumptions with Read/Grep before the next attempt; do not repeat the same call.")
            } else if (st.consecutiveErrors >= cfg.maxConsecutiveErrors) {
                notes.add(
                    "${st.consecutiveErrors} consecutive failures. Before acting again, state (1) exactly what failed, (2) 2-3 hypotheses for the root cause, " +
                        "(3) the cheapest tool call that discriminates between them. Do not repeat any earlier approach. If you are blocked on a decision, use AskUserQuestion."
                )
            }
            st.sinceTodo = if (usedTodo) 0 else st.sinceTodo + 1
            val open = session.plan.any { it.status == StepStatus.TODO || it.status == StepStatus.DOING }
            if (open && st.sinceTodo >= cfg.todoReminderEvery) {
                st.sinceTodo = 0
                notes.add("The TodoWrite tool has not been used recently. If the task still benefits from tracking, update the todo list. Current todos:\n" + renderPlan(session.plan))
            }
            val left = cfg.maxSteps - st.step
            if (left <= 3) notes.add("Only $left turns left. Re-read what you changed and finish with an honest status (\"sudah dibaca ulang dan build berhasil, belum diuji di perangkat\" or \"sudah dibaca ulang, belum di-build dan belum diuji di perangkat\"), or report precisely what remains.")

            val body = results.joinToString("\n") + if (notes.isEmpty()) "" else "\n" + Protocol.reminder(notes.joinToString("\n"))
            history.add(Msg(Role.USER, body))
        }
    }

    /** Minta balasan berikutnya dari model, dengan perbaikan format otomatis bila blok alat rusak. */
    private class TurnTemplate(val system: String, val nativeSystem: String, val tools: List<NativeTool>)

    /** Satu giliran model; pembaruan streaming diteruskan sebagai AgentEvent.Streaming (dibatasi ~7 kali/detik). */
    private suspend fun streamedTurn(req: TurnRequest, step: Int, emit: suspend (AgentEvent) -> Unit): TurnResult = coroutineScope {
        val updates = Channel<StreamUpdate>(Channel.CONFLATED)
        val pump = launch {
            var lastLabel = ""
            for (u in updates) {
                val label = streamLabel(u)
                if (label != lastLabel) { lastLabel = label; emit(AgentEvent.Streaming(step, label)) }
                delay(150)
            }
        }
        try {
            llm.turn(req) { updates.trySend(it) }
        } finally {
            updates.close()
            pump.cancel()
        }
    }

    /** Hasil native -> bentuk yang sama dengan hasil parsing teks, supaya sisa Agent tak perlu tahu jalur mana yang dipakai. */
    private fun fromStructured(r: TurnResult.Structured, st: RunState): Parsed {
        if (r.invalid != null) return Parsed.Invalid(r.invalid, r.truncated)
        // Penjaga yang sama dengan jalur teks: model tidak boleh menulis hasil alat sendiri, apa pun penyedianya.
        ReplyGuard.rejectReason(r.text, r.calls.size)?.let { return Parsed.Invalid(it) }
        val calls = r.calls.map { nc ->
            val id = "toolu_${++st.idSeq}"
            nc.signature?.let { st.sigs[id] = it }
            ToolCall(id, nc.name, nc.input)
        }
        return Parsed.Reply(ReplyGuard.clean(r.text).trim(), calls)
    }

    private suspend fun decide(tpl: TurnTemplate, history: MutableList<Msg>, st: RunState, emit: suspend (AgentEvent) -> Unit): Parsed.Reply {
        var repairs = 0
        while (true) {
            val packed = ContextManager.compactWithInfo(history, cfg.contextBudgetChars, cfg.keepRecentMessages, st.findings)
            val view = packed.view
            st.intactSteps = if (packed.compacted) packed.keep / 2 else Int.MAX_VALUE
            if (packed.compacted && !st.compacted) {
                st.compacted = true
                emit(AgentEvent.Notice("Konteks dipadatkan: hasil alat lama diringkas, catatan temuan tetap dipertahankan."))
            }
            st.calls++
            // Gambar mahal bila dikirim ulang di setiap langkah: hanya 6 panggilan pertama.
            val sendView = if (st.calls > MAX_IMAGE_CALLS && view.any { it.images.isNotEmpty() }) view.map {
                if (it.images.isEmpty()) it
                else Msg(it.role, it.text + "\n[lampiran gambar sudah ditampilkan pada langkah awal; rujuk catatan Anda sendiri]")
            } else view
            val result = try {
                streamedTurn(TurnRequest(tpl.system, tpl.nativeSystem, sendView, tpl.tools, st.sigs, st.nativeSupport), st.step + 1, emit)
            } catch (e: LlmException) {
                throw AgentAbort("Gagal memanggil model: ${e.message}")
            }
            // Beralih dari native ke protokol teks tidak boleh terjadi diam-diam: tampilkan alasan sebenarnya dari penyedia.
            st.nativeSupport.drainNotices().forEach { emit(AgentEvent.Notice(it)) }
            val native = result is TurnResult.Structured
            val raw = when (result) {
                is TurnResult.Raw -> result.text
                is TurnResult.Structured -> result.text
            }
            val parsed = when (result) {
                is TurnResult.Raw -> Protocol.parse(result.text) { "toolu_${++st.idSeq}" }
                is TurnResult.Structured -> fromStructured(result, st)
            }
            val problem: String? = when (parsed) {
                is Parsed.Reply -> if (parsed.calls.isEmpty() && parsed.text.isBlank()) "balasan kosong." else null
                is Parsed.Invalid -> parsed.reason
            }
            if (problem == null) return parsed as Parsed.Reply
            repairs++
            val truncated = parsed is Parsed.Invalid && parsed.truncated
            if (repairs > cfg.maxFormatRepairs) {
                throw AgentAbort(
                    if (truncated) "Balasan model terus terpotong oleh batas keluarannya (file terlalu besar untuk satu panggilan). Minta tugas dipecah menjadi file yang lebih kecil."
                    else "Model berulang kali membalas dengan format tidak valid: $problem"
                )
            }
            emit(
                AgentEvent.Notice(
                    if (truncated) "Balasan terpotong oleh batas keluaran model, meminta dipecah ($repairs/${cfg.maxFormatRepairs})."
                    else "Format balasan tidak valid, meminta perbaikan ($repairs/${cfg.maxFormatRepairs}): $problem"
                )
            )
            // Balasan yang memuat karangan tidak disimpan di riwayat: model berikutnya akan menirunya.
            val keep = if (ReplyGuard.isGuardReason(problem)) "" else raw
            val guardHint = if (ReplyGuard.isGuardReason(problem)) "\n" + ReplyGuard.REPAIR_HINT else ""
            history.add(Msg(Role.ASSISTANT, clip(keep.ifBlank { "(kosong)" }, 1_500)))
            history.add(
                Msg(
                    Role.USER,
                    Protocol.reminder(
                        if (truncated) TRUNCATED_HINT
                        else if (native) "Your last tool call could not be used: $problem\nResend it with valid arguments; to finish, reply with plain text and no tool call.$guardHint"
                        else "Your last reply could not be used: $problem\nResend it. Tool calls must be " +
                            "${Protocol.OPEN}{\"name\": \"<Tool>\", \"input\": {...}}${Protocol.CLOSE} with valid JSON; to finish, reply with plain text and no tool_use block.$guardHint"
                    )
                )
            )
        }
    }

    private suspend fun runTool(call: ToolCall, st: RunState, env: ToolEnv, emit: suspend (AgentEvent) -> Unit): Outcome {
        val tool = tools.get(call.name)
            ?: return Outcome(false, "Error: No such tool available: ${call.name}. Available tools: ${tools.names.joinToString()}.", true)
        val sig = signature(tool.name, call.input)

        val isMutating = tool.effect == Effect.WRITE || tool.effect == Effect.EXEC || tool.risk == Risk.WRITE || tool.risk == Risk.DANGEROUS
        if (cfg.readOnly && isMutating) {
            emit(AgentEvent.Notice("Mode hanya baca aktif: ${tool.name} diblokir."))
            return Outcome(false, "Read-only mode is active: ${tool.name} is blocked because it modifies files or triggers external actions.", false)
        }

        if (st.mode == PermissionMode.PLAN && (tool.risk != Risk.SAFE || tool.effect == Effect.WRITE || tool.effect == Effect.EXEC)) {
            return Outcome(false, "Plan mode is active: ${tool.name} is not allowed. Use read-only tools, then present your plan with ExitPlanMode.", false)
        }

        if (isMutating && st.writeActions >= cfg.maxWriteActions) {
            emit(AgentEvent.Notice("Batas aksi tulis/eksekusi (${cfg.maxWriteActions}) tercapai: ${tool.name} ditolak."))
            return Outcome(false, "Write/exec action budget (${cfg.maxWriteActions}) reached for this task. Stop modifying files and summarize your progress.", false)
        }

        if (tool.name == "ExitPlanMode") return exitPlanMode(call, tool, st, emit)

        if (sig in st.denied) {
            return Outcome(false, "The user already denied this exact action. Do not repeat it; propose an alternative or ask with AskUserQuestion.", false)
        }

        // Aksi identik di dunia yang tidak berubah = hasil identik; jangan buang giliran,
        // tetapi JANGAN dihitung sebagai error agar tidak memicu penghentian paksa 7 error berturut-turut.
        val dupWindow = minOf(cfg.keepRecentMessages / 2, st.intactSteps)
        val prev = st.seen[sig]
        if (tool.effect != Effect.META && prev != null && prev[0] == st.stateVersion && prev[1] >= st.step - dupWindow) {
            emit(AgentEvent.Notice("Aksi duplikat dilewati: ${tool.name}"))
            return Outcome(
                false,
                "Duplicate call skipped: the identical call already ran at turn ${prev[1]} and nothing has changed since, so the result is unchanged. " +
                    "Use the information already in context or <agent-findings>, or change your approach/arguments.",
                false
            )
        }

        val summary = tool.describe(call.input)
        // F-01 & F-08: GithubBuild dan workflow baru/berubah WAJIB disetujui tanpa bisa dilewati mode otonom (force=true),
        // tampilkan workflow lengkap (tanpa pemotongan 1.500 karakter) + SHA-256 digest + ringkasan berkas snapshot (F-11).
        val isGithubBuild = tool.name == "GithubBuild"
        val digest = if (isGithubBuild) workflowDigest(sandbox.root) else ""
        val digestSha = if (digest.isNotEmpty()) workflowSha256(digest) else ""
        val workflowChanged = digest.isNotEmpty() && (digest != st.approvedWorkflow || digestSha != st.approvedWorkflowSha256)
        val forceApproval = isGithubBuild || workflowChanged
        val needsApproval = tool.risk == Risk.DANGEROUS ||
            isGithubBuild ||
            (tool.risk == Risk.WRITE && st.mode == PermissionMode.DEFAULT) ||
            workflowChanged
        val shown = if (isGithubBuild) {
            val preview = snapshotPreview(sandbox.root)
            buildString {
                append(summary)
                if (preview.isNotBlank()) {
                    append("\n\n").append(preview)
                }
                if (digest.isNotEmpty()) {
                    append("\n\nSHA-256 Workflow: ").append(digestSha)
                    append("\n\nIsi lengkap workflow yang akan dijalankan di GitHub (bisa memakai secrets repo):\n")
                    append(digest)
                }
            }
        } else {
            summary
        }
        if (needsApproval && !approver.approve(tool.name, shown, forceApproval)) {
            st.denied.add(sig)
            emit(AgentEvent.Notice("Pengguna menolak: $summary"))
            return Outcome(false, "The user DENIED this action. Do not retry it. Propose a safer alternative or ask with AskUserQuestion.", false)
        }
        if (isGithubBuild) {
            // Ikat persetujuan pada digest workflow persis sebelum push; batalkan bila berubah di antara dialog dan eksekusi.
            val currentDigest = workflowDigest(sandbox.root)
            val currentSha = if (currentDigest.isNotEmpty()) workflowSha256(currentDigest) else ""
            if (currentDigest != digest || currentSha != digestSha) {
                emit(AgentEvent.Notice("Push dibatalkan: workflow berubah setelah disetujui."))
                return Outcome(false, "GithubBuild aborted: workflow files changed after approval (expected SHA-256 $digestSha, got $currentSha). Re-request approval.", false)
            }
            if (workflowChanged) {
                st.approvedWorkflow = digest
                st.approvedWorkflowSha256 = digestSha
            }
        }

        st.totalToolCalls++
        if (isMutating) st.writeActions++

        emit(AgentEvent.ToolStart(st.step, tool.name, summary))
        val t0 = System.nanoTime()
        val result = execTool(tool, call.input, env)
        val ms = (System.nanoTime() - t0) / 1_000_000
        emit(AgentEvent.ToolEnd(st.step, tool.name, result.ok, oneLine(result.text, 200), ms))

        st.seen[sig] = intArrayOf(st.stateVersion, st.step)
        if (tool.effect == Effect.EXEC || (tool.effect == Effect.WRITE && result.ok)) st.stateVersion++
        if (result.ok) {
            if (result.changed.isNotEmpty() || tool.name in setOf("Write", "Edit", "Delete", "Move", "Copy")) {
                st.anyEdited = true
                st.buildSucceeded = false
                if (result.changed.isNotEmpty()) {
                    recordFinding(st, "[Langkah ${st.step}] Mengubah berkas: ${result.changed.joinToString(", ")}")
                }
            }
            if (tool.name == "GithubBuild") {
                st.buildSucceeded = true
                recordFinding(st, "[Langkah ${st.step}] GithubBuild berhasil (sudah dibaca ulang dan build berhasil, belum diuji di perangkat).")
            }
            if (tool.name in setOf("Present", "Export", "GithubBuild")) st.delivered = true
            st.dirty.addAll(result.changed)
            st.dirty.removeAll(result.readPaths.toSet())
            if (result.verifies) st.dirty.clear()
        } else if (tool.name == "GithubBuild") {
            st.buildSucceeded = false
            recordFinding(st, "[Langkah ${st.step}] GithubBuild gagal: ${oneLine(result.text, 220)}")
        }
        return Outcome(result.ok, result.text, !result.ok)
    }

    private suspend fun exitPlanMode(call: ToolCall, tool: Tool, st: RunState, emit: suspend (AgentEvent) -> Unit): Outcome {
        if (st.mode != PermissionMode.PLAN) return Outcome(false, "Plan mode is not active; just continue with the task.", false)
        val plan = call.input.str("plan") ?: return Outcome(false, "Invalid input: parameter 'plan' is required.\nUsage: ${tool.doc}", true)
        emit(AgentEvent.ToolStart(st.step, tool.name, "ExitPlanMode (rencana diajukan)"))
        val ok = approver.approve(tool.name, plan)
        emit(AgentEvent.ToolEnd(st.step, tool.name, ok, if (ok) "rencana disetujui" else "rencana ditolak", 0))
        return if (ok) {
            st.mode = PermissionMode.ACCEPT_EDITS
            Outcome(true, "User has approved your plan. Plan mode is now off and you may modify files. Start with TodoWrite to track the steps, then implement.", false)
        } else {
            Outcome(false, "The user did not approve the plan. Revise it according to their feedback, or ask what to change with AskUserQuestion.", false)
        }
    }

    private suspend fun execTool(tool: Tool, args: JSONObject, env: ToolEnv): ToolResult = try {
        withTimeout(tool.timeoutMs) { tool.run(args, env) }
    } catch (e: TimeoutCancellationException) {
        ToolResult(false, "Timed out after ${tool.timeoutMs / 1000}s.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: ArgError) {
        ToolResult(false, "InputValidationError: ${e.message}\nUsage: ${tool.doc}")
    } catch (e: SecurityException) {
        ToolResult(false, "Permission denied: ${e.message}")
    } catch (e: Exception) {
        ToolResult(false, "Error (${e.javaClass.simpleName}): ${e.message}")
    } catch (e: StackOverflowError) {
        ToolResult(false, "Error: the pattern or input is too complex (stack overflow). Simplify the regex or narrow the search.")
    } catch (e: OutOfMemoryError) {
        ToolResult(false, "Error: out of memory while running ${tool.name}. Narrow the request (smaller file, tighter path or glob).")
    }

    private fun signature(tool: String, args: JSONObject): String {
        val keys = args.keys().asSequence().toList().sorted()
        return tool + "#" + keys.joinToString("|") { k -> "$k=${args.opt(k)}" }.hashCode()
    }

    private fun progress(st: RunState, session: SessionState): String {
        val parts = mutableListOf<String>()
        if (st.dirty.isNotEmpty()) parts.add("Perubahan belum dibaca ulang (${st.dirty.joinToString()}), belum diuji di perangkat.")
        if (session.plan.isNotEmpty()) parts.add("Rencana:\n" + renderPlan(session.plan))
        return parts.joinToString("\n")
    }
}

private const val MAX_IMAGE_CALLS = 6
private const val MAX_NUDGES = 2
private const val MAX_FINDINGS_COUNT = 30
private const val MAX_FINDINGS_CHARS = 8_000
private const val MAX_FINDING_ENTRY_CHARS = 320

private val FILE_DELIVERY_REQUEST = Regex(
    "\\b(zip|arsip|patch|paket|bundle|export|ekspor|unduh|download|kirimkan|serahkan|kemas|bungkus|" +
        "buatkan\\s+file|buat\\s+file|file\\s+hasil|dokumen|pdf|docx|xlsx|pptx|apk|full\\s+zip|kode\\s+sumber)\\b",
    RegexOption.IGNORE_CASE
)

private const val NEW_MSG_MARKER = "\nPesan baru dari pengguna:\n"

/** Mengambil pesan terbaru pengguna (mengabaikan riwayat giliran sebelumnya yang disisipkan SessionContext). */
internal fun latestUserMessage(task: String): String {
    val idx = task.lastIndexOf(NEW_MSG_MARKER)
    return (if (idx >= 0) task.substring(idx + NEW_MSG_MARKER.length) else task).trim()
}

/**
 * Mengembalikan true hanya jika pesan terbaru pengguna memang meminta pengiriman file/arsip/dokumen/paket hasil.
 * Pertanyaan singkat seperti "apakah perlu build ulang?" tidak memicu nudge Zip + Present.
 */
internal fun userRequestedFileDelivery(task: String): Boolean {
    val t = latestUserMessage(task)
    if (t.isEmpty()) return false
    return FILE_DELIVERY_REQUEST.containsMatchIn(t)
}

private val CRASH_REPORT = Regex(
    "\\b(crash|force\\s*close|fc|anr|keluar\\s+sendiri|langsung\\s+tertutup|mental\\s+saat|berhenti\\s+sendiri|has\\s+stopped|keeps\\s+stopping)\\b",
    RegexOption.IGNORE_CASE
)

private val HAS_STACK_TRACE = Regex(
    "(FATAL\\s+EXCEPTION|Caused\\s+by:|\\b[A-Za-z0-9_]+Exception\\b|\\b[A-Za-z0-9_]+Error:|\\bat\\s+[a-zA-Z0-9_.]+\\.[a-zA-Z0-9_<>$]+\\([^)]*\\)|[EW]/AndroidRuntime|Process:\\s+[a-zA-Z0-9_.]+,\\s*PID:|java\\.lang\\.[A-Z]|kotlin\\.[A-Z])",
    RegexOption.IGNORE_CASE
)

/**
 * Mengembalikan true jika pesan terbaru pengguna melaporkan crash/force-close tanpa stack trace atau logcat nyata.
 */
internal fun looksLikeCrashWithoutLog(task: String): Boolean {
    val latest = latestUserMessage(task)
    if (latest.isEmpty()) return false
    return CRASH_REPORT.containsMatchIn(latest) && !HAS_STACK_TRACE.containsMatchIn(task)
}

private val INTENT_STARTERS = listOf(
    "saya akan", "aku akan", "baik, saya akan", "baik saya akan", "baik, aku akan", "mari saya", "mari kita", "sekarang saya",
    "selanjutnya saya", "pertama, saya", "saya perlu", "saya coba", "saya mulai",
    "i will", "i'll", "let me", "let's", "now i", "next, i", "first, i", "i need to", "i am going to",
)

private val TRUNCATED_HINT = "Your last reply was cut off by the output limit (roughly 4,000 tokens), so its tool call was lost. " +
    "Do NOT resend the same large call. Split the work into small calls: Write only the first ~120 lines of the file now (a valid, self-contained prefix), " +
    "then add the next parts in later replies with Edit (anchor on the last line you wrote), or divide the code over several smaller files. " +
    "Keep every tool call under ~150 lines."

// Balasan yang jelas-jelas penutup / permintaan ke pengguna / menunggu bukan "niat yang belum dijalankan".
private val FINAL_START = Regex("^(selesai|beres|berhasil|done|completed|finished|all set)\\s*[.!]")
private val ASKS_USER = Regex(
    "\\b(saya|aku) (perlu|butuh|ingin) (tahu|mengetahui|informasi|konfirmasi|detail|penjelasan|petunjuk|klarifikasi)\\b|" +
        "\\b(tolong|mohon|silakan|please) (beri|berikan|kirim|kirimkan|jelaskan|konfirmasi|provide|tell|confirm|send)\\b|" +
        "\\b(beri tahu|beritahu|kabari) saya\\b|\\blet me know\\b|\\bi need (to know|more information|your)\\b"
)
private val WAITING = Regex("\\bmenunggu (instruksi|perintah|jawaban|konfirmasi|balasan|arahan|permintaan|respons)|\\b(siap membantu|standby)\\b|\\b(awaiting|waiting for|wait for) (your|further|new)\\b")

private val GREETING_PREFIX = Regex("^(halo|hai|hi|hello|oke|ok|baik|baiklah|siap|tentu)(\\s+(bos|boss|bro|kak|min))?\\s*[,.!:]\\s*")
private val INTENT_ANYWHERE = Regex("\\b(saya|aku|i)\\s+(akan|mau|coba|mulai|perlu|segera|will|am going to|need to)\\b|\\b(i'll|let me|let's|mari kita|mari saya)\\b")

/**
 * Balasan pendek yang hanya menyatakan niat (bukan hasil, bukan pertanyaan) dan tidak memanggil alat.
 * Sapaan di depan ("Halo BOS, saya akan ...") diabaikan, dan niat di kalimat mana pun pada balasan pendek ikut dihitung.
 */
internal fun looksLikeUnfulfilledIntent(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 600 || t.contains('?') || t.contains("```")) return false
    var s = t.lowercase()
    repeat(2) { s = s.replace(GREETING_PREFIX, "") }
    if (FINAL_START.containsMatchIn(s) || ASKS_USER.containsMatchIn(s) || WAITING.containsMatchIn(s)) return false
    if (INTENT_STARTERS.any { s.startsWith(it) } || s.endsWith(":")) return true
    return s.length <= 300 && INTENT_ANYWHERE.containsMatchIn(s)
}
