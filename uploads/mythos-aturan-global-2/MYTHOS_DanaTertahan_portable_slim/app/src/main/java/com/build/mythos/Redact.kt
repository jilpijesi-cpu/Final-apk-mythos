package com.build.mythos

/**
 * Samarkan rahasia dan data pribadi dari teks yang akan ditampilkan/disalin pengguna (mis. laporan crash).
 * Logika murni, diuji di RedactTest. Bukan jaminan mutlak: pengguna tetap diminta memeriksa sebelum mengirim.
 */
private const val MASK = "[DISEMBUNYIKAN]"

private val REDACT_RULES: List<Pair<Regex, String>> = listOf(
    // Kredensial di URL: https://user:pass@host
    Regex("""(?i)(https?://)[^/\s:@]+:[^/\s@]+@""") to "$1$MASK@",
    // Header/nilai Bearer
    Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}""") to "Bearer $MASK",
    // Bentuk token umum: OpenAI/Anthropic sk-, GitHub, Google API, Slack
    Regex("""\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{20,}|xox[abprs]-[A-Za-z0-9-]{10,})""") to MASK,
    // Token bot Telegram: 123456789:AA...
    Regex("""\b\d{6,}:[A-Za-z0-9_-]{30,}""") to MASK,
    // Parameter query berisi kredensial: ?key=... &token=...
    Regex("""(?i)([?&](?:api_?key|key|token|access_token|secret|password)=)[^&\s"']+""") to "$1$MASK",
    // key=nilai, key: nilai, dan JSON "key": "nilai" untuk nama yang jelas sensitif
    Regex("""(?i)(["']?\b(?:x-api-key|api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|auth[_-]?token|client[_-]?secret|private[_-]?key|token|secret|password|passwd|pwd|authorization|set-cookie|cookie)\b["']?\s*[=:]\s*["']?)(?!Bearer\s+\[DISEMBUNYIKAN\])[^\s"'&,;}\]]+""") to "$1$MASK",
    // Nama pengguna pada path rumah
    Regex("""/home/[^/\s]+""") to "/home/[pengguna]",
    Regex("""/Users/[^/\s]+""") to "/Users/[pengguna]"
)

internal fun redactSecrets(text: String): String {
    var out = text
    for ((re, rep) in REDACT_RULES) out = re.replace(out, rep)
    return out
}
