package com.build.mythos.cc

import org.json.JSONException
import org.json.JSONObject

/**
 * Pemadatan konteks deterministik (tanpa panggilan LLM tambahan):
 * - Pesan pertama, catatan temuan (<agent-findings>), dan hasil alat yang baru dibaca tetap utuh.
 * - Pemadatan bertingkat: tahap lunak mempertahankan cuplikan hasil alat dan penalaran lebih panjang
 *   (1.200 karakter) sebelum tahap keras mengosongkan hasil alat yang paling lama.
 */
internal object ContextManager {

    private val RESULT = Regex("<tool_result ([^>]*)>\\n?([\\s\\S]*?)\\n?</tool_result>")
    private val USE = Regex("<tool_use>([\\s\\S]*?)</tool_use>")
    private val REMINDER = Regex("<system-reminder>[\\s\\S]*?</system-reminder>")

    /** Minimum jumlah pesan terbaru yang hasil alatnya tidak boleh dihapus saat pemadatan. */
    private const val MIN_PROTECTED_RECENT = 12
    /** Batas potongan cuplikan hasil alat pada pemadatan lunak agar temuan terbaru tidak hilang. */
    private const val SOFT_RESULT_CHARS = 1_200
    /** Batas potongan teks penalaran asisten lama (dinaikkan dari 240 agar kesimpulan tetap terbaca). */
    private const val ASSISTANT_REASONING_CHARS = 1_200

    /** Hasil pemadatan: [view] yang dikirim ke model dan [keep] = jumlah pesan terbaru yang benar-benar masih utuh. */
    class Packed(val view: List<Msg>, val keep: Int, val compacted: Boolean)

    fun compact(
        history: List<Msg>,
        budget: Int,
        keepRecent: Int,
        findings: List<String> = emptyList(),
    ): List<Msg> = compactWithInfo(history, budget, keepRecent, findings).view

    fun compactWithInfo(
        history: List<Msg>,
        budget: Int,
        keepRecent: Int,
        findings: List<String> = emptyList(),
    ): Packed {
        val base = attachFindings(history, findings)
        if (base.sumOf { it.text.length } <= budget) return Packed(base, base.size, false)

        // Tahap 1 (lunak): jaga pesan terbaru utuh dan pertahankan ringkasan/cuplikan hasil alat lama tanpa menghapusnya.
        val soft = digestOlder(base, keepRecent, clearOldResults = false)
        if (soft.sumOf { it.text.length } <= budget) return Packed(soft, keepRecent, true)

        // Tahap 2 (keras bertahap): kosongkan hanya hasil alat yang paling lama sambil tetap melindungi pesan terbaru.
        val minKeep = minOf(base.size, maxOf(MIN_PROTECTED_RECENT, keepRecent / 2))
        var keep = keepRecent
        var view = soft
        var used = keepRecent
        while (keep >= minKeep) {
            view = digestOlder(base, keep, clearOldResults = true)
            used = keep
            if (view.sumOf { it.text.length } <= budget) return Packed(view, used, true)
            keep -= 2
        }

        // Cadangan terakhir bila konteks sangat ekstrem: tetap sisakan minimal 8 pesan terbaru (4 giliran terakhir) utuh.
        val floorKeep = minOf(base.size, 8)
        while (keep >= floorKeep) {
            view = digestOlder(base, keep, clearOldResults = true)
            used = keep
            if (view.sumOf { it.text.length } <= budget) return Packed(view, used, true)
            keep -= 2
        }
        return Packed(view, used, view !== base)
    }

    /**
     * Menyematkan catatan temuan agen (<agent-findings>) ke pesan pertama (indeks 0),
     * yang tidak pernah dipadatkan oleh [digestOlder], sehingga kesimpulan agen tidak pernah hilang.
     */
    private fun attachFindings(history: List<Msg>, findings: List<String>): List<Msg> {
        if (history.isEmpty() || findings.isEmpty()) return history
        val block = buildString {
            append("\n\n<agent-findings>\n")
            append("Catatan temuan yang disematkan (tidak pernah dipadatkan; jadikan rujukan agar tidak mengulang pembacaan yang sama):\n")
            for (item in findings) {
                append("- ").append(item.trim()).append('\n')
            }
            append("</agent-findings>")
        }
        val first = history[0]
        val updated = ArrayList<Msg>(history.size)
        updated.add(Msg(first.role, first.text + block, first.images))
        for (i in 1 until history.size) updated.add(history[i])
        return updated
    }

    private fun digestOlder(history: List<Msg>, keep: Int, clearOldResults: Boolean): List<Msg> {
        val cutoff = (history.size - keep).coerceAtLeast(1)
        // Hasil alat yang baru dibaca (dalam 16 pesan terakhir) tidak pernah dikosongkan total.
        val softFloor = (history.size - maxOf(keep, 16)).coerceAtLeast(1)
        return history.mapIndexed { i, m ->
            if (i == 0 || i >= cutoff) m
            else if (m.role == Role.USER) {
                val allowClear = clearOldResults && i < softFloor
                Msg(m.role, digestResults(m.text, allowClear))
            } else {
                Msg(m.role, digestAssistant(m.text))
            }
        }
    }

    private fun digestResults(t: String, allowClear: Boolean): String {
        if (!t.contains("<tool_result")) return clip(t, 800)
        val noReminders = t.replace(REMINDER, "").trim()
        return RESULT.replace(noReminders) { m ->
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            when {
                attrs.contains("is_error=\"true\"") ->
                    "<tool_result $attrs>\n${clip(body, 800)}\n</tool_result>"
                !allowClear || body.length <= SOFT_RESULT_CHARS ->
                    "<tool_result $attrs>\n${clip(body, SOFT_RESULT_CHARS)}\n</tool_result>"
                else ->
                    "<tool_result $attrs>[content cleared: ${body.length} chars; run the tool again if needed]</tool_result>"
            }
        }
    }

    private fun digestAssistant(t: String): String {
        val withSlimCalls = USE.replace(t) { m -> Protocol.OPEN + slimCall(m.groupValues[1]) + Protocol.CLOSE }
        val firstUse = withSlimCalls.indexOf(Protocol.OPEN)
        if (firstUse <= 0) return clip(withSlimCalls, ASSISTANT_REASONING_CHARS)
        return clip(withSlimCalls.substring(0, firstUse), ASSISTANT_REASONING_CHARS) + "\n" + withSlimCalls.substring(firstUse)
    }

    private fun slimCall(body: String): String = try {
        val o = JSONObject(body.trim())
        val input = o.optJSONObject("input")
        if (input != null) {
            val slim = JSONObject()
            for (k in input.keys()) {
                val v = input.opt(k)
                slim.put(k, if (v is String && v.length > 240) "<${v.length} chars trimmed>" else v)
            }
            o.put("input", slim)
        }
        unescapeSlashes(o.toString())
    } catch (e: JSONException) {
        clip(body, 300)
    }
}
