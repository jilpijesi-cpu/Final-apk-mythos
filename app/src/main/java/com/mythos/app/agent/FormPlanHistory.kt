package com.mythos.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Riwayat versi isi form yang diisi lewat rencana Browser Agent (mis. uraian keberatan), supaya teks sebelum
 * diubah atau dikirim tidak hilang. Disimpan sebagai `<root>/form/<situs>/vNNN.json`. Rencana yang isinya sama
 * persis dengan versi terakhir tidak membuat versi baru. Nilai disamarkan lewat [SensitiveDataGuard] sebelum disimpan.
 */
object FormPlanHistory {
    data class Entry(val action: String, val label: String, val value: String)

    data class Version(val host: String, val number: Int, val createdAt: Long, val entries: List<Entry>, val signature: String)

    fun hostKey(host: String): String =
        host.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "tanpa-situs" }

    fun signature(entries: List<Entry>): String {
        val raw = entries.joinToString("\u0002") { listOf(it.action, it.label, it.value).joinToString("\u0001") }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Menyimpan snapshot rencana. Null bila [entries] kosong. */
    fun record(root: File, host: String, entries: List<Entry>, now: Long = System.currentTimeMillis()): Version? {
        if (entries.isEmpty()) return null
        val safe = entries.map { it.copy(value = SensitiveDataGuard.redactSensitiveCredentials(it.value)) }
        val key = hostKey(host)
        val existing = list(root, host)
        val sig = signature(safe)
        existing.lastOrNull()?.let { if (it.signature == sig) return it }
        val v = Version(host, (existing.lastOrNull()?.number ?: 0) + 1, now, safe, sig)
        val dir = File(File(root, "form"), key).also { it.mkdirs() }
        val json = JSONObject().put("number", v.number).put("host", host).put("createdAt", now).put("signature", sig)
            .put("entries", JSONArray(safe.map { JSONObject().put("action", it.action).put("label", it.label).put("value", it.value) }))
        val target = File(dir, "v%03d.json".format(Locale.ROOT, v.number))
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        if (!tmp.renameTo(target)) { target.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8); tmp.delete() }
        return v
    }

    fun list(root: File, host: String): List<Version> {
        val dir = File(File(root, "form"), hostKey(host))
        val files = dir.listFiles { f -> f.isFile && f.name.matches(Regex("v\\d{3,}\\.json")) } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText(Charsets.UTF_8))
                val arr = o.optJSONArray("entries") ?: JSONArray()
                Version(
                    host = o.optString("host", host), number = o.getInt("number"), createdAt = o.optLong("createdAt"),
                    entries = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Entry(it.optString("action"), it.optString("label"), it.optString("value")) } },
                    signature = o.optString("signature"),
                )
            }.getOrNull()
        }.sortedBy { it.number }
    }
}
