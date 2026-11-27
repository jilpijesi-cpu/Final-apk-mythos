package com.build.mythos.cc

/**
 * Penjaga balasan model yang SAMA untuk semua penyedia dan semua jalur (native maupun protokol teks).
 *
 * Hanya aplikasi yang boleh menghasilkan blok hasil alat dan pengingat sistem. Model yang menulis keduanya sendiri sedang
 * MENGARANG hasil alat: teks itu tidak boleh dianggap jawaban, tidak boleh masuk riwayat (model berikutnya akan menirunya),
 * dan panggilan alat yang datang sesudahnya tidak boleh dijalankan karena bergantung pada hasil karangan itu.
 */
internal object ReplyGuard {
    // Bentuk asli yang dibuat aplikasi: tag pembuka dengan atribut id/name. Penyebutan biasa di narasi tidak ikut terjaring.
    private val FAKE_RESULT = Regex("""<tool_result\s+(?:id|name)\s*=""")
    private val FAKE_REMINDER = Regex("""(?m)^[ \t]*<system-reminder>""")

    // Panggilan alat bergaya lain (mis. read atau glob sebagai tag XML dengan argumen JSON) yang sering ditulis model
    // sebagai pengganti blok tool_use yang sah. Aplikasi tidak menjalankannya.
    private val FOREIGN_CALL = Regex(
        """<(?:read|write|edit|multiedit|multi_edit|glob|grep|ls|bash|todo_write|todowrite|run_command|read_file|write_file|list_dir)>\s*[\[{]""",
        RegexOption.IGNORE_CASE
    )

    const val FABRICATED_REASON =
        "balasan memuat hasil alat atau pengingat sistem yang ditulis sendiri (hanya aplikasi yang membuatnya)."
    const val FOREIGN_REASON =
        "balasan memuat panggilan alat dengan format yang salah (bukan blok tool_use yang sah), jadi tidak dijalankan."

    const val REPAIR_HINT =
        "Only the app writes tool results and system reminders; never write them yourself. " +
            "Make your tool call, then stop and wait for the real result."

    /** Posisi awal bagian karangan, atau -1 bila tidak ada. */
    fun cutIndex(text: String): Int {
        val a = FAKE_RESULT.find(text)?.range?.first ?: -1
        val b = FAKE_REMINDER.find(text)?.range?.first ?: -1
        return when {
            a < 0 -> b
            b < 0 -> a
            else -> minOf(a, b)
        }
    }

    fun hasForeignCalls(text: String): Boolean = FOREIGN_CALL.containsMatchIn(text)

    /** Teks tanpa bagian karangan (dipotong di penanda pertama). */
    fun clean(text: String): String {
        val i = cutIndex(text)
        return if (i < 0) text else text.substring(0, i).trimEnd()
    }

    /** Alasan menolak balasan yang TIDAK punya panggilan alat sah; null bila wajar. */
    fun rejectReason(text: String, validCalls: Int): String? = when {
        validCalls > 0 -> null
        cutIndex(text) >= 0 -> FABRICATED_REASON
        hasForeignCalls(text) -> FOREIGN_REASON
        else -> null
    }

    fun isGuardReason(reason: String?): Boolean = reason == FABRICATED_REASON || reason == FOREIGN_REASON
}
