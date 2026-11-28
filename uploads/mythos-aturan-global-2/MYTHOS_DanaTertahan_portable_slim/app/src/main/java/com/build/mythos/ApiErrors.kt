package com.build.mythos

import java.io.IOException
import kotlin.math.ceil

// Klasifikasi error API model. Murni JVM (tanpa Android) supaya mudah diuji.

/** Error sementara dari server (503 "high demand", 429 per menit, overloaded, dll). Aman diulang. Turunan IOException agar ikut diulang oleh callModelRetry. */
internal class TransientApiException(message: String) : IOException(message)

/** Kuota harian/kredit habis. Menunggu beberapa detik tidak membantu; ganti model atau kunci API. */
internal class QuotaExhaustedException(message: String) : RuntimeException(message)

/** Model ini menolak permintaan (tool-call rusak, tanpa dukungan alat/gambar). Bukan salah pengguna: layak dialihkan ke model lain. */
internal class ModelRejectedException(message: String) : RuntimeException(message)

/** Model menjawab kosong (tanpa teks dan tanpa alat), biasanya karena sibuk. Sementara: dialihkan ke model lain tanpa memblokir model ini untuk seterusnya. */
internal class EmptyResponseException(message: String) : java.io.IOException(message)

/** Model sudah dihapus, diganti nama, atau tidak dikenal penyedia (404 "model not found"). Layak dialihkan dan dibuang dari cache uji. */
internal class ModelGoneException(message: String) : RuntimeException(message)

internal enum class ApiErrorKind { TRANSIENT, QUOTA, MODEL, GONE, FATAL }

private val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504, 529)

private val QUOTA_MARKERS = listOf(
    "perday", "per_day", "per day", "per-day", "limit: 0", "insufficient_quota", "daily limit",
    "insufficient credits", "more credits"
)

/** Penanda error milik model (bukan milik kunci/akun): Groq tool_use_failed, OpenRouter "tanpa endpoint dengan dukungan alat", gambar ke model teks. */
private val MODEL_MARKERS = listOf(
    "tool_use_failed", "failed_generation", "failed to call a function", "no endpoints found",
    "does not support tool", "doesn't support tool", "tool use", "tools are not supported",
    "does not support image", "image input", "must be a string", "multimodal"
)

/** Penanda model sudah tidak ada. Hanya berlaku bila pesan menyebut "model" (404 karena alamat salah tidak ikut). */
private val GONE_MARKERS = listOf(
    "model_not_found", "model not found", "decommissioned", "no longer supported", "has been deprecated",
    "is deprecated", "is not found for api version", "not supported for generatecontent", "does not exist"
)

internal fun isModelGone(code: Int, lowerText: String): Boolean =
    lowerText.contains("model") && (code == 404 || GONE_MARKERS.any { lowerText.contains(it) })

private val TRANSIENT_MARKERS = listOf(
    "high demand", "overloaded", "unavailable", "try again later", "temporarily",
    "resource_exhausted", "exceeded your current quota", "rate limit", "rate_limit",
    "internal error", "deadline exceeded", "timed out"
)

/**
 * Tentukan jenis error dari kode HTTP (atau -1 bila tidak ada), status teks (mis. "UNAVAILABLE", "overloaded_error") dan pesan.
 * Pesan 429 Gemini selalu memuat "check your plan and billing details", jadi kata "billing" TIDAK dipakai sebagai tanda kuota habis;
 * yang dipakai adalah penanda per-hari / limit 0 pada detailnya.
 */
internal fun classifyApiError(code: Int, status: String, message: String): ApiErrorKind {
    val t = (status + " " + message).lowercase()
    if (code == 402 || QUOTA_MARKERS.any { t.contains(it) }) return ApiErrorKind.QUOTA
    if (code in RETRYABLE_CODES) return ApiErrorKind.TRANSIENT
    if (TRANSIENT_MARKERS.any { t.contains(it) }) return ApiErrorKind.TRANSIENT
    if (MODEL_MARKERS.any { t.contains(it) }) return ApiErrorKind.MODEL
    if (isModelGone(code, t)) return ApiErrorKind.GONE
    return ApiErrorKind.FATAL
}

internal fun quotaMessage(code: Int, body: String): String =
    "Kuota API model ini habis" + (if (code > 0) " ($code)" else "") + ". Batas harian/kredit sudah terpakai, " +
        "menunggu tidak membantu. Ganti model atau kunci API, atau cek https://ai.dev/rate-limit. Detail: " + body.take(200)

/** Ubah error di dalam stream (setelah HTTP 200) menjadi exception yang tepat. */
internal fun apiStreamException(code: Int, status: String, message: String): Exception {
    if (isContextTooLarge(code, message)) return contextTooLarge(message)
    val text = "API: " + message.take(300)
    return when (classifyApiError(code, status, message)) {
        ApiErrorKind.TRANSIENT -> TransientApiException(text)
        ApiErrorKind.QUOTA -> QuotaExhaustedException(quotaMessage(code, message))
        ApiErrorKind.MODEL -> ModelRejectedException(text)
        ApiErrorKind.GONE -> ModelGoneException(text)
        ApiErrorKind.FATAL -> RuntimeException(text)
    }
}

/** Ambil "retryDelay": "37s" dari badan error Gemini; dibulatkan ke atas. Null bila tidak ada. */
internal fun parseRetryDelaySec(body: String): Int? {
    val m = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(body) ?: return null
    return ceil(m.groupValues[1].toDouble()).toInt().takeIf { it > 0 }
}

/** Model cadangan berikutnya dari daftar yang lolos uji, selain yang sudah dicoba. */
internal fun nextFallbackModel(good: List<String>, tried: Set<String>): String? =
    bestModel(good.filter { it !in tried })

/** Hanya error sementara atau kuota habis yang layak dicoba dengan model lain. */
internal fun isFallbackWorthy(e: Throwable): Boolean =
    e is TransientApiException || e is QuotaExhaustedException || e is ContextTooLargeException || e is ModelRejectedException || e is ModelGoneException || e is EmptyResponseException

/**
 * Server penyedia internet tidak menjawab (koneksi gagal, terputus, atau lewat batas waktu setelah percobaan ulang): layak pindah ke AI lain,
 * karena yang macet bisa saja penyedia ini saja. Server lokal dikecualikan: pindah ke AI lain tidak memperbaiki server di HP sendiri.
 * [isFallbackWorthy] sengaja tidak memuat ini (galat IOException biasa), jadi dipakai bersama di callModelRetry.
 */
internal fun isUnreachable(e: Throwable, local: Boolean): Boolean =
    !local && e is IOException && e !is LocalServerDownException

/** Satu langkah cadangan: ganti model di penyedia yang sama (provider null), atau pindah ke penyedia lain dengan kunci terdaftar. */
internal data class FallbackStep(val provider: String?, val model: String?)

/** Susun urutan cadangan: model lain dulu (penyedia sama), baru penyedia lain. Masing-masing dibatasi. */
internal fun buildFallbackPlan(models: List<String>, providers: List<String>, maxModels: Int = 2, maxProviders: Int = 2): List<FallbackStep> =
    models.take(maxModels).map { FallbackStep(null, it) } + providers.take(maxProviders).map { FallbackStep(it, null) }
