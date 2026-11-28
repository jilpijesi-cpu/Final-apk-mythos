package com.build.mythos

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deteksi teks panjang di kolom ketik untuk otomatis diubah menjadi lampiran file
 * agar tidak terpotong oleh batas konteks/output token model AI.
 * Murni Kotlin (tanpa android.*) supaya 100% dapat diuji di JVM.
 */
internal object LongTextAttachment {
    const val MIN_CHARS = 1_500
    const val MIN_LINES = 25
    const val DIGEST_MAX_CHARS = 24_000

    /**
     * True jika [text] cukup panjang (>= [MIN_CHARS] karakter atau >= [MIN_LINES] baris)
     * dan bukan perintah pencarian LeakOSINT.
     */
    fun shouldConvert(text: String, leakModeActive: Boolean): Boolean {
        if (leakModeActive) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (LeakOsintEngine.isCommandPrefix(trimmed)) return false
        if (trimmed.length >= MIN_CHARS) return true
        val lines = trimmed.count { it == '\n' } + 1
        return lines >= MIN_LINES
    }

    /** Tebak ekstensi file paling cocok berdasarkan isi teks. */
    fun detectExtension(text: String): String {
        val sample = text.trimStart()
        val head = sample.take(600).lowercase(Locale.ROOT)
        return when {
            head.startsWith("<!doctype html") || head.startsWith("<html") ||
                (head.contains("<head") && head.contains("<body")) ||
                (head.contains("<div") && sample.contains("</html>", ignoreCase = true)) -> "html"
            (sample.startsWith("{") && sample.trimEnd().endsWith("}")) ||
                (sample.startsWith("[") && sample.trimEnd().endsWith("]")) -> "json"
            head.startsWith("<?xml") || head.startsWith("<svg") -> "xml"
            Regex("(?m)^\\s*(package\\s+[a-z0-9_.]+|fun\\s+[a-zA-Z_]|internal\\s+fun\\s+)").containsMatchIn(sample.take(1200)) -> "kt"
            Regex("(?m)^\\s*(def\\s+[a-zA-Z_]|from\\s+\\S+\\s+import\\s+)").containsMatchIn(sample.take(1200)) -> "py"
            Regex("(?m)^\\s*(function\\s+[a-zA-Z_]|const\\s+[a-zA-Z_]+\\s*=|export\\s+(default|function|const))").containsMatchIn(sample.take(1200)) -> "js"
            else -> "txt"
        }
    }

    /** Buat nama file lampiran otomatis yang rapi dan unik berdasarkan waktu. */
    fun buildFileName(text: String, nowMs: Long = System.currentTimeMillis()): String {
        val ext = detectExtension(text)
        val stamp = SimpleDateFormat("HHmmss", Locale.ROOT).format(Date(nowMs))
        return "teks_$stamp.$ext"
    }

    /** Ringkasan singkat 1 baris untuk catatan lampiran aktif. */
    fun buildSummary(text: String): String {
        val lines = text.count { it == '\n' } + 1
        return "Teks panjang (${text.length} karakter, $lines baris) disimpan otomatis sebagai file."
    }

    /** Potongan isi aman untuk digest awal riwayat percakapan. */
    fun buildDigest(text: String): String =
        if (text.length <= DIGEST_MAX_CHARS) text
        else text.take(DIGEST_MAX_CHARS) + "\n…(dipotong untuk pratinjau; baca file lengkap dari disk)"
}
