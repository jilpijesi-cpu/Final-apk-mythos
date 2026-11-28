package com.build.mythos

/**
 * Penghalus teks stream. Murni Kotlin supaya bisa diuji sebagai tes JVM.
 *
 * Teks dari model datang bergelombang (satu potongan, lalu diam). Kelas [Pacer] menampilkannya per frame dengan
 * kecepatan yang terlihat seperti mengetik. Semua angka diambil dari profil aktif [StreamTempo.current]:
 * kecepatan dasar naik seiring teks yang tertunda sampai batas atas, tetapi juga dijamin habis dalam batas waktu total
 * profil (jawaban panjang tidak tertinggal terlalu jauh), dan ada jeda napas di tanda baca agar tiap kalimat terlihat.
 */
internal object StreamSmoother {
    /** Jeda antar frame (ms), sekitar 60 fps. */
    const val FRAME_MS = 16L

    /** Konstanta waktu (ms) penghalusan kecepatan: laju naik/turun bertahap, bukan melonjak tiap potongan teks baru tiba. */
    const val RATE_TAU_MS = 220.0

    /** Lama (ms) satu huruf memudar dari nyaris transparan sampai penuh setelah muncul. Mengikuti profil aktif. */
    val FADE_MS: Long get() = StreamTempo.current.fadeMs

    /** Alpha (0..255) huruf yang sudah muncul selama [ageMs]: memudar masuk dengan percepatan lembut (ease-out). */
    fun fadeAlpha(ageMs: Long): Int {
        val fade = FADE_MS
        if (ageMs >= fade) return 255
        val t = (ageMs.coerceAtLeast(0L).toDouble() / fade)
        val eased = 1.0 - (1.0 - t) * (1.0 - t)
        return (24 + (255 - 24) * eased).toInt().coerceIn(0, 255)
    }

    /**
     * Pengatur laju dengan sisa pecahan, sehingga kecepatan di bawah 1 karakter per frame tetap halus.
     * Kecepatan sasaran tidak dipakai langsung: ia dikejar secara eksponensial ([RATE_TAU_MS]) supaya aliran terasa mengalir.
     */
    class Pacer {
        private var carry = 0.0
        private var cps = StreamTempo.current.minCps
        private var holdMs = 0.0
        /** Backlog terbesar sejak antrean terakhir kosong; dasar batas total waktu supaya lajunya tidak melambat saat sisa mengecil. */
        private var peak = 0

        /** Pengali kecepatan (>= 1). Dipakai untuk mempercepat teks yang masih harus selesai sebelum kejadian alat berikutnya. */
        @Volatile var boost = 1.0

        /** True bila batas total waktu sedang menekan (teks sangat panjang): jeda napas dilewati agar batas terjaga. */
        var underPressure = false
            private set

        /** Sedang menahan jeda napas? */
        val holding: Boolean get() = holdMs > 0.0

        /** Jumlah karakter yang ditampilkan pada satu frame sepanjang [dtMs] bila tertinggal [backlog] karakter. */
        fun step(backlog: Int, dtMs: Long = FRAME_MS): Int {
            val p = StreamTempo.current
            if (backlog <= 0) { carry = 0.0; cps = p.minCps; holdMs = 0.0; peak = 0; underPressure = false; return 0 }
            if (backlog > peak) peak = backlog
            val dt = dtMs.coerceIn(1L, 100L)
            val speedUp = boost.coerceAtLeast(1.0)
            if (holdMs > 0.0) {
                if (speedUp > 1.0) holdMs = 0.0 else { holdMs -= dt; return 0 }
            }
            val base = (p.minCps + backlog * p.gain).coerceAtMost(p.maxCps)
            val floor = StreamTempo.drainFloorCps(p, peak)
            underPressure = floor > base
            val goal = maxOf(base, floor) * speedUp
            cps += (goal - cps) * (1.0 - Math.exp(-dt / RATE_TAU_MS))
            carry += cps * dt / 1000.0
            val n = carry.toInt()
            carry -= n
            return n.coerceAtMost(backlog)
        }

        /** Tahan tampilan selama [ms] (jeda napas). Diabaikan bila sedang dipercepat. */
        fun hold(ms: Long) {
            if (ms > 0L && boost <= 1.0) { holdMs = ms.toDouble(); carry = 0.0 }
        }

        fun reset() { carry = 0.0; cps = StreamTempo.current.minCps; holdMs = 0.0; peak = 0; underPressure = false }
    }

    /** Titik potong [n] yang tidak memecah pasangan surrogate (emoji, aksara langka) di tengah. */
    fun safeCut(text: String, n: Int): Int = StreamPacing.safeCut(text, n)

    /**
     * Panjang prefiks [target] yang layak ditampilkan setelah [shown] karakter.
     * Bila langkah ini melewati tanda baca, tampilan berhenti tepat sesudahnya dan [pacer] menahan jeda napas.
     */
    fun nextShown(pacer: Pacer, target: String, shown: Int, dtMs: Long = FRAME_MS): Int {
        val s = safeCut(target, shown.coerceIn(0, target.length))
        val n = pacer.step(target.length - s, dtMs)
        if (n == 0) return s
        val end = safeCut(target, s + n)
        val p = StreamTempo.current
        val pausesOn = p.pauseSentenceMs > 0L || p.pauseCommaMs > 0L || p.pauseParagraphMs > 0L
        if (!pausesOn || pacer.underPressure || pacer.boost > 1.0) return end
        for (j in s until end) {
            val ms = StreamTempo.pauseAfter(p, target.getOrNull(j - 1), target[j], target.getOrNull(j + 1))
            if (ms > 0L && !StreamTempo.insideFence(target, j + 1)) {
                pacer.hold(ms)
                return safeCut(target, j + 1)
            }
        }
        return end
    }
}
