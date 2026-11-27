package com.build.mythos

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

// Memori obrolan: catatan pertanyaan-jawaban dari riwayat chat yang dipakai sebagai bahan untuk model lokal.
// Makin sering aplikasi dipakai, makin banyak catatan yang bisa dipanggil kembali. Logika murni (tanpa android.*),
// diuji di ChatMemoryTest. Penyimpanan ke disk ada di ChatMemoryStore.kt.

/** Satu catatan: pertanyaan pengguna, jawaban akhir AI, waktu, dan berapa kali dipanggil kembali. */
internal class MemoryEntry(val user: String, val ai: String, val ts: Long, val hits: Int = 0)

internal object ChatMemory {
    const val MAX_ENTRIES = 400
    const val USER_MAX = 300
    const val AI_MAX = 500
    const val RECALL_K = 3
    /** Batas karakter blok ingatan di prompt: model lokal membaca prompt di CPU HP, jadi harus kecil. */
    const val RECALL_BUDGET = 900
    /** Skor kecocokan minimum: satu kata kunci dari pertanyaan lama, atau dua kata dari jawaban lama. */
    const val MIN_OVERLAP = 2.0
    private const val DAY = 86_400_000L
    private const val PER_CHAT_IMPORT = 40

    private val STOP = setOf(
        "yang", "dan", "dari", "untuk", "dengan", "pada", "ini", "itu", "atau", "adalah", "saya", "aku", "kamu", "anda",
        "kita", "kami", "apa", "bagaimana", "gimana", "kenapa", "mengapa", "siapa", "kapan", "dimana", "bisa", "tolong",
        "dong", "nih", "deh", "saja", "juga", "akan", "sudah", "belum", "tidak", "bukan", "ada", "jadi", "buat", "bikin",
        "the", "and", "for", "you", "what", "how", "can", "please", "this", "that", "with", "are"
    )
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val SECRET_WORDS = Regex("(?i)(api[ _-]?key|kata sandi|sandi|password|token|secret|rahasia|private key)")
    private val LONG_TOKEN = Regex("[A-Za-z0-9_\\-]{32,}")
    private val GREETING = Regex("(?i)^(halo|hai|hi|hello|assalamualaikum|selamat (pagi|siang|sore|malam))[!. ]*$")

    private fun stem(t: String): String {
        for (suf in listOf("nya", "kah", "lah", "kan", "an")) {
            if (t.endsWith(suf) && t.length - suf.length >= 4) return t.take(t.length - suf.length)
        }
        return t
    }

    /** Kata kunci: huruf kecil, minimal 3 huruf, tanpa kata umum, akhiran -nya/-kan/-an dibuang. */
    fun tokens(text: String): Set<String> =
        WORD.findAll(text.lowercase()).map { it.value }.filter { it.length >= 3 && it !in STOP }.map { stem(it) }.toSet()

    /** Teks yang tampak rahasia (kunci API, sandi, token) tidak pernah disimpan ke memori. */
    fun looksSecret(text: String): Boolean = SECRET_WORDS.containsMatchIn(text) || LONG_TOKEN.containsMatchIn(text)

    fun clip(text: String, max: Int): String {
        val s = text.trim().replace(Regex("\\s+"), " ")
        return if (s.length > max) s.take(max).trimEnd() + "…" else s
    }

    fun worthRemembering(user: String, ai: String): Boolean {
        val u = user.trim()
        val a = ai.trim()
        if (u.length < 6 || a.length < 12) return false
        if (u.startsWith(SYS_PREFIX) || a.startsWith("Error:")) return false
        if (GREETING.matches(u)) return false
        if (u.startsWith("/leak", ignoreCase = true) || u.startsWith("/osint", ignoreCase = true) || LeakOsintEngine.parseStoredChatReport(a) != null) return false
        if (looksSecret(u) || looksSecret(a)) return false
        return tokens(u).isNotEmpty()
    }

    /** 1,0 untuk catatan baru, 0,5 setelah 30 hari, terus menurun. */
    fun recency(ts: Long, now: Long): Double = 1.0 / (1.0 + (now - ts).coerceAtLeast(0L) / DAY.toDouble() / 30.0)

    /** Tambah satu catatan. Pertanyaan yang kata kuncinya sama menggantikan jawaban lama (jumlah panggilan dipertahankan). */
    fun add(list: List<MemoryEntry>, user: String, ai: String, now: Long): List<MemoryEntry> {
        if (!worthRemembering(user, ai)) return list
        val u = clip(redactSecrets(user), USER_MAX)
        val a = clip(redactSecrets(ai), AI_MAX)
        val key = tokens(u)
        val out = ArrayList(list)
        val same = out.indexOfFirst { tokens(it.user) == key }
        if (same >= 0) out[same] = MemoryEntry(u, a, now, out[same].hits) else out.add(MemoryEntry(u, a, now, 0))
        return trim(out, now)
    }

    /** Bila lewat [MAX_ENTRIES], yang dibuang adalah catatan lama yang jarang dipanggil. Urutan waktu dipertahankan. */
    fun trim(list: List<MemoryEntry>, now: Long): List<MemoryEntry> =
        if (list.size <= MAX_ENTRIES) list
        else list.sortedByDescending { it.hits * 2.0 + recency(it.ts, now) }.take(MAX_ENTRIES).sortedBy { it.ts }

    /**
     * Catatan paling cocok dengan [query]: kecocokan kata kunci, ditambah sedikit bonus sering dipakai dan masih baru.
     * Kata yang jarang muncul di memori (mis. "proot") dihargai lebih tinggi daripada kata yang ada di mana-mana (mis. "install"),
     * jadi makin banyak catatan, makin tajam pemilihannya.
     */
    fun recall(list: List<MemoryEntry>, query: String, now: Long, k: Int = RECALL_K): List<MemoryEntry> {
        val q = tokens(query)
        if (q.isEmpty() || list.isEmpty()) return emptyList()
        val docs = list.map { Pair(tokens(it.user), tokens(it.ai)) }
        val n = list.size.toDouble()
        val df = HashMap<String, Int>()
        for (t in q) df[t] = docs.count { (ut, at) -> t in ut || t in at }
        return list.indices.mapNotNull { i ->
            val e = list[i]
            val (ut, at) = docs[i]
            var overlap = 0.0
            var rarity = 0.0
            for (t in q) {
                val inUser = t in ut
                val inAi = t in at
                overlap += if (inUser) 2.0 else if (inAi) 1.0 else 0.0
                if (inUser || inAi) rarity += ln(1.0 + n / (1 + (df[t] ?: 0)))
            }
            if (overlap < MIN_OVERLAP) null
            else Pair(e, overlap + minOf(e.hits, 5) * 0.1 + recency(e.ts, now) * 0.5 + rarity * 0.2)
        }.sortedByDescending { it.second }.take(k).map { it.first }
    }

    /**
     * Gabungkan dua daftar catatan (mis. memori di perangkat dan cadangan di Termux): pertanyaan yang kata kuncinya sama dijadikan satu,
     * jawaban yang lebih baru menang, jumlah panggilan diambil yang terbesar. Dipakai saat memulihkan cadangan supaya tidak ada yang hilang.
     */
    fun merge(base: List<MemoryEntry>, other: List<MemoryEntry>, now: Long): List<MemoryEntry> {
        val byKey = LinkedHashMap<Set<String>, MemoryEntry>()
        for (e in base + other) {
            val key = tokens(e.user).ifEmpty { setOf(e.user) }
            val old = byKey[key]
            byKey[key] = if (old == null) e else {
                val newer = if (e.ts >= old.ts) e else old
                MemoryEntry(newer.user, newer.ai, newer.ts, maxOf(e.hits, old.hits))
            }
        }
        return trim(byKey.values.sortedBy { it.ts }, now)
    }

    /** Catatan yang dipanggil kembali dihitung bertambah: yang berguna makin mudah muncul. */
    fun markUsed(list: List<MemoryEntry>, used: List<MemoryEntry>): List<MemoryEntry> =
        list.map { e -> if (used.any { it.user == e.user }) MemoryEntry(e.user, e.ai, e.ts, e.hits + 1) else e }

    /** Blok teks untuk prompt, dibatasi [budget] karakter. Kosong bila tidak ada yang muat. */
    fun format(entries: List<MemoryEntry>, budget: Int = RECALL_BUDGET): String {
        val head = "[Ingatan dari obrolan lama]\n"
        val sb = StringBuilder(head)
        for (e in entries) {
            val line = "- Tanya: ${clip(e.user, 160)} | Jawab: ${clip(e.ai, 260)}\n"
            if (sb.length + line.length > budget) break
            sb.append(line)
        }
        return if (sb.length == head.length) "" else sb.toString()
    }

    /** Gabungkan blok ingatan dengan pesan pengguna. Ditaruh di pesan pengguna (bukan prompt sistem) agar awalan prompt tetap sama dan cache Ollama terpakai. */
    fun withMemory(block: String, typed: String): String = if (block.isBlank()) typed else "$block\nPesan pengguna: $typed"

    /** Versi format file memori. Naikkan bila bentuk file berubah; [fromJson] harus tetap bisa membaca versi lama. */
    const val FORMAT_VERSION = 2

    fun toJson(list: List<MemoryEntry>): String {
        val a = JSONArray()
        for (e in list) a.put(JSONObject().put("u", e.user).put("a", e.ai).put("t", e.ts).put("h", e.hits))
        return JSONObject().put("v", FORMAT_VERSION).put("entries", a).toString()
    }

    /** Membaca format lama (larik polos, versi 1) dan format berversi. Daftar kosong bila teks rusak; entri yang tidak lengkap dilewati. */
    fun fromJson(text: String): List<MemoryEntry> = try {
        val t = text.trim()
        val a = if (t.startsWith("[")) JSONArray(t) else (JSONObject(t).optJSONArray("entries") ?: JSONArray())
        val out = ArrayList<MemoryEntry>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val u = o.optString("u")
            val ai = o.optString("a")
            if (u.isBlank() || ai.isBlank()) continue
            out.add(MemoryEntry(u, ai, o.optLong("t"), o.optInt("h")))
        }
        out
    } catch (e: Exception) { emptyList() }

    /** Pertukaran terakhir: pesan pengguna asli terakhir dan jawaban akhir AI (tanpa panggilan alat) sesudahnya. */
    fun lastExchange(msgs: List<Hist>): Pair<String, String>? {
        val ui = msgs.indexOfLast { it.role == 0 && it.text.isNotBlank() && !it.text.startsWith(SYS_PREFIX) }
        if (ui < 0) return null
        val ai = msgs.drop(ui + 1).lastOrNull { it.role == 1 && it.calls.isEmpty() && it.text.isNotBlank() } ?: return null
        return Pair(msgs[ui].text, ai.text)
    }

    /** Pasangan (pertanyaan, jawaban akhir) dari satu file chat lama, paling banyak [PER_CHAT_IMPORT] yang terbaru. */
    fun pairsFrom(messages: JSONArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var user: String? = null
        var ai: String? = null
        fun flush() { val u = user; val a = ai; if (u != null && a != null) out.add(Pair(u, a)) }
        for (i in 0 until messages.length()) {
            val o = messages.optJSONObject(i) ?: continue
            val text = o.optString("text")
            when (o.optInt("role", -1)) {
                0 -> if (text.isNotBlank() && !text.startsWith(SYS_PREFIX)) { flush(); user = text; ai = null }
                1 -> if (text.isNotBlank() && (o.optJSONArray("calls")?.length() ?: 0) == 0) ai = text
            }
        }
        flush()
        return out.takeLast(PER_CHAT_IMPORT)
    }
}
