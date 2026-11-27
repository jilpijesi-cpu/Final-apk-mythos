package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCacheTest {
    @Test
    fun jendelaObrolanMelompatPerBlok() {
        for (t in 0..8) assertEquals(0, chatWindowStart(t))
        assertEquals(4, chatWindowStart(9))
        assertEquals(4, chatWindowStart(12))
        assertEquals(8, chatWindowStart(13))
        // awalan stabil: dalam satu blok titik mulai tidak berubah
        assertEquals(chatWindowStart(9), chatWindowStart(11))
    }

    @Test
    fun jendelaObrolanSelaluEmpatSampaiDelapan() {
        for (t in 9..200) {
            val kept = t - chatWindowStart(t)
            assertTrue("total=$t kept=$kept", kept in 4..8)
        }
    }

    @Test
    fun hasilAlatPenyediaInternetBergeserSatuSatu() {
        val idx = List(6) { it * 2 + 5 }
        assertEquals(idx[2], shrinkKeepFrom(idx, 0, stable = false))
        assertEquals(0, shrinkKeepFrom(listOf(5, 7, 9), 0, stable = false))
    }

    @Test
    fun hasilAlatLokalMelompatPerBlok() {
        fun idx(n: Int) = List(n) { it * 2 + 5 }
        assertEquals(0, shrinkKeepFrom(idx(6), 0, stable = true))
        assertEquals(idx(7)[3], shrinkKeepFrom(idx(7), 0, stable = true))
        assertEquals(idx(9)[3], shrinkKeepFrom(idx(9), 0, stable = true))
        assertEquals(idx(10)[6], shrinkKeepFrom(idx(10), 0, stable = true))
    }

    @Test
    fun hasilUtuhLokalSelaluTigaSampaiEnam() {
        for (n in 7..100) {
            val idx = List(n) { it }
            val kept = n - shrinkKeepFrom(idx, 0, stable = true)
            assertTrue("n=$n kept=$kept", kept in 3..6)
        }
    }

    @Test
    fun timeoutLokalMengikutiUkuranPromptDanKecepatan() {
        val normal = localReadTimeoutMs(1400, 24.0)
        assertTrue(normal in 290_000..300_000)
        assertEquals(LOCAL_TIMEOUT_MIN_MS, localReadTimeoutMs(100, 25.0))
        assertEquals(LOCAL_TIMEOUT_MAX_MS, localReadTimeoutMs(5000, 3.0))
        assertTrue(localReadTimeoutMs(1400, 8.0) > normal)
        assertEquals(localReadTimeoutMs(1400, DEFAULT_LOCAL_TPS), localReadTimeoutMs(1400, Double.NaN))
    }
}
