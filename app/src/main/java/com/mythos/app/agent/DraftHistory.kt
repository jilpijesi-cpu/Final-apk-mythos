package com.mythos.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Riwayat versi draf email di folder proyek: setiap kartu draf yang dibuat agen disimpan sebagai versi bernomor,
 * sehingga isi sebelum perubahan tidak hilang dan bisa dibandingkan.
 *
 * Tata letak (di dalam proyek, jadi agen bisa membacanya dengan Read/LS):
 *   draf/<penerima>/v001.json, v002.json, ...   satu berkas per versi
 *   draf/RIWAYAT.md                              ringkasan yang mudah dibaca
 *
 * Status dibedakan antara draf, aplikasi email yang dibuka, percobaan Gmail yang perlu rekonsiliasi, dan hasil Gmail sukses.
 * Status percobaan dicatat sebelum POST; bila proses berhenti, hasilnya tidak diasumsikan gagal dan tidak dicoba ulang otomatis.
 * Draf yang isinya persis sama dengan versi terakhir tidak membuat versi baru.
 */
object DraftHistory {
    const val DIR = "draf"
    const val INDEX = "RIWAYAT.md"
    const val STATUS_DRAFT = "draf"
    const val STATUS_OPENED = "dibuka di aplikasi email"
    const val STATUS_SEND_ATTEMPTED = "percobaan Gmail dimulai; hasil perlu rekonsiliasi"
    const val STATUS_SENT = "terkirim lewat Gmail"

    data class Version(
        val key: String,
        val number: Int,
        val createdAt: Long,
        val status: String,
        val to: List<String>,
        val cc: List<String>,
        val subject: String,
        val body: String,
        val attachments: List<String>,
        val signature: String,
        val change: String,
    )

    fun keyFor(to: List<String>): String {
        val first = to.firstOrNull().orEmpty().lowercase(Locale.ROOT)
        val slug = first.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)
        return slug.ifEmpty { "tanpa-penerima" }
    }

    fun signature(to: List<String>, cc: List<String>, subject: String, body: String, attachments: List<String>): String {
        val raw = listOf(to.joinToString(","), cc.joinToString(","), subject.trim(), body.trim(), attachments.joinToString(","))
            .joinToString("\u0001")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Menyimpan draf sebagai versi baru, atau mengembalikan versi terakhir bila isinya sama persis. */
    @Synchronized
    fun record(
        projectRoot: File, to: List<String>, cc: List<String>, subject: String, body: String, attachments: List<String>,
        now: Long = System.currentTimeMillis(),
    ): Version {
        val key = keyFor(to)
        val dir = File(File(projectRoot, DIR), key).also { it.mkdirs() }
        val existing = list(projectRoot, key)
        val sig = signature(to, cc, subject, body, attachments)
        existing.lastOrNull()?.let { if (it.signature == sig) return it }
        val prev = existing.lastOrNull()
        val v = Version(
            key = key, number = (prev?.number ?: 0) + 1, createdAt = now, status = STATUS_DRAFT,
            to = to, cc = cc, subject = subject, body = body, attachments = attachments, signature = sig,
            change = describeChange(prev, subject, body, attachments),
        )
        write(dir, v)
        writeIndex(projectRoot)
        return v
    }

    /** Menandai versi yang cocok dengan isi ini sebagai sudah dibuka di aplikasi email. Null bila tidak ditemukan. */
    @Synchronized
    fun markOpened(projectRoot: File, to: List<String>, cc: List<String>, subject: String, body: String, attachments: List<String>): Version? {
        val key = keyFor(to)
        val sig = signature(to, cc, subject, body, attachments)
        val hit = list(projectRoot, key).lastOrNull { it.signature == sig } ?: return null
        if (hit.status in setOf(STATUS_OPENED, STATUS_SEND_ATTEMPTED, STATUS_SENT)) return hit
        val updated = hit.copy(status = STATUS_OPENED)
        write(File(File(projectRoot, DIR), key), updated)
        writeIndex(projectRoot)
        return updated
    }

    /** Menandai versi sebelum POST; status ini tetap ada jika coroutine/proses berhenti sebelum hasil diketahui. */
    @Synchronized
    fun markSendAttempted(projectRoot: File, to: List<String>, cc: List<String>, subject: String, body: String, attachments: List<String>): Version? {
        val key = keyFor(to)
        val sig = signature(to, cc, subject, body, attachments)
        val hit = list(projectRoot, key).lastOrNull { it.signature == sig } ?: return null
        if (hit.status !in setOf(STATUS_DRAFT, STATUS_OPENED)) return null
        val updated = hit.copy(status = STATUS_SEND_ATTEMPTED)
        write(File(File(projectRoot, DIR), key), updated)
        writeIndex(projectRoot)
        return updated
    }

    /** Menandai hasil sukses yang dikonfirmasi oleh respons Gmail API. */
    @Synchronized
    fun markSent(projectRoot: File, to: List<String>, cc: List<String>, subject: String, body: String, attachments: List<String>): Version? {
        val key = keyFor(to)
        val sig = signature(to, cc, subject, body, attachments)
        val hit = list(projectRoot, key).lastOrNull { it.signature == sig } ?: return null
        if (hit.status == STATUS_SENT) return hit
        val updated = hit.copy(status = STATUS_SENT)
        write(File(File(projectRoot, DIR), key), updated)
        writeIndex(projectRoot)
        return updated
    }

    fun list(projectRoot: File, key: String): List<Version> {
        val dir = File(File(projectRoot, DIR), key)
        val files = dir.listFiles { f -> f.isFile && f.name.matches(Regex("v\\d{3,}\\.json")) } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { f -> runCatching { parse(JSONObject(f.readText(Charsets.UTF_8)), key) }.getOrNull() }
            .sortedBy { it.number }
    }

    fun listAll(projectRoot: File): List<Version> {
        val keys = File(projectRoot, DIR).listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: return emptyList()
        return keys.flatMap { list(projectRoot, it) }
    }

    /** Ringkasan perubahan dari versi sebelumnya (hitungan baris, bukan diff penuh). */
    fun describeChange(prev: Version?, subject: String, body: String, attachments: List<String>): String {
        if (prev == null) return "versi pertama"
        val parts = mutableListOf<String>()
        if (prev.subject.trim() != subject.trim()) parts.add("subjek diubah")
        val old = prev.body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val new = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val added = new.count { it !in old }
        val removed = old.count { it !in new }
        if (added > 0 || removed > 0) parts.add("isi: +$added baris, -$removed baris")
        if (prev.attachments != attachments) parts.add("lampiran diubah")
        if (parts.isEmpty()) parts.add("penerima diubah")
        return parts.joinToString("; ")
    }

    private fun write(dir: File, v: Version) {
        dir.mkdirs()
        val json = JSONObject()
            .put("number", v.number).put("createdAt", v.createdAt).put("status", v.status)
            .put("to", JSONArray(v.to)).put("cc", JSONArray(v.cc)).put("subject", v.subject).put("body", v.body)
            .put("attachments", JSONArray(v.attachments)).put("signature", v.signature).put("change", v.change)
        val target = File(dir, "v%03d.json".format(Locale.ROOT, v.number))
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        if (!tmp.renameTo(target)) { target.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8); tmp.delete() }
    }

    private fun parse(o: JSONObject, key: String): Version {
        fun strings(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return Version(
            key = key, number = o.getInt("number"), createdAt = o.optLong("createdAt"), status = o.optString("status", STATUS_DRAFT),
            to = strings("to"), cc = strings("cc"), subject = o.optString("subject"), body = o.optString("body"),
            attachments = strings("attachments"), signature = o.optString("signature"), change = o.optString("change"),
        )
    }

    private fun writeIndex(projectRoot: File) {
        val all = listAll(projectRoot)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Asia/Jakarta") }
        val sb = StringBuilder("# Riwayat versi draf email\n\n")
        sb.append("Setiap versi tersimpan di `draf/<penerima>/vNNN.json`. Status \"dibuka di aplikasi email\" belum tentu terkirim. ")
        sb.append("Status percobaan Gmail berarti hasil perlu diperiksa manual di Gmail > Terkirim; tidak dilakukan retry otomatis.\n")
        all.groupBy { it.key }.forEach { (key, versions) ->
            sb.append("\n## ").append(key).append("\n")
            versions.forEach { v ->
                sb.append("- v%03d (".format(Locale.ROOT, v.number)).append(fmt.format(Date(v.createdAt))).append(" WIB) — ")
                    .append(v.status).append("; ").append(v.change).append("; subjek: ").append(v.subject.replace("\n", " ").take(100)).append("\n")
            }
        }
        File(projectRoot, DIR).mkdirs()
        File(File(projectRoot, DIR), INDEX).writeText(sb.toString(), Charsets.UTF_8)
    }
}
