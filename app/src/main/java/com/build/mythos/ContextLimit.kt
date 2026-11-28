package com.build.mythos

// Batas ukuran permintaan per model (logika murni, diuji di ContextLimitTest).
// Masalah lama: anggaran konteks tetap 100.000 karakter untuk semua penyedia, padahal tier gratis/on-demand
// (mis. Groq gpt-oss: 8.000 token/menit) menolak permintaan ±23.000 token dengan HTTP 413. Error itu dianggap fatal,
// agen berhenti, dan "Lanjutkan" mengirim riwayat yang sama sehingga gagal terus.

internal const val CTX_BUDGET_MAX = 100_000
internal const val CTX_BUDGET_MIN = 4_000

/** Anggaran konteks di bawah ini = model punya batas token kecil (mis. Groq gratis 8.000 TPM): prompt sistem dan alat dipadatkan. */
internal const val COMPACT_PROMPT_BELOW = 40_000

/**
 * Anggaran riwayat untuk model lokal (Ollama, jendela 8.192 token). Ollama TIDAK menolak permintaan yang terlalu besar
 * (tidak ada HTTP 413): ia memotong diam-diam, jadi batasnya harus ditetapkan dari awal, bukan dipelajari dari error.
 * 8.000 karakter (±2.700 token) + prompt sistem dan alat (±1.000 token) + jawaban (maks LOCAL_AGENT_MAX_TOKENS) muat nyaman
 * di jendela 8.192 token, dan di HP (±24 token/detik membaca prompt) satu pembacaan penuh tanpa cache ±2,5 menit, bukan ±3,5 menit (perkiraan kasar).
 */
internal const val LOCAL_CTX_BUDGET = 8_000

internal fun defaultCtxBudget(provider: String): Int = if (provider == "Ollama lokal") LOCAL_CTX_BUDGET else CTX_BUDGET_MAX
internal const val MAX_CTX_SHRINKS = 4

/** Permintaan ditolak karena terlalu besar. Aman diulang bila konteks dikecilkan; bila tetap gagal, layak dialihkan ke model lain. */
internal class ContextTooLargeException(
    message: String,
    val limitTokens: Int?,
    val requestedTokens: Int?
) : RuntimeException(message)

private val CTX_MARKERS = listOf(
    "request too large", "reduce your message size", "context_length_exceeded", "maximum context length",
    "reduce the length of the messages", "prompt is too long", "exceeds the maximum number of tokens"
)

/** True untuk HTTP 413 atau pesan yang menandakan permintaan melebihi batas token model. */
internal fun isContextTooLarge(code: Int, body: String): Boolean {
    if (code == 413) return true
    val t = body.lowercase()
    return CTX_MARKERS.any { t.contains(it) }
}

/** "Limit 8000, Requested 23082" -> 8000. Null bila tidak ada. */
internal fun parseTokenLimit(body: String): Int? =
    Regex("limit\\s+(\\d+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.toIntOrNull()

/** "Limit 8000, Requested 23082" -> 23082. Null bila tidak ada. */
internal fun parseRequestedTokens(body: String): Int? =
    Regex("requested\\s+(\\d+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.toIntOrNull()

internal fun contextTooLarge(body: String): ContextTooLargeException {
    val limit = parseTokenLimit(body)
    val req = parseRequestedTokens(body)
    val detail = if (limit != null) " (batas $limit token" + (if (req != null) ", diminta $req" else "") + ")" else ""
    return ContextTooLargeException(
        "Permintaan terlalu besar untuk model ini$detail. Konteks sudah dipangkas semaksimal mungkin. " +
            "Pakai model/penyedia lain, atau naikkan tier akun. Detail: " + body.take(200),
        limit, req
    )
}

/**
 * Anggaran konteks (karakter) baru setelah ditolak. [current] anggaran yang sedang berlaku, [sentChars] ukuran riwayat yang
 * benar-benar terkirim pada permintaan yang ditolak (0 bila tidak diketahui). Sasarannya 70% dari batas supaya ada ruang untuk
 * prompt sistem dan alat. Tanpa angka dari server, anggaran dibagi dua. Hasil selalu lebih kecil dari [current]
 * kecuali sudah di lantai [CTX_BUDGET_MIN] (hasil == current berarti tidak bisa dikecilkan lagi).
 */
internal fun shrunkBudget(current: Int, sentChars: Int, limit: Int?, requested: Int?): Int {
    val base = if (sentChars in 1 until current) sentChars else current
    val raw = if (limit != null && requested != null && requested > 0) (base * (limit * 0.7 / requested)).toInt() else base / 2
    val reduced = minOf(raw, base * 9 / 10)
    return reduced.coerceAtLeast(CTX_BUDGET_MIN).coerceAtMost(current)
}
