package com.build.mythos.cc

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/*
 * Tool calling native (+ streaming) di atas protokol teks yang sudah ada.
 *
 * Prinsip: riwayat percakapan tetap berupa TEKS kanonis (<tool_use>/<tool_result>), sehingga pemadatan konteks, pergantian
 * kunci/penyedia di tengah tugas, dan semua tes lama tidak berubah. Hanya lapisan transport yang berbeda: bila penyedia
 * mendukung, riwayat diubah menjadi pesan terstruktur (tool_calls / tool_use / functionCall), alat dikirim sebagai skema,
 * dan balasan diterima lewat streaming sebagai panggilan terstruktur (JSON dijamin utuh, tanpa parsing tag).
 * Bila penyedia menolak (400/404/422) permintaan native, klien otomatis mengulang dengan protokol teks dan mengingat bahwa
 * model itu tidak mendukung.
 */

data class NativeTool(val name: String, val description: String, val schema: JSONObject)

/** Satu panggilan alat terstruktur dari model (id ditetapkan Agent, bukan penyedia). */
data class NativeCall(val name: String, val input: JSONObject, val signature: String? = null)

/**
 * [systemText]/[systemNative]: dua versi prompt sistem (teks vs native) karena yang dipakai baru diketahui saat klien memilih jalur.
 * [tools] kosong = jangan pakai native.
 */
class TurnRequest(
    val systemText: String,
    val systemNative: String,
    val messages: List<Msg>,
    val tools: List<NativeTool>,
    /** id panggilan -> thoughtSignature asli dari model (Gemini). Per tugas, bukan global, supaya tak tertukar antar tugas. */
    val sigs: Map<String, String> = emptyMap(),
    /** State penolakan native per sesi/tugas agar agen konkuren tidak saling menimpa (bawaan: global untuk kompatibilitas). */
    val nativeSupport: NativeSupportState = NativeSupport.defaultState,
)

sealed interface TurnResult {
    /** Teks mentah: protokol teks, perlu di-parse dengan [Protocol.parse]. */
    data class Raw(val text: String) : TurnResult

    /**
     * Balasan terstruktur dari jalur native. [invalid] != null = ada panggilan alat yang argumennya bukan JSON utuh;
     * [truncated] = berhenti karena batas token.
     */
    data class Structured(
        val text: String,
        val calls: List<NativeCall>,
        val invalid: String? = null,
        val truncated: Boolean = false,
    ) : TurnResult
}

/** Keadaan balasan yang sedang mengalir: teks sejauh ini, dan alat yang sedang ditulis (bila ada) beserta argumen parsialnya. */
class StreamUpdate(val text: String, val toolName: String?, val toolArgs: String)

private val ARG_PATH = Regex("\"(?:file_path|path)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/** Label singkat untuk baris hidup di UI saat balasan mengalir. */
internal fun streamLabel(u: StreamUpdate): String {
    val tool = u.toolName ?: return if (u.text.isBlank()) "Berpikir…" else "Menyusun balasan…"
    val file = ARG_PATH.find(u.toolArgs)?.groupValues?.get(1)?.replace("\\/", "/")?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    return when {
        file != null && tool == "Write" -> "Menulis $file…"
        file != null && tool == "Edit" -> "Mengedit $file…"
        file != null && tool == "Read" -> "Membaca $file…"
        file != null && tool == "Delete" -> "Menghapus $file…"
        else -> "Menyiapkan $tool…"
    }
}

/**
 * Penyedia mana yang memakai jalur native. OpenAI-kompatibel hanya untuk host yang dikenal andal soal tool calling
 * (model kecil/lokal seperti Ollama lebih stabil dengan protokol teks). Daftar tolak dipelajari saat runtime.
 */
class NativeSupportState {
    private val denied = ConcurrentHashMap.newKeySet<String>()
    private val notices = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun enabled(cfg: ProviderConfig): Boolean {
        if (NativeSupport.key(cfg) in denied) return false
        return when (cfg.kind) {
            ProviderKind.ANTHROPIC, ProviderKind.GEMINI -> true
            ProviderKind.OPENAI_COMPATIBLE -> {
                val host = cfg.baseUrl.lowercase()
                NativeSupport.OPENAI_HOSTS.any { host.contains(it) }
            }
        }
    }

    /** Catat penolakan DAN alasannya. Beralih ke protokol teks tidak boleh terjadi diam-diam (lihat [drainNotices]). */
    fun deny(cfg: ProviderConfig, reason: String = "") {
        denied.add(NativeSupport.key(cfg))
        val why = reason.trim().take(300)
        notices.add(
            "Mode native ditolak oleh ${cfg.model}" + (if (why.isNotEmpty()) ": $why" else "") +
                ". Memakai protokol teks sampai tugas ini selesai."
        )
    }

    /** Pemberitahuan penolakan native yang belum ditampilkan; Agent menampilkannya ke pengguna. */
    fun drainNotices(): List<String> {
        val out = ArrayList<String>()
        while (true) out.add(notices.poll() ?: break)
        return out
    }

    internal fun reset() {
        denied.clear()
        notices.clear()
    }
}

object NativeSupport {
    internal val defaultState = NativeSupportState()
    internal val OPENAI_HOSTS = listOf(
        "api.openai.com", "api.groq.com", "api.mistral.ai", "integrate.api.nvidia.com", "api.deepseek.com",
        "openrouter.ai", "api.cohere.ai", "api.cohere.com", "api.together.xyz", "api.fireworks.ai", "api.x.ai",
    )

    internal fun key(cfg: ProviderConfig) = "${cfg.kind}|${cfg.baseUrl.lowercase().trimEnd('/')}|${cfg.model.lowercase()}"

    fun enabled(cfg: ProviderConfig): Boolean = defaultState.enabled(cfg)

    fun deny(cfg: ProviderConfig, reason: String = "") = defaultState.deny(cfg, reason)

    fun drainNotices(): List<String> = defaultState.drainNotices()

    /** Dipanggil di awal setiap tugas/uji: penolakan satu kali (mis. 400 sesaat) tidak boleh menghukum tugas berikutnya. */
    internal fun reset() = defaultState.reset()
}

// ---------------------------------------------------------------- skema alat

private fun sStr(desc: String? = null) = JSONObject().put("type", "string").also { if (desc != null) it.put("description", desc) }
private fun sInt(desc: String? = null) = JSONObject().put("type", "integer").also { if (desc != null) it.put("description", desc) }
private fun sBool(desc: String? = null) = JSONObject().put("type", "boolean").also { if (desc != null) it.put("description", desc) }
private fun sEnum(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))
private fun sArr(items: JSONObject) = JSONObject().put("type", "array").put("items", items)
private fun sObj(props: List<Pair<String, JSONObject>>, required: List<String> = emptyList()): JSONObject {
    val p = JSONObject()
    props.forEach { (k, v) -> p.put(k, v) }
    return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
}

object ToolSchemas {
    private val SCHEMAS: Map<String, JSONObject> by lazy {
        mapOf(
            "TodoWrite" to sObj(
                listOf(
                    "todos" to sArr(
                        sObj(
                            listOf(
                                "content" to sStr("What to do, imperative form"),
                                "status" to sEnum("pending", "in_progress", "completed"),
                                "activeForm" to sStr("Present continuous form shown while in progress"),
                            ),
                            listOf("content", "status")
                        )
                    )
                ),
                listOf("todos")
            ),
            "LS" to sObj(listOf("path" to sStr("Directory, default ."), "depth" to sInt("1-4, default 2"))),
            "Read" to sObj(
                listOf("file_path" to sStr("Path relative to the project"), "offset" to sInt("First line, default 1"), "limit" to sInt("Max lines, default 400")),
                listOf("file_path")
            ),
            "Write" to sObj(listOf("file_path" to sStr(), "content" to sStr("Complete file content"), "encoding" to sStr("Only \"base64\" for a binary file whose bytes are given as base64; omit for text")), listOf("file_path", "content")),
            "Edit" to sObj(
                listOf(
                    "file_path" to sStr(), "old_string" to sStr("Exact text to replace, without line-number prefixes"),
                    "new_string" to sStr("Replacement (empty to delete)"), "replace_all" to sBool("Replace every occurrence"),
                ),
                listOf("file_path", "old_string", "new_string")
            ),
            "Delete" to sObj(listOf("file_path" to sStr()), listOf("file_path")),
            "Glob" to sObj(listOf("pattern" to sStr("e.g. **/*.kt"), "path" to sStr("Directory, default .")), listOf("pattern")),
            "Grep" to sObj(
                listOf(
                    "pattern" to sStr("Regex (Java syntax)"), "path" to sStr(), "glob" to sStr("File name filter, e.g. *.kt"),
                    "output_mode" to sEnum("files_with_matches", "content", "count"),
                    "-i" to sBool("Ignore case"), "-n" to sBool("Show line numbers"),
                    "-C" to sInt("Context lines"), "-A" to sInt("Lines after"), "-B" to sInt("Lines before"),
                    "head_limit" to sInt("Max output lines"),
                ),
                listOf("pattern")
            ),
            "AskUserQuestion" to sObj(listOf("question" to sStr(), "options" to sArr(sStr())), listOf("question")),
            "ExitPlanMode" to sObj(listOf("plan" to sStr("Markdown plan")), listOf("plan")),
            "GithubBuild" to sObj(listOf("timeout_s" to sInt("60-1500, default 600"))),
            "Mkdir" to sObj(listOf("path" to sStr("Folder to create")), listOf("path")),
            "Move" to sObj(listOf("from" to sStr("Existing file or folder"), "to" to sStr("New path")), listOf("from", "to")),
            "Copy" to sObj(listOf("from" to sStr("Existing file or folder"), "to" to sStr("New path")), listOf("from", "to")),
            "Zip" to sObj(
                listOf("path" to sStr("File or folder to pack"), "output" to sStr("Zip file to create, e.g. dist/app.zip"), "overwrite" to sBool("Replace an existing output")),
                listOf("path", "output")
            ),
            "Unzip" to sObj(listOf("file_path" to sStr("Zip file"), "dest" to sStr("Destination folder, default: folder named after the zip")), listOf("file_path")),
            "Time" to sObj(emptyList()),
            "Calc" to sObj(listOf("expression" to sStr("Arithmetic expression, e.g. (12.5*8)/3")), listOf("expression")),
            "WebSearch" to sObj(listOf("query" to sStr("Search query, no personal data")), listOf("query")),
            "WebFetch" to sObj(listOf("url" to sStr("Full https:// address")), listOf("url")),
            "buat_gambar" to sObj(
                listOf(
                    "deskripsi" to sStr("Kalimat singkat tentang langkah yang sedang dikerjakan"),
                    "prompt" to sStr("Deskripsi visual rinci tentang gambar yang ingin dibuat"),
                    "rasio" to sStr("Opsional. Rasio aspek gambar, mis. 1:1, 16:9, 9:16, 4:3, 3:4")
                ),
                listOf("deskripsi", "prompt")
            ),
            "edit_gambar" to sObj(
                listOf(
                    "deskripsi" to sStr("Kalimat singkat tentang langkah yang sedang dikerjakan"),
                    "path" to sStr("Path file gambar sumber (relatif terhadap folder proyek atau path absolut)"),
                    "instruksi" to sStr("Instruksi perubahan yang diinginkan pada gambar")
                ),
                listOf("deskripsi", "path", "instruksi")
            ),
            "cari_leakosint" to sObj(
                listOf(
                    "deskripsi" to sStr("Kalimat singkat tentang langkah pencarian yang sedang dikerjakan"),
                    "kueri" to sStr("Kata kunci atau banyak baris (1 nama per baris, boleh pakai akhiran 2-4 digit nomor HP seperti 'JEPRI 02')"),
                    "limit" to sInt("Opsional. Batas hasil 100 sampai 10000"),
                    "lang" to sStr("Opsional. Kode bahasa hasil, mis. id atau en")
                ),
                listOf("kueri")
            ),
            "Export" to sObj(listOf("path" to sStr("File in the project"), "name" to sStr("Optional file name for the user")), listOf("path")),
            "MakeDocument" to sObj(
                listOf(
                    "path" to sStr("Output file; the extension decides the type: .docx .pdf .xlsx .pptx"),
                    "title" to sStr("Document title (docx, pdf)"),
                    "markdown" to sStr("Document text in light Markdown (docx, pdf)"),
                    "sheets" to sArr(sObj(listOf("name" to sStr(), "rows" to sArr(sArr(sStr()))), listOf("rows"))),
                    "slides" to sArr(sObj(listOf("title" to sStr(), "bullets" to sArr(sStr())), listOf("title"))),
                ),
                listOf("path")
            ),
            "Present" to sObj(listOf("paths" to sArr(sStr("File in the project")), "names" to sArr(sStr("Optional display name"))), listOf("paths")),
        )
    }

    /** Skema JSON untuk tiap alat di [registry]; alat tak dikenal diberi skema objek longgar. */
    fun specs(registry: ToolRegistry): List<NativeTool> = registry.tools.map {
        val desc = if (it is AppBridgeTool) it.nativeDescription else it.doc
        NativeTool(it.name, desc, it.schema ?: SCHEMAS[it.name] ?: sObj(emptyList()))
    }

    /** Gemini hanya menerima subset OpenAPI dengan tipe huruf besar. */
    internal fun forGemini(s: JSONObject): JSONObject {
        val o = JSONObject()
        s.optString("type").takeIf { it.isNotEmpty() }?.let { o.put("type", it.uppercase()) }
        s.optString("description").takeIf { it.isNotEmpty() }?.let { o.put("description", it) }
        s.optJSONArray("enum")?.let { o.put("enum", it) }
        s.optJSONObject("items")?.let { o.put("items", forGemini(it)) }
        s.optJSONObject("properties")?.let { props ->
            val np = JSONObject()
            for (k in props.keys()) np.put(k, forGemini(props.getJSONObject(k)))
            // Gemini menolak objek tanpa properti; alat tanpa argumen dikirim tanpa skema (lihat geminiTools).
            o.put("properties", np)
        }
        s.optJSONArray("required")?.takeIf { it.length() > 0 }?.let { o.put("required", it) }
        return o
    }
}

// ---------------------------------------------------------------- riwayat teks -> pesan terstruktur

internal sealed interface NMsg
internal class NUser(val text: String, val images: List<ImagePart>) : NMsg
internal class NAssistant(val text: String, val calls: List<NCall>) : NMsg
internal class NResults(val results: List<NResult>, val text: String) : NMsg
internal class NCall(val id: String, val name: String, val input: JSONObject)
internal class NResult(val id: String, val name: String, val isError: Boolean, val body: String)

/**
 * Mengubah riwayat teks kanonis menjadi pesan terstruktur. Panggilan ke-k pada pesan asisten dipasangkan dengan
 * <tool_result> ke-k pada pesan pengguna berikutnya (id diambil dari hasilnya). Pesan yang tak bisa dipasangkan
 * dikirim sebagai teks biasa, karena API native menolak tool_use tanpa tool_result.
 */
internal object NativeHistory {
    private val RESULT = Regex("<tool_result ([^>]*)>\\n?([\\s\\S]*?)\\n?</tool_result>")
    private val ATTR_ID = Regex("\\bid=\"([^\"]*)\"")
    private val ATTR_ERR = Regex("is_error=\"true\"")

    fun convert(messages: List<Msg>): List<NMsg> {
        val out = ArrayList<NMsg>()
        val used = HashSet<String>()
        var syn = 0
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (m.role == Role.ASSISTANT && m.text.contains(Protocol.OPEN)) {
                val parsed = Protocol.parse(m.text)
                val next = messages.getOrNull(i + 1)
                if (parsed is Parsed.Reply && parsed.calls.isNotEmpty() && next != null && next.role == Role.USER && next.text.contains("<tool_result")) {
                    val found = RESULT.findAll(next.text).toList()
                    if (found.isNotEmpty()) {
                        val rest = RESULT.replace(next.text, "").trim()
                        val calls = ArrayList<NCall>()
                        val results = ArrayList<NResult>()
                        parsed.calls.forEachIndexed { k, c ->
                            val f = found.getOrNull(k)
                            var id = f?.let { ATTR_ID.find(it.groupValues[1])?.groupValues?.get(1) }.orEmpty()
                            if (id.isBlank() || id in used) id = "toolu_h${++syn}"
                            used.add(id)
                            calls.add(NCall(id, c.name, c.input))
                            results.add(
                                if (f != null) {
                                    val unescaped = f.groupValues[2]
                                        .replace("<\\/tool_result>", "</tool_result>")
                                        .replace("<\\/system-reminder>", "</system-reminder>")
                                        .replace("<\\system-reminder>", "<system-reminder>")
                                    NResult(id, c.name, ATTR_ERR.containsMatchIn(f.groupValues[1]), unescaped)
                                } else {
                                    NResult(id, c.name, true, "(no result was recorded for this call)")
                                }
                            )
                        }
                        out.add(NAssistant(parsed.text, calls))
                        out.add(NResults(results, rest))
                        i += 2
                        continue
                    }
                }
            }
            out.add(if (m.role == Role.USER) NUser(m.text, m.images) else NAssistant(m.text, emptyList()))
            i++
        }
        return out
    }
}
