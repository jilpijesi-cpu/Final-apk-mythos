package com.build.mythos

import org.json.JSONArray
import org.json.JSONObject

// Streaming Gemini CLI seperti Codex: `gemini --output-format stream-json -p ...` mengeluarkan satu objek JSON per baris
// (init, message, tool_use, tool_result, error, result). Bagian MURNI ini mengubahnya jadi GeminiEvent; penyambung ke layar ada
// di GeminiStreamUi.kt, dan bentuk item alat sengaja dibuat sama dengan item Codex agar CodexActivityUi bisa memakainya.
// Diuji di GeminiStreamTest. Bentuk kejadian mengikuti dokumentasi Gemini CLI; baris yang bukan JSON diabaikan.

internal sealed class GeminiEvent {
    object SegmentStart : GeminiEvent()
    data class Text(val delta: String) : GeminiEvent()
    data class ToolStart(val item: JSONObject) : GeminiEvent()
    data class ToolEnd(val item: JSONObject) : GeminiEvent()
    data class Failure(val message: String) : GeminiEvent()
    data class Done(val ok: Boolean) : GeminiEvent()
}

/** null = belum diketahui, true = CLI mendukung stream-json, false = CLI lama (pakai mode biasa selama aplikasi hidup). */
@Volatile internal var geminiStreamJsonOk: Boolean? = null

/** CLI lama menolak flag baru dengan galat di stderr dan tanpa teks jawaban; saat itu ulangi dengan mode biasa. */
internal fun geminiStreamUnsupported(stderr: String, gotText: Boolean): Boolean {
    if (gotText) return false
    val s = stderr.lowercase()
    if (!s.contains("output-format") && !s.contains("stream-json")) return false
    return listOf("unknown", "unrecognized", "not recognized", "invalid", "not supported", "choices").any { s.contains(it) }
}

/** Ubah panggilan alat Gemini menjadi item berbentuk Codex (commandExecution, fileChange, webSearch, dynamicToolCall). */
internal fun geminiToolItem(id: String, name: String, p: JSONObject): JSONObject {
    fun first(vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> p.optString(k).takeIf { it.isNotBlank() } }.orEmpty()
    val item = JSONObject().put("id", id)
    return when (name) {
        "run_shell_command" -> item.put("type", "commandExecution").put("command", first("command"))
        "read_file" -> item.put("type", "commandExecution").put("command", "cat " + first("absolute_path", "file_path", "path"))
        "read_many_files" -> item.put("type", "commandExecution")
            .put("command", "cat " + (p.optJSONArray("paths")?.optString(0).orEmpty().ifBlank { first("include") }))
        "list_directory" -> item.put("type", "commandExecution").put("command", "ls " + first("path", "dir_path"))
        "glob" -> item.put("type", "commandExecution").put("command", "find " + first("pattern"))
        "search_file_content", "grep_search" -> item.put("type", "commandExecution").put("command", "grep " + first("pattern"))
        "write_file", "replace", "edit" -> item.put("type", "fileChange").put(
            "changes",
            JSONArray().put(JSONObject().put("path", first("file_path", "absolute_path", "path")).put("kind", if (name == "write_file") "add" else "update"))
        )
        "google_web_search", "web_search" -> item.put("type", "webSearch").put("query", first("query"))
        "web_fetch" -> item.put("type", "webSearch").put("query", first("url", "prompt"))
        else -> item.put("type", "dynamicToolCall").put("tool", name.ifBlank { "alat" })
    }
}

private const val MAX_STREAM_LINE_CHARS = 2 * 1024 * 1024

internal class GeminiStreamParser {
    private val buf = StringBuilder()
    private var droppingOversizeLine = false
    private val tools = HashMap<String, JSONObject>()
    private val text = StringBuilder()
    private var sawDelta = false
    private var segmentOpen = false
    private var needGap = false

    /** True bila minimal satu baris JSON terbaca; false berarti keluaran biasa (CLI mengabaikan flag). */
    @Volatile var sawJson = false
        private set
    @Volatile var failed: String? = null
        private set

    @Synchronized fun text(): String = text.toString()

    /** Masukkan potongan keluaran; kembalikan kejadian dari semua baris yang sudah lengkap. */
    @Synchronized fun feed(chunk: String): List<GeminiEvent> {
        val out = ArrayList<GeminiEvent>()
        var start = 0
        if (droppingOversizeLine) {
            val nl = chunk.indexOf('\n')
            if (nl < 0) return out
            droppingOversizeLine = false
            start = nl + 1
        }
        if (start < chunk.length) {
            if (buf.length + (chunk.length - start) > MAX_STREAM_LINE_CHARS && chunk.indexOf('\n', start) < 0) {
                buf.setLength(0)
                droppingOversizeLine = true
                return out
            }
            buf.append(chunk, start, chunk.length)
        }
        while (true) {
            val nl = buf.indexOf("\n")
            if (nl < 0) break
            if (nl <= MAX_STREAM_LINE_CHARS) {
                val line = buf.substring(0, nl).trim()
                if (line.isNotEmpty()) parseLine(line, out)
            }
            buf.delete(0, nl + 1)
        }
        if (buf.length > MAX_STREAM_LINE_CHARS) {
            buf.setLength(0)
            droppingOversizeLine = true
        }
        return out
    }

    /** Proses sisa baris terakhir yang tidak berakhir dengan newline. */
    @Synchronized fun finish(): List<GeminiEvent> {
        if (droppingOversizeLine) {
            droppingOversizeLine = false
            buf.setLength(0)
            return emptyList()
        }
        val rest = buf.toString().trim()
        buf.setLength(0)
        val out = ArrayList<GeminiEvent>()
        if (rest.isNotEmpty()) parseLine(rest, out)
        return out
    }

    private fun parseLine(line: String, out: MutableList<GeminiEvent>) {
        if (!line.startsWith("{")) return
        val o = try { JSONObject(line) } catch (e: Exception) { return }
        sawJson = true
        when (o.optString("type")) {
            "message" -> onMessage(o, out)
            "tool_use" -> onToolUse(o, out)
            "tool_result" -> onToolResult(o, out)
            "error" -> {
                val m = o.optString("message")
                val severity = o.optString("severity")
                if (m.isNotBlank() && severity != "warning") {
                    if (failed == null) failed = m
                    out.add(GeminiEvent.Failure(m))
                }
            }
            "result" -> {
                val status = o.optString("status")
                val ok = status.isEmpty() || status == "success"
                if (!ok && failed == null) failed = o.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "status: $status" }
                out.add(GeminiEvent.Done(ok))
            }
        }
    }

    private fun onMessage(o: JSONObject, out: MutableList<GeminiEvent>) {
        if (o.optString("role") != "assistant") return
        val content = o.optString("content").ifEmpty { o.optString("text") }
        if (content.isEmpty()) return
        if (o.optBoolean("delta", false)) sawDelta = true
        else if (sawDelta) return   // ringkasan penuh dari teks yang sudah mengalir: jangan ditampilkan dua kali
        if (!segmentOpen) {
            out.add(GeminiEvent.SegmentStart)
            segmentOpen = true
        }
        if (needGap && text.isNotEmpty() && !text.last().isWhitespace()) text.append("\n\n")
        needGap = false
        text.append(content)
        out.add(GeminiEvent.Text(content))
    }

    private fun onToolUse(o: JSONObject, out: MutableList<GeminiEvent>) {
        val id = o.optString("tool_id").ifEmpty { "gemini-tool-${tools.size + 1}" }
        val item = geminiToolItem(id, o.optString("tool_name"), o.optJSONObject("parameters") ?: JSONObject())
        tools[id] = item
        sawDelta = false
        segmentOpen = false
        needGap = text.isNotEmpty()
        out.add(GeminiEvent.ToolStart(item))
    }

    private fun onToolResult(o: JSONObject, out: MutableList<GeminiEvent>) {
        val id = o.optString("tool_id")
        val base = tools[id] ?: JSONObject().put("id", id.ifEmpty { "gemini-tool-0" }).put("type", "dynamicToolCall").put("tool", "alat")
        val ok = o.optString("status").let { it.isEmpty() || it == "success" }
        val item = JSONObject(base.toString())
        item.put("status", if (ok) "completed" else "failed")
        val output = o.optString("output").ifEmpty { o.optJSONObject("error")?.optString("message").orEmpty() }
        if (output.isNotEmpty()) item.put("output", output.take(2400))
        if (item.optString("type") == "commandExecution") item.put("exitCode", if (ok) 0 else 1)
        out.add(GeminiEvent.ToolEnd(item))
    }
}
