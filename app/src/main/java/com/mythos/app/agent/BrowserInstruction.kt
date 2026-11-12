package com.mythos.app.agent

import java.util.Locale

/** Perintah Browser Agent yang sudah diparse dari bahasa natural. */
sealed class BrowserCommand {
    object Read : BrowserCommand()
    object Inspect : BrowserCommand()
    data class Fill(val label: String, val value: String) : BrowserCommand()
    data class Choose(val label: String, val value: String) : BrowserCommand()
    data class Check(val label: String, val checked: Boolean) : BrowserCommand()
    data class Radio(val label: String, val value: String) : BrowserCommand()
    data class DateField(val label: String, val isoDate: String) : BrowserCommand()
    data class Click(val label: String) : BrowserCommand()
}

object BrowserInstruction {
    private const val Q = "[\"'“”‘’]?"
    private val MONTHS = mapOf(
        "januari" to 1, "februari" to 2, "maret" to 3, "april" to 4, "mei" to 5, "juni" to 6, "juli" to 7,
        "agustus" to 8, "september" to 9, "oktober" to 10, "november" to 11, "desember" to 12,
        "january" to 1, "february" to 2, "march" to 3, "may" to 5, "june" to 6, "july" to 7, "august" to 8,
        "october" to 10, "december" to 12,
    )

    private fun String.clean() = trim().trim('"', '\'', '“', '”', '‘', '’', '.', ' ')

    /** Terima yyyy-MM-dd, dd/MM/yyyy, dd-MM-yyyy, atau "8 November 2026". Kembalikan yyyy-MM-dd atau null. */
    fun toIsoDate(raw: String): String? {
        val s = raw.trim().lowercase(Locale.ROOT)
        fun ok(y: Int, m: Int, d: Int): String? {
            if (m !in 1..12 || d !in 1..31 || y !in 1900..2200) return null
            val max = when (m) { 2 -> if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
            if (d > max) return null
            return "%04d-%02d-%02d".format(Locale.ROOT, y, m, d)
        }
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(s)?.let { return ok(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        Regex("^(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})$").find(s)?.let { return ok(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
        Regex("^(\\d{1,2})\\s+([a-z]+)\\s+(\\d{4})$").find(s)?.let { m ->
            val mon = MONTHS[m.groupValues[2]] ?: return null
            return ok(m.groupValues[3].toInt(), mon, m.groupValues[1].toInt())
        }
        return null
    }

    /** Null bila instruksi belum dikenali (lalu diserahkan ke perencana LLM). */
    fun parse(input: String): BrowserCommand? {
        val t = input.trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase(Locale.ROOT)

        if (Regex("^(?:periksa|petakan|daftar(?:kan)?)\\s+(?:semua\\s+)?(?:kolom|field|form)").containsMatchIn(lower)) return BrowserCommand.Inspect

        Regex("(?i)^(?:centang|ceklis|aktifkan|pilih)\\s+(?:checkbox|kotak)?\\s*$Q(.+?)$Q$").find(t)?.let {
            if (lower.startsWith("centang") || lower.startsWith("ceklis") || lower.startsWith("aktifkan") || lower.contains("checkbox") || lower.contains("kotak"))
                return BrowserCommand.Check(it.groupValues[1].clean(), true)
        }
        Regex("(?i)^(?:hapus\\s+centang|batal(?:kan)?\\s+centang|nonaktifkan|uncheck)\\s+(?:checkbox|kotak)?\\s*$Q(.+?)$Q$").find(t)?.let {
            return BrowserCommand.Check(it.groupValues[1].clean(), false)
        }
        Regex("(?i)^(?:pilih|pilihkan)\\s+(?:radio|opsi\\s+radio)\\s+$Q(.+?)$Q\\s+(?:pada|untuk|di)\\s+$Q(.+?)$Q$").find(t)?.let {
            return BrowserCommand.Radio(it.groupValues[2].clean(), it.groupValues[1].clean())
        }
        Regex("(?i)^(?:pilih|pilihkan)\\s+(?:radio|opsi\\s+radio)\\s+$Q(.+?)$Q$").find(t)?.let {
            return BrowserCommand.Radio("", it.groupValues[1].clean())
        }
        Regex("(?i)^(?:isi|isikan|atur|set)\\s+(?:tanggal|kolom\\s+tanggal)\\s+$Q(.+?)$Q\\s+(?:dengan|menjadi|ke)\\s+$Q(.+?)$Q$").find(t)?.let {
            val iso = toIsoDate(it.groupValues[2].clean()) ?: return null
            return BrowserCommand.DateField("tanggal " + it.groupValues[1].clean(), iso)
        }
        Regex("(?i)^(?:isi|isikan|atur|set)\\s+(?:kolom|field)?\\s*$Q(.+?)$Q\\s+dengan\\s+$Q(.+?)$Q$").find(t)?.let {
            val label = it.groupValues[1].clean()
            val value = it.groupValues[2].clean()
            toIsoDate(value)?.takeIf { label.lowercase(Locale.ROOT).contains("tanggal") || label.lowercase(Locale.ROOT).contains("tgl") || label.lowercase(Locale.ROOT).contains("date") }
                ?.let { iso -> return BrowserCommand.DateField(label, iso) }
            return BrowserCommand.Fill(label, value)
        }
        Regex("(?i)^pilih(?:kan)?\\s+$Q(.+?)$Q\\s+(?:pada|untuk|di)\\s+(?:dropdown|menu|daftar|kolom|field)?\\s*$Q(.+?)$Q$").find(t)?.let {
            return BrowserCommand.Choose(it.groupValues[2].clean(), it.groupValues[1].clean())
        }
        Regex("(?i)^(?:klik|tekan|ketuk)\\s+$Q(.+?)$Q$").find(t)?.let { return BrowserCommand.Click(it.groupValues[1].clean()) }

        if (Regex("(?i)^(?:baca|ringkas|lihat)\\b").containsMatchIn(t) && t.split(Regex("\\s+")).size <= 4) return BrowserCommand.Read
        return null
    }
}
