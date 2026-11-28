package com.build.mythos

import org.json.JSONArray
import org.json.JSONObject

// Logika murni (tanpa android.*) untuk riwayat banyak chat: judul, indeks, dan format file per chat.
// Penyimpanan ke disk ada di ChatHistory.kt; diuji di ChatStoreTest.

/** Ringkasan satu chat untuk daftar di drawer. [custom] = judul diubah sendiri oleh pengguna (tidak ditimpa otomatis). */
internal class ChatMeta(
    val id: String,
    val title: String,
    val updated: Long,
    val count: Int,
    val custom: Boolean = false
)

/** Isi satu file chat: ringkasan + semua pesan (format netral [Hist] dalam JSON). */
internal class ChatFile(val meta: ChatMeta, val messages: JSONArray)

internal object ChatStore {
    const val MAX_TITLE = 60
    const val EMPTY_TITLE = "Chat baru"
    private val ID_OK = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun newId(now: Long): String = "c$now"

    /** Id yang aman dipakai sebagai nama file; null bila mengandung karakter lain. */
    fun safeId(id: String): String? = id.takeIf { ID_OK.matches(it) }

    /** Judul dari pesan pengguna pertama: baris pertama yang tidak kosong, dipotong. */
    fun titleFrom(firstUserText: String?): String {
        val t = firstUserText?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }.orEmpty()
        return when {
            t.isEmpty() -> EMPTY_TITLE
            t.length > MAX_TITLE -> t.take(MAX_TITLE).trimEnd() + "…"
            else -> t
        }
    }

    /** Judul hasil ubah nama: dirapikan, dipotong; kosong berarti kembali ke judul otomatis (null). */
    fun cleanRename(raw: String): String? {
        val t = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (t.isEmpty()) return null
        return if (t.length > MAX_TITLE) t.take(MAX_TITLE).trimEnd() + "…" else t
    }

    private fun isRealUser(o: JSONObject): Boolean =
        o.optInt("role", -1) == 0 && o.optString("text").isNotBlank() && !o.optString("text").startsWith(SYS_PREFIX)

    fun firstUserText(messages: JSONArray): String? {
        for (i in 0 until messages.length()) {
            val o = messages.optJSONObject(i) ?: continue
            if (isRealUser(o)) return o.optString("text")
        }
        return null
    }

    fun userCount(messages: JSONArray): Int {
        var n = 0
        for (i in 0 until messages.length()) if (messages.optJSONObject(i)?.let { isRealUser(it) } == true) n++
        return n
    }

    // ---------------------------------------------------------------- Indeks

    /** Ganti/tambah [m]; hasil terurut dari yang terbaru. */
    fun upsert(list: List<ChatMeta>, m: ChatMeta): List<ChatMeta> =
        (list.filter { it.id != m.id } + m).sortedByDescending { it.updated }

    fun remove(list: List<ChatMeta>, id: String): List<ChatMeta> = list.filter { it.id != id }

    fun indexToJson(list: List<ChatMeta>): String {
        val a = JSONArray()
        for (m in list) a.put(JSONObject().put("id", m.id).put("title", m.title).put("updated", m.updated)
            .put("count", m.count).put("custom", m.custom))
        return a.toString()
    }

    /** Null bila teks bukan indeks yang sah (rusak): pemanggil membangun ulang dari file chat. */
    fun parseIndex(text: String): List<ChatMeta>? = try {
        val a = JSONArray(text)
        val out = ArrayList<ChatMeta>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val id = safeId(o.optString("id")) ?: continue
            out.add(ChatMeta(id, o.optString("title").ifBlank { EMPTY_TITLE }, o.optLong("updated"), o.optInt("count"), o.optBoolean("custom")))
        }
        out.sortedByDescending { it.updated }
    } catch (e: Exception) { null }

    fun matches(m: ChatMeta, query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || m.title.contains(q, ignoreCase = true)
    }

    // ---------------------------------------------------------------- File chat

    fun fileToJson(meta: ChatMeta, messages: JSONArray): String =
        JSONObject().put("id", meta.id).put("title", meta.title).put("updated", meta.updated)
            .put("custom", meta.custom).put("messages", messages).toString()

    /**
     * Baca isi file chat. Mendukung format lama (history.json: larik pesan telanjang);
     * untuk itu judul diturunkan dari pesan pertama dan waktu dari [fallbackTime].
     */
    fun parseFile(text: String, id: String, fallbackTime: Long): ChatFile {
        val t = text.trimStart()
        if (t.startsWith("[")) {
            val arr = JSONArray(t)
            return ChatFile(ChatMeta(id, titleFrom(firstUserText(arr)), fallbackTime, userCount(arr)), arr)
        }
        val o = JSONObject(t)
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val custom = o.optBoolean("custom")
        val title = if (custom && o.optString("title").isNotBlank()) o.optString("title") else titleFrom(firstUserText(arr))
        return ChatFile(ChatMeta(id, title, o.optLong("updated", fallbackTime), userCount(arr), custom), arr)
    }

    /** Semua path gambar (asli dan pratinjau) yang disebut pesan pada [messages]. */
    fun imagePaths(messages: JSONArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until messages.length()) {
            val imgs = messages.optJSONObject(i)?.optJSONArray("images") ?: continue
            for (j in 0 until imgs.length()) {
                when (val it = imgs.opt(j)) {
                    is JSONObject -> {
                        it.optString("path").takeIf { p -> p.isNotBlank() }?.let { p -> out.add(p) }
                        it.optString("preview").takeIf { p -> p.isNotBlank() }?.let { p -> out.add(p) }
                    }
                    is String -> if (it.isNotBlank()) out.add(it)
                }
            }
        }
        return out.distinct()
    }

    /** Teks tanggal ringkas untuk daftar: "Hari ini", "Kemarin", atau selisih hari kalender. */
    fun dayLabel(updated: Long, now: Long, offsetMs: Long): String {
        val day = 86_400_000L
        val d = Math.floorDiv(now + offsetMs, day) - Math.floorDiv(updated + offsetMs, day)
        return when {
            d <= 0L -> "Hari ini"
            d == 1L -> "Kemarin"
            d < 7L -> "$d hari lalu"
            else -> ""
        }
    }
}
