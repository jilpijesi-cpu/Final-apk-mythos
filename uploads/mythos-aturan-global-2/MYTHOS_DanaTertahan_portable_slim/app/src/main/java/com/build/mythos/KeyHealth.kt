package com.build.mythos

import android.content.Context
import com.build.mythos.cc.isDeadKeyError
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// Pemeriksaan kesehatan & pencatatan status API key yang mati/kedaluwarsa tanpa menyimpan kunci dalam teks biasa.

internal enum class KeyHealthState { ALIVE, RATE_LIMITED, DEAD, ERROR }

internal data class KeyHealthResult(
    val index: Int,
    val key: String,
    val masked: String,
    val state: KeyHealthState,
    val detail: String,
)

/** Samarkan kunci untuk tampilan status (hanya 4 karakter awal & 4 akhir). */
internal fun maskApiKey(key: String): String {
    val k = key.trim()
    if (k.length <= 8) return "••••"
    return "${k.take(4)}…${k.takeLast(4)}"
}

/** Klasifikasikan hasil probe ringan (GET daftar model / auth) untuk satu API key. */
internal fun classifyKeyProbeResponse(code: Int, body: String): Pair<KeyHealthState, String> = when {
    code in 200..299 -> KeyHealthState.ALIVE to "Aktif"
    code == 429 -> KeyHealthState.RATE_LIMITED to "Aktif · sedang kena batas"
    isDeadKeyError(code, body) -> KeyHealthState.DEAD to "Kunci mati/ditolak (HTTP $code)"
    code <= 0 -> KeyHealthState.ERROR to "Gagal terhubung"
    else -> KeyHealthState.ERROR to "Gagal periksa (HTTP $code)"
}

/** Penyimpanan status kunci mati memakai hash SHA-256 (tidak pernah menyimpan teks asli API key). */
internal object DeadKeyStore {
    private const val PREF_KEY = "dead_key_hashes_v1"

    private fun hash(apiKey: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(apiKey.trim().toByteArray(Charsets.UTF_8))
        return dig.take(12).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    private fun loadMap(ctx: Context): JSONObject {
        val raw = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString(PREF_KEY, "").orEmpty()
        return try { if (raw.isBlank()) JSONObject() else JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    @Synchronized
    private fun saveMap(ctx: Context, obj: JSONObject) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString(PREF_KEY, obj.toString()).apply()
    }

    @Synchronized
    fun markDead(ctx: Context, apiKey: String, reason: String) {
        if (apiKey.isBlank()) return
        val obj = loadMap(ctx)
        obj.put(hash(apiKey), reason.take(80))
        saveMap(ctx, obj)
    }

    @Synchronized
    fun markAlive(ctx: Context, apiKey: String) {
        if (apiKey.isBlank()) return
        val obj = loadMap(ctx)
        val h = hash(apiKey)
        if (obj.has(h)) {
            obj.remove(h)
            saveMap(ctx, obj)
        }
    }

    @Synchronized
    fun reasonOf(ctx: Context, apiKey: String): String? {
        if (apiKey.isBlank()) return null
        val obj = loadMap(ctx)
        val h = hash(apiKey)
        return if (obj.has(h) && !obj.isNull(h)) obj.optString(h).takeIf { it.isNotBlank() } else null
    }

    @Synchronized
    fun countDead(ctx: Context, keys: List<String>): Int = keys.count { reasonOf(ctx, it) != null }
}

private const val KEY_CHECK_CONNECT_MS = 8_000
private const val KEY_CHECK_READ_MS = 10_000

/** Periksa kesehatan satu API key lewat endpoint GET ringan (tanpa memakai token generasi). */
private fun MainActivity.probeSingleApiKey(index: Int, provider: String, baseUrl: String, key: String): KeyHealthResult {
    val masked = maskApiKey(key)
    val base = baseUrl.trim().trimEnd('/')
    val fmt = preset(provider).fmt
    val headers = HashMap<String, String>()
    val url = when {
        fmt == "gemini" -> {
            headers["x-goog-api-key"] = key
            "$base/models?pageSize=1"
        }
        fmt == "anthropic" -> {
            headers["x-api-key"] = key
            headers["anthropic-version"] = "2023-06-01"
            "$base/models?limit=1"
        }
        base.contains("openrouter.ai", ignoreCase = true) -> {
            headers["Authorization"] = "Bearer $key"
            "$base/auth/key"
        }
        else -> {
            headers["Authorization"] = "Bearer $key"
            "$base/models"
        }
    }
    return try {
        checkUrl(url)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = KEY_CHECK_CONNECT_MS
        conn.readTimeout = KEY_CHECK_READ_MS
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        try {
            val code = conn.responseCode
            val body = readLimitedText(if (code >= 400) conn.errorStream else conn.inputStream, 32 * 1024)
            val (state, detail) = classifyKeyProbeResponse(code, body)
            when (state) {
                KeyHealthState.DEAD -> DeadKeyStore.markDead(this, key, detail)
                KeyHealthState.ALIVE, KeyHealthState.RATE_LIMITED -> DeadKeyStore.markAlive(this, key)
                KeyHealthState.ERROR -> {}
            }
            KeyHealthResult(index, key, masked, state, detail)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        KeyHealthResult(index, key, masked, KeyHealthState.ERROR, "Gagal terhubung")
    }
}

/** Periksa daftar API key secara paralel (maks 4 thread) untuk mendeteksi kunci yang mati. Jalankan di thread latar. */
internal fun MainActivity.checkApiKeysHealth(provider: String, baseUrl: String, keys: List<String>): List<KeyHealthResult> {
    if (keys.isEmpty()) return emptyList()
    val pool = Executors.newFixedThreadPool(keys.size.coerceIn(1, 4))
    return try {
        pool.invokeAll(keys.mapIndexed { idx, k -> Callable { probeSingleApiKey(idx + 1, provider, baseUrl, k) } })
            .map { it.get() }
    } finally {
        pool.shutdownNow()
    }
}

private val keyRemoveLock = Any()

/** Hapus sekumpulan kunci mati dari daftar kunci mentah, kembalikan sisa kunci yang bersih. */
internal fun removeKeysFromRaw(raw: String, toRemove: Set<String>): List<String> {
    if (toRemove.isEmpty()) return parseApiKeys(raw)
    val normRemove = toRemove.map { normalizeKey(it) }.toSet()
    return parseApiKeys(raw).filter { it !in normRemove }
}

/** Hapus langsung kunci API yang terdeteksi mati dari penyimpanan terenkripsi SharedPreferences; kembalikan jumlah kunci tersisa. */
internal fun removeDeadApiKeysFromPrefs(ctx: Context, provider: String, deadKeys: Set<String>): Int = synchronized(keyRemoveLock) {
    if (deadKeys.isEmpty() || provider.isBlank()) return@synchronized 0
    val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    val storageName = "p_${provider}_key"
    val rawMain = (Secret.decrypt(prefs.getString(storageName, "") ?: "") as? SecretDecryptResult.Success)?.value.orEmpty()
    val rawLegacy = if (provider == "Gemini") {
        (Secret.decrypt(prefs.getString("key", "") ?: "") as? SecretDecryptResult.Success)?.value.orEmpty()
    } else ""
    val combined = listOf(rawMain, rawLegacy).filter { it.isNotBlank() }.joinToString("\n")
    val remaining = removeKeysFromRaw(combined, deadKeys)
    val ed = prefs.edit()
    if (remaining.isEmpty()) {
        ed.remove(storageName)
    } else {
        val enc = Secret.encrypt(remaining.joinToString("\n"))
        if (enc.isSuccess) ed.putString(storageName, enc.getOrThrow())
    }
    if (provider == "Gemini" && rawLegacy.isNotBlank()) {
        val remLegacy = removeKeysFromRaw(rawLegacy, deadKeys)
        if (remLegacy.isEmpty()) ed.remove("key")
        else Secret.encrypt(remLegacy.joinToString("\n")).getOrNull()?.let { ed.putString("key", it) }
    }
    ed.apply()
    deadKeys.forEach { DeadKeyStore.markAlive(ctx, it) }
    remaining.size
}
