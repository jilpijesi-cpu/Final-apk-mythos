package com.build.mythos

// Mengenali penyedia AI dari bentuk API key (awalan dan panjang) serta memisahkan daftar multi-key.
// Murni Kotlin tanpa Android, jadi bisa di-unit-test (lihat KeyDetectTest / MultiApiKeyTest).
// Nama yang dikembalikan harus sama dengan Preset.name di MainActivity.presets.
// Untuk menambah penyedia baru: tambahkan satu baris di when(...) di detectProvider().

/** Key lebih pendek dari ini tidak dianggap (mencegah salah deteksi saat baru mengetik). */
internal const val KEY_MIN_LEN = 20

/** Membersihkan hasil tempel: spasi, awalan "Bearer ", dan tanda kutip di sekeliling key. */
internal fun normalizeKey(raw: String): String {
    var k = raw.trim()
    if (k.startsWith("Bearer ", ignoreCase = true)) k = k.substring(7).trim()
    return k.trim('"', '\'', '`').trim()
}

private val KEY_SEPARATORS = Regex("[\r\n,;]+")

/** Memecah satu atau banyak API key (dipisah baris baru, koma, atau titik koma), membersihkan, dan membuang duplikat. */
internal fun parseApiKeys(raw: String): List<String> =
    raw.split(KEY_SEPARATORS)
        .map { normalizeKey(it) }
        .filter { it.isNotBlank() }
        .distinct()

/** Menyimpan satu atau banyak API key dalam format kanonik (satu kunci per baris). */
internal fun serializeApiKeys(raw: String): String = parseApiKeys(raw).joinToString("\n")

private val DEEPSEEK_KEY = Regex("^sk-[a-f0-9]{32}$")

private fun detectSingleProvider(raw: String): String? {
    val k = normalizeKey(raw)
    if (k.length < KEY_MIN_LEN || k.any { it.isWhitespace() }) return null
    return when {
        k.startsWith("sk-ant-") -> "Anthropic"
        k.startsWith("sk-or-") -> "OpenRouter"
        k.startsWith("gsk_") -> "Groq"
        k.startsWith("AIza") -> "Gemini"
        DEEPSEEK_KEY.matches(k) -> "DeepSeek"
        k.startsWith("sk-") -> "OpenAI"
        else -> null
    }
}

/** Nama preset yang cocok dengan key ini (mendukung tempel multi-key), atau null bila bentuknya tidak dikenali. */
internal fun detectProvider(raw: String): String? {
    if (KEY_SEPARATORS.containsMatchIn(raw)) {
        for (part in parseApiKeys(raw)) {
            detectSingleProvider(part)?.let { return it }
        }
        return null
    }
    return detectSingleProvider(raw)
}
