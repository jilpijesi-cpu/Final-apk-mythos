package com.build.mythos

// Logika murni (tanpa Android) untuk dialog ask_user, supaya bisa diuji sebagai tes JVM.

private val WH_START = Regex("(?i)^(apa|siapa|mana|berapa|bagaimana|gimana|kapan|mengapa|kenapa|dimana|di mana)\\b")
private val YES_NO_WORDS = Regex("(?i)\\b(sudah|belum|setuju|boleh|ingin|lanjut|siap|memiliki|punya)\\b")

/**
 * True bila pertanyaan cukup dijawab IYA/TIDAK. Pertanyaan terbuka ("Apa yang ingin Anda bersihkan?") bukan ya/tidak
 * walau memuat kata seperti "ingin"; dulu kata itu saja sudah membuatnya diperlakukan sebagai ya/tidak.
 */
internal fun isYesNoQuestion(question: String, options: List<String>): Boolean {
    if (options.isNotEmpty()) return false
    val q = question.trimStart()
    if (q.startsWith("apakah", ignoreCase = true)) return true
    if (WH_START.containsMatchIn(q)) return false
    return YES_NO_WORDS.containsMatchIn(q)
}
