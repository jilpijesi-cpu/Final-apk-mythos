package com.build.mythos

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Konektor Telegram (Bot API) dan WhatsApp (WhatsApp Business Cloud API milik Meta) lewat HTTPS.
// Murni Kotlin/JVM (tanpa android.*), diuji di ConnectorsTest. Token tidak pernah dicatat: galat selalu melewati Cx.redact.
//
// Batasan yang disengaja:
//  - Telegram: hanya chat yang ada di daftar izin yang dibaca atau dikirimi (bot Telegram bersifat publik, siapa pun bisa menulis ke bot).
//  - WhatsApp: hanya KIRIM. Menerima pesan lewat Cloud API butuh webhook di server publik; aplikasi di HP tidak punya itu.

internal class HttpReply(val code: Int, val body: String)

internal object CxHttp {
    private fun readLimited(stream: java.io.InputStream?, maxBytes: Int): String {
        if (stream == null) return ""
        return stream.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < maxBytes) {
                val n = ins.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    /** POST application/x-www-form-urlencoded (dipakai penukaran token OAuth). */
    fun requestForm(url: String, body: String): HttpReply {
        if (!url.startsWith("https://")) throw ConnectorException("Hanya HTTPS yang diizinkan")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.instanceFollowRedirects = false
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return HttpReply(code, readLimited(stream, 100_000))
        } finally {
            c.disconnect()
        }
    }

    /** [maxBytes] = batas isi balasan yang dibaca (bawaan 1 MB; unduhan lampiran Gmail memakai batas lebih besar). */
    fun request(
        method: String, url: String, headers: Map<String, String>, json: JSONObject?,
        readTimeoutMs: Int = 25000, maxBytes: Int = 1_000_000
    ): HttpReply {
        if (!url.startsWith("https://")) throw ConnectorException("Hanya HTTPS yang diizinkan")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = readLimited(stream, maxBytes)
            return HttpReply(code, body)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Kirim [bytes] mentah (bukan JSON) dengan [contentType] tertentu, mis. email RFC 822 utuh ke endpoint unggah Gmail.
     * Memakai mode streaming panjang tetap agar isi besar tidak digandakan di memori oleh HttpURLConnection.
     */
    fun requestBytes(
        method: String, url: String, headers: Map<String, String>, contentType: String, bytes: ByteArray,
        readTimeoutMs: Int = 120_000
    ): HttpReply {
        if (!url.startsWith("https://")) throw ConnectorException("Hanya HTTPS yang diizinkan")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return HttpReply(code, readLimited(stream, 100_000))
        } finally {
            c.disconnect()
        }
    }
}

internal class TgMessage(val updateId: Long, val chatId: Long, val from: String, val text: String, val date: Long)
internal class TgUpdates(val messages: List<TgMessage>, val maxUpdateId: Long)

internal object Telegram {
    private val TOKEN_RE = Regex("^\\d{5,15}:[A-Za-z0-9_-]{30,60}$")
    const val MAX_TEXT = 4000

    fun validToken(t: String): Boolean = TOKEN_RE.matches(t.trim())

    /** Daftar chat ID dari teks pengaturan (dipisah koma/spasi/baris baru); entri yang bukan angka diabaikan. */
    fun parseChatIds(raw: String): List<Long> =
        raw.split(',', ' ', '\n', ';').mapNotNull { it.trim().toLongOrNull() }.distinct()

    fun chunk(text: String, size: Int = MAX_TEXT): List<String> {
        if (text.length <= size) return listOf(text)
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            var end = minOf(i + size, text.length)
            if (end < text.length) {
                val nl = text.lastIndexOf('\n', end)
                if (nl > i + size / 2) end = nl + 1
            }
            out.add(text.substring(i, end))
            i = end
        }
        return out
    }

    /** Urai balasan getUpdates. Update yang bukan pesan teks tetap dihitung ke maxUpdateId. */
    fun parseUpdates(json: String): TgUpdates {
        val root = try { JSONObject(json) } catch (e: JSONException) { throw ConnectorException("Balasan Telegram bukan JSON") }
        if (!root.optBoolean("ok")) throw ConnectorException("Telegram: " + root.optString("description", "galat tidak dikenal"))
        val arr = root.optJSONArray("result") ?: JSONArray()
        val out = ArrayList<TgMessage>()
        var maxId = -1L
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i) ?: continue
            val id = u.optLong("update_id", -1)
            if (id > maxId) maxId = id
            val msg = u.optJSONObject("message") ?: u.optJSONObject("channel_post") ?: continue
            val chat = msg.optJSONObject("chat") ?: continue
            val text = msg.optString("text", "").ifBlank { msg.optString("caption", "") }
                .ifBlank { "[bukan teks: lampiran, stiker, atau media]" }
            val fromObj = msg.optJSONObject("from")
            val who = if (fromObj != null) {
                listOfNotNull(
                    fromObj.optString("first_name", "").ifBlank { null },
                    fromObj.optString("username", "").ifBlank { null }?.let { "@$it" }
                ).joinToString(" ")
            } else chat.optString("title", "")
            out.add(TgMessage(id, chat.optLong("id"), who.ifBlank { "tanpa nama" }, text, msg.optLong("date", 0)))
        }
        return TgUpdates(out, maxId)
    }

    private fun call(token: String, method: String, body: JSONObject): String {
        try {
            val r = CxHttp.request("POST", "https://api.telegram.org/bot$token/$method", emptyMap(), body)
            if (r.body.isBlank()) throw ConnectorException("Telegram tidak menjawab (HTTP ${r.code})")
            return r.body
        } catch (e: ConnectorException) {
            throw e
        } catch (e: Exception) {
            throw ConnectorException(Cx.friendly(e, listOf(token)))
        }
    }

    /** Uji token: mengembalikan @username bot. */
    fun getMe(token: String): String {
        val j = parseOk(call(token, "getMe", JSONObject()))
        return "@" + j.optJSONObject("result")?.optString("username", "?")
    }

    private fun parseOk(json: String): JSONObject {
        val root = try { JSONObject(json) } catch (e: JSONException) { throw ConnectorException("Balasan Telegram bukan JSON") }
        if (!root.optBoolean("ok")) throw ConnectorException("Telegram: " + root.optString("description", "galat tidak dikenal"))
        return root
    }

    /** Ambil pesan masuk mulai dari [offset] (belum dikonfirmasi). Tidak mengonfirmasi apa pun sendiri. */
    fun updates(token: String, offset: Long, limit: Int): TgUpdates {
        val body = JSONObject().put("limit", limit).put("timeout", 0)
            .put("allowed_updates", JSONArray().put("message"))
        if (offset > 0) body.put("offset", offset)
        return parseUpdates(call(token, "getUpdates", body))
    }

    /** Kirim teks polos (tanpa parse_mode, supaya tanda Markdown/HTML dalam isi tidak ditafsirkan). Mengembalikan jumlah potongan. */
    fun send(token: String, chatId: Long, text: String): Int {
        val parts = chunk(text)
        for (p in parts) {
            parseOk(call(token, "sendMessage", JSONObject().put("chat_id", chatId).put("text", p).put("disable_web_page_preview", true)))
        }
        return parts.size
    }
}

internal object WhatsApp {
    // Versi Graph API; bila Meta menghentikan versi ini, ganti angkanya.
    const val GRAPH = "https://graph.facebook.com/v21.0"
    const val MAX_TEXT = 4000

    /** Nomor internasional hanya angka (8-15 digit), tanpa +, spasi, atau tanda hubung; null bila tidak valid. */
    fun normalizeNumber(raw: String): String? {
        val d = raw.filter { it.isDigit() }
        return if (d.length in 8..15) d else null
    }

    fun parseNumbers(raw: String): List<String> =
        raw.split(',', ';', '\n').mapNotNull { normalizeNumber(it) }.distinct()

    fun validPhoneId(id: String): Boolean = Regex("^\\d{5,20}$").matches(id.trim())

    fun explainError(httpCode: Int, body: String): String {
        val err = try { JSONObject(body).optJSONObject("error") } catch (e: JSONException) { null }
        val code = err?.optInt("code", 0) ?: 0
        val msg = err?.optString("message", "").orEmpty()
        val hint = when (code) {
            190 -> "Token akses tidak valid atau kedaluwarsa; buat token baru di Meta for Developers."
            131047 -> "Lewat 24 jam sejak penerima terakhir membalas: pesan bebas ditolak WhatsApp (hanya pesan template yang boleh)."
            131030 -> "Nomor penerima belum ada di daftar penerima uji (mode uji Meta)."
            131026 -> "Pesan tidak bisa dikirim ke nomor itu (bukan pengguna WhatsApp atau belum menerima syarat)."
            10, 200 -> "Izin token kurang (butuh whatsapp_business_messaging)."
            100 -> "Parameter ditolak (cek Phone Number ID dan nomor tujuan)."
            else -> ""
        }
        return "WhatsApp menolak (HTTP $httpCode, kode $code): ${Cx.clip(msg, 140)} $hint".trim()
    }

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun guarded(token: String, block: () -> HttpReply): HttpReply = try {
        block()
    } catch (e: ConnectorException) {
        throw e
    } catch (e: Exception) {
        throw ConnectorException(Cx.friendly(e, listOf(token)))
    }

    /** Uji token + Phone Number ID: mengembalikan nomor tampilan akun bisnis. */
    fun check(token: String, phoneId: String): String {
        val r = guarded(token) { CxHttp.request("GET", "$GRAPH/${phoneId.trim()}?fields=display_phone_number,verified_name", auth(token), null) }
        if (r.code !in 200..299) throw ConnectorException(Cx.redact(explainError(r.code, r.body), listOf(token)))
        val j = try { JSONObject(r.body) } catch (e: JSONException) { throw ConnectorException("Balasan WhatsApp bukan JSON") }
        return j.optString("display_phone_number", "?") + " (" + j.optString("verified_name", "tanpa nama") + ")"
    }

    /** Kirim teks bebas. Mengembalikan ID pesan WhatsApp. */
    fun send(token: String, phoneId: String, to: String, text: String): String {
        if (text.length > MAX_TEXT) throw ConnectorException("Teks terlalu panjang (maksimal $MAX_TEXT karakter)")
        val body = JSONObject().put("messaging_product", "whatsapp").put("recipient_type", "individual")
            .put("to", to).put("type", "text").put("text", JSONObject().put("preview_url", false).put("body", text))
        val r = guarded(token) { CxHttp.request("POST", "$GRAPH/${phoneId.trim()}/messages", auth(token), body) }
        if (r.code !in 200..299) throw ConnectorException(Cx.redact(explainError(r.code, r.body), listOf(token)))
        return try { JSONObject(r.body).optJSONArray("messages")?.optJSONObject(0)?.optString("id", "").orEmpty() } catch (e: JSONException) { "" }
    }
}
