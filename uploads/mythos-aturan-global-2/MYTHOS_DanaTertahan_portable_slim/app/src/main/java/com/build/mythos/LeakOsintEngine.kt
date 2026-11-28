package com.build.mythos

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * MYTHOS LeakOSINT Search Engine
 *
 * Modul inti (murni Kotlin tanpa android.*, 100% dapat diuji di JVM) yang meniru mekanisme
 * pencarian API LeakOSINT dan penyusunan laporan terstruktur sesuai spesifikasi documentation.html.
 *
 * Fitur utama:
 * - Pengelompokan berurutan sesuai urutan nama input pengguna (Target #1 beserta seluruh kecocokannya
 *   secara berurutan di atas, lanjut Target #2, dst.) untuk tampilan chat mengalir langsung.
 * - Format 7 kolom standar dengan ikon stiker per kolom & dukungan salin satuan per data:
 *   Sumber, Nama, Nomor, Email, Alamat, No NIK/Paspor, Jenis kelamin.
 * - Filter pintar batch Indonesia + akhiran nomor HP (`JEPRI 02` per baris -> 1 permintaan batch API).
 * - Pengaman [unfilteredReport] di memori untuk melihat data mentah kapan saja tanpa memanggil API ulang.
 */
data class LeakOsintConfig(
    val token: String,
    val limit: Int = LeakOsintEngine.DEFAULT_LIMIT,
    val lang: String = LeakOsintEngine.DEFAULT_LANG,
    val type: String = LeakOsintEngine.DEFAULT_TYPE,
    val botName: String = ""
)

data class LeakOsintRecord(
    val fields: Map<String, String>
)

data class LeakOsintStructuredRecord(
    val indexInTarget: Int,
    val sourceName: String,
    val nama: String,
    val nomor: String,
    val email: String,
    val alamat: String,
    val nikPaspor: String,
    val jenisKelamin: String
)

data class LeakOsintTargetGroup(
    val orderIndex: Int,
    val label: String,
    val records: List<LeakOsintStructuredRecord>
)

data class LeakOsintSource(
    val name: String,
    val infoLeak: String,
    val records: List<LeakOsintRecord>,
    val isNoResults: Boolean
)

data class LeakOsintPage(
    val pageIndex: Int,
    val totalPages: Int,
    val sourceIndex: Int,
    val totalSources: Int,
    val sourceName: String,
    val infoLeak: String,
    val subPage: Int,
    val totalSubPages: Int,
    val recordStart: Int,
    val recordEnd: Int,
    val totalRecordsInSource: Int,
    val markdownText: String,
    val structuredRecords: List<LeakOsintStructuredRecord> = emptyList()
)

/** Satu aturan filter pintar per baris, mis. `JEPRI 02` atau `- JEREMI: 6070, 3311`. */
data class LeakOsintFilterRule(
    val name: String,
    val phoneSuffix: String? = null,
    val indonesiaOnly: Boolean = true,
    val phoneSuffixes: List<String> = if (phoneSuffix != null) listOf(phoneSuffix) else emptyList()
) {
    fun effectiveSuffixes(): List<String> =
        if (phoneSuffixes.isNotEmpty()) phoneSuffixes else listOfNotNull(phoneSuffix)

    fun displayLabel(): String {
        val sufs = effectiveSuffixes()
        return if (sufs.isNotEmpty()) "$name ${sufs.joinToString(", ")}" else name
    }
}

/** Rencana permintaan batch optimal hasil parsing input pengguna. */
data class LeakOsintBatchPlan(
    val apiQueries: List<String>,
    val filterRules: List<LeakOsintFilterRule>,
    val hasSmartFilter: Boolean
) {
    fun filterSummary(): String? {
        if (!hasSmartFilter || filterRules.isEmpty()) return null
        val preview = filterRules.take(4).joinToString(", ") { r ->
            if (r.phoneSuffix != null) "${r.name} (..${r.phoneSuffix})" else r.name
        }
        val extra = if (filterRules.size > 4) " +${filterRules.size - 4} lainnya" else ""
        return "Filter ID: $preview$extra"
    }
}

data class LeakOsintReport(
    val queryId: String,
    val query: String,
    val sources: List<LeakOsintSource>,
    val pages: List<LeakOsintPage>,
    val numOfDatabase: Int,
    val numOfResults: Int,
    val freeRequestsLeft: Int?,
    val price: Double?,
    val filterSummary: String? = null,
    val rawTotalResults: Int? = null,
    val unfilteredReport: LeakOsintReport? = null,
    val targetGroups: List<LeakOsintTargetGroup> = emptyList()
) {
    val hasResults: Boolean
        get() = sources.any { !it.isNoResults && it.records.isNotEmpty() }

}

sealed class LeakOsintOutcome {
    data class Success(val report: LeakOsintReport) : LeakOsintOutcome()
    data class Error(val code: String, val message: String) : LeakOsintOutcome()
}

data class LeakOsintHttpResponse(
    val statusCode: Int,
    val body: String
)

fun interface LeakOsintTransport {
    fun postJson(
        endpoint: String,
        payloadJson: String,
        timeoutMs: Int,
        onConnectionOpen: ((HttpURLConnection) -> Unit)?
    ): LeakOsintHttpResponse
}

/**
 * Pembatas laju permintaan (sliding window 3 permintaan per detik per IP, sesuai documentation.html).
 */
internal class LeakOsintRateLimiter(
    private val maxPerSecond: Int = LeakOsintEngine.MAX_REQUESTS_PER_SECOND,
    private val windowMs: Long = 1000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    private val timestamps = ArrayDeque<Long>()

    fun acquire() {
        while (true) {
            val waitMs: Long
            synchronized(timestamps) {
                val now = clockMs()
                while (timestamps.isNotEmpty() && now - timestamps.peekFirst() >= windowMs) {
                    timestamps.removeFirst()
                }
                if (timestamps.size < maxPerSecond) {
                    timestamps.addLast(now)
                    return
                }
                waitMs = (windowMs - (now - timestamps.peekFirst())).coerceAtLeast(1L)
            }
            sleeper(waitMs)
        }
    }
}

/**
 * Cache laporan berkapasitas terbatas (LRU) untuk navigasi instan di chat MYTHOS.
 */
internal class LeakOsintReportCache(private val maxEntries: Int = 32) {
    private val map = object : LinkedHashMap<String, LeakOsintReport>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LeakOsintReport>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun put(report: LeakOsintReport) {
        map[report.queryId] = report
    }

    @Synchronized
    fun get(queryId: String): LeakOsintReport? = map[queryId]

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}

/**
 * Transport HTTP bawaan memakai HttpURLConnection dengan Content-Type application/json
 * (menghindari kesalahan 501 sesuai catatan documentation.html).
 */
internal object HttpUrlConnectionLeakOsintTransport : LeakOsintTransport {
    override fun postJson(
        endpoint: String,
        payloadJson: String,
        timeoutMs: Int,
        onConnectionOpen: ((HttpURLConnection) -> Unit)?
    ): LeakOsintHttpResponse {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MYTHOS-LeakOSINT-Search-Engine/1.0")
        }
        onConnectionOpen?.invoke(conn)
        try {
            val bytes = payloadJson.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                val maxBytes = 8 * 1024 * 1024
                while (out.size() < maxBytes) {
                    val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }.orEmpty()
            return LeakOsintHttpResponse(code, body)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}

internal object LeakOsintEngine {
    const val MODULE_NAME = "MYTHOS LeakOSINT Search Engine"
    const val ENDPOINT = "https://leakosintapi.com/"
    const val MIN_LIMIT = 100
    const val MAX_LIMIT = 10_000
    const val DEFAULT_LIMIT = 100
    const val DEFAULT_LANG = "en"
    const val DEFAULT_TYPE = "json"
    const val MAX_PAGE_CHARS = 3500
    const val MAX_REQUESTS_PER_SECOND = 3
    const val DEFAULT_TIMEOUT_MS = 30_000
    const val NO_RESULTS_KEY = "No results found"

    private val SUPPORTED_TYPES = setOf("json", "short", "html")
    private val COMMAND_RE = Regex("(?is)^/(?:cek|cari|data|leak|osint|leakosint)\\s+(.+)$")
    private val COMMAND_PREFIX_RE = Regex("(?i)^(\\s*)(/(?:cek|cari|data|leak|osint|leakosint))(?=$|\\s)")
    private val QUOTED_OR_WORD_RE = Regex("\"([^\"]+)\"|(\\S+)")
    private val DATE_RE = Regex("^(\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{1,2}[-./]\\d{1,2}[-./]\\d{2,4})$")
    private val DIGITS_ONLY_RE = Regex("^\\d+$")
    private val LEADING_BULLET_RE = Regex("^\\s*(?:[-*•]+|\\d+[.)])\\s*")
    private val SMART_SUFFIX_LINE_RE = Regex("^(.+?)(?:\\s*[:–—-]\\s*|\\s+)(\\d{2,4}(?:\\s*[,/]\\s*\\d{2,4})*)$")
    private val HAS_LETTER_RE = Regex("[A-Za-z]")
    private val PHONE_KEY_RE = Regex("(?i)(phone|tel|hp|mobile|cell|wa|whatsapp|msisdn|nomor|no_?hp|contact|handphone)")
    private val COUNTRY_KEY_RE = Regex("(?i)(country|negara|nation|region|state|prov|city|kota|district|kecamatan|address|alamat|location|lokasi)")
    private val PHONE_CANDIDATE_RE = Regex("\\+?\\d[\\d\\s().-]{6,18}\\d")
    private val NON_DIGITS_RE = Regex("[^0-9]")
    private val ID_TRUNCATED_ZERO_RE = Regex("^8[1235789]\\d{7,11}$")
    private val EXPLICIT_FOREIGN_PLUS_RE = Regex("^\\+(?!62\\b)\\d{1,3}")

    // Regex pemetaan 7 kolom standar tampilan chat
    private val FULL_NAME_KEY_RE = Regex("(?i)^(full_?name|fullname|nama_?lengkap|nama|name|display_?name)$")
    private val FIRST_NAME_KEY_RE = Regex("(?i)^(first_?name|firstname|nama_?depan|given_?name)$")
    private val MIDDLE_NAME_KEY_RE = Regex("(?i)^(middle_?name|middlename|nama_?tengah)$")
    private val LAST_NAME_KEY_RE = Regex("(?i)^(last_?name|lastname|surname|family_?name|nama_?belakang)$")
    private val USERNAME_FALLBACK_KEY_RE = Regex("(?i)^(username|user_?name|nick_?name|nickname|login|alias|tags?)$")
    private val EMAIL_KEY_RE = Regex("(?i)(e?mail)")
    private val NIK_PASSPORT_KEY_RE = Regex("(?i)(nik|ktp|paspor|passport|national_?id|identity|id_?number|id_?card|no_?id|doc_?num|document|ssn|kk|no_?kk)")
    private val GENDER_KEY_RE = Regex("(?i)^(gender|sex|jenis_?kelamin|kelamin|jk)$")

    private val ADDRESS_KEY_PRIORITY: List<Regex> = listOf(
        Regex("(?i)^(address|alamat|street|jalan|addr|home_?address|residence)$"),
        Regex("(?i)^(location|lokasi|village|kelurahan|desa)$"),
        Regex("(?i)^(district|kecamatan|subdistrict)$"),
        Regex("(?i)^(city|kota|kabupaten|regency|town)$"),
        Regex("(?i)^(region|wilayah|area)$"),
        Regex("(?i)^(state|province|provinsi)$"),
        Regex("(?i)^(postal_?code|zip|zipcode|kode_?pos)$"),
        Regex("(?i)^(country|negara|nation)$")
    )

    private val ID_INSTITUTION_AND_DOMAIN_MARKERS = listOf(
        ".co.id", ".go.id", ".ac.id", ".or.id", ".sch.id", ".web.id", ".id",
        "tokopedia", "bukalapak", "shopee.co.id", "blibli", "lazada.co.id", "bhinneka", "olx.co.id",
        "indihome", "telkomsel", "indosat", "smartfren", "biznet", "firstmedia", "myrepublic",
        "bpjs", "dukcapil", "kemendagri", "kpu", "kominfo", "imigrasi", "polri", "bkn",
        "kemdikbud", "kemenkes", "pedulilindungi", "satusehat", "pdiperjuangan",
        "pln", "pertamina", "mypertamina", "gojek", "traveloka", "tiket.com",
        "ruangguru", "zenius", "halodoc", "alodokter", "kaskus", "detik", "kompas",
        "tribunnews", "liputan6", "bank bri", "bank bca", "bank bni", "bank mandiri"
    )

    private val ID_GEO_MARKERS = listOf(
        "indonesia", "jakarta", "jawa", "sumatera", "sumatra", "kalimantan", "sulawesi",
        "papua", "bali", "banten", "bangka", "belitung", "kep_bang_bel", "babel",
        "palembang", "bandung", "surabaya", "medan", "semarang", "makassar", "yogyakarta", "jogja",
        "aceh", "lampung", "riau", "kepri", "jambi", "bengkulu", "maluku", "gorontalo",
        "depok", "bekasi", "tangerang", "bogor", "malang", "solo", "surakarta", "denpasar",
        "batam", "pekanbaru", "padang", "pontianak", "banjarmasin", "manado", "jayapura",
        "samarinda", "balikpapan", "cirebon", "toboali", "pangkalpinang", "ntb", "ntt",
        "lombok", "kupang", "ambon", "ternate", "sumsel", "sumut", "sumbar", "jabar",
        "jateng", "jatim", "kaltim", "kalsel", "kalbar", "kalteng", "sulsel", "sulut",
        "sulteng", "sultra", "sulbar", "kabupaten", "kecamatan", "kelurahan", "provinsi"
    )

    private val FOREIGN_MARKERS = listOf(
        "russia", "russian", "россия", "москва", "ukraine", "украина", "belarus", "kazakhstan",
        "united states", "usa", "united kingdom", "england", "germany", "france", "spain",
        "italy", "brazil", "mexico", "argentina", "india", "china", "japan", "korea",
        "malaysia", "singapore", "philippines", "vietnam", "thailand", "australia", "canada",
        "turkey", "poland", "netherlands", "nigeria", "pakistan", "bangladesh", "egypt"
    )

    val defaultRateLimiter = LeakOsintRateLimiter()
    val defaultCache = LeakOsintReportCache()

    /**
     * Deteksi perintah cepat di chat biasa: `/leak <kueri>`, `/osint <kueri>`, atau `/leakosint <kueri>`.
     */
    fun extractCommandQuery(rawInput: String): String? {
        val trimmed = rawInput.trim()
        val m = COMMAND_RE.matchEntire(trimmed) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    /** True jika kolom ketik diawali `/leak`, `/osint`, atau `/leakosint` (meski belum ada kata kunci di belakangnya). */
    fun isCommandPrefix(rawInput: String): Boolean = COMMAND_PREFIX_RE.containsMatchIn(rawInput)

    /** Rentang indeks `[start, end)` token perintah `/leak` di teks asli untuk pewarnaan live, atau null bila bukan perintah. */
    fun commandPrefixRange(rawInput: String): IntRange? {
        val m = COMMAND_PREFIX_RE.find(rawInput) ?: return null
        val grp = m.groups[2] ?: return null
        return grp.range
    }

    /** Normalisasi nama bot cermin sesuai format `@name` pada documentation.html. */
    fun normalizeBotName(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return if (t.startsWith("@")) t else "@$t"
    }

    /** Validasi dan rapikan batas pencarian pada rentang resmi 100..10.000. */
    fun clampLimit(limit: Int): Int = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)

    /** Parse input pengguna menjadi rencana batch optimal (mendukung bullet `- `, titik dua `:`, dan banyak akhiran `6070, 3311`). */
    fun parseSmartBatchInput(rawQuery: String): LeakOsintBatchPlan {
        val lines = rawQuery.lineSequence()
            .map { LEADING_BULLET_RE.replace(it.trim(), "").trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) {
            return LeakOsintBatchPlan(emptyList(), emptyList(), hasSmartFilter = false)
        }

        val parsed = ArrayList<Pair<String, List<String>>>()
        var anySmart = false

        for (line in lines) {
            val m = SMART_SUFFIX_LINE_RE.matchEntire(line)
            if (m != null) {
                val left = m.groupValues[1].trim().trimEnd(':', '-', '–', '—', ',').trim()
                val suffixes = m.groupValues[2]
                    .split(Regex("[,/]"))
                    .map { it.trim() }
                    .filter { it.length in 2..4 && it.all { ch -> ch.isDigit() } }
                    .distinct()
                if (left.isNotEmpty() && suffixes.isNotEmpty() && HAS_LETTER_RE.containsMatchIn(left) && !left.contains("@") && !left.contains("://")) {
                    anySmart = true
                    parsed.add(left to suffixes)
                    continue
                }
            }
            val cleanPlain = line.trimEnd(':').trim()
            if (cleanPlain.isNotEmpty()) parsed.add(cleanPlain to emptyList())
        }

        if (!anySmart) {
            return LeakOsintBatchPlan(
                apiQueries = parsed.map { it.first },
                filterRules = emptyList(),
                hasSmartFilter = false
            )
        }

        val seenQueries = LinkedHashMap<String, String>()
        val seenRules = HashSet<String>()
        val rules = ArrayList<LeakOsintFilterRule>()
        for ((name, suffixes) in parsed) {
            val normKey = name.lowercase()
            if (!seenQueries.containsKey(normKey)) {
                seenQueries[normKey] = name
            }
            val ruleKey = "$normKey|${suffixes.joinToString(",")}"
            if (seenRules.add(ruleKey)) {
                rules.add(
                    LeakOsintFilterRule(
                        name = name,
                        phoneSuffix = suffixes.firstOrNull(),
                        indonesiaOnly = true,
                        phoneSuffixes = suffixes
                    )
                )
            }
        }

        return LeakOsintBatchPlan(
            apiQueries = seenQueries.values.toList(),
            filterRules = rules,
            hasSmartFilter = true
        )
    }

    /** Bangun payload JSON permintaan sesuai documentation.html. */
    fun buildPayload(query: String, config: LeakOsintConfig): JSONObject {
        val lines = query.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return buildPayloadForQueries(lines, config)
    }

    fun buildPayloadForQueries(queries: List<String>, config: LeakOsintConfig): JSONObject {
        val cleanQueries = queries.map { it.trim() }.filter { it.isNotEmpty() }
        require(cleanQueries.isNotEmpty()) { "Kata kunci pencarian tidak boleh kosong." }
        val cleanToken = config.token.trim()
        require(cleanToken.isNotEmpty()) { "Token API LeakOSINT belum diisi." }

        val typeNorm = config.type.trim().lowercase().let { if (it in SUPPORTED_TYPES) it else DEFAULT_TYPE }
        val langNorm = config.lang.trim().lowercase().ifBlank { DEFAULT_LANG }
        val botNorm = normalizeBotName(config.botName)

        val payload = JSONObject()
            .put("token", cleanToken)
            .put("limit", clampLimit(config.limit))
            .put("lang", langNorm)
            .put("type", typeNorm)

        if (cleanQueries.size == 1) {
            payload.put("request", cleanQueries[0])
        } else {
            val arr = JSONArray()
            cleanQueries.forEach { arr.put(it) }
            payload.put("request", arr)
        }
        if (botNorm.isNotEmpty()) {
            payload.put("bot_name", botNorm)
        }
        return payload
    }

    /** Hitung Complexity sesuai aturan documentation.html. */
    fun estimateComplexity(query: String): Int {
        var effectiveWords = 0
        for (m in QUOTED_OR_WORD_RE.findAll(query.trim())) {
            val quoted = m.groupValues[1]
            if (quoted.isNotEmpty()) {
                effectiveWords++
                continue
            }
            val token = m.groupValues[2].trim()
            if (token.isEmpty()) continue
            if (DATE_RE.matches(token)) continue
            if (DIGITS_ONLY_RE.matches(token)) {
                if (token.length < 6) continue
            } else if (token.length < 4) {
                continue
            }
            effectiveWords++
        }
        return when {
            effectiveWords <= 1 -> 1
            effectiveWords == 2 -> 5
            effectiveWords == 3 -> 16
            else -> 40
        }
    }

    /** Formula resmi harga permintaan dalam USD: `0.0002 * (5 + sqrt(Limit * Complexity))`. */
    fun estimatePriceUsd(limit: Int, complexity: Int): Double {
        val safeLimit = clampLimit(limit).toDouble()
        val safeComplexity = complexity.coerceAtLeast(1).toDouble()
        return 0.0002 * (5.0 + sqrt(safeLimit * safeComplexity))
    }

    /** Indeks halaman melingkar (wrap-around) untuk navigasi `<<` dan `>>`. */
    fun wrapPageIndex(pageIndex: Int, totalPages: Int): Int {
        if (totalPages <= 1) return 0
        val mod = pageIndex % totalPages
        return if (mod < 0) mod + totalPages else mod
    }

    internal data class ExtractedPhone(val raw: String, val digits: String)

    /** Ekstrak semua nomor telepon (mentah + digit murni) dari sebuah record. */
    internal fun extractPhoneEntries(record: LeakOsintRecord): List<ExtractedPhone> {
        val seen = LinkedHashMap<String, ExtractedPhone>()
        for ((k, v) in record.fields) {
            if (PHONE_KEY_RE.containsMatchIn(k)) {
                val trimmed = v.trim()
                val digits = NON_DIGITS_RE.replace(trimmed, "")
                if (digits.length in 7..16) {
                    seen.putIfAbsent(digits, ExtractedPhone(trimmed, digits))
                }
                for (m in PHONE_CANDIDATE_RE.findAll(v)) {
                    val d = NON_DIGITS_RE.replace(m.value, "")
                    if (d.length in 7..16) {
                        seen.putIfAbsent(d, ExtractedPhone(m.value.trim(), d))
                    }
                }
            } else {
                for (m in PHONE_CANDIDATE_RE.findAll(v)) {
                    val raw = m.value.trim()
                    val d = NON_DIGITS_RE.replace(raw, "")
                    if ((d.startsWith("62") && d.length in 10..15) ||
                        (d.startsWith("08") && d.length in 9..14) ||
                        ID_TRUNCATED_ZERO_RE.matches(d)
                    ) {
                        seen.putIfAbsent(d, ExtractedPhone(raw, d))
                    }
                }
            }
        }
        return seen.values.toList()
    }

    internal fun isExplicitForeignPhone(phone: ExtractedPhone): Boolean {
        if (EXPLICIT_FOREIGN_PLUS_RE.containsMatchIn(phone.raw)) return true
        val d = phone.digits
        if (d.startsWith("62") || d.startsWith("08")) return false
        if (ID_TRUNCATED_ZERO_RE.matches(d)) return false
        return d.startsWith("60") || d.startsWith("65") || d.startsWith("63") || d.startsWith("66") ||
            d.startsWith("84") || d.startsWith("86") || d.startsWith("91") || d.startsWith("44") ||
            d.startsWith("49") || d.startsWith("33") || d.startsWith("380") ||
            (d.startsWith("79") && d.length == 11) || (d.startsWith("1") && d.length == 11)
    }

    internal fun isIndonesianPhone(phone: ExtractedPhone, indonesianContext: Boolean): Boolean {
        if (EXPLICIT_FOREIGN_PLUS_RE.containsMatchIn(phone.raw)) return false
        val d = phone.digits
        if (d.startsWith("62") && d.length in 10..15) return true
        if (d.startsWith("08") && d.length in 9..14) return true
        if (indonesianContext && ID_TRUNCATED_ZERO_RE.matches(d)) return true
        return false
    }

    internal fun hasIndonesianContext(sourceName: String, infoLeak: String, record: LeakOsintRecord): Boolean {
        val fullBlob = buildString {
            append(sourceName).append(' ').append(infoLeak)
            for ((_, v) in record.fields) append(' ').append(v)
        }.lowercase()
        return ID_INSTITUTION_AND_DOMAIN_MARKERS.any { fullBlob.contains(it) } ||
            ID_GEO_MARKERS.any { fullBlob.contains(it) }
    }

    /** Periksa apakah record berasal dari Indonesia (dan bukan data luar negeri). */
    internal fun isIndonesianRecord(sourceName: String, infoLeak: String, record: LeakOsintRecord): Boolean {
        val phones = extractPhoneEntries(record)
        val countryBlob = buildString {
            for ((k, v) in record.fields) {
                if (COUNTRY_KEY_RE.containsMatchIn(k)) append(' ').append(v)
            }
        }.lowercase()

        val explicitForeignCountry = FOREIGN_MARKERS.any { countryBlob.contains(it) }
        if (explicitForeignCountry) return false

        val indoContext = hasIndonesianContext(sourceName, infoLeak, record)
        val hasIdPhone = phones.any { isIndonesianPhone(it, indoContext) }
        val hasOnlyForeignPhone = phones.isNotEmpty() && phones.all { isExplicitForeignPhone(it) }

        if (hasIdPhone) return true
        if (hasOnlyForeignPhone) return false
        if (phones.isNotEmpty()) return false
        return indoContext
    }

    /** Periksa apakah record cocok dengan satu aturan `(nama, akhiran HP, khusus Indonesia)`. */
    internal fun matchesRule(
        sourceName: String,
        infoLeak: String,
        record: LeakOsintRecord,
        rule: LeakOsintFilterRule,
        allValuesLower: String = record.fields.values.joinToString(" ").lowercase(),
        phones: List<ExtractedPhone> = extractPhoneEntries(record),
        indoContext: Boolean = hasIndonesianContext(sourceName, infoLeak, record),
        isIndo: Boolean = isIndonesianRecord(sourceName, infoLeak, record)
    ): Boolean {
        if (rule.indonesiaOnly && !isIndo) return false

        val nameTokens = rule.name
            .replace("\"", " ")
            .trim()
            .split(Regex("\\s+"))
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }
        if (nameTokens.isNotEmpty() && nameTokens.any { !allValuesLower.contains(it) }) {
            return false
        }

        val suffixes = rule.effectiveSuffixes()
        if (suffixes.isNotEmpty()) {
            val matchingPhone = phones.any { phone ->
                (!rule.indonesiaOnly || isIndonesianPhone(phone, indoContext)) &&
                    suffixes.any { suf -> phone.digits.endsWith(suf) }
            }
            if (!matchingPhone) return false
        }
        return true
    }

    /**
     * Ekstrak 7 kolom standar tampilan chat MYTHOS dari satu record:
     * Sumber, Nama, Nomor, Email, Alamat, No NIK/Paspor, Jenis kelamin.
     */
    fun extractStandardFields(
        sourceName: String,
        record: LeakOsintRecord,
        indexInTarget: Int = 0
    ): LeakOsintStructuredRecord {
        val fields = record.fields

        // 1. Nama
        val fullNames = LinkedHashSet<String>()
        var first = ""
        var middle = ""
        var last = ""
        var usernameFallback = ""
        for ((k, v) in fields) {
            val clean = v.trim()
            if (clean.isEmpty()) continue
            when {
                FULL_NAME_KEY_RE.matches(k) -> fullNames.add(clean)
                FIRST_NAME_KEY_RE.matches(k) && first.isEmpty() -> first = clean
                MIDDLE_NAME_KEY_RE.matches(k) && middle.isEmpty() -> middle = clean
                LAST_NAME_KEY_RE.matches(k) && last.isEmpty() -> last = clean
                USERNAME_FALLBACK_KEY_RE.matches(k) && usernameFallback.isEmpty() -> usernameFallback = clean
            }
        }
        val combinedParts = listOf(first, middle, last).filter { it.isNotEmpty() }.joinToString(" ")
        val nama = when {
            fullNames.isNotEmpty() -> fullNames.joinToString(" / ")
            combinedParts.isNotEmpty() -> combinedParts
            usernameFallback.isNotEmpty() -> usernameFallback
            else -> "-"
        }

        // 2. Nomor
        val nomorList = LinkedHashSet<String>()
        for ((k, v) in fields) {
            val clean = v.trim()
            if (clean.isNotEmpty() && PHONE_KEY_RE.containsMatchIn(k)) {
                nomorList.add(clean)
            }
        }
        if (nomorList.isEmpty()) {
            extractPhoneEntries(record).forEach { nomorList.add(it.raw) }
        }
        val nomor = if (nomorList.isNotEmpty()) nomorList.joinToString(", ") else "-"

        // 3. Email
        val emailList = LinkedHashSet<String>()
        for ((k, v) in fields) {
            val clean = v.trim()
            if (clean.isNotEmpty() && (EMAIL_KEY_RE.containsMatchIn(k) || clean.contains("@"))) {
                emailList.add(clean)
            }
        }
        val email = if (emailList.isNotEmpty()) emailList.joinToString(", ") else "-"

        // 4. Alamat (gabung urut & deduplikasi case-insensitive mis. Location == Region)
        val addrParts = ArrayList<String>()
        val addrSeenLower = HashSet<String>()
        for (re in ADDRESS_KEY_PRIORITY) {
            for ((k, v) in fields) {
                if (!re.matches(k)) continue
                val clean = v.trim()
                if (clean.isNotEmpty() && addrSeenLower.add(clean.lowercase())) {
                    addrParts.add(clean)
                }
            }
        }
        val alamat = if (addrParts.isNotEmpty()) addrParts.joinToString(", ") else "-"

        // 5. No NIK/Paspor
        val idList = LinkedHashSet<String>()
        for ((k, v) in fields) {
            val clean = v.trim()
            if (clean.isNotEmpty() && NIK_PASSPORT_KEY_RE.containsMatchIn(k)) {
                idList.add(clean)
            }
        }
        val nikPaspor = if (idList.isNotEmpty()) idList.joinToString(", ") else "-"

        // 6. Jenis kelamin
        var genderRaw = ""
        for ((k, v) in fields) {
            if (GENDER_KEY_RE.matches(k) && v.isNotBlank()) {
                genderRaw = v.trim()
                break
            }
        }
        val jenisKelamin = normalizeGender(genderRaw)

        return LeakOsintStructuredRecord(
            indexInTarget = indexInTarget,
            sourceName = sourceName,
            nama = nama,
            nomor = nomor,
            email = email,
            alamat = alamat,
            nikPaspor = nikPaspor,
            jenisKelamin = jenisKelamin
        )
    }

    /**
     * Format satu record ke 7 baris standar tampilan chat MYTHOS:
     * Sumber, Nama, Nomor, Email, Alamat, No NIK/Paspor, Jenis kelamin.
     */
    fun formatStandardRecordMarkdown(sourceName: String, record: LeakOsintRecord): String =
        formatStructuredRecordMarkdown(extractStandardFields(sourceName, record))

    internal fun formatStructuredRecordMarkdown(item: LeakOsintStructuredRecord): String = buildString {
        append("**Sumber**: ").append(item.sourceName).append("\n")
        append("**Nama**: ").append(item.nama).append("\n")
        append("**Nomor**: ").append(item.nomor).append("\n")
        append("**Email**: ").append(item.email).append("\n")
        append("**Alamat**: ").append(item.alamat).append("\n")
        append("**No NIK/Paspor**: ").append(item.nikPaspor).append("\n")
        append("**Jenis kelamin**: ").append(item.jenisKelamin)
    }

    /**
     * Format 1 data siap salin (salin satuan) lengkap dengan stiker masing-masing kolom.
     */
    fun formatStructuredRecordCopyText(item: LeakOsintStructuredRecord, targetLabel: String? = null): String = buildString {
        if (item.indexInTarget > 0) {
            append("📌 Data #").append(item.indexInTarget)
            if (!targetLabel.isNullOrBlank()) append(" (").append(targetLabel).append(")")
            append("\n")
        }
        append("📂 Sumber: ").append(item.sourceName).append("\n")
        append("👤 Nama: ").append(item.nama).append("\n")
        append("📞 Nomor: ").append(item.nomor).append("\n")
        append("📧 Email: ").append(item.email).append("\n")
        append("📍 Alamat: ").append(item.alamat).append("\n")
        append("🪪 No NIK/Paspor: ").append(item.nikPaspor).append("\n")
        append("⚧ Jenis kelamin: ").append(item.jenisKelamin)
    }

    internal fun normalizeGender(raw: String): String = when (raw.trim().lowercase()) {
        "" -> "-"
        "l", "lk", "m", "male", "laki-laki", "laki laki", "pria" -> "Laki-laki"
        "p", "pr", "f", "female", "perempuan", "wanita" -> "Perempuan"
        else -> raw.trim()
    }

    /**
     * Terapkan filter batch Indonesia + akhiran nomor HP pada [LeakOsintReport],
     * lalu kelompokkan hasil secara berurutan mengikuti urutan nama input pengguna ([targetGroups]).
     * Menyimpan [unfilteredReport] di memori agar pengguna bisa meninjau data mentah kapan saja tanpa memanggil API ulang.
     */
    fun applyBatchFilter(
        report: LeakOsintReport,
        plan: LeakOsintBatchPlan,
        maxPageChars: Int = MAX_PAGE_CHARS
    ): LeakOsintReport {
        if (!plan.hasSmartFilter || plan.filterRules.isEmpty()) return report

        val rawCount = report.sources.sumOf { it.records.size }
        val filteredSources = ArrayList<LeakOsintSource>()
        val targetBuckets = plan.filterRules.map { rule ->
            rule to ArrayList<Pair<String, LeakOsintRecord>>()
        }

        for (src in report.sources) {
            if (src.isNoResults) continue
            val matchedInSource = ArrayList<LeakOsintRecord>()
            for (rec in src.records) {
                val allValuesLower = rec.fields.values.joinToString(" ").lowercase()
                val phones = extractPhoneEntries(rec)
                val indoContext = hasIndonesianContext(src.name, src.infoLeak, rec)
                val isIndo = isIndonesianRecord(src.name, src.infoLeak, rec)

                var matchedAny = false
                for ((rule, bucket) in targetBuckets) {
                    if (matchesRule(src.name, src.infoLeak, rec, rule, allValuesLower, phones, indoContext, isIndo)) {
                        bucket.add(src.name to rec)
                        matchedAny = true
                    }
                }
                if (matchedAny) {
                    matchedInSource.add(rec)
                }
            }
            if (matchedInSource.isNotEmpty()) {
                filteredSources.add(
                    LeakOsintSource(
                        name = src.name,
                        infoLeak = src.infoLeak,
                        records = matchedInSource,
                        isNoResults = false
                    )
                )
            }
        }

        val summary = plan.filterSummary()
        val emptyInfoLeak =
            "Tidak ditemukan data Indonesia yang cocok dengan $summary (dari $rawCount data mentah yang diperiksa). Ketuk tombol 'Mentah' di bawah untuk melihat seluruh hasil asli tanpa memanggil API ulang."

        if (filteredSources.isEmpty()) {
            filteredSources.add(
                LeakOsintSource(
                    name = NO_RESULTS_KEY,
                    infoLeak = emptyInfoLeak,
                    records = emptyList(),
                    isNoResults = true
                )
            )
        }

        val groups = targetBuckets.mapIndexed { idx, (rule, entries) ->
            val structured = entries.mapIndexed { rIdx, (srcName, rec) ->
                extractStandardFields(srcName, rec, indexInTarget = rIdx + 1)
            }
            LeakOsintTargetGroup(
                orderIndex = idx + 1,
                label = rule.displayLabel(),
                records = structured
            )
        }

        val newPages = buildOrderedTargetPages(groups, emptyInfoLeak, maxPageChars.coerceAtLeast(400))
        val matchedSourcesCount = filteredSources.count { !it.isNoResults }
        val matchedRecordsCount = filteredSources.sumOf { it.records.size }

        return report.copy(
            sources = filteredSources,
            pages = newPages,
            numOfDatabase = matchedSourcesCount,
            numOfResults = matchedRecordsCount,
            filterSummary = summary,
            rawTotalResults = rawCount,
            unfilteredReport = report,
            targetGroups = groups
        )
    }

    /**
     * Susun halaman berbasis urutan nama target (untuk kompatibilitas riwayat & fallback).
     */
    internal fun buildOrderedTargetPages(
        groups: List<LeakOsintTargetGroup>,
        emptyInfoLeak: String,
        maxPageChars: Int = MAX_PAGE_CHARS
    ): List<LeakOsintPage> {
        val hasAny = groups.any { it.records.isNotEmpty() }
        if (!hasAny) {
            val emptyText = "**$NO_RESULTS_KEY**\n\n$emptyInfoLeak"
            return listOf(
                LeakOsintPage(
                    pageIndex = 0,
                    totalPages = 1,
                    sourceIndex = 0,
                    totalSources = 1,
                    sourceName = NO_RESULTS_KEY,
                    infoLeak = emptyInfoLeak,
                    subPage = 1,
                    totalSubPages = 1,
                    recordStart = 0,
                    recordEnd = 0,
                    totalRecordsInSource = 0,
                    markdownText = emptyText
                )
            )
        }

        val matchedGroups = groups.filter { it.records.isNotEmpty() }
        val pages = ArrayList<LeakOsintPage>()

        matchedGroups.forEachIndexed { gIdx, group ->
            val formatted = group.records.map { item ->
                buildString {
                    append("📌 Data #").append(item.indexInTarget).append("\n")
                    append(formatStructuredRecordMarkdown(item))
                }
            }
            val chunks = ArrayList<Triple<Int, Int, String>>()
            val buf = StringBuilder()
            var start = 0
            for (i in formatted.indices) {
                val block = formatted[i]
                val candidate = buf.length + (if (buf.isNotEmpty()) 2 else 0) + block.length + 64
                if (buf.isNotEmpty() && candidate > maxPageChars) {
                    chunks.add(Triple(start + 1, i, buf.toString().trimEnd()))
                    buf.setLength(0)
                    start = i
                }
                if (buf.isNotEmpty()) buf.append("\n\n")
                buf.append(block)
            }
            if (buf.isNotEmpty()) {
                chunks.add(Triple(start + 1, formatted.size, buf.toString().trimEnd()))
            }

            val totalSub = chunks.size
            chunks.forEachIndexed { cIdx, (recStart, recEnd, body) ->
                val md = buildString {
                    append("🔎 **${group.orderIndex}. ${group.label}** (${group.records.size} cocok)\n\n")
                    append(body)
                }.trimEnd()
                pages.add(
                    LeakOsintPage(
                        pageIndex = pages.size,
                        totalPages = 0, // diisi ulang di bawah
                        sourceIndex = gIdx,
                        totalSources = matchedGroups.size,
                        sourceName = group.label,
                        infoLeak = "",
                        subPage = cIdx + 1,
                        totalSubPages = totalSub,
                        recordStart = recStart,
                        recordEnd = recEnd,
                        totalRecordsInSource = group.records.size,
                        markdownText = md,
                        structuredRecords = group.records.subList(recStart - 1, recEnd)
                    )
                )
            }
        }

        val total = pages.size
        return pages.mapIndexed { idx, p -> p.copy(pageIndex = idx, totalPages = total) }
    }

    /**
     * Parse respons JSON dari LeakOSINT API menjadi [LeakOsintReport] dengan multi-sumber
     * dan halaman-halaman lengkap memakai format ringkas 7 kolom.
     */
    fun parseResponse(
        query: String,
        rawJson: String,
        queryId: String = UUID.randomUUID().toString().take(8),
        maxPageChars: Int = MAX_PAGE_CHARS
    ): LeakOsintOutcome {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return LeakOsintOutcome.Error("EMPTY_RESPONSE", "Server LeakOSINT mengembalikan respons kosong.")
        }
        val root: JSONObject = try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val mergedList = JSONObject()
                for (i in 0 until arr.length()) {
                    val elem = arr.optJSONObject(i) ?: continue
                    val subList = elem.optJSONObject("List") ?: elem.optJSONObject("list") ?: continue
                    val ks = subList.keys()
                    while (ks.hasNext()) {
                        val k = ks.next()
                        val v = subList.optJSONObject(k) ?: continue
                        val targetKey = if (mergedList.has(k)) "$k (#${i + 1})" else k
                        mergedList.put(targetKey, v)
                    }
                }
                JSONObject().put("List", mergedList)
            } else {
                JSONObject(trimmed)
            }
        } catch (e: Exception) {
            return LeakOsintOutcome.Error(
                "INVALID_JSON",
                "Respons LeakOSINT tidak berbentuk JSON yang valid."
            )
        }

        val errKey = listOf("Error code", "error", "Error", "error_code", "detail").firstOrNull { key ->
            root.has(key) && !root.isNull(key) && root.optString(key).isNotBlank()
        }
        if (errKey != null) {
            val errCode = root.optString(errKey).trim().ifBlank { "UNKNOWN_ERROR" }
            return LeakOsintOutcome.Error(errCode, friendlyApiError(errCode))
        }

        val listObj: JSONObject? = when {
            root.optJSONObject("List") != null -> root.optJSONObject("List")
            root.optJSONObject("list") != null -> root.optJSONObject("list")
            root.optJSONArray("List") != null || root.optJSONArray("list") != null -> {
                val arr = root.optJSONArray("List") ?: root.optJSONArray("list")
                val merged = JSONObject()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val elem = arr.optJSONObject(i) ?: continue
                        val sub = elem.optJSONObject("List") ?: elem.optJSONObject("list")
                        if (sub != null) {
                            val ks = sub.keys()
                            while (ks.hasNext()) {
                                val k = ks.next()
                                val v = sub.optJSONObject(k) ?: continue
                                val tk = if (merged.has(k)) "$k (#${i + 1})" else k
                                merged.put(tk, v)
                            }
                        } else if (elem.has("Data") || elem.has("InfoLeak")) {
                            merged.put("Sumber #${i + 1}", elem)
                        }
                    }
                }
                merged
            }
            else -> {
                // Cek apakah respons batch dibungkus per kueri, mis. { "Query1": { "List": {...} }, ... }
                val nested = JSONObject()
                var foundNested = false
                val rKeys = root.keys()
                while (rKeys.hasNext()) {
                    val rk = rKeys.next()
                    val child = root.optJSONObject(rk) ?: continue
                    val sub = child.optJSONObject("List") ?: child.optJSONObject("list") ?: continue
                    foundNested = true
                    val sk = sub.keys()
                    while (sk.hasNext()) {
                        val dbk = sk.next()
                        val dbv = sub.optJSONObject(dbk) ?: continue
                        val tk = if (nested.has(dbk)) "$dbk ($rk)" else dbk
                        nested.put(tk, dbv)
                    }
                }
                if (foundNested) nested else null
            }
        }
        if (listObj == null) {
            val keyNames = ArrayList<String>()
            val rk = root.keys()
            while (rk.hasNext()) keyNames.add(rk.next())
            val diag = if (keyNames.isNotEmpty()) " (kunci: ${keyNames.joinToString(", ")})" else ""
            return LeakOsintOutcome.Error(
                "MISSING_LIST",
                "Respons API tidak memuat objek 'List'$diag. Coba kirim 1 nama per pencarian bila server menolak batch banyak nama."
            )
        }

        val sources = ArrayList<LeakOsintSource>()
        val dbKeys = listObj.keys()
        while (dbKeys.hasNext()) {
            val dbName = dbKeys.next()
            val dbObj = listObj.optJSONObject(dbName) ?: continue
            val infoLeak = dbObj.optString("InfoLeak", "").trim()
            val isNoResults = dbName.equals(NO_RESULTS_KEY, ignoreCase = true)
            val records = ArrayList<LeakOsintRecord>()

            if (!isNoResults) {
                val dataArr = dbObj.optJSONArray("Data")
                if (dataArr != null) {
                    for (i in 0 until dataArr.length()) {
                        val rowObj = dataArr.optJSONObject(i) ?: continue
                        val fields = LinkedHashMap<String, String>()
                        val colKeys = rowObj.keys()
                        while (colKeys.hasNext()) {
                            val col = colKeys.next()
                            val rawVal = rowObj.opt(col)
                            val strVal = formatJsonValue(rawVal)
                            if (strVal.isNotBlank()) {
                                fields[col] = strVal
                            }
                        }
                        if (fields.isNotEmpty()) {
                            records.add(LeakOsintRecord(fields))
                        }
                    }
                }
            }
            sources.add(
                LeakOsintSource(
                    name = dbName,
                    infoLeak = infoLeak,
                    records = records,
                    isNoResults = isNoResults
                )
            )
        }

        if (sources.isEmpty()) {
            sources.add(
                LeakOsintSource(
                    name = NO_RESULTS_KEY,
                    infoLeak = "Tidak ditemukan data yang cocok untuk kata kunci ini.",
                    records = emptyList(),
                    isNoResults = true
                )
            )
        }

        val pages = buildPages(sources, maxPageChars.coerceAtLeast(400))
        val realSourcesCount = sources.count { !it.isNoResults }
        val countedRecords = sources.sumOf { it.records.size }
        val numOfDatabase = if (root.has("NumOfDatabase")) root.optInt("NumOfDatabase", realSourcesCount) else realSourcesCount
        val numOfResults = if (root.has("NumOfResults")) root.optInt("NumOfResults", countedRecords) else countedRecords
        val freeLeft = if (root.has("free_requests_left") && !root.isNull("free_requests_left")) {
            root.optInt("free_requests_left")
        } else null
        val price = if (root.has("price") && !root.isNull("price")) {
            root.optDouble("price").takeIf { !it.isNaN() }
        } else null

        return LeakOsintOutcome.Success(
            LeakOsintReport(
                queryId = queryId,
                query = query.trim(),
                sources = sources,
                pages = pages,
                numOfDatabase = numOfDatabase,
                numOfResults = numOfResults,
                freeRequestsLeft = freeLeft,
                price = price
            )
        )
    }

    /**
     * Susun daftar halaman multi-sumber dengan format ringkas 7 kolom standar
     * dan pemecahan sub-halaman penuh tanpa memotong record.
     */
    fun buildPages(
        sources: List<LeakOsintSource>,
        maxPageChars: Int = MAX_PAGE_CHARS
    ): List<LeakOsintPage> {
        data class DraftPage(
            val sourceIndex: Int,
            val sourceName: String,
            val infoLeak: String,
            val subPage: Int,
            var totalSubPages: Int,
            val recordStart: Int,
            val recordEnd: Int,
            val totalRecordsInSource: Int,
            val structuredRecords: List<LeakOsintStructuredRecord>,
            val bodyMarkdown: String
        )

        val drafts = ArrayList<DraftPage>()
        val totalSources = sources.size

        sources.forEachIndexed { sIdx, src ->
            if (src.isNoResults || src.records.isEmpty()) {
                val emptyText = buildString {
                    append("**").append(src.name).append("**")
                    if (src.infoLeak.isNotBlank()) append("\n\n").append(src.infoLeak)
                }
                drafts.add(
                    DraftPage(
                        sourceIndex = sIdx,
                        sourceName = src.name,
                        infoLeak = src.infoLeak,
                        subPage = 1,
                        totalSubPages = 1,
                        recordStart = 0,
                        recordEnd = 0,
                        totalRecordsInSource = 0,
                        structuredRecords = emptyList(),
                        bodyMarkdown = emptyText
                    )
                )
                return@forEachIndexed
            }

            val structuredAll = src.records.mapIndexed { rIdx, rec ->
                extractStandardFields(src.name, rec, indexInTarget = rIdx + 1)
            }
            val formattedRecords = structuredAll.map { item ->
                formatStructuredRecordMarkdown(item)
            }

            val chunks = ArrayList<Triple<Int, Int, String>>()
            val currentBuf = StringBuilder()
            var chunkStart = 0

            for (rIdx in formattedRecords.indices) {
                val recBlock = formattedRecords[rIdx]
                val candidateLen = currentBuf.length + (if (currentBuf.isNotEmpty()) 2 else 0) + recBlock.length + 64
                if (currentBuf.isNotEmpty() && candidateLen > maxPageChars) {
                    chunks.add(Triple(chunkStart + 1, rIdx, currentBuf.toString().trimEnd()))
                    currentBuf.setLength(0)
                    chunkStart = rIdx
                }
                if (currentBuf.isNotEmpty()) currentBuf.append("\n\n")
                currentBuf.append(recBlock)
            }
            if (currentBuf.isNotEmpty()) {
                chunks.add(Triple(chunkStart + 1, formattedRecords.size, currentBuf.toString().trimEnd()))
            }

            val totalSub = chunks.size
            chunks.forEachIndexed { cIdx, (recStart, recEnd, chunkBody) ->
                val slice = structuredAll.subList(recStart - 1, recEnd)
                val fullMd = buildString {
                    if (totalSub > 1) {
                        append("_Bagian ${cIdx + 1}/$totalSub · Data $recStart–$recEnd dari ${src.records.size}_\n\n")
                    }
                    append(chunkBody)
                }.trimEnd()
                drafts.add(
                    DraftPage(
                        sourceIndex = sIdx,
                        sourceName = src.name,
                        infoLeak = src.infoLeak,
                        subPage = cIdx + 1,
                        totalSubPages = totalSub,
                        recordStart = recStart,
                        recordEnd = recEnd,
                        totalRecordsInSource = src.records.size,
                        structuredRecords = slice,
                        bodyMarkdown = fullMd
                    )
                )
            }
        }

        val totalPages = drafts.size
        return drafts.mapIndexed { idx, d ->
            LeakOsintPage(
                pageIndex = idx,
                totalPages = totalPages,
                sourceIndex = d.sourceIndex,
                totalSources = totalSources,
                sourceName = d.sourceName,
                infoLeak = d.infoLeak,
                subPage = d.subPage,
                totalSubPages = d.totalSubPages,
                recordStart = d.recordStart,
                recordEnd = d.recordEnd,
                totalRecordsInSource = d.totalRecordsInSource,
                markdownText = d.bodyMarkdown,
                structuredRecords = d.structuredRecords
            )
        }
    }

    /** Ringkasan singkat 1 baris untuk header hasil di chat MYTHOS. */
    fun formatSummaryLine(report: LeakOsintReport, page: LeakOsintPage? = null): String {
        val parts = ArrayList<String>()
        if (report.targetGroups.isNotEmpty()) {
            val hitTargets = report.targetGroups.count { it.records.isNotEmpty() }
            parts.add("$hitTargets dari ${report.targetGroups.size} nama cocok")
            if (report.rawTotalResults != null) {
                parts.add("${report.numOfResults} data (dari ${report.rawTotalResults})")
            } else {
                parts.add("${report.numOfResults} data")
            }
            parts.add("Urut sesuai input")
        } else {
            if (page != null && page.totalPages > 1) {
                parts.add("Halaman ${page.pageIndex + 1}/${page.totalPages}")
            }
            if (report.hasResults) {
                parts.add("${report.numOfDatabase} sumber")
                val recLabel = if (report.rawTotalResults != null) {
                    "${report.numOfResults} cocok (dari ${report.rawTotalResults})"
                } else {
                    "${report.numOfResults} data"
                }
                parts.add(recLabel)
            }
        }
        report.freeRequestsLeft?.let { parts.add("Sisa gratis: $it") }
        return parts.joinToString(" · ")
    }

    /** Gabungkan seluruh hasil menjadi satu teks utuh rapi berstiker (untuk Salin Semua / riwayat chat). */
    fun formatAllPagesMarkdown(report: LeakOsintReport): String = buildString {
        if (report.targetGroups.isNotEmpty()) {
            val hitTargets = report.targetGroups.count { it.records.isNotEmpty() }
            append("MYTHOS LeakOSINT · $hitTargets/${report.targetGroups.size} nama cocok · ${report.numOfResults} data\n\n")
            report.targetGroups.forEachIndexed { gIdx, group ->
                if (gIdx > 0) append("\n\n")
                if (group.records.isEmpty()) {
                    append("❌ ${group.orderIndex}. ${group.label} — Tidak ditemukan (0 cocok)")
                } else {
                    append("🔎 ${group.orderIndex}. ${group.label} (${group.records.size} cocok)\n")
                    group.records.forEachIndexed { rIdx, rec ->
                        append("\n")
                        append(formatStructuredRecordCopyText(rec))
                        if (rIdx < group.records.lastIndex) append("\n")
                    }
                }
            }
            return@buildString
        }

        append("**MYTHOS LeakOSINT Search Engine**\n")
        append("Kueri: `").append(report.query.replace("\n", " | ")).append("`")
        if (report.hasResults) {
            append(" · ${report.numOfDatabase} sumber · ${report.numOfResults} data")
        }
        report.filterSummary?.let { append(" · ").append(it) }
        append("\n\n")
        report.pages.forEachIndexed { idx, p ->
            if (idx > 0) append("\n\n")
            if (report.pages.size > 1) {
                append("_— Halaman ${idx + 1}/${report.pages.size} —_\n")
            }
            append(p.markdownText)
        }
    }.trimEnd()

    /**
     * Rekonstruksi [LeakOsintReport] dari teks riwayat yang disimpan oleh [formatAllPagesMarkdown]
     * agar tampilan saat aplikasi dibuka ulang tetap sama persis (badge hijau, stiker, tombol Salin satuan).
     * Mengembalikan null jika [storedText] adalah pesan AI biasa.
     */
    fun parseStoredChatReport(storedText: String): LeakOsintReport? {
        val text = storedText.trim()
        if (!text.startsWith("MYTHOS LeakOSINT · ")) return null

        val lines = text.lines()
        val targetHeaderRe = Regex("""^🔎\s*(\d+)\.\s*(.+?)\s*\((\d+)\s+cocok\)$""")
        val zeroTargetRe = Regex("""^❌\s*(\d+)\.\s*(.+?)\s*[—–-]\s*Tidak ditemukan.*$""")
        val dataHeaderRe = Regex("""^📌\s*Data\s*#(\d+).*$""")

        val groups = ArrayList<LeakOsintTargetGroup>()
        var curOrder = 0
        var curLabel = ""
        var curRecs: ArrayList<LeakOsintStructuredRecord>? = null

        var recIndex = 0
        var fSumber = "-"
        var fNama = "-"
        var fNomor = "-"
        var fEmail = "-"
        var fAlamat = "-"
        var fNik = "-"
        var fGender = "-"
        var inRec = false
        var lastField = ""

        fun flushRec() {
            if (!inRec) return
            curRecs?.add(
                LeakOsintStructuredRecord(
                    indexInTarget = if (recIndex > 0) recIndex else (curRecs?.size ?: 0) + 1,
                    sourceName = fSumber,
                    nama = fNama,
                    nomor = fNomor,
                    email = fEmail,
                    alamat = fAlamat,
                    nikPaspor = fNik,
                    jenisKelamin = fGender
                )
            )
            inRec = false
            lastField = ""
        }

        fun flushGroup() {
            flushRec()
            val list = curRecs ?: return
            if (curLabel.isNotBlank()) {
                groups.add(LeakOsintTargetGroup(orderIndex = curOrder, label = curLabel, records = list))
            }
            curRecs = null
        }

        for (rawLine in lines.drop(1)) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val tm = targetHeaderRe.matchEntire(line)
            if (tm != null) {
                flushGroup()
                curOrder = tm.groupValues[1].toIntOrNull() ?: (groups.size + 1)
                curLabel = tm.groupValues[2].trim()
                curRecs = ArrayList()
                continue
            }
            val zm = zeroTargetRe.matchEntire(line)
            if (zm != null) {
                flushGroup()
                val ord = zm.groupValues[1].toIntOrNull() ?: (groups.size + 1)
                val lbl = zm.groupValues[2].trim()
                groups.add(LeakOsintTargetGroup(orderIndex = ord, label = lbl, records = emptyList()))
                continue
            }
            val dm = dataHeaderRe.matchEntire(line)
            if (dm != null) {
                flushRec()
                if (curRecs == null) {
                    curOrder = groups.size + 1
                    curLabel = "Hasil"
                    curRecs = ArrayList()
                }
                recIndex = dm.groupValues[1].toIntOrNull() ?: 1
                fSumber = "-"; fNama = "-"; fNomor = "-"; fEmail = "-"; fAlamat = "-"; fNik = "-"; fGender = "-"
                inRec = true
                lastField = ""
                continue
            }
            if (!inRec) continue
            when {
                line.startsWith("📂 Sumber:") -> { fSumber = line.removePrefix("📂 Sumber:").trim().ifBlank { "-" }; lastField = "sumber" }
                line.startsWith("👤 Nama:") -> { fNama = line.removePrefix("👤 Nama:").trim().ifBlank { "-" }; lastField = "nama" }
                line.startsWith("📞 Nomor:") -> { fNomor = line.removePrefix("📞 Nomor:").trim().ifBlank { "-" }; lastField = "nomor" }
                line.startsWith("📧 Email:") -> { fEmail = line.removePrefix("📧 Email:").trim().ifBlank { "-" }; lastField = "email" }
                line.startsWith("📍 Alamat:") -> { fAlamat = line.removePrefix("📍 Alamat:").trim().ifBlank { "-" }; lastField = "alamat" }
                line.startsWith("🪪 No NIK/Paspor:") -> { fNik = line.removePrefix("🪪 No NIK/Paspor:").trim().ifBlank { "-" }; lastField = "nik" }
                line.startsWith("⚧ Jenis kelamin:") -> { fGender = line.removePrefix("⚧ Jenis kelamin:").trim().ifBlank { "-" }; lastField = "gender" }
                lastField == "alamat" -> fAlamat = if (fAlamat == "-") line else "$fAlamat $line"
            }
        }
        flushGroup()
        if (groups.isEmpty()) return null

        val allSources = LinkedHashMap<String, ArrayList<LeakOsintRecord>>()
        for (g in groups) {
            for (r in g.records) {
                val bucket = allSources.getOrPut(r.sourceName) { ArrayList() }
                bucket.add(
                    LeakOsintRecord(
                        linkedMapOf(
                            "FullName" to r.nama,
                            "Phone" to r.nomor,
                            "Email" to r.email,
                            "Address" to r.alamat,
                            "NIK" to r.nikPaspor,
                            "Gender" to r.jenisKelamin
                        )
                    )
                )
            }
        }
        val sources = if (allSources.isNotEmpty()) {
            allSources.map { (name, recs) -> LeakOsintSource(name, "", recs, false) }
        } else {
            listOf(LeakOsintSource(NO_RESULTS_KEY, "Tidak ditemukan data yang cocok.", emptyList(), true))
        }
        val totalRecs = groups.sumOf { it.records.size }
        val pages = buildOrderedTargetPages(groups, "Tidak ditemukan data yang cocok.")
        return LeakOsintReport(
            queryId = "restored",
            query = groups.joinToString("\n") { it.label },
            sources = sources,
            pages = pages,
            numOfDatabase = sources.count { !it.isNoResults },
            numOfResults = totalRecs,
            freeRequestsLeft = null,
            price = null,
            targetGroups = groups
        )
    }

    /** Eksekusi pencarian end-to-end. */
    fun search(
        query: String,
        config: LeakOsintConfig,
        endpoint: String = ENDPOINT,
        transport: LeakOsintTransport = HttpUrlConnectionLeakOsintTransport,
        rateLimiter: LeakOsintRateLimiter = defaultRateLimiter,
        cache: LeakOsintReportCache = defaultCache,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        maxPageChars: Int = MAX_PAGE_CHARS,
        onConnectionOpen: ((HttpURLConnection) -> Unit)? = null
    ): LeakOsintOutcome {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return LeakOsintOutcome.Error("EMPTY_QUERY", "Masukkan nama atau kata kunci yang ingin dicari.")
        }
        if (config.token.trim().isEmpty()) {
            return LeakOsintOutcome.Error(
                "MISSING_TOKEN",
                "Token API LeakOSINT belum diatur. Buka Pengaturan > Konektor > LeakOSINT untuk menghubungkannya."
            )
        }

        val batchPlan = parseSmartBatchInput(cleanQuery)
        val payload = try {
            buildPayloadForQueries(batchPlan.apiQueries, config)
        } catch (e: Exception) {
            return LeakOsintOutcome.Error("INVALID_REQUEST", e.message ?: "Permintaan tidak valid.")
        }

        rateLimiter.acquire()

        val httpRes = try {
            transport.postJson(endpoint, payload.toString(), timeoutMs, onConnectionOpen)
        } catch (e: Exception) {
            val safeMsg = maskToken(e.message ?: "Gagal terhubung ke server LeakOSINT.", config.token)
            return LeakOsintOutcome.Error("NETWORK_ERROR", "Gagal menghubungi LeakOSINT API: $safeMsg")
        }

        if (httpRes.statusCode == 501) {
            return LeakOsintOutcome.Error(
                "HTTP_501",
                "Server menolak format permintaan (HTTP 501). Pastikan payload dikirim sebagai JSON."
            )
        }
        if (httpRes.statusCode == 429) {
            return LeakOsintOutcome.Error(
                "HTTP_429",
                "Batas frekuensi tercapai (maks 3 permintaan per detik). Tunggu sebentar lalu coba lagi."
            )
        }
        if (httpRes.statusCode !in 200..299) {
            val safeSnippet = maskToken(httpRes.body.take(200), config.token)
            return LeakOsintOutcome.Error(
                "HTTP_${httpRes.statusCode}",
                "LeakOSINT API mengembalikan HTTP ${httpRes.statusCode}${if (safeSnippet.isNotBlank()) ": $safeSnippet" else "."}"
            )
        }

        val parsedOutcome = parseResponse(
            query = cleanQuery,
            rawJson = httpRes.body,
            maxPageChars = maxPageChars
        )
        if (parsedOutcome is LeakOsintOutcome.Success) {
            val finalReport = applyBatchFilter(parsedOutcome.report, batchPlan, maxPageChars)
            cache.put(finalReport)
            return LeakOsintOutcome.Success(finalReport)
        }
        // Aturan hemat kuota: 1 pesan chat selalu maksimal 1 kali panggilan API (tanpa pemecahan multi-request diam-diam).
        return parsedOutcome
    }

    internal fun maskToken(text: String, token: String): String {
        val clean = token.trim()
        if (clean.isEmpty()) return text
        return text.replace(clean, "[DISEMBUNYIKAN]")
    }

    private fun friendlyApiError(code: String): String = when {
        code.contains("token", ignoreCase = true) -> "Token API LeakOSINT tidak valid atau sudah dicabut ($code)."
        code.contains("limit", ignoreCase = true) || code.contains("balance", ignoreCase = true) ->
            "Kuota atau saldo API LeakOSINT tidak mencukupi ($code)."
        code.contains("rate", ignoreCase = true) || code.contains("many", ignoreCase = true) ->
            "Terlalu banyak permintaan dalam satu waktu (maks 3/detik). Coba lagi sebentar lagi ($code)."
        else -> "LeakOSINT API mengembalikan kesalahan: $code"
    }

    private fun formatJsonValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value.trim()
        is JSONArray -> {
            val items = ArrayList<String>()
            for (i in 0 until value.length()) {
                val s = formatJsonValue(value.opt(i))
                if (s.isNotEmpty()) items.add(s)
            }
            items.joinToString(", ")
        }
        is JSONObject -> value.toString()
        else -> value.toString().trim()
    }
}
