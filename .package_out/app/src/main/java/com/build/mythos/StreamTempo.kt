package com.build.mythos

/**
 * Konfigurasi tempo tampilan jawaban AI yang hidup (stream), terpusat di satu tempat. Murni Kotlin supaya bisa diuji sebagai tes JVM.
 *
 * Tujuan profil "Santai": visual terlihat lebih lama sehingga bisa dinikmati. Kecepatan ketik dibatasi, ada jeda napas di tanda baca,
 * huruf memudar lebih lembut, dan baris aktivitas alat bertahan lebih lama. Tampilan TIDAK menahan kerja AI: yang diatur hanya
 * kecepatan layar mengejar teks yang sudah diterima.
 */
internal object StreamTempo {
    /** Satu set nilai tempo. Semua waktu dalam milidetik, kecepatan dalam karakter per detik. */
    data class Params(
        val key: String,
        val label: String,
        val hint: String,
        /** Kecepatan dasar saat teks tertunda sedikit. */
        val minCps: Double,
        /** Batas atas kecepatan. */
        val maxCps: Double,
        /** Tambahan karakter/detik per satu karakter yang tertunda. */
        val gain: Double,
        /** Batas total waktu menghabiskan teks yang tertunda; 0 = tanpa batas. Mencegah jawaban panjang tertinggal terlalu jauh. */
        val maxDrainMs: Long,
        /** Lama satu huruf memudar masuk. */
        val fadeMs: Long,
        /** Berapa huruf terakhir yang masih dianimasikan memudar. */
        val fadeReach: Int,
        /** Jeda setelah akhir kalimat (. ! ? …) atau akhir baris. */
        val pauseSentenceMs: Long,
        /** Jeda setelah koma, titik koma, atau titik dua. */
        val pauseCommaMs: Long,
        /** Jeda setelah baris kosong (paragraf baru). */
        val pauseParagraphMs: Long,
        /** Pengali kecepatan untuk teks yang di belakangnya masih ada kejadian alat menunggu (percepatan, bukan loncat instan). */
        val fastForward: Double,
        /** Lama minimum satu teks baris status bertahan sebelum diganti. */
        val gateHoldMs: Long,
        /** Jeda setelah teks selesai diketik sebelum baris aktivitas berikutnya tampil. */
        val settleMs: Long,
        /** Lama memudar masuk blok kode/tabel saat jawaban difinalkan; 0 = langsung muncul. */
        val blockFadeMs: Long,
        /** Jeda antar kemunculan blok. */
        val blockStaggerMs: Long
    )

    val SANTAI = Params(
        key = "santai", label = "Santai", hint = "Pelan dan berjeda, visual terlihat lama",
        minCps = 36.0, maxCps = 120.0, gain = 0.25, maxDrainMs = 25_000L,
        fadeMs = 420L, fadeReach = 160,
        pauseSentenceMs = 140L, pauseCommaMs = 90L, pauseParagraphMs = 300L,
        fastForward = 3.0, gateHoldMs = 1500L, settleMs = 1000L,
        blockFadeMs = 260L, blockStaggerMs = 200L
    )

    val NORMAL = Params(
        key = "normal", label = "Normal", hint = "Kecepatan lama aplikasi",
        minCps = 45.0, maxCps = 260.0, gain = 0.6, maxDrainMs = 0L,
        fadeMs = 260L, fadeReach = 90,
        pauseSentenceMs = 0L, pauseCommaMs = 0L, pauseParagraphMs = 0L,
        fastForward = 6.0, gateHoldMs = 900L, settleMs = 600L,
        blockFadeMs = 0L, blockStaggerMs = 0L
    )

    val CEPAT = Params(
        key = "cepat", label = "Cepat", hint = "Teks cepat selesai",
        minCps = 70.0, maxCps = 400.0, gain = 1.0, maxDrainMs = 8_000L,
        fadeMs = 180L, fadeReach = 60,
        pauseSentenceMs = 0L, pauseCommaMs = 0L, pauseParagraphMs = 0L,
        fastForward = 8.0, gateHoldMs = 600L, settleMs = 300L,
        blockFadeMs = 0L, blockStaggerMs = 0L
    )

    val ALL = listOf(SANTAI, NORMAL, CEPAT)

    /** Kunci penyimpanan di SharedPreferences "cfg". */
    const val PREF_KEY = "stream_tempo"

    /** Profil bawaan untuk pengguna baru: Santai. */
    val DEFAULT = SANTAI

    @Volatile var current: Params = DEFAULT

    fun byKey(key: String?): Params = ALL.firstOrNull { it.key == key } ?: DEFAULT

    /** Berapa karakter per detik minimum agar [peakBacklog] karakter habis dalam batas [Params.maxDrainMs]; 0 bila tanpa batas. */
    fun drainFloorCps(p: Params, peakBacklog: Int): Double =
        if (p.maxDrainMs <= 0L || peakBacklog <= 0) 0.0 else peakBacklog * 1000.0 / p.maxDrainMs

    /**
     * Jeda (ms) yang pantas diberikan sesudah huruf [ch] muncul, atau 0.
     * [prev] huruf sebelumnya dan [next] huruf berikutnya (null bila belum diterima) dipakai agar angka desimal "3.5"
     * dan singkatan tanpa spasi tidak ikut berjeda.
     */
    fun pauseAfter(p: Params, prev: Char?, ch: Char, next: Char?): Long {
        val nextBreaks = next == null || next.isWhitespace()
        return when (ch) {
            '\n' -> if (prev == '\n') p.pauseParagraphMs else p.pauseSentenceMs
            '.', '!', '?', '…' -> if (nextBreaks) p.pauseSentenceMs else 0L
            ',', ';', ':' -> if (nextBreaks) p.pauseCommaMs else 0L
            else -> 0L
        }
    }

    /** Apakah posisi [pos] (jumlah huruf yang sudah tampil) berada di dalam blok kode ``` yang belum ditutup? Jeda tidak dipakai di kode. */
    fun insideFence(text: String, pos: Int): Boolean {
        var count = 0
        var i = text.indexOf("```")
        val end = pos.coerceIn(0, text.length)
        while (i >= 0 && i + 3 <= end) { count++; i = text.indexOf("```", i + 3) }
        return count % 2 == 1
    }
}
