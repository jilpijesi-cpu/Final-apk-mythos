package com.build.mythos.cc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

// ---------------------------------------------------------------- transport SSE

/** POST yang mengalirkan balasan (Server-Sent Events). [onData] dipanggil untuk tiap payload `data:` di thread IO. */
internal suspend fun postStream(url: String, headers: Map<String, String>, body: JSONObject, timeoutMs: Int, onData: (String) -> Unit) {
    withContext(Dispatchers.IO) {
        val conn = openConn(url)
        try {
            blockingIo(conn) {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20_000
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val text = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    throw LlmException("HTTP $code: ${text.take(500)}", code == 429 || code >= 500, code)
                }
                val data = StringBuilder()
                val other = StringBuilder()
                var sawData = false
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    var firstLine = true
                    while (true) {
                        var line = r.readLine() ?: break
                        if (firstLine) {
                            firstLine = false
                            line = line.removePrefix("\uFEFF")
                        }
                        when {
                            line.isEmpty() -> if (data.isNotEmpty()) { onData(data.toString()); data.setLength(0) }
                            line.startsWith("data:") -> {
                                sawData = true
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substring(5).removePrefix(" "))
                            }
                            line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:") -> Unit
                            sawData -> Unit
                            other.length < 1_000_000 -> other.append(line).append('\n')
                        }
                    }
                }
                if (data.isNotEmpty()) onData(data.toString())
                // Server yang mengabaikan stream=true membalas satu JSON biasa: perlakukan sebagai satu peristiwa.
                if (!sawData && other.isNotBlank()) onData(other.toString().trim())
            }
        } catch (e: IOException) {
            ensureActive()
            throw LlmException("Jaringan: ${e.message}", true)
        } finally {
            conn.disconnect()
        }
    }
}

private fun parseOrNull(s: String): JSONObject? = try { JSONObject(s) } catch (e: JSONException) { null }

private fun parseArgs(raw: String): JSONObject? {
    val t = raw.trim()
    if (t.isEmpty()) return JSONObject()
    return try { JSONObject(t) } catch (e: JSONException) { null }
}

/** Menyusun hasil dari panggilan yang sudah terkumpul; argumen yang bukan JSON utuh menandai balasan tidak valid. */
private fun structured(
    text: String,
    raw: List<Triple<String, String, JSONObject?>>,
    truncated: Boolean,
    signatures: List<String?> = emptyList(),
): TurnResult.Structured {
    val calls = ArrayList<NativeCall>()
    var invalid: String? = null
    for ((idx, item) in raw.withIndex()) {
        val (name, argText, obj) = item
        val input = obj ?: parseArgs(argText)
        when {
            name.isBlank() -> invalid = invalid ?: "nama alat kosong."
            input == null -> invalid = invalid ?: "argumen alat $name bukan JSON utuh${if (truncated) " (keluaran terpotong)" else ""}."
            else -> calls.add(NativeCall(name.trim(), input, signatures.getOrNull(idx)))
        }
    }
    return TurnResult.Structured(text, calls, invalid, truncated && invalid != null)
}

// ---------------------------------------------------------------- OpenAI-kompatibel

internal fun openAiNativeBody(cfg: ProviderConfig, system: String, messages: List<Msg>, tools: List<NativeTool>): JSONObject {
    val arr = JSONArray()
    arr.put(JSONObject().put("role", "system").put("content", system))
    for (n in NativeHistory.convert(messages)) {
        when (n) {
            is NUser -> {
                if (n.images.isEmpty()) {
                    arr.put(JSONObject().put("role", "user").put("content", n.text))
                } else {
                    val parts = JSONArray().put(JSONObject().put("type", "text").put("text", n.text))
                    n.images.forEach { im ->
                        parts.put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:${im.mime};base64,${im.base64}"))
                        )
                    }
                    arr.put(JSONObject().put("role", "user").put("content", parts))
                }
            }
            is NAssistant -> {
                val o = JSONObject().put("role", "assistant")
                if (n.calls.isEmpty()) {
                    o.put("content", n.text.ifBlank { "(kosong)" })
                } else {
                    if (n.text.isNotBlank()) o.put("content", n.text)
                    val tc = JSONArray()
                    n.calls.forEach { c ->
                        tc.put(
                            JSONObject().put("id", c.id).put("type", "function")
                                .put("function", JSONObject().put("name", c.name).put("arguments", unescapeSlashes(c.input.toString())))
                        )
                    }
                    o.put("tool_calls", tc)
                }
                arr.put(o)
            }
            is NResults -> {
                n.results.forEach { r ->
                    arr.put(JSONObject().put("role", "tool").put("tool_call_id", r.id).put("content", r.body.ifBlank { "(empty)" }))
                }
                if (n.text.isNotBlank()) arr.put(JSONObject().put("role", "user").put("content", n.text))
            }
        }
    }
    val toolsArr = JSONArray()
    tools.forEach {
        toolsArr.put(
            JSONObject().put("type", "function")
                .put("function", JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.schema))
        )
    }
    val body = JSONObject().put("model", cfg.model).put("messages", arr)
        .put("stream", true).put(cfg.tokenParam, cfg.maxOutputTokens)
    if (toolsArr.length() > 0) body.put("tools", toolsArr)   // tanpa alat (giliran polos): jangan kirim "tools": []
    cfg.temperature?.let { body.put("temperature", it) }
    return body
}

internal class OpenAiStream(private val onUpdate: (StreamUpdate) -> Unit) {
    private class Call { var id = ""; var name = ""; val args = StringBuilder() }

    private val text = StringBuilder()
    private val calls = ArrayList<Call>()
    private val byIndex = HashMap<Int, Call>()
    private var finish: String? = null

    fun feed(data: String) {
        if (data.trim() == "[DONE]") return
        val j = parseOrNull(data) ?: return
        j.optJSONObject("error")?.let { throw errorException(it) }
        val ch = j.optJSONArray("choices")?.optJSONObject(0) ?: return
        ch.str("finish_reason")?.takeIf { it.isNotBlank() }?.let { finish = it }
        val delta = ch.optJSONObject("delta") ?: ch.optJSONObject("message") ?: return
        delta.str("content")?.let { if (it.isNotEmpty()) text.append(it) }
        val tcs = delta.optJSONArray("tool_calls")
        if (tcs != null) {
            for (k in 0 until tcs.length()) {
                val tc = tcs.optJSONObject(k) ?: continue
                val id = tc.str("id").orEmpty()
                val idx = when {
                    tc.has("index") && !tc.isNull("index") -> tc.optInt("index")
                    id.isNotEmpty() -> calls.size
                    else -> maxOf(0, calls.size - 1)
                }
                val c = byIndex.getOrPut(idx) { Call().also { calls.add(it) } }
                if (id.isNotEmpty() && c.id.isEmpty()) c.id = id
                val fn = tc.optJSONObject("function") ?: continue
                fn.str("name")?.let { if (c.name.isEmpty()) c.name = it }
                fn.str("arguments")?.let { c.args.append(it) }
            }
        }
        publish()
    }

    private fun publish() {
        val last = calls.lastOrNull()
        onUpdate(StreamUpdate(text.toString(), last?.name?.takeIf { it.isNotEmpty() }, last?.args?.toString().orEmpty()))
    }

    fun result(): TurnResult.Structured =
        structured(text.toString(), calls.map { Triple(it.name, it.args.toString(), null) }, finish == "length")
}

// ---------------------------------------------------------------- Anthropic

/** Menambah giliran ke daftar pesan; giliran berurutan dengan peran sama digabung (Anthropic/Gemini mensyaratkan selang-seling). */
private fun pushTurn(into: JSONArray, role: String, blocks: List<JSONObject>) {
    val last = into.optJSONObject(into.length() - 1)
    if (last != null && last.optString("role") == role) {
        val c = last.getJSONArray("content")
        blocks.forEach { c.put(it) }
    } else {
        into.put(JSONObject().put("role", role).put("content", JSONArray(blocks)))
    }
}

internal fun anthropicNativeBody(cfg: ProviderConfig, system: String, messages: List<Msg>, tools: List<NativeTool>): JSONObject {
    val arr = JSONArray()
    fun text(t: String) = JSONObject().put("type", "text").put("text", t)
    for (n in NativeHistory.convert(messages)) {
        when (n) {
            is NUser -> {
                val blocks = ArrayList<JSONObject>()
                n.images.forEach { im ->
                    blocks.add(
                        JSONObject().put("type", "image")
                            .put("source", JSONObject().put("type", "base64").put("media_type", im.mime).put("data", im.base64))
                    )
                }
                blocks.add(text(n.text.ifBlank { "(kosong)" }))
                pushTurn(arr, "user", blocks)
            }
            is NAssistant -> {
                val blocks = ArrayList<JSONObject>()
                if (n.text.isNotBlank()) blocks.add(text(n.text))
                n.calls.forEach { c -> blocks.add(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", c.input)) }
                if (blocks.isEmpty()) blocks.add(text("(kosong)"))
                pushTurn(arr, "assistant", blocks)
            }
            is NResults -> {
                val blocks = ArrayList<JSONObject>()
                n.results.forEach { r ->
                    blocks.add(
                        JSONObject().put("type", "tool_result").put("tool_use_id", r.id)
                            .put("content", r.body.ifBlank { "(empty)" }).put("is_error", r.isError)
                    )
                }
                if (n.text.isNotBlank()) blocks.add(text(n.text))
                pushTurn(arr, "user", blocks)
            }
        }
    }
    val toolsArr = JSONArray()
    tools.forEach { toolsArr.put(JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.schema)) }
    val body = JSONObject().put("model", cfg.model).put("max_tokens", cfg.maxOutputTokens)
        .put("system", system).put("messages", arr).put("stream", true)
    if (toolsArr.length() > 0) body.put("tools", toolsArr)
    cfg.temperature?.let { body.put("temperature", it) }
    return body
}

internal class AnthropicStream(private val onUpdate: (StreamUpdate) -> Unit) {
    private class Block(val type: String, val name: String) { val sb = StringBuilder() }

    private val blocks = LinkedHashMap<Int, Block>()
    private var stop: String? = null

    fun feed(data: String) {
        val j = parseOrNull(data) ?: return
        when (j.optString("type")) {
            "content_block_start" -> {
                val cb = j.optJSONObject("content_block") ?: return
                val b = Block(cb.optString("type"), cb.optString("name"))
                if (b.type == "text") cb.str("text")?.let { b.sb.append(it) }
                blocks[j.optInt("index")] = b
            }
            "content_block_delta" -> {
                val b = blocks[j.optInt("index")] ?: return
                val d = j.optJSONObject("delta") ?: return
                when (d.optString("type")) {
                    "text_delta" -> if (b.type == "text") b.sb.append(d.optString("text"))
                    "input_json_delta" -> if (b.type == "tool_use") b.sb.append(d.optString("partial_json"))
                }
            }
            "message_delta" -> j.optJSONObject("delta")?.str("stop_reason")?.let { stop = it }
            "message" -> {
                j.str("stop_reason")?.let { stop = it }
                val content = j.optJSONArray("content") ?: return
                for (i in 0 until content.length()) {
                    val cb = content.optJSONObject(i) ?: continue
                    val type = cb.optString("type")
                    val b = Block(type, cb.optString("name"))
                    when (type) {
                        "text" -> cb.str("text")?.let { b.sb.append(it) }
                        "tool_use" -> cb.optJSONObject("input")?.let { b.sb.append(it.toString()) }
                    }
                    blocks[i] = b
                }
            }
            "error" -> throw streamError(j.optJSONObject("error"))
            else -> return
        }
        val text = blocks.values.filter { it.type == "text" }.joinToString("") { it.sb }
        val tool = blocks.values.lastOrNull { it.type == "tool_use" }
        onUpdate(StreamUpdate(text, tool?.name?.takeIf { it.isNotEmpty() }, tool?.sb?.toString().orEmpty()))
    }

    private fun streamError(err: JSONObject?): LlmException {
        val msg = err?.optString("message").orEmpty()
        val code = when (err?.optString("type")) {
            "overloaded_error" -> 529
            "rate_limit_error" -> 429
            "authentication_error" -> 401
            "permission_error" -> 403
            "not_found_error" -> 404
            "invalid_request_error" -> 400
            else -> -1
        }
        return LlmException("HTTP ${if (code > 0) code else 200}: ${msg.take(400)}", code == 429 || code >= 500 || code == -1, code)
    }

    fun result(): TurnResult.Structured {
        val text = blocks.values.filter { it.type == "text" }.joinToString("") { it.sb }
        val raw = blocks.values.filter { it.type == "tool_use" }.map { Triple(it.name, it.sb.toString(), null as JSONObject?) }
        return structured(text, raw, stop == "max_tokens")
    }
}

// ---------------------------------------------------------------- Gemini

/** Model yang pernah menolak thinkingConfig (HTTP 400); tidak dikirimi lagi selama aplikasi berjalan. */
private val geminiThinkingRejected: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

private fun geminiModelKey(model: String): String = model.lowercase().removePrefix("models/")

/**
 * Pengaturan berpikir per keluarga Gemini untuk jalur agen. Tanpa ini server memakai bawaannya dan tiap langkah agen
 * bisa menunggu puluhan detik sebelum token pertama. Gemini 3 dan 4 memakai thinkingLevel "medium"; Gemini 2.5 Pro/Flash
 * memakai thinkingBudget sedang. Varian gambar/suara/live dan Flash-Lite tidak dikirimi. Bila API menolak (400),
 * permintaan diulang tanpa pengaturan ini (lihat geminiNativeTurn). Penyedia lain tidak punya berpikir bawaan yang berat
 * atau tidak aman diberi parameter tambahan, jadi tidak diubah.
 */
internal fun geminiNativeThinking(model: String): JSONObject? {
    val m = geminiModelKey(model)
    if (!m.startsWith("gemini-")) return null
    if (listOf("image", "tts", "live", "audio", "embedding").any { m.contains(it) }) return null
    if (m in geminiThinkingRejected) return null
    return when {
        m.startsWith("gemini-4") || Regex("^gemini-[3-9]").containsMatchIn(m) -> JSONObject().put("thinkingLevel", "medium")
        m.startsWith("gemini-2.5") && m.contains("lite") -> null
        m.startsWith("gemini-2.5") && m.contains("pro") -> JSONObject().put("thinkingBudget", 8192)
        m.startsWith("gemini-2.5") && m.contains("flash") -> JSONObject().put("thinkingBudget", 4096)
        else -> null
    }
}

/** Nilai pengganti yang didokumentasikan Google untuk panggilan alat yang bukan berasal dari model ini (riwayat dipulihkan, dipadatkan, atau dari model lain). */
internal const val GEMINI_SKIP_SIGNATURE = "skip_thought_signature_validator"

private val GEMINI_NEEDS_SIGNATURE = Regex("^gemini-([3-9]|\\d{2,})")

/**
 * thoughtSignature untuk satu functionCall di riwayat. Gemini 3 ke atas menolak (400) panggilan alat tanpa tanda tangan
 * pada giliran berjalan. Tanda tangan asli dipakai bila ada; bila tidak (riwayat dipulihkan, dipadatkan, atau panggilan
 * pertama pada giliran model) dipakai nilai pengganti, hanya untuk keluarga yang mewajibkannya.
 */
internal fun geminiCallSignature(model: String, real: String?, first: Boolean): String? {
    if (!real.isNullOrBlank()) return real
    if (!first) return null
    return if (GEMINI_NEEDS_SIGNATURE.containsMatchIn(geminiModelKey(model))) GEMINI_SKIP_SIGNATURE else null
}

internal fun geminiNativeBody(
    cfg: ProviderConfig,
    system: String,
    messages: List<Msg>,
    tools: List<NativeTool>,
    sigs: Map<String, String> = emptyMap(),
): JSONObject {
    val contents = JSONArray()
    fun add(role: String, parts: List<JSONObject>) {
        val last = contents.optJSONObject(contents.length() - 1)
        if (last != null && last.optString("role") == role) {
            val p = last.getJSONArray("parts")
            parts.forEach { p.put(it) }
        } else {
            contents.put(JSONObject().put("role", role).put("parts", JSONArray(parts)))
        }
    }
    for (n in NativeHistory.convert(messages)) {
        when (n) {
            is NUser -> {
                val parts = ArrayList<JSONObject>()
                n.images.forEach { im -> parts.add(JSONObject().put("inline_data", JSONObject().put("mime_type", im.mime).put("data", im.base64))) }
                parts.add(JSONObject().put("text", n.text.ifBlank { "(kosong)" }))
                add("user", parts)
            }
            is NAssistant -> {
                val parts = ArrayList<JSONObject>()
                if (n.text.isNotBlank()) parts.add(JSONObject().put("text", n.text))
                n.calls.forEachIndexed { k, c ->
                    val part = JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.input))
                    geminiCallSignature(cfg.model, sigs[c.id], first = k == 0)?.let { part.put("thoughtSignature", it) }
                    parts.add(part)
                }
                if (parts.isEmpty()) parts.add(JSONObject().put("text", "(kosong)"))
                add("model", parts)
            }
            is NResults -> {
                val parts = ArrayList<JSONObject>()
                n.results.forEach { r ->
                    val resp = JSONObject().put(if (r.isError) "error" else "output", r.body.ifBlank { "(empty)" })
                    parts.add(JSONObject().put("functionResponse", JSONObject().put("name", r.name).put("response", resp)))
                }
                if (n.text.isNotBlank()) parts.add(JSONObject().put("text", n.text))
                add("user", parts)
            }
        }
    }
    val decls = JSONArray()
    tools.forEach {
        val d = JSONObject().put("name", it.name).put("description", it.description)
        val params = ToolSchemas.forGemini(it.schema)
        // Gemini menolak objek tanpa properti: alat tanpa argumen dikirim tanpa "parameters".
        if ((params.optJSONObject("properties")?.length() ?: 0) > 0) d.put("parameters", params)
        decls.put(d)
    }
    val gen = JSONObject().put("maxOutputTokens", cfg.maxOutputTokens)
    cfg.temperature?.let { gen.put("temperature", it) }
    geminiNativeThinking(cfg.model)?.let { gen.put("thinkingConfig", it) }
    val body = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        .put("contents", contents)
        .put("generationConfig", gen)
    if (decls.length() > 0) body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
    return body
}

internal class GeminiStream(private val onUpdate: (StreamUpdate) -> Unit) {
    private val text = StringBuilder()
    private val calls = ArrayList<NativeCall>()
    private var finish: String? = null

    fun feed(data: String) {
        val trimmed = data.trim()
        if (trimmed.startsWith("[")) {
            val arr = try { JSONArray(trimmed) } catch (e: JSONException) { return }
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { feedObject(it) }
            return
        }
        val j = parseOrNull(trimmed) ?: return
        feedObject(j)
    }

    private fun feedObject(j: JSONObject) {
        j.optJSONObject("error")?.let { throw errorException(it) }
        val cand = j.optJSONArray("candidates")?.optJSONObject(0) ?: return
        cand.str("finishReason")?.takeIf { it.isNotBlank() }?.let { finish = it }
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (k in 0 until parts.length()) {
                val p = parts.optJSONObject(k) ?: continue
                val fc = p.optJSONObject("functionCall")
                if (fc != null) {
                    val sig = p.optString("thoughtSignature", "").takeIf { it.isNotBlank() }
                    calls.add(NativeCall(fc.optString("name").trim(), fc.optJSONObject("args") ?: JSONObject(), sig))
                } else if (!p.optBoolean("thought", false)) {
                    p.str("text")?.let { text.append(it) }
                }
            }
        }
        val last = calls.lastOrNull()
        onUpdate(StreamUpdate(text.toString(), last?.name?.takeIf { it.isNotEmpty() }, last?.input?.toString().orEmpty()))
    }

    fun result(): TurnResult.Structured {
        val raw = calls.map { Triple(it.name, "", it.input as JSONObject?) }
        return structured(text.toString(), raw, finish == "MAX_TOKENS", calls.map { it.signature })
    }
}

// ---------------------------------------------------------------- jalur native per penyedia

/** Coba native; bila penyedia menolak bentuk permintaannya (400/404/422), ulangi dengan protokol teks dan ingat penolakannya. */
internal suspend fun nativeOrText(
    cfg: ProviderConfig,
    client: LlmClient,
    req: TurnRequest,
    native: suspend () -> TurnResult,
): TurnResult {
    if (req.tools.isEmpty() || !req.nativeSupport.enabled(cfg)) return TurnResult.Raw(client.complete(req.systemText, req.messages))
    try {
        return native()
    } catch (e: LlmException) {
        if (e.code != 400 && e.code != 404 && e.code != 422) throw e
        val raw = client.complete(req.systemText, req.messages)   // bila ini juga gagal, galatnya yang diteruskan (bukan salah native)
        req.nativeSupport.deny(cfg, e.message ?: "")
        return TurnResult.Raw(raw)
    }
}

/**
 * Giliran tanya-jawab polos (tanpa alat) yang mengalir. [stream] menjalankan jalur native dengan daftar alat kosong dan
 * melaporkan teks lengkap sejauh ini lewat `onUpdate`. Bila penyedia menolak bentuk permintaannya (400/404/422) SEBELUM ada
 * teks yang keluar, ulangi tanpa streaming supaya jawaban tetap datang. Teks yang sudah tampil tidak pernah ditimpa ulang.
 */
internal suspend fun plainStream(
    client: LlmClient,
    system: String,
    messages: List<Msg>,
    onText: (String) -> Unit,
    stream: suspend (onUpdate: (StreamUpdate) -> Unit) -> TurnResult,
): String {
    var emitted = false
    try {
        val r = stream { u -> if (u.text.isNotBlank()) { emitted = true; onText(u.text) } }
        val text = when (r) {
            is TurnResult.Structured -> r.text
            is TurnResult.Raw -> r.text
        }
        if (text.isBlank()) throw LlmException("Respons kosong dari model", true)
        return text
    } catch (e: LlmException) {
        if (emitted || (e.code != 400 && e.code != 404 && e.code != 422)) throw e
        val raw = client.complete(system, messages)   // bila ini juga gagal, galatnya yang diteruskan
        onText(raw)
        return raw
    }
}

internal suspend fun openAiNativeTurn(cfg: ProviderConfig, req: TurnRequest, onUpdate: (StreamUpdate) -> Unit): TurnResult {
    val headers = HashMap<String, String>()
    if (cfg.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
    val acc = OpenAiStream(onUpdate)
    postStream(cfg.baseUrl.trimEnd('/') + "/chat/completions", headers, openAiNativeBody(cfg, req.systemNative, req.messages, req.tools), cfg.timeoutMs) { acc.feed(it) }
    return acc.result()
}

internal suspend fun anthropicNativeTurn(cfg: ProviderConfig, req: TurnRequest, onUpdate: (StreamUpdate) -> Unit): TurnResult {
    val headers = mapOf("x-api-key" to cfg.apiKey, "anthropic-version" to "2023-06-01")
    val acc = AnthropicStream(onUpdate)
    postStream(cfg.baseUrl.trimEnd('/') + "/v1/messages", headers, anthropicNativeBody(cfg, req.systemNative, req.messages, req.tools), cfg.timeoutMs) { acc.feed(it) }
    return acc.result()
}

internal suspend fun geminiNativeTurn(cfg: ProviderConfig, req: TurnRequest, onUpdate: (StreamUpdate) -> Unit): TurnResult {
    val model = cfg.model.removePrefix("models/")
    val url = cfg.baseUrl.trimEnd('/') + "/v1beta/models/" + model + ":streamGenerateContent?alt=sse"
    var acc = GeminiStream(onUpdate)
    val headers = mapOf("x-goog-api-key" to cfg.apiKey)
    val body = geminiNativeBody(cfg, req.systemNative, req.messages, req.tools, req.sigs)
    try {
        postStream(url, headers, body, cfg.timeoutMs) { acc.feed(it) }
    } catch (e: LlmException) {
        // API menolak thinkingConfig (nilai belum dikenal model ini): ulangi tanpa itu dan ingat untuk langkah berikutnya.
        val gen = body.optJSONObject("generationConfig")
        if (e.code != 400 || gen == null || !gen.has("thinkingConfig") || !(e.message ?: "").lowercase().contains("thinking")) throw e
        geminiThinkingRejected.add(geminiModelKey(cfg.model))
        gen.remove("thinkingConfig")
        acc = GeminiStream(onUpdate)
        postStream(url, headers, body, cfg.timeoutMs) { acc.feed(it) }
    }
    return acc.result()
}
