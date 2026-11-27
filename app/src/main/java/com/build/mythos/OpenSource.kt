package com.build.mythos

// Pemilihan model open source otomatis (logika murni tanpa Android, diuji di OpenSourceTest).
// Tujuan: pengguna cukup menempel satu API key (mis. Groq). Penyedia dikenali dari bentuk key
// (KeyDetect.kt), lalu model open source terbaik dipilih dan diuji otomatis (ModelPicker.autoSetupModels).
// OpenRouter tidak termasuk: modelnya dikunci ke openrouter/free (ModelLock.kt), jadi tidak ada yang dipilih atau diuji.

/** Penyedia yang menyajikan model open source: di sini model open source didahulukan. */
internal val OPEN_SOURCE_HOSTS = setOf("Groq", "Ollama lokal", "Kustom (OpenAI)")

private val OSS_FAMILIES = listOf(
    "gpt-oss", "llama", "qwen", "qwq", "deepseek", "mistral", "mixtral", "gemma", "kimi", "glm",
    "nemotron", "phi-", "phi3", "phi4", "olmo", "granite"
)

/** Hanya di penyedia ini daftar model disaring ke open source saja. Ollama/Kustom: semua model tetap diuji (hanya diurutkan). */
private val OPEN_SOURCE_ONLY_HOSTS = setOf("Groq")

/** Model yang bukan untuk percakapan agen (penjaga konten, suara, dll). */
private val NOT_AGENT_WORDS = listOf("guard", "safeguard", "orpheus", "reward", "classifier")

/** Urutan kesukaan: pola yang cocok pertama menentukan skor dasar. Versi besar dan kuat di tool-call lebih dulu. */
private val OSS_SCORES = listOf(
    "gpt-oss-120b" to 100,
    "qwen3-coder" to 95,
    "kimi-k2" to 92,
    "deepseek-v3" to 90,
    "deepseek-chat" to 90,
    "glm-4" to 88,
    "llama-3.3-70b" to 85,
    "llama-4" to 84,
    "qwen3" to 80,
    "gpt-oss-20b" to 75,
    "mistral-large" to 70,
    "llama-3.1-70b" to 70,
    "mistral-medium" to 68,
    "mistral-small" to 65
)

private const val OSS_GENERIC_SCORE = 50
private const val SMALL_MODEL_PENALTY = 30
private const val FREE_BONUS = 10

private val SIZE_B = Regex("(?<![0-9.])(\\d{1,3}(?:\\.\\d)?)b(?![a-z])")

internal fun isOpenSourceModel(id: String): Boolean {
    val m = id.lowercase()
    return OSS_FAMILIES.any { m.contains(it) } && NOT_AGENT_WORDS.none { m.contains(it) }
}

/** Skor kesukaan model open source; makin tinggi makin diutamakan. Model kecil (<15B) dikurangi, versi gratis ":free" ditambah. */
internal fun openSourceScore(id: String): Int {
    val m = id.lowercase()
    var s = OSS_SCORES.firstOrNull { m.contains(it.first) }?.second ?: OSS_GENERIC_SCORE
    val size = SIZE_B.find(m)?.groupValues?.get(1)?.toDoubleOrNull()
    if (size != null && size < 15.0) s -= SMALL_MODEL_PENALTY - size.toInt().coerceAtMost(14)
    if (m.contains("distill")) s -= 20
    if (m.endsWith(":free")) s += FREE_BONUS
    return s
}

/** Model open source terbaik dari daftar, atau null bila tidak ada satu pun. Skor sama: yang lebih dulu di daftar. */
internal fun bestOpenSource(models: List<String>): String? =
    models.filter { isOpenSourceModel(it) }.maxByOrNull { openSourceScore(it) }

/**
 * Pilih model yang diuji otomatis. Groq: hanya model open source, yang terbaik lebih dulu. Ollama dan Kustom: semua model tetap ikut, hanya diurutkan (open source
 * terbaik dulu). Penyedia lain: perilaku lama (potong [limit] pertama).
 */
internal fun probeCandidates(provider: String, models: List<String>, limit: Int): List<String> {
    if (provider !in OPEN_SOURCE_HOSTS) return models.take(limit)
    val cap = limit
    if (provider in OPEN_SOURCE_ONLY_HOSTS) {
        val oss = models.filter { isOpenSourceModel(it) }
        if (oss.isNotEmpty()) return oss.sortedByDescending { openSourceScore(it) }.take(cap)
        return models.take(cap)
    }
    return models.sortedByDescending { if (isOpenSourceModel(it)) openSourceScore(it) else 0 }.take(cap)
}

private val VISION_WORDS = listOf(
    "vision", "-vl", "vl-", "llava", "pixtral", "llama-4", "maverick", "scout", "gemma-3", "gemma3",
    "gemma-4", "gemma4", "minicpm-v", "internvl", "molmo", "kimi-k2.5", "glm-4.5v", "glm-4.6v"
)

/**
 * Apakah model ini bisa menerima gambar. Model open source teks (gpt-oss, Llama 3.x, Qwen3, DeepSeek) menolak gambar
 * dengan HTTP 400. Penyedia dan model lain dianggap bisa (perilaku lama).
 */
internal fun modelSeesImages(provider: String, model: String): Boolean {
    if (provider !in OPEN_SOURCE_HOSTS) return true
    val m = model.lowercase()
    if (!isOpenSourceModel(m)) return true
    return VISION_WORDS.any { m.contains(it) }
}



/** Alamat server di perangkat sendiri (Ollama di Termux): lebih lambat, dan galat koneksi berarti server belum jalan. */
internal fun isLocalUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    return try {
        val parsed = java.net.URL(trimmed)
        if (!parsed.protocol.equals("http", ignoreCase = true)) return false
        val host = parsed.host.lowercase().trim('[', ']')
        host == "127.0.0.1" || host == "localhost" || host == "::1"
    } catch (_: Exception) {
        false
    }
}
