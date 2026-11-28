package com.build.mythos

import kotlin.math.roundToInt

// Progres membaca prompt di model lokal: perkiraan jumlah token dan waktu tunggu. Murni Kotlin (LocalProgressTest).
// Kecepatan awal diambil dari ~/ollama.log HP uji (qwen2.5:1.5b, 8 inti): 23-25 token/detik. Setelah itu dipelajari dari
// tiap permintaan (waktu sampai jawaban pertama), jadi menyesuaikan diri dengan HP dan model yang dipakai.

internal const val DEFAULT_LOCAL_TPS = 25.0
internal const val MIN_LOCAL_TPS = 3.0
internal const val MAX_LOCAL_TPS = 300.0

/** Bobot hasil ukur terbaru. Kecil, karena cache Ollama membuat sebagian permintaan tampak lebih cepat dari kecepatan sebenarnya. */
private const val LEARN_WEIGHT = 0.3

/** Perkiraan kasar: 3 karakter per token (teks Indonesia + JSON alat). */
internal fun estimateTokens(chars: Int): Int = (chars / 3.0).roundToInt()

/** "±40 dtk" / "±1,5 mnt": dibulatkan supaya tidak terkesan presisi. */
internal fun formatWait(seconds: Double): String {
    val rounded = ((seconds.coerceAtLeast(0.0) / 5).roundToInt() * 5).coerceAtLeast(5)
    return if (rounded < 90) "±$rounded dtk"
    else "±" + String.format(java.util.Locale.forLanguageTag("id-ID"), "%.1f", rounded / 60.0) + " mnt"
}

/** Keterangan di bawah "Model lokal membaca prompt". */
internal fun promptWaitDetail(tokens: Int, tps: Double): String {
    val speed = if (tps.isNaN() || tps < MIN_LOCAL_TPS) DEFAULT_LOCAL_TPS else tps
    return "±$tokens token, perkiraan terlama ${formatWait(tokens / speed)} sebelum jawaban pertama (lebih cepat bila cache terpakai)"
}

/** Kecepatan baru (token/detik) dari satu pengukuran. Pengukuran terlalu singkat atau kecil diabaikan (kecepatan lama dikembalikan). */
internal fun learnTps(old: Double, tokens: Int, seconds: Double): Double {
    val base = if (old.isNaN() || old < MIN_LOCAL_TPS) DEFAULT_LOCAL_TPS else old
    if (seconds < 3.0 || tokens < 200) return base
    val measured = (tokens / seconds).coerceIn(MIN_LOCAL_TPS, MAX_LOCAL_TPS)
    return (base * (1 - LEARN_WEIGHT) + measured * LEARN_WEIGHT).coerceIn(MIN_LOCAL_TPS, MAX_LOCAL_TPS)
}

/** Batas tunggu per bacaan untuk model lokal: dulu tetap 15 menit, sehingga server yang macet baru ketahuan setelah 15 menit. */
internal const val LOCAL_TIMEOUT_MIN_MS = 180_000
internal const val LOCAL_TIMEOUT_MAX_MS = 900_000

/**
 * 3x perkiraan waktu membaca prompt (dari kecepatan yang dipelajari) + 2 menit untuk memuat model, dibatasi 3 sampai 15 menit.
 * Kecepatan turun (HP diperlambat Android) -> batas naik sendiri, jadi permintaan yang memang lambat tidak diputus lalu diulang
 * dari awal (yang membaca prompt dua kali).
 */
internal fun localReadTimeoutMs(tokens: Int, tps: Double): Int {
    val speed = if (tps.isNaN() || tps < MIN_LOCAL_TPS) DEFAULT_LOCAL_TPS else tps
    val ms = ((tokens / speed) * 3 + 120) * 1000
    return ms.toInt().coerceIn(LOCAL_TIMEOUT_MIN_MS, LOCAL_TIMEOUT_MAX_MS)
}
