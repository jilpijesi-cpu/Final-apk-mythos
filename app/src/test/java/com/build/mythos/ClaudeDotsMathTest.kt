package com.build.mythos

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeDotsMathTest {
    private val ph = floatArrayOf(PI.toFloat(), 0f, PI.toFloat() / 2f)
    private val w = floatArrayOf(4f, 3.5f, 5f)
    private val tilt = 1.3f

    @Test fun gayaBerikutnyaSelaluBerbeda() {
        for (cur in 0 until ClaudeDotsMath.ORBIT_STYLES) {
            for (r in 0..50) {
                val n = ClaudeDotsMath.nextStyle(cur, r)
                assertNotEquals(cur, n)
                assertTrue(n in 0 until ClaudeDotsMath.ORBIT_STYLES)
            }
        }
    }

    @Test fun bobotPeralihanDariNolKeSatu() {
        assertEquals(0f, ClaudeDotsMath.blendWeight(0, 800), 1e-6f)
        assertEquals(0.5f, ClaudeDotsMath.blendWeight(400, 800), 1e-6f)
        assertEquals(1f, ClaudeDotsMath.blendWeight(800, 800), 1e-6f)
        assertEquals(1f, ClaudeDotsMath.blendWeight(5000, 800), 1e-6f)
        assertEquals(0f, ClaudeDotsMath.blendWeight(-10, 800), 1e-6f)
    }

    @Test fun semuaGayaOrbitTetapDalamBatas() {
        val out = FloatArray(3)
        for (style in 0 until ClaudeDotsMath.ORBIT_STYLES) {
            for (i in 0..2) {
                var ts = 0f
                while (ts < 30f) {
                    ClaudeDotsMath.orbitDotStyle(style, i, ts, ph, w, tilt, out)
                    assertTrue("x gaya $style", abs(out[0]) <= 7.6f)
                    assertTrue("y gaya $style", abs(out[1]) <= 4.6f)
                    assertTrue("jari-jari gaya $style", out[2] in 1.0f..2.1f)
                    ts += 0.05f
                }
            }
        }
    }

    @Test fun gayaBerbedaMenghasilkanPosisiBerbeda() {
        val a = FloatArray(3)
        val b = FloatArray(3)
        ClaudeDotsMath.orbitDotStyle(0, 0, 1.0f, ph, w, tilt, a)
        ClaudeDotsMath.orbitDotStyle(3, 0, 1.0f, ph, w, tilt, b)
        assertTrue(abs(a[0] - b[0]) > 1e-3f || abs(a[1] - b[1]) > 1e-3f)
    }

    @Test fun campuranMengikutiBobot() {
        val out = FloatArray(3)
        val tmp = FloatArray(3)
        val a = FloatArray(3)
        val b = FloatArray(3)
        ClaudeDotsMath.orbitDotStyle(1, 2, 2.0f, ph, w, tilt, a)
        ClaudeDotsMath.orbitDotStyle(2, 2, 2.0f, ph, w, tilt, b)
        ClaudeDotsMath.orbitDotBlend(1, 2, 0f, 2, 2.0f, ph, w, tilt, out, tmp)
        for (j in 0..2) assertEquals(a[j], out[j], 1e-5f)
        ClaudeDotsMath.orbitDotBlend(1, 2, 1f, 2, 2.0f, ph, w, tilt, out, tmp)
        for (j in 0..2) assertEquals(b[j], out[j], 1e-4f)
        ClaudeDotsMath.orbitDotBlend(1, 2, 0.5f, 2, 2.0f, ph, w, tilt, out, tmp)
        for (j in 0..2) assertEquals((a[j] + b[j]) / 2f, out[j], 1e-4f)
    }

    @Test fun orbitDotLamaSamaDenganGayaNol() {
        val old = ClaudeDotsMath.orbitDot(1, 1.7f, ph, w, tilt)
        val out = FloatArray(3)
        ClaudeDotsMath.orbitDotStyle(0, 1, 1.7f, ph, w, tilt, out)
        for (j in 0..2) assertEquals(old[j], out[j], 1e-6f)
    }

    @Test fun jenisDenyutMencakupSemuaJenis() {
        val seen = (0..99).map { ClaudeDotsMath.pulseKind(it) }.toSet()
        assertEquals((0 until ClaudeDotsMath.GRID_KINDS).toSet(), seen)
        assertEquals(0, ClaudeDotsMath.pulseKind(0))
        assertEquals(3, ClaudeDotsMath.pulseKind(99))
    }

    @Test fun riakLebihPanjangDariDenyutLain() {
        assertTrue(ClaudeDotsMath.pulseDuration(3, 0) > ClaudeDotsMath.pulseDuration(0, 259))
    }

    @Test fun gridKembaliKeJarakPenuhDiAwalDanAkhirSemuaJenis() {
        val out = FloatArray(3)
        for (kind in 0 until ClaudeDotsMath.GRID_KINDS) {
            for (k in 0..3) {
                for (u in listOf(0f, 1f)) {
                    ClaudeDotsMath.gridDot(kind, k, u, 1f, 8f, true, out)
                    assertEquals("jarak jenis $kind titik $k u=$u", ClaudeDotsMath.GRID_R_DP, out[1], 1e-4f)
                    assertEquals(1f, out[2], 1e-4f)
                }
            }
        }
    }

    @Test fun gridBelumMulaiDiamDiSudutDasar() {
        val out = FloatArray(3)
        for (kind in 0 until ClaudeDotsMath.GRID_KINDS) {
            for (k in 0..3) {
                ClaudeDotsMath.gridDot(kind, k, -1f, 1f, 8f, true, out)
                assertEquals(45f + 90f * k, out[0], 1e-4f)
                assertEquals(ClaudeDotsMath.GRID_R_DP, out[1], 1e-4f)
            }
        }
    }

    @Test fun gridJenisAsliHanyaMenggerakkanPasanganAktif() {
        val out = FloatArray(3)
        ClaudeDotsMath.gridDot(0, 0, 0.5f, 1f, 8f, false, out)
        assertEquals(45f, out[0], 1e-4f)
        assertEquals(ClaudeDotsMath.GRID_R_DP, out[1], 1e-4f)
        ClaudeDotsMath.gridDot(0, 0, 0.5f, 1f, 8f, true, out)
        assertTrue(out[1] < ClaudeDotsMath.GRID_R_DP)
    }

    @Test fun gridJenisNapasMerapatDanMembesarkanTitik() {
        val out = FloatArray(3)
        ClaudeDotsMath.gridDot(1, 2, 0.4f, 1f, 8f, false, out)
        assertEquals(45f + 180f, out[0], 1e-4f) // tidak berputar
        assertTrue(out[1] < ClaudeDotsMath.GRID_R_DP)
        assertTrue(out[2] > 1f)
    }

    @Test fun gridRiakMenggerakkanTitikBergantian() {
        val out0 = FloatArray(3)
        val out3 = FloatArray(3)
        ClaudeDotsMath.gridDot(3, 0, 0.3f, 1f, 8f, true, out0)
        ClaudeDotsMath.gridDot(3, 3, 0.3f, 1f, 8f, true, out3)
        // pada u=0,3 titik 0 sudah merapat, titik 3 belum mulai (u - 0,42 < 0)
        assertTrue(out0[1] < ClaudeDotsMath.GRID_R_DP)
        assertEquals(ClaudeDotsMath.GRID_R_DP, out3[1], 1e-4f)
    }
}
