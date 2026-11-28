package com.build.mythos.cc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class ProviderKind { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }

/**
 * temperature = null -> parameter tidak dikirim (perlu untuk model "reasoning" yang menolaknya).
 * tokenParam: "max_tokens" (umum) atau "max_completion_tokens" (model OpenAI terbaru).
 */
data class ProviderConfig(
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double? = 0.2,
    val maxOutputTokens: Int = 4096,
    val tokenParam: String = "max_tokens",
    val timeoutMs: Int = 120_000,
)

private val GEMINI_BIG_OUTPUT = Regex("gemini-(2\\.5|[3-9])|latest")
private val OPENAI_BIG_OUTPUT = Regex("gpt-4\\.1|gpt-4o|gpt-5|(^|[^a-z])o[134]($|[-_.])")

/**
 * Batas token keluaran per permintaan. Terlalu kecil membuat Write file besar terpotong (dan di Gemini 2.5 token "thinking"
 * ikut terhitung); terlalu besar ditolak (400) oleh model lama dan ikut dihitung ke kuota laju penyedia gratis. Karena itu
 * hanya dinaikkan untuk keluarga model yang batasnya diketahui lega; sisanya tetap 4096 dan mengandalkan pemecahan file.
 */
internal fun defaultMaxOutputTokens(kind: ProviderKind, baseUrl: String, model: String): Int {
    val m = model.lowercase()
    val host = baseUrl.lowercase()
    return when (kind) {
        ProviderKind.GEMINI -> if (GEMINI_BIG_OUTPUT.containsMatchIn(m)) 16_384 else 8_192
        ProviderKind.ANTHROPIC ->
            if (m.contains("claude-3-opus") || m.contains("claude-3-haiku") || m.contains("claude-2") || m.contains("claude-instant")) 4_096 else 8_192
        ProviderKind.OPENAI_COMPATIBLE -> when {
            host.contains("api.openai.com") && OPENAI_BIG_OUTPUT.containsMatchIn(m) -> 16_384
            host.contains("api.deepseek.com") -> 8_192
            else -> 4_096
        }
    }
}

object Presets {
    private fun cfg(kind: ProviderKind, baseUrl: String, apiKey: String, model: String) =
        ProviderConfig(kind, baseUrl, apiKey, model, maxOutputTokens = defaultMaxOutputTokens(kind, baseUrl, model))

    fun openAi(apiKey: String, model: String) =
        cfg(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", apiKey, model)

    fun deepSeek(apiKey: String, model: String) =
        cfg(ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1", apiKey, model)

    fun groq(apiKey: String, model: String) =
        cfg(ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", apiKey, model)

    /** Ollama lokal (Termux / PC di jaringan yang sama). Ganti host bila bukan di perangkat ini. */
    fun ollama(model: String, host: String = "http://127.0.0.1:11434") =
        cfg(ProviderKind.OPENAI_COMPATIBLE, host.trimEnd('/') + "/v1", "", model)

    fun anthropic(apiKey: String, model: String) =
        cfg(ProviderKind.ANTHROPIC, "https://api.anthropic.com", apiKey, model)

    fun gemini(apiKey: String, model: String) =
        cfg(ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", apiKey, model)
}

interface LlmClient {
    suspend fun complete(system: String, messages: List<Msg>): String

    /**
     * Satu giliran model. Bawaan = protokol teks lewat [complete] (klien tiruan di tes tak perlu mengubah apa pun).
     * Klien sungguhan boleh memakai tool calling native + streaming dan mengembalikan [TurnResult.Structured];
     * [onUpdate] dipanggil (dari thread I/O) selama balasan mengalir.
     */
    suspend fun turn(req: TurnRequest, onUpdate: (StreamUpdate) -> Unit = {}): TurnResult =
        TurnResult.Raw(complete(req.systemText, req.messages))

    /**
     * Tanya-jawab polos (tanpa alat) yang mengalir: [onText] menerima teks LENGKAP sejauh ini (dari thread I/O) dan fungsi
     * mengembalikan teks akhir. Bawaan = tanpa streaming (satu panggilan [complete]); klien sungguhan menggantinya.
     */
    suspend fun streamText(system: String, messages: List<Msg>, onText: (String) -> Unit = {}): String {
        val t = complete(system, messages)
        onText(t)
        return t
    }
}

class LlmException(message: String, val retryable: Boolean, val code: Int = -1) : Exception(message)

fun createRawClient(cfg: ProviderConfig): LlmClient = VisionGate(
    cfg,
    when (cfg.kind) {
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(cfg)
        ProviderKind.ANTHROPIC -> AnthropicClient(cfg)
        ProviderKind.GEMINI -> GeminiClient(cfg)
    }
)

private val VISION_OPENAI_LIKE = Regex(
    "(gpt-4o|gpt-4\\.1|gpt-4\\.5|gpt-5|gpt-4-turbo|gpt-4-vision|vision|(^|[^a-z])o[34]($|[-_.])|llava|minicpm-v|bakllava|moondream|gemma-?3|qwen[^ ]*-?vl|pixtral|llama-?4|llama-?3\\.2-(11|90)b|gemini|claude|molmo|internvl)"
)

/** Perkiraan dari nama model: apakah model bisa menerima gambar. Dipakai untuk mengurutkan cadangan dan menyaring lampiran. */
fun supportsVision(kind: ProviderKind, model: String): Boolean {
    val m = model.lowercase()
    return when (kind) {
        ProviderKind.ANTHROPIC -> !(m.startsWith("claude-1") || m.contains("claude-2") || m.contains("claude-instant"))
        ProviderKind.GEMINI -> !m.contains("embedding")
        ProviderKind.OPENAI_COMPATIBLE -> VISION_OPENAI_LIKE.containsMatchIn(m)
    }
}

/** Model tanpa kemampuan gambar tidak diberi gambar (akan error 400); pesan diberi catatan agar agen tahu. */
class VisionGate(private val cfg: ProviderConfig, private val inner: LlmClient) : LlmClient {
    private fun needsStrip(messages: List<Msg>) = messages.any { it.images.isNotEmpty() } && !supportsVision(cfg.kind, cfg.model)

    private fun strip(messages: List<Msg>) = messages.map {
        if (it.images.isEmpty()) it
        else Msg(it.role, it.text + "\n\n[CATATAN SISTEM: ${it.images.size} gambar terlampir TIDAK bisa dilihat model ini dan tidak dikirim. " +
            "Jangan berpura-pura melihatnya; jelaskan keterbatasan ini kepada pengguna bila gambar penting.]")
    }

    override suspend fun complete(system: String, messages: List<Msg>): String =
        inner.complete(system, if (needsStrip(messages)) strip(messages) else messages)

    override suspend fun turn(req: TurnRequest, onUpdate: (StreamUpdate) -> Unit): TurnResult =
        inner.turn(if (needsStrip(req.messages)) TurnRequest(req.systemText, req.systemNative, strip(req.messages), req.tools, req.sigs, req.nativeSupport) else req, onUpdate)

    override suspend fun streamText(system: String, messages: List<Msg>, onText: (String) -> Unit): String =
        inner.streamText(system, if (needsStrip(messages)) strip(messages) else messages, onText)
}

fun createClient(cfg: ProviderConfig, onWait: (BusyKind, Int) -> Unit = { _, _ -> }): LlmClient = RetryingClient(createRawClient(cfg), onWait = onWait)

/** Coba ulang otomatis untuk 429 / 5xx / gangguan jaringan dengan jeda eksponensial. */
class RetryingClient(
    private val inner: LlmClient,
    private val maxAttempts: Int = 4,
    private val onWait: (BusyKind, Int) -> Unit = { _, _ -> }
) : LlmClient {
    private suspend fun <T> retrying(call: suspend () -> T): T {
        var attempt = 0
        var wait = 1_500L
        while (true) {
            try {
                return call()
            } catch (e: LlmException) {
                attempt++
                if (!e.retryable || attempt >= maxAttempts) throw e
                onWait(busyKindOf(e.code, e.message), attempt - 1) // kalimat ramah di baris live; kode galat tidak ditampilkan
                delay(wait)
                wait = minOf(wait * 2, 20_000L)
            }
        }
    }

    override suspend fun complete(system: String, messages: List<Msg>): String = retrying { inner.complete(system, messages) }

    override suspend fun turn(req: TurnRequest, onUpdate: (StreamUpdate) -> Unit): TurnResult = retrying { inner.turn(req, onUpdate) }

    override suspend fun streamText(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
        var emitted = false
        return retrying {
            try {
                inner.streamText(system, messages) { if (it.isNotBlank()) emitted = true; onText(it) }
            } catch (e: LlmException) {
                // Teks sudah tampil: mengulang dari awal akan menimpa yang sedang dibaca, jadi teruskan sebagai galat.
                if (emitted) throw LlmException(e.message ?: "Koneksi terputus", false, e.code) else throw e
            }
        }
    }
}

internal fun List<Msg>.mergeSameRole(): List<Msg> {
    val out = ArrayList<Msg>()
    for (m in this) {
        val last = out.lastOrNull()
        if (last != null && last.role == m.role) out[out.size - 1] = Msg(m.role, last.text + "\n\n" + m.text, last.images + m.images)
        else out.add(m)
    }
    return out
}

internal fun parseJson(raw: String): JSONObject =
    try { JSONObject(raw) } catch (e: JSONException) {
        throw LlmException("Respons bukan JSON valid: ${raw.take(200)}", false)
    }

/** Membuka koneksi; alamat yang salah (tanpa http://, spasi, skema asing) menjadi LlmException yang jelas, bukan crash. */
internal fun openConn(url: String): HttpURLConnection = try {
    URL(url).openConnection() as HttpURLConnection
} catch (e: IOException) {
    throw LlmException("Alamat tidak valid: ${e.message}", false)
} catch (e: ClassCastException) {
    throw LlmException("Alamat harus diawali http:// atau https://", false)
}

/**
 * Menjalankan I/O blocking pada [conn] dan memutus koneksi seketika bila korutin dibatalkan (tombol Stop, timeout alat),
 * supaya pembatalan tidak harus menunggu read timeout.
 */
internal suspend fun <T> blockingIo(conn: HttpURLConnection, block: () -> T): T = coroutineScope {
    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    val watcher = launch {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) conn.disconnect()
        }
    }
    try {
        block()
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}

/** Galat di dalam badan respons (mis. OpenRouter/Gemini membalas 200 dengan objek "error") dipetakan agar dikenali KeyPool. */
internal fun errorException(err: JSONObject): LlmException {
    val msg = err.optString("message", "").ifBlank { err.toString() }
    val raw = err.optInt("code", -1)
    val low = msg.lowercase()
    val code = when {
        raw in 100..599 -> raw
        "rate limit" in low || "quota" in low || "too many requests" in low -> 429
        "api key" in low || "unauthorized" in low || "authentication" in low -> 401
        else -> -1
    }
    return LlmException("HTTP ${if (code > 0) code else 200}: ${msg.take(400)}", code == 429 || code >= 500 || code == -1, code)
}

internal object Http {
    suspend fun postJson(url: String, headers: Map<String, String>, body: JSONObject, timeoutMs: Int): String =
        withContext(Dispatchers.IO) {
            val conn = openConn(url)
            try {
                blockingIo(conn) {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 20_000
                    conn.readTimeout = timeoutMs
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    if (code !in 200..299) {
                        throw LlmException("HTTP $code: ${text.take(500)}", code == 429 || code >= 500, code)
                    }
                    text
                }
            } catch (e: IOException) {
                ensureActive()   // dibatalkan pengguna -> CancellationException, bukan "Jaringan: Socket closed"
                throw LlmException("Jaringan: ${e.message}", true)
            } finally {
                conn.disconnect()
            }
        }
}

internal fun openAiBody(cfg: ProviderConfig, system: String, messages: List<Msg>): JSONObject {
    val arr = JSONArray()
    arr.put(JSONObject().put("role", "system").put("content", system))
    messages.mergeSameRole().forEach {
        val role = if (it.role == Role.USER) "user" else "assistant"
        if (it.images.isEmpty() || it.role != Role.USER) {
            arr.put(JSONObject().put("role", role).put("content", it.text))
        } else {
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", it.text))
            it.images.forEach { im ->
                parts.put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:${im.mime};base64,${im.base64}"))
                )
            }
            arr.put(JSONObject().put("role", role).put("content", parts))
        }
    }
    val body = JSONObject().put("model", cfg.model).put("messages", arr).put(cfg.tokenParam, cfg.maxOutputTokens)
    cfg.temperature?.let { body.put("temperature", it) }
    return body
}

internal fun anthropicBody(cfg: ProviderConfig, system: String, messages: List<Msg>): JSONObject {
    val arr = JSONArray()
    messages.mergeSameRole().forEach {
        val role = if (it.role == Role.USER) "user" else "assistant"
        if (it.images.isEmpty() || it.role != Role.USER) {
            arr.put(JSONObject().put("role", role).put("content", it.text))
        } else {
            val blocks = JSONArray()
            it.images.forEach { im ->
                blocks.put(
                    JSONObject().put("type", "image")
                        .put("source", JSONObject().put("type", "base64").put("media_type", im.mime).put("data", im.base64))
                )
            }
            blocks.put(JSONObject().put("type", "text").put("text", it.text))
            arr.put(JSONObject().put("role", role).put("content", blocks))
        }
    }
    val body = JSONObject().put("model", cfg.model).put("max_tokens", cfg.maxOutputTokens)
        .put("system", system).put("messages", arr)
    cfg.temperature?.let { body.put("temperature", it) }
    return body
}

internal fun geminiBody(cfg: ProviderConfig, system: String, messages: List<Msg>): JSONObject {
    val contents = JSONArray()
    messages.mergeSameRole().forEach {
        val parts = JSONArray()
        if (it.role == Role.USER) {
            it.images.forEach { im ->
                parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", im.mime).put("data", im.base64)))
            }
        }
        parts.put(JSONObject().put("text", it.text))
        contents.put(JSONObject().put("role", if (it.role == Role.USER) "user" else "model").put("parts", parts))
    }
    val gen = JSONObject().put("maxOutputTokens", cfg.maxOutputTokens)
    cfg.temperature?.let { gen.put("temperature", it) }
    return JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        .put("contents", contents)
        .put("generationConfig", gen)
}

class OpenAiCompatibleClient(private val cfg: ProviderConfig) : LlmClient {
    override suspend fun turn(req: TurnRequest, onUpdate: (StreamUpdate) -> Unit): TurnResult =
        nativeOrText(cfg, this, req) { openAiNativeTurn(cfg, req, onUpdate) }

    override suspend fun streamText(system: String, messages: List<Msg>, onText: (String) -> Unit): String =
        plainStream(this, system, messages, onText) { up -> openAiNativeTurn(cfg, TurnRequest(system, system, messages, emptyList()), up) }

    override suspend fun complete(system: String, messages: List<Msg>): String {
        val body = openAiBody(cfg, system, messages)
        val headers = HashMap<String, String>()
        if (cfg.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
        val raw = Http.postJson(cfg.baseUrl.trimEnd('/') + "/chat/completions", headers, body, cfg.timeoutMs)
        val json = parseJson(raw)
        val content = json.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content", "")
        if (content.isNullOrBlank()) {
            json.optJSONObject("error")?.let { throw errorException(it) }
            throw LlmException("Respons kosong dari model", true)
        }
        return content
    }
}

class AnthropicClient(private val cfg: ProviderConfig) : LlmClient {
    override suspend fun turn(req: TurnRequest, onUpdate: (StreamUpdate) -> Unit): TurnResult =
        nativeOrText(cfg, this, req) { anthropicNativeTurn(cfg, req, onUpdate) }

    override suspend fun streamText(system: String, messages: List<Msg>, onText: (String) -> Unit): String =
        plainStream(this, system, messages, onText) { up -> anthropicNativeTurn(cfg, TurnRequest(system, system, messages, emptyList()), up) }

    override suspend fun complete(system: String, messages: List<Msg>): String {
        val body = anthropicBody(cfg, system, messages)
        val headers = mapOf("x-api-key" to cfg.apiKey, "anthropic-version" to "2023-06-01")
        val raw = Http.postJson(cfg.baseUrl.trimEnd('/') + "/v1/messages", headers, body, cfg.timeoutMs)
        val json = parseJson(raw)
        val blocks = json.optJSONArray("content")
            ?: throw (json.optJSONObject("error")?.let { errorException(it) } ?: LlmException("Respons tanpa konten", true))
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        if (sb.isBlank()) throw LlmException("Respons kosong dari model", true)
        return sb.toString()
    }
}

class GeminiClient(private val cfg: ProviderConfig) : LlmClient {
    override suspend fun turn(req: TurnRequest, onUpdate: (StreamUpdate) -> Unit): TurnResult =
        nativeOrText(cfg, this, req) { geminiNativeTurn(cfg, req, onUpdate) }

    override suspend fun streamText(system: String, messages: List<Msg>, onText: (String) -> Unit): String =
        plainStream(this, system, messages, onText) { up -> geminiNativeTurn(cfg, TurnRequest(system, system, messages, emptyList()), up) }

    override suspend fun complete(system: String, messages: List<Msg>): String {
        val body = geminiBody(cfg, system, messages)
        val model = cfg.model.removePrefix("models/")
        val url = cfg.baseUrl.trimEnd('/') + "/v1beta/models/" + model + ":generateContent"
        val raw = Http.postJson(url, mapOf("x-goog-api-key" to cfg.apiKey), body, cfg.timeoutMs)
        val json = parseJson(raw)
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw (json.optJSONObject("error")?.let { errorException(it) } ?: LlmException("Respons kosong/diblokir oleh model", true))
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text", ""))
        }
        if (sb.isBlank()) throw LlmException("Respons kosong dari model", true)
        return sb.toString()
    }
}
