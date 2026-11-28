package com.build.mythos

import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

// Alat pembuatan dan pengeditan gambar (buat_gambar, edit_gambar) lewat Gemini API.
// Bagian murni ada di object GambarTools (tanpa referensi android.*) agar bisa diuji lewat tes JVM (GambarToolsTest).
// Bagian jaringan, izin, penyimpanan, dan kartu langkah ada pada ekstensi MainActivity di bawah.

internal sealed class ImageApiResult {
    data class Ok(val mimeType: String, val base64: String, val textNote: String = "") : ImageApiResult()
    data class Fail(val note: String, val message: String) : ImageApiResult()
}

internal object GambarTools {
    /** Model gambar resmi Gemini API (mudah diganti bila ada versi baru atau penghentian model). */
    const val IMAGE_MODEL = "gemini-3.1-flash-image"

    /** Endpoint dasar resmi Gemini API (tidak pernah memakai cfgBase() penyedia lain). */
    const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"

    /** Endpoint OpenAI-compatible deAPI untuk pembuatan & edit gambar (FLUX, gratis $5 tanpa kartu kredit). */
    const val DEAPI_OAI_BASE = "https://oai.deapi.ai/v1"
    const val DEAPI_GENERATE_MODEL = "Flux1schnell"
    const val DEAPI_EDIT_MODEL = "Flux_2_Klein_4B_BF16"

    const val MIN_PROMPT_LEN = 2
    const val MAX_PROMPT_LEN = 1000
    const val MAX_OUTPUT_BYTES = 20 * 1024 * 1024
    const val MAX_INPUT_BYTES = 30L * 1024L * 1024L
    const val PREVIEW_MAX_SIDE = 1600

    val SUPPORTED_RATIOS = setOf(
        "1:1", "1:4", "1:8", "2:3", "3:2", "3:4", "4:1", "4:3", "4:5", "5:4", "8:1", "9:16", "16:9", "21:9"
    )

    private val SUPPORTED_IMAGE_EXTS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff", "avif"
    )

    /** null bila panjang prompt/instruksi valid (2-1000 karakter); selain itu alasan penolakan. */
    fun checkPrompt(raw: String, label: String = "Prompt"): String? {
        val t = raw.trim()
        if (t.length < MIN_PROMPT_LEN) return "$label terlalu pendek (minimal $MIN_PROMPT_LEN karakter)."
        if (t.length > MAX_PROMPT_LEN) return "$label terlalu panjang (maks $MAX_PROMPT_LEN karakter)."
        return null
    }

    /** null bila rasio kosong (opsional) atau didukung; selain itu alasan penolakan. */
    fun checkRatio(raw: String): String? {
        val r = raw.trim()
        if (r.isEmpty()) return null
        return if (r in SUPPORTED_RATIOS) null
        else "Rasio '$r' tidak didukung. Gunakan salah satu: ${SUPPORTED_RATIOS.sorted().joinToString(", ")}."
    }

    /** True bila nama/path berakhiran ekstensi gambar yang didukung (selaras dengan ImagePayload.isImageName). */
    fun isSupportedImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUPPORTED_IMAGE_EXTS

    /** Validasi murni path dan metadata file input untuk edit_gambar. */
    fun checkInputPath(path: String, exists: Boolean, readable: Boolean, sizeBytes: Long): String? {
        val p = path.trim()
        if (p.isEmpty()) return "Path gambar kosong."
        if (!isSupportedImageName(p)) return "File bukan format gambar yang didukung: $p."
        if (!exists || !readable) return "File gambar tidak ditemukan atau tidak bisa dibaca: $p."
        if (sizeBytes <= 0L) return "File gambar kosong: $p."
        if (sizeBytes > MAX_INPUT_BYTES) return "Ukuran gambar melebihi batas 30 MB."
        return null
    }

    /** Hitung inSampleSize pangkat 2 agar sisi terpanjang <= [maxSide] (pola ImagePayload.kt). */
    fun computeInSampleSize(outWidth: Int, outHeight: Int, maxSide: Int = PREVIEW_MAX_SIDE): Int {
        if (outWidth <= 0 || outHeight <= 0 || maxSide <= 0) return 1
        var sample = 1
        val longest = maxOf(outWidth, outHeight)
        while (longest / sample > maxSide) sample *= 2
        return sample
    }

    /** URL lengkap generateContent untuk model gambar aktif. */
    fun generateUrl(model: String = IMAGE_MODEL): String =
        "$GEMINI_API_BASE/models/$model:generateContent"

    /** Bangun body JSON generateContent untuk buat_gambar. */
    fun buildGenerateRequest(prompt: String, ratio: String? = null): JSONObject {
        val genCfg = JSONObject()
            .put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
        val r = ratio?.trim().orEmpty()
        if (r.isNotEmpty()) {
            genCfg.put("imageConfig", JSONObject().put("aspectRatio", r))
        }
        val content = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt.trim())))
        return JSONObject()
            .put("contents", JSONArray().put(content))
            .put("generationConfig", genCfg)
    }

    /** Bangun body JSON generateContent untuk edit_gambar (teks instruksi + inlineData gambar). */
    fun buildEditRequest(instruksi: String, mimeType: String, base64Data: String): JSONObject {
        val parts = JSONArray()
            .put(JSONObject().put("text", instruksi.trim()))
            .put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", mimeType.ifBlank { "image/jpeg" })
                        .put("data", base64Data)
                )
            )
        val content = JSONObject()
            .put("role", "user")
            .put("parts", parts)
        val genCfg = JSONObject()
            .put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
        return JSONObject()
            .put("contents", JSONArray().put(content))
            .put("generationConfig", genCfg)
    }

    /** Ekstensi berkas dari MIME type hasil Gemini API. */
    fun extForMime(mimeType: String): String = when (mimeType.lowercase().substringBefore(';').trim()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    /** Nama file unik untuk salinan preview dan salinan pengguna. */
    fun outputFileName(prefix: String, nowMs: Long, mimeType: String): String =
        "${prefix}_${nowMs}.${extForMime(mimeType)}"

    /** Format pesan error HTTP; beri saran ganti konstanta bila 404/403 (bukan kuota habis). */
    fun formatApiError(rawMessage: String, model: String = IMAGE_MODEL): String {
        val m = rawMessage.trim()
        val lower = m.lowercase()
        val is404 = lower.contains("404") || lower.contains("not_found") || lower.contains("not found")
        val is403 = lower.contains("403") || lower.contains("permission_denied") || lower.contains("forbidden")
        return when {
            is404 -> "Model gambar '$model' tidak ditemukan (HTTP 404). Periksa atau ganti konstanta GambarTools.IMAGE_MODEL."
            is403 -> "Akses ke model gambar '$model' ditolak (HTTP 403). Periksa izin API key Gemini atau ganti konstanta GambarTools.IMAGE_MODEL."
            m.isEmpty() -> "Permintaan ke Gemini API gagal."
            else -> "Permintaan ke Gemini API gagal: ${m.take(260)}"
        }
    }

    /** Parse body JSON respons generateContent (mendukung inlineData / inline_data, blockReason, finishReason). */
    fun parseImageResponse(body: String, model: String = IMAGE_MODEL): ImageApiResult {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return ImageApiResult.Fail("respons rusak", "Respons Gemini API bukan JSON yang valid.")
        }
        root.optJSONObject("error")?.let { err ->
            val code = err.optInt("code", -1)
            val status = err.optString("status", "")
            val msg = err.optString("message", "")
            val combined = listOf(if (code > 0) "HTTP $code" else "", status, msg).filter { it.isNotBlank() }.joinToString(" ")
            return ImageApiResult.Fail("error API", formatApiError(combined, model))
        }
        val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason", "").orEmpty().trim()
        if (blockReason.isNotEmpty()) {
            return ImageApiResult.Fail(
                "diblokir keamanan",
                "Permintaan gambar diblokir filter keamanan ($blockReason). Ubah prompt lalu coba lagi."
            )
        }
        val cand = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: return ImageApiResult.Fail("tanpa kandidat", "Respons Gemini API tidak berisi kandidat gambar.")
        val finishReason = cand.optString("finishReason", "").trim()
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val textNotes = StringBuilder()
        var foundMime = ""
        var foundBase64 = ""
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
                if (inline != null) {
                    val data = inline.optString("data", "").trim()
                    if (data.isNotEmpty()) {
                        foundBase64 = data
                        foundMime = inline.optString("mimeType", inline.optString("mime_type", "image/png")).ifBlank { "image/png" }
                    }
                } else if (p.has("text")) {
                    val t = p.optString("text", "").trim()
                    if (t.isNotEmpty()) {
                        if (textNotes.isNotEmpty()) textNotes.append('\n')
                        textNotes.append(t)
                    }
                }
            }
        }
        if (foundBase64.isNotEmpty()) {
            return ImageApiResult.Ok(foundMime, foundBase64, textNotes.toString())
        }
        if (finishReason.isNotEmpty() && finishReason != "STOP") {
            return ImageApiResult.Fail(
                "diblokir ($finishReason)",
                "Model berhenti tanpa gambar (finishReason=$finishReason). Ubah prompt lalu coba lagi."
            )
        }
        val note = textNotes.toString().trim()
        return if (note.isNotEmpty()) {
            ImageApiResult.Fail("tanpa gambar", "Model tidak mengembalikan gambar: ${note.take(240)}")
        } else {
            ImageApiResult.Fail("tanpa gambar", "Respons Gemini API tidak menyertakan data gambar.")
        }
    }

    /** URL endpoint OpenAI-compatible deAPI untuk buat_gambar dan edit_gambar. */
    fun deapiGenerateUrl(): String = "$DEAPI_OAI_BASE/images/generations"
    fun deapiEditUrl(): String = "$DEAPI_OAI_BASE/images/edits"

    /** Petakan rasio aspek ke ukuran yang didukung Flux1schnell di deAPI. */
    fun deapiSizeForRatio(ratio: String?): String = when (ratio?.trim().orEmpty()) {
        "9:16", "2:3", "3:4", "4:5", "1:4", "1:8" -> "1024x1536"
        "16:9", "3:2", "4:3", "5:4", "4:1", "8:1", "21:9" -> "1536x1024"
        else -> "1024x1024"
    }

    /** Bangun body JSON OpenAI-compatible deAPI untuk buat_gambar (FLUX.1 Schnell). */
    fun buildDeapiGenerateRequest(
        prompt: String,
        ratio: String? = null,
        model: String = DEAPI_GENERATE_MODEL
    ): JSONObject = JSONObject()
        .put("model", model)
        .put("prompt", prompt.trim())
        .put("size", deapiSizeForRatio(ratio))
        .put("response_format", "b64_json")

    /** Bangun body JSON OpenAI-compatible deAPI untuk edit_gambar (FLUX.2 Klein). */
    fun buildDeapiEditRequest(
        instruksi: String,
        mimeType: String,
        base64Data: String,
        model: String = DEAPI_EDIT_MODEL
    ): JSONObject {
        val mime = mimeType.ifBlank { "image/jpeg" }
        val imgObj = JSONObject().put("image_url", "data:$mime;base64,$base64Data")
        return JSONObject()
            .put("model", model)
            .put("prompt", instruksi.trim())
            .put("images", JSONArray().put(imgObj))
            .put("response_format", "b64_json")
    }

    /** Parse respons JSON OpenAI-compatible deAPI (/v1/images/generations & /v1/images/edits). */
    fun parseDeapiImageResponse(body: String, model: String = DEAPI_GENERATE_MODEL): ImageApiResult {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return ImageApiResult.Fail("respons rusak", "Respons deAPI bukan JSON yang valid.")
        }
        root.optJSONObject("error")?.let { err ->
            val code = err.optString("code", "").trim()
            val type = err.optString("type", "").trim()
            val msg = err.optString("message", "").trim()
            val lower = "$code $type $msg".lowercase()
            val friendly = when {
                lower.contains("invalid_api_key") || lower.contains("missing_api_key") || lower.contains("authentication_error") ->
                    "Kunci API deAPI tidak valid. Periksa kunci sk-dp-... di Pengaturan > Lanjutan."
                lower.contains("insufficient_quota") || lower.contains("insufficient balance") ->
                    "Saldo kredit deAPI sudah habis. Periksa dashboard deAPI."
                lower.contains("model_not_found") ->
                    "Model deAPI '$model' tidak tersedia untuk akun ini."
                msg.isNotEmpty() -> "Permintaan ke deAPI gagal: ${msg.take(240)}"
                else -> "Permintaan ke deAPI gagal."
            }
            return ImageApiResult.Fail("error deAPI", friendly)
        }
        val b64 = root.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json", "").orEmpty().trim()
        if (b64.isNotEmpty()) {
            return ImageApiResult.Ok("image/png", b64)
        }
        return ImageApiResult.Fail("tanpa gambar", "Respons deAPI tidak menyertakan data gambar.")
    }
}

private data class DecodedGeminiImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val fileName: String,
    val previewFile: File
)

private sealed class FetchImageResult {
    data class Ok(val image: DecodedGeminiImage) : FetchImageResult()
    data class Fail(val note: String, val message: String) : FetchImageResult()
}



/** Baca kunci API Gemini khusus (tidak memakai cfgKey() agar kunci penyedia lain tidak terkirim ke Google). */
internal fun MainActivity.geminiImageKey(): String =
    pvOf("Gemini", "key").ifEmpty { readStoredKey("key") }

/** Baca kunci API deAPI terenkripsi dari Pengaturan > Lanjutan (diprioritaskan bila diisi). */
internal fun MainActivity.deapiImageKey(): String =
    when (val r = Secret.decrypt(prefs.getString("deapi_key", "") ?: "")) {
        is SecretDecryptResult.Success -> r.value.trim()
        else -> ""
    }



/**
 * Cari salinan gambar yang bisa dibaca proses aplikasi ini:
 * 1) previewPath dari riwayat gambar chat (HistImage),
 * 2) salinan di cacheDir/share, filesDir/chat_images, atau projectRoot (bila diberikan),
 * 3) path absolut/asli langsung,
 * 4) opsional fallback salin dari Termux ke cache sementara (hanya bila [allowTermuxCopy] true).
 */
private fun MainActivity.resolveReadableImageFile(
    normed: String,
    abs: String,
    projectRoot: File? = null,
    allowTermuxCopy: Boolean = false
): File? {
    val leaf = File(abs).name
    val histCandidates = synchronized(hist) {
        hist.asReversed().flatMap { it.images }.filter { img ->
            val p = img.path
            p == normed || p == abs || absPath(normPath(p)) == abs || (leaf.isNotEmpty() && File(p).name == leaf)
        }
    }
    for (img in histCandidates) {
        val pv = img.previewPath?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (pv != null && pv.isFile && pv.canRead()) return pv
    }
    if (projectRoot != null) {
        val relCandidate = File(projectRoot, normed.removePrefix("./"))
        if (relCandidate.isFile && relCandidate.canRead()) return relCandidate
    }
    if (leaf.isNotEmpty()) {
        val cacheShare = File(File(cacheDir, "share"), leaf)
        if (cacheShare.isFile && cacheShare.canRead()) return cacheShare
        val chatImg = File(File(filesDir, "chat_images"), leaf)
        if (chatImg.isFile && chatImg.canRead()) return chatImg
    }
    val directAbs = File(abs)
    if (directAbs.isFile && directAbs.canRead()) return directAbs
    val directNorm = File(normed)
    if (directNorm.isFile && directNorm.canRead()) return directNorm
    if (allowTermuxCopy && abs.startsWith("/") && sensitiveReason(abs) == null) {
        return try {
            val fetched = fetchTermuxFile(abs)
            val tmpDir = File(cacheDir, "gambar_edit").apply { mkdirs() }
            val copy = File(tmpDir, "src_${System.currentTimeMillis()}_${fetched.name}")
            fetched.copyTo(copy, overwrite = true)
            copy
        } catch (c: Cancelled) {
            throw c
        } catch (_: Exception) {
            null
        }
    }
    return null
}

private const val MAX_IMAGE_RESPONSE_BYTES = 30 * 1024 * 1024

private fun readImageResponseLimited(input: java.io.InputStream, maxBytes: Int = MAX_IMAGE_RESPONSE_BYTES): String {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16384)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) {
            throw IOException("Respons gambar melebihi batas ${maxBytes / (1024 * 1024)} MB.")
        }
        out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}

/** Panggil Gemini API, parse & validasi gambar hasil, lalu simpan salinan preview (a) di cacheDir/share. */
private fun MainActivity.fetchAndPreviewGeminiImage(reqBody: JSONObject, key: String): FetchImageResult {
    val url = GambarTools.generateUrl()
    val respText = try {
        val conn = openStream(url, mapOf("x-goog-api-key" to key), reqBody)
        try {
            conn.inputStream.use { readImageResponseLimited(it) }
        } finally {
            conn.disconnect()
            if (activeConn === conn) activeConn = null
        }
    } catch (c: Cancelled) {
        throw c
    } catch (e: QuotaExhaustedException) {
        return FetchImageResult.Fail("kuota habis", e.message ?: "Kuota API Gemini habis.")
    } catch (e: IOException) {
        return FetchImageResult.Fail("jaringan gagal", "Koneksi ke Gemini API terputus: ${Cx.friendly(e)}")
    } catch (e: Exception) {
        return FetchImageResult.Fail("API gagal", GambarTools.formatApiError(e.message.orEmpty()))
    }

    val parsed = when (val r = GambarTools.parseImageResponse(respText)) {
        is ImageApiResult.Fail -> return FetchImageResult.Fail(r.note, r.message)
        is ImageApiResult.Ok -> r
    }
    return decodeAndCachePreviewImage(parsed, "Gemini API")
}

/** Panggil deAPI (OpenAI-compatible endpoint /v1/images), parse & validasi gambar hasil, lalu simpan preview di cacheDir/share. */
private fun MainActivity.fetchAndPreviewDeapiImage(
    url: String,
    reqBody: JSONObject,
    key: String,
    model: String
): FetchImageResult {
    val respText = try {
        val conn = openStream(url, mapOf("Authorization" to "Bearer $key"), reqBody)
        try {
            conn.inputStream.use { readImageResponseLimited(it) }
        } finally {
            conn.disconnect()
            if (activeConn === conn) activeConn = null
        }
    } catch (c: Cancelled) {
        throw c
    } catch (e: QuotaExhaustedException) {
        return FetchImageResult.Fail("kuota habis", e.message ?: "Kuota atau saldo deAPI sudah habis.")
    } catch (e: IOException) {
        return FetchImageResult.Fail("jaringan gagal", "Koneksi ke deAPI terputus: ${Cx.friendly(e)}")
    } catch (e: Exception) {
        return FetchImageResult.Fail("API gagal", "Permintaan ke deAPI gagal: ${(e.message ?: "").take(240)}")
    }

    val parsed = when (val r = GambarTools.parseDeapiImageResponse(respText, model)) {
        is ImageApiResult.Fail -> return FetchImageResult.Fail(r.note, r.message)
        is ImageApiResult.Ok -> r
    }
    return decodeAndCachePreviewImage(parsed, "deAPI")
}

private fun MainActivity.decodeAndCachePreviewImage(parsed: ImageApiResult.Ok, providerLabel: String): FetchImageResult {
    val bytes = try {
        Base64.decode(parsed.base64, Base64.DEFAULT)
    } catch (e: Exception) {
        return FetchImageResult.Fail("gambar rusak", "Data base64 gambar dari $providerLabel tidak valid.")
    }
    if (bytes.isEmpty()) {
        return FetchImageResult.Fail("gambar kosong", "Data gambar hasil dekode kosong.")
    }
    if (bytes.size > GambarTools.MAX_OUTPUT_BYTES) {
        return FetchImageResult.Fail("gambar terlalu besar", "Ukuran gambar hasil (${bytes.size / (1024 * 1024)} MB) melebihi batas 20 MB.")
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return FetchImageResult.Fail("gambar tidak valid", "Data yang dikembalikan $providerLabel bukan gambar yang valid.")
    }

    val fileName = GambarTools.outputFileName("gambar", System.currentTimeMillis(), parsed.mimeType)
    val previewFile = try {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        File(dir, fileName).also { it.writeBytes(bytes) }
    } catch (e: Exception) {
        return FetchImageResult.Fail("gagal simpan preview", "Gagal menyimpan salinan preview di cache aplikasi: ${e.message ?: e.javaClass.simpleName}")
    }

    return FetchImageResult.Ok(DecodedGeminiImage(bytes, parsed.mimeType, bounds.outWidth, bounds.outHeight, fileName, previewFile))
}


// ---------------------------------------------------------------- untuk agen API dalam-aplikasi (CcBridge.kt / cc/KitTools.kt)
// Agen API aktif bekerja penuh di penyimpanan aplikasi (<projectRoot>/artifacts/) tanpa Termux.

internal fun MainActivity.ccBuatGambar(deskripsi: String, prompt: String, rasio: String, projectRoot: File): Pair<Boolean, String> {
    if (readOnlyMode()) return false to "Ditolak: mode hanya baca aktif. Pembuatan gambar menulis file, jadi tidak diizinkan."
    GambarTools.checkPrompt(prompt, "Prompt")?.let { return false to "exit=1 $it" }
    GambarTools.checkRatio(rasio)?.let { return false to "exit=1 $it" }
    val deapiKey = deapiImageKey()
    val geminiKey = if (deapiKey.isEmpty()) geminiImageKey() else ""
    if (deapiKey.isEmpty() && geminiKey.isEmpty()) {
        return false to "exit=1 Masukkan kunci deAPI (FLUX) di Pengaturan > Lanjutan, atau API key Gemini."
    }

    val caption = deskripsi.trim().ifBlank { prompt.trim() }.take(120)
    val fetched = if (deapiKey.isNotEmpty()) {
        fetchAndPreviewDeapiImage(
            GambarTools.deapiGenerateUrl(),
            GambarTools.buildDeapiGenerateRequest(prompt, rasio),
            deapiKey,
            GambarTools.DEAPI_GENERATE_MODEL
        )
    } else {
        fetchAndPreviewGeminiImage(GambarTools.buildGenerateRequest(prompt, rasio), geminiKey)
    }
    return ccSaveFetchedImage(fetched, caption, "dibuat", projectRoot)
}

internal fun MainActivity.ccEditGambar(deskripsi: String, rawPath: String, instruksi: String, projectRoot: File): Pair<Boolean, String> {
    if (readOnlyMode()) return false to "Ditolak: mode hanya baca aktif. Pengeditan gambar menulis file, jadi tidak diizinkan."
    GambarTools.checkPrompt(instruksi, "Instruksi")?.let { return false to "exit=1 $it" }
    val p = rawPath.trim()
    if (p.isEmpty()) return false to "exit=1 Path gambar kosong."
    if (!isImageName(p)) return false to "exit=1 File bukan format gambar yang didukung: $p."

    // Batasi akses hanya ke dalam sandbox proyek (cegah path traversal ../ atau pembacaan chat_images milik chat lain).
    val sandboxed = try {
        com.build.mythos.cc.ProjectSandbox(projectRoot).resolve(p)
    } catch (e: Exception) {
        null
    }
    val resolved = when {
        sandboxed != null && sandboxed.isFile && sandboxed.canRead() -> sandboxed
        else -> {
            // Izinkan pula gambar yang benar-benar ada di riwayat chat AKTIF saat ini.
            val leaf = File(p).name
            synchronized(hist) {
                hist.asReversed().flatMap { it.images }.firstNotNullOfOrNull { img ->
                    val pv = img.previewPath?.takeIf { it.isNotBlank() }?.let { File(it) }
                    if ((img.path == p || (leaf.isNotEmpty() && File(img.path).name == leaf)) && pv != null && pv.isFile && pv.canRead()) pv else null
                }
            }
        }
    }
    val abs = resolved?.absolutePath ?: File(projectRoot, p).absolutePath
    val pathErr = GambarTools.checkInputPath(
        path = p,
        exists = resolved != null && resolved.isFile,
        readable = resolved != null && resolved.canRead(),
        sizeBytes = resolved?.length() ?: 0L
    )
    if (pathErr != null) return false to "exit=1 $pathErr"

    val deapiKey = deapiImageKey()
    val geminiKey = if (deapiKey.isEmpty()) geminiImageKey() else ""
    if (deapiKey.isEmpty() && geminiKey.isEmpty()) {
        return false to "exit=1 Masukkan kunci deAPI (FLUX) di Pengaturan > Lanjutan, atau API key Gemini."
    }

    val payload = try {
        encodedImage(HistImage(abs, resolved?.absolutePath))
    } catch (e: Exception) {
        return false to "exit=1 ${e.message ?: "Gambar sumber gagal dibaca."}"
    }

    val caption = deskripsi.trim().ifBlank { instruksi.trim() }.take(120)
    val fetched = if (deapiKey.isNotEmpty()) {
        fetchAndPreviewDeapiImage(
            GambarTools.deapiEditUrl(),
            GambarTools.buildDeapiEditRequest(instruksi, payload.mimeType, payload.base64),
            deapiKey,
            GambarTools.DEAPI_EDIT_MODEL
        )
    } else {
        fetchAndPreviewGeminiImage(GambarTools.buildEditRequest(instruksi, payload.mimeType, payload.base64), geminiKey)
    }
    return ccSaveFetchedImage(fetched, caption, "diedit", projectRoot)
}

private fun MainActivity.ccSaveFetchedImage(
    fetched: FetchImageResult,
    caption: String,
    verb: String,
    projectRoot: File
): Pair<Boolean, String> {
    val img = when (fetched) {
        is FetchImageResult.Fail -> return false to "exit=1 ${fetched.message}"
        is FetchImageResult.Ok -> fetched.image
    }

    val relPath = "artifacts/${img.fileName}"
    val outFile = File(projectRoot, relPath)
    val saveErr = try {
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(img.bytes)
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    addImageCard(img.previewFile.absolutePath, caption)
    val sizeKb = maxOf(1, img.bytes.size / 1024)
    return if (saveErr == null) {
        true to "Gambar berhasil $verb (${img.width}x${img.height}, $sizeKb KB) dan ditampilkan di chat.\nPath: $relPath"
    } else {
        false to "exit=1 Gambar berhasil $verb (${img.width}x${img.height}, $sizeKb KB) dan ditampilkan di chat dari salinan preview, " +
            "tetapi gagal menyimpan ke $relPath ($saveErr)."
    }
}
