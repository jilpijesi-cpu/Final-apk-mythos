package com.build.mythos

import android.view.View
import org.json.JSONObject

// Penalaran mendalam: untuk masalah kompleks. Bila aktif (menu +), agen:
// 1) berpikir dulu di tahap tersendiri tanpa tool (analisis, pendekatan, risiko, cara verifikasi),
// 2) membawa hasil analisis itu sebagai acuan di prompt sistem selama tugas berjalan,
// 3) memakai mode berpikir bawaan penyedia bila modelnya mendukung (Gemini 2.5, model reasoning OpenAI).
// Anthropic tidak memakai mode bawaan karena blok berpikirnya harus ikut dikirim balik tiap putaran tool.

internal fun MainActivity.deepOn(): Boolean = prefs.getBoolean("deep", false)

internal fun MainActivity.setDeep(on: Boolean) {
    val was = deepOn()
    prefs.edit().putBoolean("deep", on).apply()
    runOnUiThread {
        deepBtn.animate().cancel()
        if (on) {
            if (deepBtn.visibility != View.VISIBLE) {
                deepBtn.visibility = View.VISIBLE
                if (on != was) {   // muncul halus: membesar dari 80% sambil memudar masuk, lalu otak berdenyut
                    deepBtn.alpha = 0f; deepBtn.scaleX = 0.8f; deepBtn.scaleY = 0.8f
                    deepBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
            } else { deepBtn.alpha = 1f; deepBtn.scaleX = 1f; deepBtn.scaleY = 1f }
        } else {
            deepBtn.alpha = 1f; deepBtn.scaleX = 1f; deepBtn.scaleY = 1f
            deepBtn.visibility = View.GONE
        }
        // Bola <-> otak langsung berubah bila ada tugas berjalan; saat idle, baris live memang tidak tampil.
        if (busy && on != was) try { zone.deepMode(on, true) } catch (e: UninitializedPropertyAccessException) { /* tampilan belum dibangun */ }
    }
    // Dipakai live: loop agen membaca ulang pengaturan ini di awal tiap putaran, jadi tugas yang sedang berjalan ikut berubah.
    if (busy && on != was) addNote(
        if (on) "Penalaran mendalam aktif: diterapkan mulai langkah berikutnya."
        else "Penalaran mendalam dimatikan: berlaku mulai langkah berikutnya."
    )
}

/** Keputusan di awal tiap putaran agen: jalankan tahap berpikir, bersihkan catatannya, atau tidak melakukan apa-apa. */
internal enum class DeepAction { NONE, RUN, CLEAR }

internal fun deepStageAction(on: Boolean, stageDone: Boolean, simple: Boolean): DeepAction = when {
    !on -> if (stageDone) DeepAction.CLEAR else DeepAction.NONE
    stageDone || simple -> DeepAction.NONE
    else -> DeepAction.RUN
}

/** Ringkasan riwayat untuk tahap berpikir: pesan, alat yang dipanggil, dan potongan hasil alat. */
internal fun deepHistoryDigest(items: List<Hist>): String = items.joinToString("\n") { h ->
    when (h.role) {
        0 -> "Pengguna: ${h.text.take(400)}"
        1 -> "AI: ${h.text.take(400)}" + (if (h.calls.isEmpty()) "" else " [alat: ${h.calls.joinToString { it.name }}]")
        else -> "Hasil alat: " + h.results.joinToString("; ") { (c, o) -> "${c.name} -> ${o.take(160).replace('\n', ' ')}" }
    }
}

/** Mode CLI (mis. gemini -p) tidak lewat loop agen; instruksi berpikir dititipkan di awal prompt. */
internal const val DEEP_CLI_PREFIX = "Berpikirlah mendalam sebelum menjawab: uraikan masalahnya, bandingkan minimal dua pendekatan, " +
    "pilih yang paling kuat, periksa ulang kesimpulan terhadap bukti, dan sebutkan bagian yang belum terverifikasi. " +
    "Jawab dalam bahasa Indonesia.\n\nPermintaan:\n"

internal fun deepCliPrompt(msg: String, on: Boolean, simple: Boolean): String =
    if (on && !simple) DEEP_CLI_PREFIX + msg else msg

private const val DEEP_NOTES_MAX = 2400

internal const val DEEP_SYS_ADDON = "\nPENALARAN MENDALAM aktif: untuk masalah yang tidak sepele, uraikan dulu masalahnya, " +
    "pertimbangkan sedikitnya dua pendekatan beserta untung-ruginya, pilih yang paling kuat, dan periksa asumsi sebelum bertindak. " +
    "Setelah tiap hasil alat, nilai apakah hasilnya benar-benar membuktikan tujuan; kalau belum, selidiki penyebabnya, jangan menebak. " +
    "Sebelum jawaban akhir, cek ulang kesimpulan terhadap bukti yang ada dan sebutkan bagian yang belum terverifikasi. " +
    "Sapaan dan pertanyaan sederhana tetap dijawab langsung dan singkat."

private const val DEEP_THINK_SYS = "Kamu sedang berpikir mendalam sebelum agen bertindak. Jangan memakai tool dan jangan menjalankan tindakan. " +
    "Tulis analisis ringkas dalam bahasa Indonesia dengan bagian: Tujuan; Fakta dan batasan; Pendekatan yang mungkin (minimal dua, dengan untung-rugi); " +
    "Pendekatan terpilih beserta langkah berurutan; Risiko dan cara memverifikasi hasil. Maksimal sekitar 250 kata. " +
    "Jika permintaan hanya sapaan atau pertanyaan sederhana yang tidak perlu analisis, balas persis satu kata: SEDERHANA."

private const val DEEP_MID_ADDON = " Penalaran mendalam baru diaktifkan di tengah tugas: fokuskan analisis pada sisa pekerjaan " +
    "berdasarkan progres yang sudah ada, jangan mengulang dari nol."

/** Hasil tahap berpikir menjadi catatan acuan; kosong bila sederhana. */
internal fun parseDeepNotes(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return ""
    val first = s.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    // Hanya jawaban yang isinya persis satu kata SEDERHANA (boleh berhias markdown/tanda baca) yang dianggap sederhana.
    // startsWith salah: "Sederhananya, ..." adalah awal analisis yang sah.
    if (first.filter { it.isLetter() }.equals("SEDERHANA", ignoreCase = true)) return ""
    return cutDeepNotes(s)
}

/** Potong catatan di batas baris/kalimat terdekat sebelum batas, supaya bagian akhir tidak terputus di tengah kata. */
internal fun cutDeepNotes(s: String, max: Int = DEEP_NOTES_MAX): String {
    if (s.length <= max) return s
    val head = s.take(max)
    val at = maxOf(head.lastIndexOf('\n'), head.lastIndexOf(". "), head.lastIndexOf('.'))
    return if (at >= max * 7 / 10) head.take(at + 1).trimEnd() else head
}

/** Anggaran token berpikir Gemini 2.5; null untuk model yang tidak mendukung konfigurasi ini. */
internal fun geminiThinkingBudget(model: String): Int? {
    val m = model.lowercase().removePrefix("models/")
    return when {
        !m.startsWith("gemini-2.5") || !geminiSupportsThinking(m) -> null
        m.contains("pro") -> 32768
        m.contains("flash") -> 24576
        else -> null
    }
}

/** Gemini 3.x memakai thinkingLevel (bukan thinkingBudget); keduanya tidak boleh dikirim bersamaan. */
internal fun geminiThinkingLevel(model: String): String? {
    val m = model.lowercase().removePrefix("models/")
    if (!geminiSupportsThinking(m)) return null
    // Gemini 4 berpikir sangat lama bila dibiarkan bawaan server (25-35 dtk per langkah agen); "medium" menjaga mutu dengan jauh lebih cepat.
    return when {
        Regex("^gemini-3").containsMatchIn(m) -> "high"
        Regex("^gemini-4").containsMatchIn(m) -> "medium"
        else -> null
    }
}

/** Varian gambar, suara, dan live tidak mengenal thinkingConfig; mengirimnya membuat API menolak permintaan. */
internal fun geminiSupportsThinking(m: String): Boolean =
    listOf("image", "tts", "live", "audio", "embedding").none { m.contains(it) }

internal fun geminiThinkingConfig(model: String): JSONObject? {
    geminiThinkingLevel(model)?.let {
        return JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", it))
    }
    return geminiThinkingBudget(model)?.let {
        JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", it))
    }
}

/** reasoning_effort hanya dikirim ke OpenAI asli dan model reasoning; penyedia lain bisa menolak parameter ini. */
internal fun supportsReasoningEffort(model: String, base: String): Boolean {
    if (!base.contains("api.openai.com", ignoreCase = true)) return false
    val m = model.lowercase().removePrefix("models/")
    if (m.startsWith("o1-mini") || m.startsWith("o1-preview")) return false
    if (m.contains("-chat")) return false // gpt-5-chat-latest bukan model reasoning: parameter ini ditolak (400)
    return Regex("^(o[1-9]|gpt-5)").containsMatchIn(m)
}

