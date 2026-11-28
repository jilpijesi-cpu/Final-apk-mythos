package com.build.mythos

import org.json.JSONArray
import org.json.JSONObject

// Penyedia Gemini (streaming SSE).
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

// ---- Gemini (streaming SSE)
internal fun MainActivity.geminiContents(): JSONArray {
    val arr = JSONArray()
    for (h in ctxHist()) {
        if (h.role == 0) {
            val parts = JSONArray().put(JSONObject().put("text", h.text))
            for (image in h.images.filterNot { it.released }) {
                val payload = encodedImage(image)
                parts.put(
                    JSONObject().put("inlineData", JSONObject()
                        .put("mimeType", payload.mimeType)
                        .put("data", payload.base64))
                )
            }
            arr.put(JSONObject().put("role", "user").put("parts", parts))
        } else if (h.role == 1) {
            if (h.raw != null) {
                arr.put(h.raw)
            } else {
                val parts = JSONArray()
                if (h.text.isNotEmpty()) parts.put(JSONObject().put("text", h.text))
                for (c in h.calls) {
                    parts.put(JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.args)))
                }
                arr.put(JSONObject().put("role", "model").put("parts", parts))
            }
        } else {
            val parts = JSONArray()
            for ((c, o) in h.results) {
                parts.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", c.name).put("response", JSONObject().put("result", o))
                    )
                )
            }
            arr.put(JSONObject().put("role", "user").put("parts", parts))
        }
    }
    return arr
}

/** Model yang pernah menolak thinkingConfig; tidak dikirimi lagi selama aplikasi berjalan. */
private val geminiThinkingRejected: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

internal fun MainActivity.callGemini(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
    // Panggilan model di jalur ini selalu tanpa alat; pekerjaan beralat penyedia API ada di mesin cc (CcBridge.kt).
    val gemSys = sysText()
    val body = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", gemSys))))
        .put("contents", geminiContents())
    val model = cfgModel()
    val thinking = if (deepOn() && model !in geminiThinkingRejected) geminiThinkingConfig(model) else null
    thinking?.let { body.put("generationConfig", it) }
    val url = cfgBase().trimEnd('/') + "/models/" + model + ":streamGenerateContent?alt=sse"
    val conn = try {
        openStream(url, mapOf("x-goog-api-key" to key), body)
    } catch (e: RuntimeException) {
        // API menolak thinkingConfig (nilai belum dikenal model ini): ulangi tanpa konfigurasi itu dan ingat untuk langkah berikutnya.
        val m = (e.message ?: "").lowercase()
        if (thinking == null || !m.contains("400") || !m.contains("thinking")) throw e
        geminiThinkingRejected.add(model)
        body.remove("generationConfig")
        openStream(url, mapOf("x-goog-api-key" to key), body)
    }

    val parts = JSONArray()
    val buf = StringBuilder()
    var sig: String? = null
    var lastEmit = 0L
    var blockReason = ""
    var finishReason = ""

    fun flushText() {
        if (buf.isEmpty()) return
        onText(buf.toString())
        onFlush()
        val tp = JSONObject().put("text", buf.toString())
        sig?.let { tp.put("thoughtSignature", it) }
        parts.put(tp)
        buf.setLength(0)
        sig = null
    }

    try {
        conn.inputStream.bufferedReader().use { r ->
            while (true) {
                if (cancelled) throw Cancelled()
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val json = line.removePrefix("data:").trim()
                if (json.isEmpty()) continue
                val chunk = try { JSONObject(json) } catch (e: Exception) { continue }
                chunk.optJSONObject("error")?.let { throw apiStreamException(it.optInt("code", -1), str(it, "status"), str(it, "message")) }
                chunk.optJSONObject("promptFeedback")?.let { str(it, "blockReason") }?.takeIf { it.isNotBlank() }?.let { blockReason = it }
                chunk.optJSONArray("candidates")?.optJSONObject(0)?.let { str(it, "finishReason") }?.takeIf { it.isNotBlank() }?.let { finishReason = it }
                val cps = chunk.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (i in 0 until cps.length()) {
                    val p = cps.optJSONObject(i) ?: continue
                    if (p.has("functionCall") && !p.isNull("functionCall")) {
                        flushText()
                        parts.put(p)
                    } else if (p.has("text") && !p.isNull("text") && !p.optBoolean("thought", false)) {
                        val txt = str(p, "text")
                        if (txt.isNotEmpty()) buf.append(txt)
                        val ts = str(p, "thoughtSignature")
                        if (ts.isNotEmpty()) sig = ts
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 80) {
                            onText(buf.toString())
                            lastEmit = now
                        }
                    }
                }
            }
        }
    } finally {
        conn.disconnect()
        if (activeConn === conn) activeConn = null
    }
    // Stream berakhir sebelum finishReason diterima: koneksi terputus di tengah jawaban, lempar agar diulang.
    if ((parts.length() > 0 || buf.isNotEmpty()) && finishReason.isEmpty()) {
        throw java.io.IOException("Koneksi AI terputus sebelum jawaban selesai")
    }
    flushText()
    if (parts.length() == 0) {
        if (blockReason.isNotEmpty()) throw RuntimeException("Permintaan diblokir oleh model ($blockReason). Ubah isi pesan lalu coba lagi.")
        if (finishReason.isNotEmpty() && finishReason != "STOP") throw RuntimeException("Model berhenti tanpa jawaban ($finishReason).")
        return null
    }
    val sb = StringBuilder()
    val calls = ArrayList<Call>()
    for (i in 0 until parts.length()) {
        val p = parts.optJSONObject(i) ?: continue
        val fc = p.optJSONObject("functionCall")
        if (fc != null) {
            calls.add(Call("g$i", str(fc, "name"), fc.optJSONObject("args") ?: JSONObject()))
        } else if (p.has("text") && !p.isNull("text")) {
            sb.append(str(p, "text"))
        }
    }
    return Hist(1, sb.toString(), calls, raw = JSONObject().put("role", "model").put("parts", parts))
}
