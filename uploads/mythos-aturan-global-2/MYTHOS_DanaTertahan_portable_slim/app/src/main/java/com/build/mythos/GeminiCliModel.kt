package com.build.mythos

/** Model bawaan Gemini CLI: auto mengaktifkan pemilihan/routing model bawaan CLI. */
internal const val DEFAULT_GEMINI_CLI_MODEL = "auto"

/** Model boleh berupa alias (auto/pro/flash/flash-lite) atau ID model konkret. */
internal fun normalizeGeminiCliModel(raw: String): String =
    raw.trim().ifEmpty { DEFAULT_GEMINI_CLI_MODEL }

internal fun isValidGeminiCliModel(raw: String): Boolean =
    raw.length <= 128 && Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$").matches(raw)

/**
 * Terapkan model yang dipilih ke template perintah Gemini CLI.
 * Argumen model lama di template dihapus agar tidak ada dua --model yang saling bertentangan.
 * [quote] harus mengutip satu argumen shell (MainActivity.sh).
 * Mengembalikan null jika template bukan pemanggilan Gemini CLI non-interaktif.
 */
internal fun geminiCliCommandWithModel(
    template: String,
    rawModel: String,
    quote: (String) -> String,
    streamJson: Boolean = false
): String? {
    val model = normalizeGeminiCliModel(rawModel)
    if (!isValidGeminiCliModel(model)) return null

    val cleaned = normalizeGeminiCommandTemplate(template)
    val withoutOldModel = cleaned.replace(
        Regex("(?<!\\S)(?:--model|-m)(?:=|\\s+)(?:\"[^\"]*\"|'[^']*'|[^\\s;&|]+)"),
        " "
    ).replace(
        Regex("(?<!\\S)(?:--output-format|-o)(?:=|\\s+)(?:\"[^\"]*\"|'[^']*'|[^\\s;&|]+)"),
        " "
    ).replace(Regex("\\s{2,}"), " ").trim()

    val promptFlag = Regex("(?<!\\S)(?:-p|--prompt)(?==|\\s|$)").find(withoutOldModel)
        ?: return null
    val prefix = withoutOldModel.substring(0, promptFlag.range.first).trimEnd()
    val suffix = withoutOldModel.substring(promptFlag.range.first)
    if (!Regex("(^|\\s)(?:[A-Za-z0-9_./-]+/)?gemini(?=\\s|$)").containsMatchIn(prefix)) return null
    val format = if (streamJson) " --output-format stream-json" else ""
    return "$prefix --model ${quote(model)}$format $suffix"
}

/**
 * Satu pilihan pada daftar model Gemini CLI. [value] dikirim ke --model; [title] dan [note] hanya tampilan.
 * Alias (auto/pro/flash/flash-lite) diselesaikan Gemini CLI sendiri ke model terbaru di tingkatnya, jadi daftar ini
 * tidak cepat usang. ID konkret tetap pada versi itu. Nama ID diambil dari dokumentasi model Google (Oktober 2026);
 * model lain tetap bisa diketik sendiri.
 */
internal data class GeminiModelChoice(val value: String, val title: String, val note: String) {
    fun menuLabel(): String = "$title\n$note"
}

internal val GEMINI_CLI_MODEL_CHOICES: List<GeminiModelChoice> = listOf(
    GeminiModelChoice("auto", "Otomatis (auto)", "CLI memilih model sendiri. Bawaan."),
    GeminiModelChoice("pro", "Pro", "Paling mampu, biasanya paling lambat."),
    GeminiModelChoice("flash", "Flash", "Seimbang: cepat dan cukup mampu."),
    GeminiModelChoice("flash-lite", "Flash-Lite", "Paling ringan dan cepat."),
    GeminiModelChoice("gemini-3.8-flash", "gemini-3.8-flash", "Versi tetap, tingkat Flash."),
    GeminiModelChoice("gemini-3.5-flash", "gemini-3.5-flash", "Versi tetap, tingkat Flash."),
    GeminiModelChoice("gemini-3.5-flash-lite", "gemini-3.5-flash-lite", "Versi tetap, tingkat Flash-Lite.")
)

/** Indeks pilihan yang cocok dengan [current]; bila tidak ada di daftar (diketik sendiri) hasilnya GEMINI_CLI_MODEL_CHOICES.size. */
internal fun geminiModelChoiceIndex(current: String): Int =
    GEMINI_CLI_MODEL_CHOICES.indexOfFirst { it.value == current }.let { if (it < 0) GEMINI_CLI_MODEL_CHOICES.size else it }
