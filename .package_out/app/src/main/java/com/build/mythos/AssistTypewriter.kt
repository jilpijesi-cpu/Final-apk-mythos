package com.build.mythos

/**
 * Mesin ketik jawaban hidup untuk panel asisten layar. Memakai penghalus yang SAMA dengan chat utama
 * ([StreamSmoother.Pacer] dan profil [StreamTempo.current]: kecepatan, jeda napas di tanda baca, lama huruf memudar),
 * ditambah waktu muncul tiap huruf untuk efek memudar per huruf (setara RevealClock di ChatMessages.kt).
 * Murni Kotlin (tanpa android.*) supaya bisa diuji sebagai tes JVM; panel yang memanggil [tick] tiap frame.
 */
internal class AssistTypewriter {
    private val pacer = StreamSmoother.Pacer()
    private var at = LongArray(256)
    private var n = 0
    private var lastRevealAt = 0L

    /** Teks lengkap yang sudah diterima dari model (sasaran yang dikejar). */
    var target: String = ""
        private set

    /** Bagian yang sudah ditampilkan. Selalu awalan dari [target]. */
    var shown: String = ""
        private set

    /** Ganti sasaran. Bila tidak lagi berawalan teks yang sudah tampil (mis. pengulangan dari awal), mulai ketik dari nol. */
    fun setTarget(t: String) {
        if (!t.startsWith(shown)) reset()
        target = t
    }

    fun reset() {
        target = ""
        shown = ""
        n = 0
        lastRevealAt = 0L
        pacer.reset()
    }

    /** Satu frame sepanjang [dtMs]. [skip] = tampilkan sisanya sekarang juga (pengguna mengetuk teks). True bila ada huruf baru. */
    fun tick(now: Long, dtMs: Long, skip: Boolean = false): Boolean {
        val s = shown.length.coerceAtMost(target.length)
        val next = if (skip) target.length else StreamSmoother.nextShown(pacer, target, s, dtMs)
        if (next <= s) return false
        shown = target.substring(0, next)
        mark(next, now)
        return true
    }

    val caughtUp: Boolean get() = shown.length >= target.length

    /** Masih ada huruf yang belum selesai memudar? Dipakai agar huruf terakhir tidak tiba-tiba "pop" penuh. */
    fun fading(now: Long): Boolean = n > 0 && now - lastRevealAt < StreamSmoother.FADE_MS

    /** Umur (ms) huruf ke-[index] sejak muncul; sangat besar bila belum/tidak tercatat. */
    fun ageAt(index: Int, now: Long): Long = if (index in 0 until n) now - at[index] else Long.MAX_VALUE

    /** Semua huruf sudah tampil dan tak ada yang masih memudar (atau [skip]). */
    fun settled(now: Long, skip: Boolean = false): Boolean = caughtUp && (skip || !fading(now))

    private fun mark(count: Int, now: Long) {
        if (count > at.size) at = at.copyOf(maxOf(count, at.size * 2))
        for (i in n until count) at[i] = now
        if (count > n) lastRevealAt = now
        n = count
    }
}
