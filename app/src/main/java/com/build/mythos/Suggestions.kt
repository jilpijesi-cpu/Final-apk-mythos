package com.build.mythos

// Chip saran yang mengikuti isi balasan AI: hanya muncul bila memang ada yang perlu dijawab atau dilanjutkan.
// Logika murni (tanpa Android) supaya bisa diuji unit.

internal data class SuggestChip(val label: String, val prompt: String)

private const val MAX_CHIPS = 4
private const val MAX_LABEL = 28

private val optionLine = Regex("""^\s*(?:\d{1,2}[.)]|[A-Da-d][.)]|[-*•])\s+(.+?)\s*$""")
private val yesNoStart = Regex(
    """^(apakah|apa\s+(?:kamu|anda|mau|perlu|boleh)|mau|ingin|perlu|boleh|haruskah|sebaiknya|lanjut|setuju|oke|ok|bisa|jadi|kalau begitu|sudah|is|do|does|should|shall|can|may|would|want|ready)\b""",
    RegexOption.IGNORE_CASE
)
private val openWord = Regex(
    """\b(siapa|di\s*mana|dimana|ke\s*mana|kemana|kapan|berapa|bagaimana|gimana|kenapa|mengapa|yang\s+mana|apa\s+(?:nama|yang|saja|isi|alasan)|what|who|where|when|how|why|which)\b""",
    RegexOption.IGNORE_CASE
)
private val choiceCue = Regex("""\b(pilih|opsi|pilihan|salah\s+satu|choose|select|options?)\b""", RegexOption.IGNORE_CASE)

private fun cleanTail(text: String): String = text.trimEnd().trimEnd('*', '_', '`', ' ', '\n')

private fun clean(s: String): String =
    s.replace(Regex("""[*_`]+"""), "").trim().trim('"', '\'', '.', ',', ';', ':', '?').trim()

private fun label(s: String): String =
    if (s.length <= MAX_LABEL) s else s.take(MAX_LABEL - 1).trimEnd() + "…"

/** Opsi bernomor/berhuruf/berbutir di blok paling bawah, bila tepat di atasnya ada pertanyaan. */
internal fun trailingOptions(text: String): List<String> {
    val lines = cleanTail(text).lines()
    var i = lines.size - 1
    val opts = ArrayList<String>()
    while (i >= 0) {
        val m = optionLine.matchEntire(lines[i]) ?: break
        opts.add(0, clean(m.groupValues[1]))
        i--
    }
    if (opts.size < 2) return emptyList()
    // Pertanyaan harus ada di baris-baris tepat sebelum daftar (abaikan baris kosong).
    var j = i
    while (j >= 0 && lines[j].isBlank()) j--
    val hasQuestion = j >= 0 && (lines[j].contains('?') || (lines[j].trimEnd().endsWith(":") && choiceCue.containsMatchIn(lines[j])))
    return if (hasQuestion) opts.filter { it.isNotEmpty() } else emptyList()
}

/** Pola "A atau B?" di kalimat terakhir; hanya dipakai bila tiap opsi pendek dan jelas. */
internal fun inlineOptions(question: String): List<String> {
    var q = clean(question)
    q = q.replace(Regex("""^(apakah|mau|ingin|perlu|sebaiknya|lebih\s+suka|pilih|kamu\s+mau|anda\s+mau)\s+""", RegexOption.IGNORE_CASE), "")
    if (!Regex("""\batau\b|\bor\b""", RegexOption.IGNORE_CASE).containsMatchIn(q)) return emptyList()
    val parts = q.split(Regex("""\s*,\s*|\s+atau\s+|\s+or\s+""", RegexOption.IGNORE_CASE))
        .map { clean(it) }
        .filter { it.isNotEmpty() }
    if (parts.size !in 2..3) return emptyList()
    if (parts.any { it.split(' ').size > 5 }) return emptyList()
    // "build Debug atau Release" -> "Debug", "Release": buang kata kerja pengantar di opsi pertama.
    if (parts.drop(1).all { !it.contains(' ') } && parts[0].contains(' ')) {
        return listOf(parts[0].substringAfterLast(' ')) + parts.drop(1)
    }
    return parts
}

private fun lastSentence(text: String): String {
    val body = cleanTail(text)
    val para = body.substringAfterLast("\n\n").substringAfterLast("\n")
    // Ambil kalimat terakhir yang diakhiri '?'.
    val idx = para.dropLast(1).lastIndexOfAny(charArrayOf('.', '!', '?', ':'))
    return if (idx >= 0) para.substring(idx + 1).trim() else para.trim()
}

/**
 * Chip untuk sebuah balasan AI.
 * - Pertanyaan berpilihan  -> satu chip per pilihan.
 * - Pertanyaan ya/tidak    -> tanpa chip (tombol konfirmasi dihapus).
 * - Pertanyaan terbuka     -> tanpa chip (jawabannya harus diketik).
 * - Bukan pertanyaan       -> hanya tindakan yang benar-benar relevan (perbaiki error).
 * [unfinished] = tugas berhenti sebelum selesai (gagal, terpotong, atau dihentikan).
 */
internal fun contextualChips(text: String, sawFail: Boolean, sawBuild: Boolean, unfinished: Boolean): List<SuggestChip> {
    val items = ArrayList<SuggestChip>()
    val body = cleanTail(text)

    val listOpts = trailingOptions(body)
    if (listOpts.isNotEmpty()) {
        for (o in listOpts.take(MAX_CHIPS)) items.add(SuggestChip(label(o), o))
        return items
    }

    if (body.endsWith("?")) {
        val q = lastSentence(body)
        val inline = inlineOptions(q)
        when {
            inline.isNotEmpty() -> inline.forEach { items.add(SuggestChip(label(it), it)) }
            openWord.containsMatchIn(q) && !yesNoStart.containsMatchIn(q) -> Unit
            else -> Unit   // pertanyaan ya/tidak: tidak ada tombol konfirmasi, pengguna menjawab lewat ketikan
        }
        return items.take(MAX_CHIPS)
    }

    if (sawFail) {
        items.add(
            if (sawBuild) SuggestChip("Perbaiki lalu build", "Perbaiki error tadi, lalu build ulang dengan ./gradlew assembleDebug dan pastikan berhasil.")
            else SuggestChip("Perbaiki error", "Perbaiki error tadi lalu coba lagi.")
        )
    }
    if (unfinished) items.add(SuggestChip("Lanjutkan tugas", "Lanjutkan"))
    return items.take(MAX_CHIPS)
}
