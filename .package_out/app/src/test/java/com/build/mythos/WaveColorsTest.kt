package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveColorsTest {
    private val base = 0xFFB4B2AC.toInt()
    private val hi = 0xFF141413.toInt()
    private val glow = 0xFFD67658.toInt()

    @Test fun titikWarnaSamaBanyakDenganPosisi() {
        assertEquals(WAVE_SAMPLES, WAVE_STOPS.size)
        assertEquals(WAVE_SAMPLES, waveColors(base, hi).size)
        assertEquals(0f, WAVE_STOPS.first(), 0f)
        assertEquals(1f, WAVE_STOPS.last(), 1e-6f)
        for (i in 1 until WAVE_STOPS.size) assertTrue(WAVE_STOPS[i] > WAVE_STOPS[i - 1])
    }

    @Test fun bobotSorotDalamBatasDanBerpuncakDiInti() {
        var maxAt = 0
        var maxV = -1f
        for (i in 0 until WAVE_SAMPLES) {
            val v = waveWeight(WAVE_STOPS[i])
            assertTrue(v in 0f..1f)
            if (v > maxV) { maxV = v; maxAt = i }
        }
        assertEquals(1f, maxV, 1e-6f)
        assertTrue(WAVE_STOPS[maxAt] in 0.55f..0.70f)
    }

    @Test fun keduaUjungAdalahWarnaDasar() {
        for (glowColor in listOf(0, glow)) {
            val c = waveColors(base, hi, glowColor)
            assertEquals(base, c.first())
            assertEquals(base, c.last())
        }
    }

    @Test fun puncakAdalahWarnaSorotMurniWalauAdaSemburat() {
        val c = waveColors(base, hi, glow)
        val peak = (0 until WAVE_SAMPLES).maxByOrNull { waveWeight(WAVE_STOPS[it]) }!!
        assertEquals(hi, c[peak])
    }

    @Test fun semburatMengubahTepiDepanTetapiBukanTanpaSemburat() {
        val plain = waveColors(base, hi, 0)
        val warm = waveColors(base, hi, glow)
        assertTrue(plain.indices.any { plain[it] != warm[it] })
        // Semburat paling kuat di tepi depan (di depan inti, 0,8), bukan di belakang inti.
        val front = (0 until WAVE_SAMPLES).minByOrNull { Math.abs(WAVE_STOPS[it] - 0.80f) }!!
        assertNotEquals(plain[front], warm[front])
        assertTrue(waveGlowWeight(0.80f) > waveGlowWeight(0.30f))
    }

    @Test fun tanpaSemburatSamaDenganCampuranBiasa() {
        val c = waveColors(base, hi)
        for (i in 0 until WAVE_SAMPLES) assertEquals(mixArgb(base, hi, waveWeight(WAVE_STOPS[i])), c[i])
    }
}
