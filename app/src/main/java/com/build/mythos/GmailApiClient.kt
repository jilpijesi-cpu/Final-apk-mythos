package com.build.mythos

import java.net.URLEncoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class GmailSummary(
    val id: String,
    val from: String,
    val subject: String,
    val date: String,
    val snippet: String,
    val seen: Boolean
)

internal class GmailList(
    val totalEstimate: Int,
    val items: List<GmailSummary>,
    val nextPageToken: String = "",
    val effectiveFolder: String = ""
)

internal class GmailAuthExpiredException(message: String) : ConnectorException(message)

internal class GmailProfile(val emailAddress: String, val messagesTotal: Int)

/**
 * Klien Gmail REST API v1 (https://gmail.googleapis.com/gmail/v1/users/me) memakai access token OAuth Google.
 * Menggantikan jalur IMAP/SMTP mentah agar pencarian, daftar, baca, dan kirim email mengikuti sintaks Gmail standar.
 */
internal object GmailApiClient {
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
    private val MSG_ID_RE = Regex("^[A-Za-z0-9_-]{4,64}$")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun authHeaders(accessToken: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $accessToken")

    /**
     * Normalisasi parameter `cari` agar model kecil sekalipun tidak gagal:
     * kosong / "semua" / "all" / "terbaru" / "recent" / "latest" / "*" -> "" (ambil email terbaru langsung).
     * "belum_dibaca" / "unread" / "unseen" -> "is:unread".
     * "dari:X" -> "from:X", "subjek:X" -> "subject:X", lainnya diteruskan langsung ke kueri pencarian Gmail.
     */
    fun normalizeQuery(cari: String): String {
        val raw = cari.trim().trim('"', '\'').trim()
        if (raw.isEmpty()) return ""
        val lower = raw.lowercase()
        return when {
            lower in setOf("semua", "all", "terbaru", "recent", "latest", "*", "inbox", "kotak masuk") -> ""
            lower in setOf("belum_dibaca", "belum dibaca", "unread", "unseen") -> "is:unread"
            lower.startsWith("dari:") -> "from:" + raw.substring(5).trim()
            lower.startsWith("subjek:") -> "subject:" + raw.substring(7).trim()
            else -> raw
        }
    }

    /** Petakan nama folder umum ke labelId Gmail; null berarti cari di seluruh surat (All Mail). */
    fun labelForFolder(folder: String): String? {
        val f = folder.trim().trim('"', '\'').trim()
        if (f.isEmpty()) return "INBOX"
        return when (f.lowercase()) {
            "inbox", "kotak masuk", "utama" -> "INBOX"
            "sent", "[gmail]/sent mail", "sent mail", "terkirim" -> "SENT"
            "draft", "drafts", "[gmail]/drafts", "draf" -> "DRAFT"
            "spam", "[gmail]/spam" -> "SPAM"
            "trash", "[gmail]/trash", "sampah" -> "TRASH"
            "starred", "[gmail]/starred", "berbintang" -> "STARRED"
            "important", "penting" -> "IMPORTANT"
            "all", "semua", "[gmail]/all mail", "all mail", "semua email" -> null
            else -> null
        }
    }

    private val KNOWN_FOLDERS = setOf(
        "inbox", "kotak masuk", "utama", "sent", "[gmail]/sent mail", "sent mail", "terkirim",
        "draft", "drafts", "[gmail]/drafts", "draf", "spam", "[gmail]/spam", "trash", "[gmail]/trash", "sampah",
        "starred", "[gmail]/starred", "berbintang", "important", "penting",
        "all", "semua", "[gmail]/all mail", "all mail", "semua email"
    )

    /** Folder kosong (= INBOX) atau salah satu nama yang dikenal [labelForFolder]. Nama lain (mis. salah ketik) bukan folder. */
    fun isKnownFolder(folder: String): Boolean {
        val f = folder.trim().trim('"', '\'').trim()
        return f.isEmpty() || f.lowercase() in KNOWN_FOLDERS
    }

    /** Bersihkan ID pesan Gmail dari pembungkus seperti "[id 1935ab...]" atau "id: 1935ab...". */
    fun cleanMessageId(raw: String): String? {
        val stripped = raw.trim()
            .removePrefix("[").removeSuffix("]").trim()
            .replace(Regex("^id\\s*[:=]?\\s*", RegexOption.IGNORE_CASE), "")
            .trim('"', '\'', ' ')
        return stripped.takeIf { MSG_ID_RE.matches(it) }
    }

    private fun parseJsonOrThrow(reply: HttpReply, context: String): JSONObject {
        val j = try { JSONObject(reply.body) } catch (e: JSONException) { null }
        if (reply.code !in 200..299) {
            val apiMsg = j?.optJSONObject("error")?.optString("message").orEmpty()
            val detail = when (reply.code) {
                401 -> "Sesi Google kedaluwarsa atau dicabut. Hubungkan ulang Gmail di Pengaturan > Konektor."
                403 -> "Akses Gmail API ditolak (HTTP 403): ${apiMsg.ifBlank { "pastikan Gmail API aktif di Google Cloud dan izin sudah disetujui" }}"
                404 -> "Email tidak ditemukan (HTTP 404)."
                else -> "Gmail API gagal saat $context (HTTP ${reply.code}): ${apiMsg.ifBlank { Cx.clip(reply.body, 140) }}"
            }
            if (reply.code == 401) throw GmailAuthExpiredException(detail)
            throw ConnectorException(detail)
        }
        return j ?: throw ConnectorException("Balasan Gmail API bukan JSON saat $context (HTTP ${reply.code})")
    }

    fun profile(accessToken: String): GmailProfile {
        val r = CxHttp.request("GET", "$BASE/profile", authHeaders(accessToken), null)
        val j = parseJsonOrThrow(r, "membaca profil")
        return GmailProfile(j.optString("emailAddress"), j.optInt("messagesTotal", 0))
    }

    private val PAGE_TOKEN_RE = Regex("^[A-Za-z0-9_=-]{1,200}$")

    private fun fetchMessageListRaw(accessToken: String, labelId: String?, q: String, limit: Int, pageToken: String = ""): JSONObject {
        val sb = StringBuilder("$BASE/messages?maxResults=").append(limit.coerceIn(1, 20))
        if (!labelId.isNullOrBlank()) sb.append("&labelIds=").append(enc(labelId))
        if (q.isNotBlank()) sb.append("&q=").append(enc(q))
        // Spam dan sampah tidak ikut daftar Gmail kecuali diminta; perlu agar folder SPAM/TRASH bisa dibaca.
        if (labelId == "SPAM" || labelId == "TRASH") sb.append("&includeSpamTrash=true")
        if (pageToken.isNotBlank()) sb.append("&pageToken=").append(enc(pageToken))
        val r = CxHttp.request("GET", sb.toString(), authHeaders(accessToken), null)
        return parseJsonOrThrow(r, "mengambil daftar email")
    }

    /** Token halaman dari hasil sebelumnya; null bila bentuknya tidak wajar (model kadang mengarang). */
    fun cleanPageToken(raw: String): String? {
        val t = raw.trim().trim('"', '\'', ' ')
        return when {
            t.isEmpty() -> ""
            PAGE_TOKEN_RE.matches(t) -> t
            else -> null
        }
    }

    fun list(accessToken: String, folder: String, cari: String, limit: Int, pageToken: String = ""): GmailList {
        val max = limit.coerceIn(1, 20)
        val q = normalizeQuery(cari)
        val queryPicksFolder = q.contains("in:", ignoreCase = true) || q.contains("label:", ignoreCase = true)
        if (!queryPicksFolder && !isKnownFolder(folder)) {
            throw ConnectorException("Folder \"${Cx.clip(folder, 40)}\" tidak dikenal. Pakai INBOX, SENT, DRAFT, SPAM, TRASH, STARRED, IMPORTANT, atau ALL.")
        }
        val label = if (queryPicksFolder) null else labelForFolder(folder)
        var effectiveFolder = folder.trim().ifBlank { "INBOX" }
        var j = fetchMessageListRaw(accessToken, label, q, max, pageToken)
        var msgs = j.optJSONArray("messages")
        // Bila pencarian kata kunci di INBOX kosong, otomatis perluas ke semua surat (arsip/sent/updates).
        if ((msgs == null || msgs.length() == 0) && label == "INBOX" && q.isNotBlank() && pageToken.isBlank()) {
            effectiveFolder = "ALL"
            j = fetchMessageListRaw(accessToken, null, q, max)
            msgs = j.optJSONArray("messages")
        }
        if (msgs == null || msgs.length() == 0) return GmailList(0, emptyList(), effectiveFolder = effectiveFolder)
        val totalEst = j.optInt("resultSizeEstimate", msgs.length())
        val next = j.optString("nextPageToken").trim()
        val out = ArrayList<GmailSummary>()
        val count = minOf(msgs.length(), max)
        for (i in 0 until count) {
            val id = msgs.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isBlank()) continue
            val url = "$BASE/messages/${enc(id)}?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date"
            val mr = CxHttp.request("GET", url, authHeaders(accessToken), null)
            if (mr.code == 404) continue
            val mj = parseJsonOrThrow(mr, "membaca ringkasan email $id")
            out.add(parseSummary(mj))
        }
        return GmailList(totalEst, out, next, effectiveFolder)
    }

    fun read(accessToken: String, messageId: String): ParsedMail {
        val id = cleanMessageId(messageId) ?: throw ConnectorException("ID email tidak valid.")
        val r = CxHttp.request("GET", "$BASE/messages/${enc(id)}?format=full", authHeaders(accessToken), null)
        val j = parseJsonOrThrow(r, "membaca isi email $id")
        return parseFullMessage(j)
    }

    fun send(accessToken: String, rawRfc2822: String) = sendBytes(accessToken, rawRfc2822.toByteArray(Charsets.UTF_8))

    /** Di atas ini, email dikirim lewat endpoint unggah (media) alih-alih JSON "raw", yang dibatasi ukuran badan permintaannya. */
    private const val JSON_SEND_MAX = 3 * 1024 * 1024

    /** Kirim email RFC 822 utuh (dengan atau tanpa lampiran). Kecil: JSON "raw" base64url; besar: unggah message/rfc822 mentah (maks 35 MB di Gmail). */
    fun sendBytes(accessToken: String, rfc822: ByteArray) {
        val r = if (rfc822.size <= JSON_SEND_MAX) {
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(rfc822)
            CxHttp.request("POST", "$BASE/messages/send", authHeaders(accessToken), JSONObject().put("raw", b64), readTimeoutMs = 60_000)
        } else {
            CxHttp.requestBytes(
                "POST", "https://gmail.googleapis.com/upload/gmail/v1/users/me/messages/send?uploadType=media",
                authHeaders(accessToken), "message/rfc822", rfc822
            )
        }
        parseJsonOrThrow(r, "mengirim email")
    }

    /** Batas unduhan satu lampiran. Dibatasi demi memori HP: Gmail API mengembalikan base64 dalam JSON (±1,33x ukuran asli). */
    const val MAX_DOWNLOAD_BYTES = 15L * 1024 * 1024

    /**
     * Unduh isi satu lampiran. [expectedSize] (dari [read]) dipakai menolak lampiran terlalu besar SEBELUM mengunduh.
     * Mengembalikan byte asli berkas.
     */
    fun downloadAttachment(accessToken: String, messageId: String, attachmentId: String, expectedSize: Long = -1): ByteArray {
        val id = cleanMessageId(messageId) ?: throw ConnectorException("ID email tidak valid.")
        if (attachmentId.isBlank() || attachmentId.length > 4096 || !Cx.noCtl(attachmentId)) throw ConnectorException("ID lampiran tidak valid.")
        if (expectedSize > MAX_DOWNLOAD_BYTES) {
            throw ConnectorException("Lampiran ${expectedSize / 1024 / 1024} MB melebihi batas unduhan ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB di HP.")
        }
        val url = "$BASE/messages/${enc(id)}/attachments/${enc(attachmentId)}"
        val limit = (MAX_DOWNLOAD_BYTES * 4 / 3 + 8192).toInt()
        val r = CxHttp.request("GET", url, authHeaders(accessToken), null, readTimeoutMs = 80_000, maxBytes = limit)
        val j = try { JSONObject(r.body) } catch (e: JSONException) {
            if (r.code in 200..299) throw ConnectorException("Lampiran terlalu besar atau balasan terpotong (batas ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB).")
            null
        }
        if (r.code !in 200..299) parseJsonOrThrow(r, "mengunduh lampiran")
        val data = j?.optString("data").orEmpty()
        if (data.isEmpty()) throw ConnectorException("Gmail tidak mengembalikan isi lampiran.")
        return try {
            Base64.getUrlDecoder().decode(data.replace(Regex("\\s+"), ""))
        } catch (e: IllegalArgumentException) {
            throw ConnectorException("Isi lampiran dari Gmail tidak bisa didekode.")
        }
    }

    private fun headersMap(payload: JSONObject?): Map<String, String> {
        val arr = payload?.optJSONArray("headers") ?: return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            val k = h.optString("name").trim().lowercase()
            if (k.isNotEmpty() && !m.containsKey(k)) m[k] = h.optString("value").trim()
        }
        return m
    }

    private fun isUnread(labels: JSONArray?): Boolean {
        if (labels == null) return false
        for (i in 0 until labels.length()) {
            if (labels.optString(i).equals("UNREAD", ignoreCase = true)) return true
        }
        return false
    }

    internal fun parseSummary(j: JSONObject): GmailSummary {
        val h = headersMap(j.optJSONObject("payload"))
        return GmailSummary(
            id = j.optString("id"),
            from = h["from"].orEmpty(),
            subject = h["subject"].orEmpty(),
            date = h["date"].orEmpty(),
            snippet = j.optString("snippet").trim(),
            seen = !isUnread(j.optJSONArray("labelIds"))
        )
    }

    private fun decodeBase64Url(data: String): String {
        if (data.isBlank()) return ""
        return try {
            val clean = data.replace('-', '+').replace('_', '/').replace(Regex("\\s+"), "")
            val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /** Seperti [decodeBase64Url] tetapi mengembalikan byte mentah (untuk lampiran); null bila tidak bisa didekode. */
    internal fun decodeBase64UrlBytes(data: String): ByteArray? = try {
        val clean = data.replace('-', '+').replace('_', '/').replace(Regex("\\s+"), "")
        Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4))
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun walkPayload(
        part: JSONObject?, plain: StringBuilder, html: StringBuilder, atts: MutableList<String>,
        infos: MutableList<GmailAttachment>, depth: Int
    ) {
        if (part == null || depth > 8) return
        val mime = part.optString("mimeType").trim().lowercase()
        val fname = part.optString("filename").trim()
        val body = part.optJSONObject("body")
        val bodyData = body?.optString("data").orEmpty()
        if (fname.isNotEmpty()) {
            atts.add(fname)
            // Lampiran besar punya attachmentId (isi diunduh terpisah); lampiran kecil bisa tertanam di body.data.
            val attId = body?.optString("attachmentId").orEmpty()
            val type = mime.ifBlank { "application/octet-stream" }
            if (attId.isNotEmpty()) {
                infos.add(GmailAttachment(fname, type, body?.optLong("size", -1L) ?: -1L, attId))
            } else if (bodyData.isNotEmpty()) {
                val bytes = decodeBase64UrlBytes(bodyData)
                if (bytes != null) infos.add(GmailAttachment(fname, type, bytes.size.toLong(), "", bytes))
            }
        } else if (mime == "text/plain" && bodyData.isNotEmpty()) {
            plain.append(decodeBase64Url(bodyData)).append('\n')
        } else if (mime == "text/html" && bodyData.isNotEmpty()) {
            html.append(decodeBase64Url(bodyData)).append('\n')
        }
        val parts = part.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            walkPayload(parts.optJSONObject(i), plain, html, atts, infos, depth + 1)
        }
    }

    internal fun parseFullMessage(j: JSONObject): ParsedMail {
        val payload = j.optJSONObject("payload")
        val h = headersMap(payload)
        val plain = StringBuilder()
        val html = StringBuilder()
        val atts = ArrayList<String>()
        val infos = ArrayList<GmailAttachment>()
        walkPayload(payload, plain, html, atts, infos, 0)
        val text = when {
            plain.isNotBlank() -> plain.toString().trim()
            html.isNotBlank() -> Mime.htmlToText(html.toString())
            else -> j.optString("snippet").trim()
        }
        return ParsedMail(
            from = h["from"].orEmpty(),
            to = h["to"].orEmpty(),
            subject = h["subject"].orEmpty(),
            date = h["date"].orEmpty(),
            messageId = h["message-id"].orEmpty(),
            text = text,
            attachments = atts,
            attachmentInfo = infos,
            cc = h["cc"].orEmpty()
        )
    }
}
