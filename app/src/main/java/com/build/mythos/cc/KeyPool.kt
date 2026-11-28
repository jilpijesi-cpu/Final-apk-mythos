package com.build.mythos.cc

import kotlinx.coroutines.delay

data class KeySpec(
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val tokenParam: String = "max_tokens",
    val temperature: Double? = 0.2,
) {
    fun toConfig() = ProviderConfig(kind, baseUrl, apiKey, model, temperature, defaultMaxOutputTokens(kind, baseUrl, model), tokenParam)
}

data class EntryStatus(val label: String, val model: String, val state: String, val healthy: Boolean)

class PoolEntry(val spec: KeySpec) {
    var disabledReason: String? = null
    var cooldownUntil: Long = 0L
    var lastReason: String? = null
    var uses: Int = 0
}

internal data class FailureInfo(val reason: String, val cooldownMs: Long, val disable: Boolean)

private val RETRY_IN = Regex("""retry (?:in|after)\s*(\d+(?:\.\d+)?)\s*s""")

private val DEAD_KEY_HINTS = listOf(
    "api_key_invalid", "api key not valid", "api_key_expired", "api key expired",
    "api_key_revoked", "invalid_api_key", "invalid api key", "incorrect api key",
    "authentication_error", "key revoked", "key disabled", "user not found",
)

/** True jika respons penyedia menunjukkan API key mati, kedaluwarsa, dicabut, atau tidak valid (termasuk HTTP 400 khas Gemini). */
fun isDeadKeyError(code: Int, message: String?): Boolean {
    if (code == 401 || code == 403) return true
    val m = (message ?: "").lowercase()
    return DEAD_KEY_HINTS.any { it in m }
}

/** Menerjemahkan galat penyedia menjadi tindakan: nonaktifkan kunci, atau istirahatkan sementara. */
internal fun classify(e: LlmException): FailureInfo {
    val m = (e.message ?: "").lowercase()
    val hinted = RETRY_IN.find(m)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ((it + 2) * 1000).toLong() }
    val perDay = listOf("perday", "per day", "per-day", "daily", "per_day").any { it in m }
    val perMinute = listOf("perminute", "per minute", "rate limit", "rate_limit", "too many requests", "per_minute").any { it in m }
    val quota = listOf("quota", "insufficient", "billing", "credit", "balance", "exhausted").any { it in m }
    return when {
        isDeadKeyError(e.code, e.message) -> FailureInfo("kunci mati/ditolak (${e.code})", 0, true)
        e.code == 404 -> FailureInfo("model/endpoint tidak ditemukan", 0, true)
        e.code == 402 -> FailureInfo("kredit habis", 6 * 3600_000L, false)
        e.code == 429 && perDay -> FailureInfo("kuota harian habis", 6 * 3600_000L, false)
        e.code == 429 && hinted != null -> FailureInfo("batas laju", hinted, false)
        e.code == 429 && perMinute -> FailureInfo("batas laju", 60_000L, false)
        e.code == 429 && quota -> FailureInfo("kuota/kredit habis", 6 * 3600_000L, false)
        e.code == 429 -> FailureInfo("batas laju", 60_000L, false)
        // 400/413/422 biasanya karena bentuk/ukuran PERMINTAAN, bukan kunci: istirahat singkat saja agar tugas berikutnya tidak ikut terkunci.
        e.code == 400 || e.code == 413 || e.code == 422 -> FailureInfo("permintaan ditolak (cek nama model/parameter/ukuran)", 45_000L, false)
        e.code >= 500 || e.retryable -> FailureInfo("gangguan penyedia/jaringan", 20_000L, false)
        else -> FailureInfo("galat tak terduga", 2 * 60_000L, false)
    }
}

/**
 * Kumpulan kunci dengan status kesehatan. Strategi: tetap di satu kunci selama sehat (konsisten),
 * pindah otomatis saat gagal, dan kembali ke kunci berprioritas lebih tinggi bila sudah pulih.
 * rotateEvery > 0 = bergilir ke kunci sehat berikutnya setiap N panggilan (menyebar beban).
 */
class KeyPool(
    specs: List<KeySpec>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val rotateEvery: Int = 0,
    private val sticky: Boolean = false,
) {
    val entries: List<PoolEntry> = specs.map { PoolEntry(it) }
    private var current: PoolEntry? = null
    private var callsOnCurrent = 0

    private fun usable(e: PoolEntry, now: Long) = e.disabledReason == null && now >= e.cooldownUntil

    private fun setCurrent(e: PoolEntry) {
        current = e
        callsOnCurrent = 0
    }

    @Synchronized
    fun pick(): PoolEntry? {
        val now = clock()
        val firstUsable = entries.firstOrNull { usable(it, now) } ?: return null
        val cur = current
        if (rotateEvery > 0) {
            // Utamakan tier model prioritas tertinggi yang masih punya minimal 1 kunci sehat;
            // rotasi bergantian hanya di antara kunci-kunci pada model tersebut, dan otomatis turun ke model aktif lain bila semua kunci di model ini kena batas.
            val activeModel = firstUsable.spec.model
            if (cur != null && cur.spec.model == activeModel) {
                if (callsOnCurrent >= rotateEvery) {
                    val i = entries.indexOf(cur)
                    for (k in 1..entries.size) {
                        val e = entries[(i + k) % entries.size]
                        if (e.spec.model == activeModel && usable(e, now)) {
                            setCurrent(e)
                            return e
                        }
                    }
                }
                if (usable(cur, now)) return cur
            }
            setCurrent(firstUsable)
            return firstUsable
        }
        // Mode sticky: begitu ketemu (kunci, model) yang merespons, bertahan di situ selama sehat agar cepat.
        if (sticky && cur != null && usable(cur, now)) return cur
        // Mode prioritas: selalu entri sehat teratas (otomatis kembali ke prioritas tinggi saat pulih).
        if (firstUsable !== cur) setCurrent(firstUsable)
        return firstUsable
    }

    @Synchronized
    fun onSuccess(e: PoolEntry) {
        if (current === e) callsOnCurrent++
        e.uses++
        e.lastReason = null
    }

    @Synchronized
    fun activeCount(): Int = entries.count { it.disabledReason == null }

    @Synchronized
    internal fun onFailure(e: PoolEntry, f: FailureInfo) {
        e.lastReason = f.reason
        if (f.disable) {
            e.disabledReason = f.reason
            // Bila kunci mati/ditolak (401/403 atau 400 API_KEY_INVALID), nonaktifkan seluruh entri model yang memakai apiKey yang sama.
            if (f.reason.startsWith("kunci mati") && e.spec.apiKey.isNotBlank()) {
                entries.forEach { other ->
                    if (other.spec.apiKey == e.spec.apiKey) {
                        other.disabledReason = f.reason
                        other.lastReason = f.reason
                    }
                }
            }
        } else {
            e.cooldownUntil = clock() + f.cooldownMs
        }
        if (current === e || current?.disabledReason != null) current = null
    }

    /** Menghapus hukuman istirahat: dipakai bila SEMUA entri menolak permintaan yang sama (salahnya permintaan, bukan kunci). */
    @Synchronized
    fun forgive(e: PoolEntry) {
        e.cooldownUntil = 0L
        e.lastReason = null
    }

    @Synchronized
    fun shortestWaitMs(): Long? {
        val now = clock()
        return entries.filter { it.disabledReason == null }.minOfOrNull { (it.cooldownUntil - now).coerceAtLeast(0) }
    }

    @Synchronized
    fun reset() {
        entries.forEach {
            it.disabledReason = null
            it.cooldownUntil = 0
            it.lastReason = null
        }
        current = null
    }

    @Synchronized
    fun snapshot(): List<EntryStatus> {
        val now = clock()
        return entries.map { e ->
            val dis = e.disabledReason
            when {
                dis != null -> EntryStatus(e.spec.label, e.spec.model, "nonaktif: $dis", false)
                now < e.cooldownUntil ->
                    EntryStatus(e.spec.label, e.spec.model, "istirahat ${(e.cooldownUntil - now) / 1000}s: ${e.lastReason}", false)
                else -> EntryStatus(e.spec.label, e.spec.model, if (e.uses > 0) "siap · dipakai ${e.uses}×" else "siap", true)
            }
        }
    }

    fun summary(): String = snapshot().joinToString("; ") { "${it.label}: ${it.state}" }

    companion object {
        /** Diganti oleh ModelResolver setelah model tiap kunci diperiksa. */
        @Volatile
        var shared: KeyPool = KeyPool(EmbeddedKeys.specs)
    }
}

/** LlmClient yang memilih dan mengganti kunci sendiri. Protokol teks polos membuat pergantian mulus di tengah tugas. */
class FailoverClient(
    private val pool: KeyPool,
    private val factory: (ProviderConfig) -> LlmClient = ::createRawClient,
    private val onNotice: (String) -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxWaitMs: Long = 60_000L,
    private val noun: String = "kunci",
    private val onDeadKey: (apiKey: String, label: String, reason: String) -> Unit = { _, _, _ -> },
) : LlmClient {
    private var lastUsed: PoolEntry? = null

    override suspend fun complete(system: String, messages: List<Msg>): String = withFailover { it.complete(system, messages) }

    override suspend fun turn(req: TurnRequest, onUpdate: (StreamUpdate) -> Unit): TurnResult = withFailover { it.turn(req, onUpdate) }

    override suspend fun streamText(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
        var emitted = false
        return withFailover(canRetry = { !emitted }) { client ->
            client.streamText(system, messages) { t ->
                if (t.isNotBlank()) emitted = true
                onText(t)
            }
        }
    }

    private suspend fun <T> withFailover(canRetry: () -> Boolean = { true }, call: suspend (LlmClient) -> T): T {
        val maxAttempts = pool.entries.size * 2 + 2
        var attempts = 0
        var last: LlmException? = null
        val rejectedBy = HashSet<PoolEntry>()
        while (attempts < maxAttempts) {
            attempts++
            val entry = pool.pick()
            if (entry == null) {
                val wait = pool.shortestWaitMs()
                if (wait == null || wait > maxWaitMs) {
                    throw LlmException(redact("Semua $noun tidak tersedia. ${pool.summary()}"), false)
                }
                onNotice("Semua $noun sedang istirahat; menunggu ${wait / 1000 + 1}s…")
                sleep(wait + 500)
                continue
            }
            val prev = lastUsed
            if (prev != null && prev !== entry) onNotice("Beralih ke $noun ${entry.spec.label} (${entry.spec.model}).")
            lastUsed = entry
            try {
                val out = call(factory(entry.spec.toConfig()))
                pool.onSuccess(entry)
                return out
            } catch (e: LlmException) {
                last = e
                val f = classify(e)
                pool.onFailure(entry, f)
                if (f.disable && f.reason.startsWith("kunci mati") && entry.spec.apiKey.isNotBlank()) {
                    onDeadKey(entry.spec.apiKey, entry.spec.label, f.reason)
                }
                if (!canRetry()) {
                    throw LlmException(redact(e.message ?: "Koneksi terputus"), false, e.code)
                }
                onNotice("${entry.spec.label} (${entry.spec.model}) gagal: ${f.reason}; mencoba $noun lain.")
                if (!f.disable && (e.code == 400 || e.code == 413 || e.code == 422)) {
                    rejectedBy.add(entry)
                    // Semua entri yang masih hidup menolak permintaan yang sama: jangan menunggu/mengulang, dan jangan menghukum kunci.
                    if (rejectedBy.size >= pool.activeCount()) {
                        rejectedBy.forEach { pool.forgive(it) }
                        throw LlmException(redact("Permintaan ditolak oleh semua $noun (HTTP ${e.code}): ${e.message?.take(300)}"), false, e.code)
                    }
                }
            }
        }
        throw LlmException(redact("Semua percobaan gagal. Terakhir: ${last?.message}"), false)
    }

    /** Jangan pernah membocorkan kunci lewat pesan galat. */
    private fun redact(s: String): String = pool.entries.filter { it.spec.apiKey.isNotBlank() }.fold(s) { acc, e -> acc.replace(e.spec.apiKey, "***") }
}
