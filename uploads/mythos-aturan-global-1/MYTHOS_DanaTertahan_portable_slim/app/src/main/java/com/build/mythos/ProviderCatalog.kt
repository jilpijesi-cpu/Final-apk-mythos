package com.build.mythos

// Katalog penyedia AI untuk dialog "Hubungkan penyedia AI" (ApiKeyDialog.kt): jenis penyedia, label, lencana,
// aturan tombol Hubungkan, dan MODEL REKOMENDASI per penyedia. Murni Kotlin tanpa Android, diuji di ProviderCatalogTest.
// Pengguna cukup memilih penyedia dan menempel kunci; model terbaik dipilih otomatis dari daftar model milik akun itu.

internal enum class ProviderKind {
    API,      // butuh kunci API; model dipilih otomatis
    LOCAL,    // server lokal (Ollama): tanpa kunci, butuh alamat
    CUSTOM,   // endpoint OpenAI-compatible sendiri: alamat + kunci + nama model
    CLI       // CLI berlangganan: tanpa kunci, masuk lewat akun
}

internal object ProviderCatalog {
    /** Urutan tampil penyedia berkunci API di daftar pilihan (yang lain menyusul: lokal, kustom, CLI). */
    val API_ORDER = listOf("OpenAI", "Anthropic", "Gemini", "OpenRouter", "Groq", "DeepSeek")

    fun kindOf(name: String, fmt: String): ProviderKind = when {
        fmt == "cli" -> ProviderKind.CLI
        name.startsWith("Ollama") -> ProviderKind.LOCAL
        name.startsWith("Kustom") -> ProviderKind.CUSTOM
        else -> ProviderKind.API
    }

    /** Urutan daftar: penyedia API dulu (sesuai [API_ORDER]), lalu sisanya sesuai urutan aslinya. */
    fun displayOrder(all: List<String>): List<String> =
        API_ORDER.filter { it in all } + all.filter { it !in API_ORDER }

    /** Nama yang ditampilkan ke pengguna (nama preset tetap dipakai di penyimpanan). */
    fun label(name: String): String = when (name) {
        "Gemini" -> "Google AI Studio"
        "Ollama lokal" -> "Ollama (di HP ini)"
        "Kustom (OpenAI)" -> "Kustom (OpenAI-compatible)"
        else -> name
    }

    /** Domain situs resmi penyedia, sumber logo di dropdown. Null (Kustom) = tidak ada logo, pakai lencana huruf. */
    fun logoDomain(name: String): String? = when {
        name == "OpenAI" -> "openai.com"
        name == "Anthropic" -> "anthropic.com"
        name == "Gemini" -> "aistudio.google.com"
        name == "OpenRouter" -> "openrouter.ai"
        name == "Groq" -> "groq.com"
        name == "DeepSeek" -> "deepseek.com"
        name.startsWith("Ollama") -> "ollama.com"
        name.startsWith("Codex") -> "chatgpt.com"
        name.startsWith("Gemini CLI") -> "gemini.google.com"
        name.startsWith("Claude Code") -> "claude.ai"
        name == "CLI Termux" -> "termux.dev"
        else -> null
    }

    /** Alamat gambar logo (layanan ikon situs Google, 128 px). Ganti di sini bila ingin sumber logo lain. */
    fun logoUrl(name: String): String? = logoDomain(name)?.let { "https://www.google.com/s2/favicons?domain=$it&sz=128" }

    /** Lencana monogram: huruf dan warna latar (ARGB). Dipakai sebagai cadangan selama logo belum termuat / tanpa internet. */
    fun badge(name: String): Pair<String, Int> = when {
        name == "OpenAI" -> "O" to 0xFF10A37F.toInt()
        name == "Anthropic" -> "A" to 0xFFD97757.toInt()
        name == "Gemini" -> "G" to 0xFF4285F4.toInt()
        name == "OpenRouter" -> "R" to 0xFF6467F2.toInt()
        name == "Groq" -> "Q" to 0xFFF55036.toInt()
        name == "DeepSeek" -> "D" to 0xFF4D6BFE.toInt()
        name.startsWith("Ollama") -> "L" to 0xFF475569.toInt()
        name.startsWith("Kustom") -> "K" to 0xFF64748B.toInt()
        else -> ">_" to 0xFF334155.toInt()
    }

    /**
     * Tombol Hubungkan aktif bila isian wajib untuk jenis penyedia itu sudah ada.
     * [hasProvider] false = belum memilih penyedia.
     */
    fun canConnect(kind: ProviderKind?, hasProvider: Boolean, key: String, base: String, model: String): Boolean {
        if (!hasProvider || kind == null) return false
        return when (kind) {
            ProviderKind.API -> normalizeKey(key).length >= 8
            ProviderKind.LOCAL -> base.isNotBlank()
            ProviderKind.CUSTOM -> normalizeKey(key).isNotEmpty() && base.isNotBlank() && model.isNotBlank()
            ProviderKind.CLI -> true
        }
    }

    /** Model terisi otomatis hanya bila penyedia ini belum punya model (pilihan manual sebelumnya tidak ditimpa). */
    fun shouldAutoPick(storedModel: String): Boolean = storedModel.isBlank()

    /** Model awal instan (sebelum daftar model akun dimuat dan diuji). Kosong = ditentukan setelah daftar model dimuat. */
    fun startingModel(provider: String, presetModel: String): String = when (provider) {
        "Anthropic" -> "claude-sonnet-5-5"
        "DeepSeek" -> "deepseek-chat"
        else -> presetModel
    }

    private val EXCLUDE = listOf(
        "embed", "moderation", "whisper", "tts", "transcribe", "audio", "realtime", "image", "dall", "guard", "rerank",
        "speech", "live", "native", "computer-use", "search", "codex", "instruct", "davinci", "babbage", "chat-latest"
    )
    private val UNSTABLE = listOf("preview", "exp", "beta", "thinking")

    // Tiap tingkat: kata yang HARUS ada. Tingkat lebih awal = lebih disukai. [avoid] = kata yang dihindari pada tingkat itu.
    private class Tier(val must: List<String>, val avoid: List<String> = emptyList())

    private val TIERS: Map<String, List<Tier>> = mapOf(
        // Agen kode: Sonnet seimbang kuat dan cepat; Opus lebih mahal; Haiku paling ringan.
        "Anthropic" to listOf(Tier(listOf("sonnet")), Tier(listOf("opus")), Tier(listOf("haiku"))),
        "OpenAI" to listOf(
            Tier(listOf("gpt-5"), listOf("mini", "nano", "-pro")),
            Tier(listOf("gpt-4.1"), listOf("mini", "nano")),
            Tier(listOf("gpt-4o"), listOf("mini")),
            Tier(listOf("o4"), listOf("-pro")),
            Tier(listOf("gpt-5")),
            Tier(listOf("mini"))
        ),
        // Flash: cepat, murah, kuat memanggil alat. Pro hanya bila Flash tidak ada. Versi lite paling akhir.
        "Gemini" to listOf(Tier(listOf("flash"), listOf("lite")), Tier(listOf("pro")), Tier(listOf("flash"))),
        "DeepSeek" to listOf(Tier(listOf("deepseek-chat")), Tier(listOf("deepseek-v"), listOf("reasoner")), Tier(listOf("deepseek")))
    )

    private val DATED = Regex("-(\\d{4}-\\d{2}-\\d{2}|\\d{8}|\\d{4})$")
    private val NUM = Regex("\\d+")

    private fun versionKey(name: String): List<Int> =
        NUM.findAll(name.replace(DATED, "")).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun cmpVersion(a: String, b: String): Int {
        val x = versionKey(a); val y = versionKey(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrNull(i) ?: -1).compareTo(y.getOrNull(i) ?: -1)
            if (c != 0) return c
        }
        return 0
    }

    private fun newest(names: List<String>): String? {
        if (names.isEmpty()) return null
        val stable = names.filter { n -> UNSTABLE.none { n.contains(it, true) } }.ifEmpty { names }
        val undated = stable.filter { !DATED.containsMatchIn(it) }.ifEmpty { stable }
        return undated.maxWithOrNull(Comparator { a, b -> cmpVersion(a, b).let { if (it != 0) it else b.length.compareTo(a.length) } })
    }

    /**
     * Model terbaik untuk agen dari [models] (daftar milik akun). Null bila penyedia tidak punya aturan di sini
     * (Groq, Ollama, Kustom, OpenRouter: ditangani aturan lain) atau tidak ada yang cocok.
     * Nama dikembalikan persis seperti di [models].
     */
    fun recommend(provider: String, models: List<String>): String? {
        val tiers = TIERS[provider] ?: return null
        val pool = models.filter { m -> m.isNotBlank() && EXCLUDE.none { m.contains(it, true) } }
        for (t in tiers) {
            val hits = pool.filter { m -> t.must.all { m.contains(it, true) } && t.avoid.none { m.contains(it, true) } }
            newest(hits)?.let { return it }
        }
        return null
    }
}
