package com.build.mythos

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

// Daftar model dan uji (probe) model: cek URL, ambil daftar, simpan hasil.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** Ambil daftar model aktif ("g") dari JSON probe bila kunci utama cocok dan belum kedaluwarsa. */
internal fun parseActiveProbedModels(raw: String, primaryKey: String, nowMs: Long = System.currentTimeMillis()): List<String> {
    if (raw.isEmpty()) return emptyList()
    return try {
        val o = JSONObject(raw)
        if (o.optInt("h") != primaryKey.hashCode()) return emptyList()
        if (!probeFresh(o.optLong("t", 0L), nowMs)) return emptyList()
        val g = o.optJSONArray("g") ?: return emptyList()
        (0 until g.length()).map { g.getString(it) }.filter { it.isNotBlank() }.distinct()
    } catch (e: Exception) {
        emptyList()
    }
}

/** Susun daftar model aktif untuk failover: model pilihan pengguna selalu pertama, diikuti model aktif hasil probe terbaik. */
internal fun activeModelsForFailover(primaryModel: String, probedGood: List<String>, provider: String, maxModels: Int = 6): List<String> {
    val primary = primaryModel.trim()
    val others = if (lockedModelFor(provider) != null) emptyList()
    else rankModels(probedGood, provider).filter { it.isNotBlank() && it != primary }
    return (listOfNotNull(primary.takeIf { it.isNotBlank() }) + others).distinct().take(maxModels)
}

internal fun MainActivity.loadProbed(): Pair<List<String>, List<String>>? {
    val raw = pvOf(effectiveProv(), "probe")
    if (raw.isEmpty()) return null
    return try {
        val o = JSONObject(raw)
        if (o.optInt("h") != cfgKey().hashCode()) return null
        // Kedaluwarsa 24 jam (ModelCache.kt): daftar lama dianggap tidak ada, jadi diuji ulang.
        if (!probeFresh(o.optLong("t", 0L), System.currentTimeMillis())) return null
        val g = o.optJSONArray("g") ?: JSONArray()
        val b = o.optJSONArray("b") ?: JSONArray()
        val gl = (0 until g.length()).map { g.getString(it) }
        val bl = (0 until b.length()).map { b.getString(it) }
        if (gl.isEmpty() && bl.isEmpty()) null else Pair(gl, bl)
    } catch (e: Exception) {
        null
    }
}

/** Waktu (ms) hasil uji penyedia aktif disimpan; 0 bila belum ada. */
internal fun MainActivity.probeSavedAt(): Long =
    try { JSONObject(pvOf(effectiveProv(), "probe")).optLong("t", 0L) } catch (e: Exception) { 0L }

/** Hasil uji tersimpan APA ADANYA (tanpa memeriksa kedaluwarsa), termasuk daftar model yang disembunyikan. Null bila belum ada / kunci berganti. */
internal class ProbeRaw(val good: List<String>, val busy: List<String>, val bad: Map<String, UnavailableEntry>, val savedAt: Long)

private fun jsonStrings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

private fun jsonBad(o: JSONObject?): Map<String, UnavailableEntry> {
    val out = LinkedHashMap<String, UnavailableEntry>()
    if (o == null) return out
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val e = o.optJSONObject(k) ?: continue
        out[k] = UnavailableEntry(e.optInt("c"), e.optLong("t"))
    }
    return out
}

internal fun MainActivity.loadProbedRaw(): ProbeRaw? {
    val raw = pvOf(effectiveProv(), "probe")
    if (raw.isEmpty()) return null
    return try {
        val o = JSONObject(raw)
        if (o.optInt("h") != cfgKey().hashCode()) return null
        ProbeRaw(jsonStrings(o.optJSONArray("g")), jsonStrings(o.optJSONArray("b")), jsonBad(o.optJSONObject("x")), o.optLong("t", 0L))
    } catch (e: Exception) {
        null
    }
}

/** [bad] null = pertahankan daftar model tersembunyi yang sudah tersimpan. */
internal fun MainActivity.saveProbed(
    good: List<String>, busy: List<String>, savedAt: Long = System.currentTimeMillis(),
    bad: Map<String, UnavailableEntry>? = null
) {
    val badNow = bad ?: (loadProbedRaw()?.bad ?: emptyMap())
    val x = JSONObject()
    for ((m, e) in badNow) x.put(m, JSONObject().put("c", e.code).put("t", e.at))
    val o = JSONObject().put("h", cfgKey().hashCode()).put("t", savedAt).put("g", JSONArray(good)).put("b", JSONArray(busy)).put("x", x)
    prefs.edit().putString("p_${effectiveProv()}_probe", o.toString()).apply()
}

/**
 * Penyedia melaporkan [model] sudah tidak ada (404 "model not found"): buang dari cache uji tanpa menyegarkan waktunya,
 * dan bila itu model tersimpan pilihan, ganti ke model terbaik yang tersisa. Tanpa pengganti, pilihan dibiarkan.
 */
internal fun MainActivity.forgetGoneModel(provider: String, model: String) {
    if (model.isBlank() || lockedModelFor(provider) != null) return
    onProvider(provider, null) {
        val at = probeSavedAt()
        val (good, busyList) = loadProbed() ?: Pair(emptyList<String>(), emptyList<String>())
        val (g2, b2) = dropModel(good, busyList, model)
        if (g2.size != good.size || b2.size != busyList.size) saveProbed(g2, b2, at)
        if (sameModel(pvOf(provider, "model"), model)) {
            val next = bestModel(g2, provider) ?: bestModel(b2, provider)
            if (next != null) {
                prefs.edit().putString("p_${provider}_model", next).apply()
                runOnUiThread { updateModelLabel() }
            }
        }
    }
}

/** Baca stream teks dengan batas ukuran maksimum untuk mencegah OutOfMemoryError. */
internal fun readLimitedText(stream: java.io.InputStream?, maxBytes: Int): String {
    if (stream == null) return ""
    return stream.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < maxBytes) {
            val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        String(out.toByteArray(), Charsets.UTF_8)
    }
}

private fun drainLimited(stream: java.io.InputStream?, maxBytes: Int) {
    if (stream == null) return
    stream.use { input ->
        val buf = ByteArray(4096)
        var total = 0
        while (total < maxBytes) {
            val n = input.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n < 0) break
            total += n
        }
    }
}

private val QUERY_CREDENTIAL_RE = Regex("""(?i)(^|&)(api[_-]?key|key|token|access[_-]?token|secret|password|auth)=""")

/** Validasi basis URL murni (dapat diuji di JVM): tolak userinfo, query kredensial, host kosong, dan http:// non-lokal (F-14). */
internal fun validateBaseUrl(url: String): String {
    val parsed = try {
        URL(url.trim())
    } catch (e: Exception) {
        throw RuntimeException("Format basis URL tidak valid.")
    }
    if (!parsed.userInfo.isNullOrEmpty()) {
        throw RuntimeException("Basis URL tidak boleh memuat kredensial (user:pass@host).")
    }
    val query = parsed.query.orEmpty()
    if (query.isNotEmpty() && QUERY_CREDENTIAL_RE.containsMatchIn(query)) {
        throw RuntimeException("Basis URL tidak boleh membawa kunci/token pada parameter query.")
    }
    val host = parsed.host.orEmpty().trim()
    if (host.isEmpty()) {
        throw RuntimeException("Host pada basis URL tidak boleh kosong.")
    }
    val scheme = parsed.protocol.lowercase()
    if (scheme == "https") return host.lowercase()
    if (scheme == "http") {
        val cleanHost = host.lowercase().trim('[', ']')
        val local = cleanHost == "localhost" || cleanHost == "127.0.0.1" || cleanHost == "::1"
        if (!local) {
            throw RuntimeException("Basis URL http:// ke host publik diblokir agar kunci API tidak terkirim tanpa enkripsi. Pakai https://.")
        }
        return cleanHost
    }
    throw RuntimeException("Skema URL tidak didukung ($scheme). Gunakan https://.")
}

/** Cleartext (http://) hanya boleh ke localhost/127.0.0.1/::1; skema selain http/https ditolak. */
@Suppress("UnusedReceiverParameter")
internal fun MainActivity.checkUrl(url: String) {
    validateBaseUrl(url)
}

internal fun MainActivity.postStatus(
    url: String, headers: Map<String, String>, body: JSONObject,
    connectMs: Int = 20000, readMs: Int = 60000
): Int {
    checkUrl(url)
    val conn = URL(url.trim()).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.connectTimeout = connectMs
    conn.readTimeout = readMs
    conn.setRequestProperty("Content-Type", "application/json")
    for ((k, v) in headers) conn.setRequestProperty(k, v)
    try {
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        try {
            val st = if (code >= 400) conn.errorStream else conn.inputStream
            drainLimited(st, 64 * 1024)
        } catch (e: Exception) {
        }
        return code
    } finally {
        conn.disconnect()
    }
}

private fun MainActivity.probeWithSnapshot(m: String, fmt: String, base: String, key: String): Int {
    return try {
        val headers = HashMap<String, String>()
        val url: String
        val body: JSONObject
        val userMsg = JSONObject().put("role", "user").put("content", "hi")
        if (fmt == "gemini") {
            url = "$base/models/$m:generateContent"
            val part = JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "hi")))
            body = JSONObject().put("contents", JSONArray().put(part)).put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(PROBE_TOOL))))
            headers["x-goog-api-key"] = key
        } else if (fmt == "anthropic") {
            url = "$base/messages"
            val tl = JSONArray()
            for (d in listOf(PROBE_TOOL)) {
                tl.put(
                    JSONObject().put("name", d.getString("name")).put("description", d.getString("description"))
                        .put("input_schema", d.getJSONObject("parameters"))
                )
            }
            body = JSONObject().put("model", m).put("max_tokens", 16)
                .put("messages", JSONArray().put(userMsg)).put("tools", tl)
            headers["x-api-key"] = key
            headers["anthropic-version"] = "2023-06-01"
        } else {
            url = "$base/chat/completions"
            val tl = JSONArray()
            // Definisi ringkas: uji hanya membuktikan model menerima alat, dan paket gratis (TPM kecil) tidak terbebani.
            for (d in listOf(PROBE_TOOL)) tl.put(JSONObject().put("type", "function").put("function", d))
            body = JSONObject().put("model", m).put("messages", JSONArray().put(userMsg)).put("tools", tl)
            if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
        }
        postStatus(url, headers, body, PROBE_CONNECT_MS, PROBE_READ_MS)
    } catch (e: Exception) {
        -1
    }
}

/** Uji satu model dengan permintaan kecil + definisi tool. Mengembalikan kode HTTP (-1 jika gagal). */
internal fun MainActivity.probe(m: String): Int =
    probeWithSnapshot(m, preset(effectiveProv()).fmt, cfgBase().trimEnd('/'), cfgKey())

/** Alat uji sekecil mungkin: uji model hanya membuktikan penyedia menerima skema alat. Bukan alat nyata dan tidak pernah dijalankan. */
private val PROBE_TOOL: JSONObject = JSONObject()
    .put("name", "ping")
    .put("description", "Uji koneksi.")
    .put("parameters", JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("x", JSONObject().put("type", "string").put("description", "Isi bebas")))
        .put("required", org.json.JSONArray().put("x")))

/** Uji banyak model sekaligus (5 paralel). Mengembalikan (bisa dipakai, sedang sibuk). Jalankan di thread latar. */
internal fun MainActivity.probeCodes(cand: List<String>, threads: Int = 5): List<Pair<String, Int>> {
    if (cand.isEmpty()) return emptyList()
    val fmt = preset(effectiveProv()).fmt
    val base = cfgBase().trimEnd('/')
    val key = cfgKey()
    val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 8))
    return try {
        pool.invokeAll(cand.map { m -> Callable { Pair(m, probeWithSnapshot(m, fmt, base, key)) } }).map { it.get() }
    } finally {
        pool.shutdownNow()
    }
}

internal fun MainActivity.probeBlocking(cand: List<String>): Pair<List<String>, List<String>> {
    val res = probeCodes(cand)
    val good = res.filter { it.second in 200..299 }.map { it.first }
    val busy = res.filter { it.second == 429 || it.second in 500..599 }.map { it.first }
    return Pair(good, busy)
}

/** Batas waktu memuat DAFTAR model: cukup singkat agar penyedia yang macet (mis. Ollama lokal sibuk) tidak menahan pengguna 30 dtk. */
internal const val LIST_CONNECT_MS = 8000
internal const val LIST_READ_MS = 12000

internal fun MainActivity.getJson(
    url: String, headers: Map<String, String>,
    connectMs: Int = 20000, readMs: Int = 30000
): JSONObject {
    checkUrl(url)
    val conn = URL(url.trim()).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = connectMs
    conn.readTimeout = readMs
    for ((k, v) in headers) conn.setRequestProperty(k, v)
    try {
        val code = conn.responseCode
        if (code >= 400) {
            val err = readLimitedText(conn.errorStream, 256 * 1024)
            throw RuntimeException("API $code: ${err.take(200)}")
        }
        val txt = readLimitedText(conn.inputStream, 4 * 1024 * 1024)
        return JSONObject(txt)
    } finally {
        conn.disconnect()
    }
}

/** Ambil daftar model langsung dari penyedia yang aktif. */
internal fun MainActivity.fetchModels(): List<String> {
    val key = cfgKey()
    val base = cfgBase().trimEnd('/')
    val out = ArrayList<String>()
    val fmt = preset(effectiveProv()).fmt
    if (fmt == "gemini") {
        val r = getJson("$base/models?pageSize=1000", mapOf("x-goog-api-key" to key), LIST_CONNECT_MS, LIST_READ_MS)
        val arr = r.optJSONArray("models") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")
            var ok = methods == null
            if (methods != null) {
                for (j in 0 until methods.length()) {
                    if (methods.getString(j) == "generateContent") ok = true
                }
            }
            if (ok) out.add(m.optString("name").removePrefix("models/"))
        }
    } else {
        val h = HashMap<String, String>()
        val url: String
        if (fmt == "anthropic") {
            h["x-api-key"] = key
            h["anthropic-version"] = "2023-06-01"
            url = "$base/models?limit=1000"
        } else {
            if (key.isNotEmpty()) h["Authorization"] = "Bearer $key"
            url = "$base/models"
        }
        val r = getJson(url, h, LIST_CONNECT_MS, LIST_READ_MS)
        val arr = r.optJSONArray("data") ?: JSONArray()
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).optString("id"))
    }
    return out.filter { it.isNotBlank() }.sorted()
}

private class ModelListCache(val provider: String, val base: String, val keyHash: Int, val at: Long, val list: List<String>)

@Volatile private var modelListCache: ModelListCache? = null

/** Daftar model dari penyedia aktif; dipakai ulang bila baru diambil (<[maxAgeMs]) agar tidak dua kali berturut-turut ke jaringan. */
internal fun MainActivity.fetchModelsCached(maxAgeMs: Long = 60_000L): List<String> {
    val now = System.currentTimeMillis()
    val base = cfgBase()
    val kh = cfgKey().hashCode()
    val p = effectiveProv()
    val c = modelListCache
    if (c != null && c.provider == p && c.base == base && c.keyHash == kh && (now - c.at) in 0L..maxAgeMs) return c.list
    val l = fetchModels()
    if (l.isNotEmpty()) modelListCache = ModelListCache(p, base, kh, now, l) // daftar kosong jangan di-cache: "Coba lagi" harus benar-benar menghubungi penyedia
    return l
}
