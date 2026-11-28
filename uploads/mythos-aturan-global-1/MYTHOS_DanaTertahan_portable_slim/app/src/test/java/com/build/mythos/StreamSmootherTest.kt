package com.build.mythos

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamSmootherTest {
    private var savedTempo = StreamTempo.current

    @Before fun resetTempoBefore() {
        savedTempo = StreamTempo.current
        StreamTempo.current = StreamTempo.DEFAULT
    }

    @After fun restoreTempoAfter() {
        StreamTempo.current = savedTempo
    }
    private fun drainMs(len: Int): Long {
        val pacer = StreamSmoother.Pacer()
        val target = "x".repeat(len)
        var shown = 0
        var ms = 0L
        while (shown < len && ms < 60000) {
            shown = StreamSmoother.nextShown(pacer, target, shown)
            ms += StreamSmoother.FRAME_MS
        }
        assertEquals(len, shown)
        return ms
    }

    @Test fun noBacklogMeansNoStep() = assertEquals(0, StreamSmoother.Pacer().step(0))

    @Test fun neverExceedsBacklog() {
        val p = StreamSmoother.Pacer()
        for (b in 1..50) assertTrue(p.step(b) <= b)
    }

    @Test fun shortSentenceIsLeisurelyButNotSlow() {
        val ms = drainMs(80)   // profil Santai (bawaan): sekitar 2 detik, cukup lama untuk dinikmati
        assertTrue("terlalu cepat: $ms ms", ms >= 1200L)
        assertTrue("terlalu lambat: $ms ms", ms <= 3500L)
    }

    @Test fun longTextStillFinishesWithinTotalCap() {
        assertTrue(drainMs(1500) <= 25_000L)
    }

    @Test fun veryLongTextIsBoundedByDrainCap() {
        // 5.000 karakter pada 120 cps butuh 41 detik; batas total 25 detik menaikkan lajunya.
        val ms = drainMs(5000)
        assertTrue("melewati batas total: $ms ms", ms <= 27_000L)
    }

    @Test fun largerBacklogIsFasterPerSecond() {
        val small = StreamSmoother.Pacer(); val big = StreamSmoother.Pacer()
        var a = 0; var b = 0
        repeat(62) { a += small.step(40); b += big.step(2000) }
        assertTrue(b > a)
    }

    @Test fun doesNotSplitSurrogatePair() {
        val target = "ab\uD83D\uDE00cd"
        val pacer = StreamSmoother.Pacer()
        for (s in 0..target.length) {
            val n = StreamSmoother.nextShown(pacer, target, s)
            assertTrue(!(n in 1 until target.length && Character.isHighSurrogate(target[n - 1]) && Character.isLowSurrogate(target[n])))
        }
    }
}

class StreamFadeTest {
    @Test fun fadeStartsFaintAndEndsOpaque() {
        assertTrue(StreamSmoother.fadeAlpha(0) < 60)
        assertEquals(255, StreamSmoother.fadeAlpha(StreamSmoother.FADE_MS))
        assertEquals(255, StreamSmoother.fadeAlpha(10_000))
    }

    @Test fun fadeNeverDecreasesWithAge() {
        var prev = -1
        for (ms in 0L..300L step 10L) {
            val a = StreamSmoother.fadeAlpha(ms)
            assertTrue(a >= prev)
            prev = a
        }
    }

    @Test fun rateChangesGraduallyNotInJumps() {
        val p = StreamSmoother.Pacer()
        var first = 0
        repeat(3) { first += p.step(2000) }   // 3 frame pertama pada backlog besar tetap pelan (belum 260 cps)
        assertTrue("terlalu melonjak: $first", first <= 5)
    }
}

class StreamTempoTest {
    private fun <T> withProfile(p: StreamTempo.Params, block: () -> T): T {
        val old = StreamTempo.current
        StreamTempo.current = p
        try { return block() } finally { StreamTempo.current = old }
    }

    /** Jalankan penghalus frame demi frame; kembalikan daftar (waktu ms, jumlah huruf tampil). */
    private fun run(target: String, boost: Double = 1.0, maxMs: Long = 120_000L): List<Pair<Long, Int>> {
        val pacer = StreamSmoother.Pacer().also { it.boost = boost }
        val out = ArrayList<Pair<Long, Int>>()
        var shown = 0
        var ms = 0L
        while (shown < target.length && ms < maxMs) {
            shown = StreamSmoother.nextShown(pacer, target, shown)
            ms += StreamSmoother.FRAME_MS
            out += ms to shown
        }
        assertEquals(target.length, shown)
        return out
    }

    @Test fun defaultProfileIsSantai() {
        assertEquals("santai", StreamTempo.DEFAULT.key)
        assertEquals("santai", StreamTempo.byKey(null).key)
        assertEquals("santai", StreamTempo.byKey("tidak-ada").key)
        assertEquals("cepat", StreamTempo.byKey("cepat").key)
    }

    @Test fun santaiIsSlowerThanNormalAndCepat() {
        val text = "Ini kalimat contoh yang cukup panjang untuk diukur lajunya per frame. ".repeat(6)
        val santai = withProfile(StreamTempo.SANTAI) { run(text).last().first }
        val normal = withProfile(StreamTempo.NORMAL) { run(text).last().first }
        val cepat = withProfile(StreamTempo.CEPAT) { run(text).last().first }
        assertTrue("santai $santai harus > normal $normal", santai > normal)
        assertTrue("normal $normal harus >= cepat $cepat", normal >= cepat)
    }

    @Test fun pausesRightAfterSentenceEnd() = withProfile(StreamTempo.SANTAI) {
        val text = "Halo dunia. Apa kabar semuanya hari ini"
        val dot = text.indexOf('.') + 1
        val frames = run(text)
        val atDot = frames.filter { it.second == dot }
        // Berhenti tepat sesudah titik selama sekitar pauseSentenceMs (>= 6 frame 16 ms ~ 96 ms).
        assertTrue("jeda kalimat terlalu singkat: ${atDot.size} frame", atDot.size >= 6)
        assertTrue(frames.none { it.second == dot + 1 && it.first < atDot.first().first })
    }

    @Test fun decimalNumberDoesNotPause() = withProfile(StreamTempo.SANTAI) {
        val text = "Nilainya 3.5 persen"
        val cut = text.indexOf('.') + 1
        val atCut = run(text).count { it.second == cut }
        assertTrue("angka desimal tidak boleh berjeda: $atCut frame", atCut <= 3)
    }

    @Test fun noPauseInsideCodeFence() = withProfile(StreamTempo.SANTAI) {
        val text = "```\na = 1.\nb = 2.\n```"
        val pausedFrames = run(text).groupBy { it.second }.values.maxOf { it.size }
        assertTrue("jeda di dalam kode: $pausedFrames frame", pausedFrames <= 8)
    }

    @Test fun normalAndCepatNeverPause() {
        for (p in listOf(StreamTempo.NORMAL, StreamTempo.CEPAT)) withProfile(p) {
            val text = "Satu. Dua, tiga!\n\nEmpat? Lima."
            val maxFrames = run(text).groupBy { it.second }.values.maxOf { it.size }
            assertTrue("${p.key} berjeda $maxFrames frame", maxFrames <= 8)
        }
    }

    @Test fun boostIsFasterButStillRevealsEveryCharacter() = withProfile(StreamTempo.SANTAI) {
        val text = "Teks yang harus selesai sebelum alat berikutnya. ".repeat(4)
        val normal = run(text).last().first
        val boosted = run(text, boost = StreamTempo.SANTAI.fastForward).last().first
        assertTrue("percepatan tidak lebih cepat: $boosted vs $normal", boosted < normal)
    }

    @Test fun drainFloorOnlyAppliesWithCap() {
        assertEquals(0.0, StreamTempo.drainFloorCps(StreamTempo.NORMAL, 10_000), 0.0001)
        assertEquals(200.0, StreamTempo.drainFloorCps(StreamTempo.SANTAI, 5000), 0.0001)
        assertEquals(0.0, StreamTempo.drainFloorCps(StreamTempo.SANTAI, 0), 0.0001)
    }

    @Test fun insideFenceCountsOpenFences() {
        val t = "a ```kode``` b ```belum"
        assertTrue(!StreamTempo.insideFence(t, 2))
        assertTrue(StreamTempo.insideFence(t, 8))
        assertTrue(!StreamTempo.insideFence(t, 15))
        assertTrue(StreamTempo.insideFence(t, t.length))
    }
}
