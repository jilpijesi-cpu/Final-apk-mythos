package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepBrainTest {
    @Test fun padaNolSamaPersisDenganModeNormal() {
        assertEquals(1f, BrainMath.dotScale(0f), 0f)
        assertEquals(0f, BrainMath.spread(0f), 0f)
        assertEquals(0f, BrainMath.converge(0f), 0f)
        assertEquals(0f, BrainMath.brainAlpha(0f), 0f)
    }

    @Test fun padaSatuHanyaOtakYangTersisa() {
        assertEquals(0f, BrainMath.dotScale(1f), 0f)
        assertEquals(1f, BrainMath.brainAlpha(1f), 0.0001f)
        assertEquals(1f, BrainMath.converge(1f), 0.0001f)
        assertEquals(0f, BrainMath.spread(1f), 0f)
    }

    @Test fun bolaMenyebarDuluBaruMenyatu() {
        assertTrue(BrainMath.spread(0.25f) > 0.5f)
        assertEquals(0f, BrainMath.converge(0.25f), 0f)          // belum bergerak ke bentuk otak
        assertTrue(BrainMath.converge(0.6f) > 0.2f)
        assertEquals(0f, BrainMath.brainAlpha(0.5f), 0f)         // otak belum terlihat saat bola masih berkumpul
    }

    @Test fun otakMunculBersamaanBolaMenyusutTanpaCelah() {
        var p = 0f
        while (p <= 1f) {
            val cover = BrainMath.brainAlpha(p) + minOf(1f, BrainMath.dotScale(p))
            assertTrue("p=$p cover=$cover", cover >= 0.99f)
            p += 0.01f
        }
    }

    @Test fun kemajuanMajuMundurDanTerjepit() {
        assertEquals(0.5f, BrainMath.advance(0f, BrainMath.FORM_MS / 2, true), 0.001f)
        assertEquals(1f, BrainMath.advance(0.9f, 10_000, true), 0f)
        assertEquals(0.5f, BrainMath.advance(1f, BrainMath.UNFORM_MS / 2, false), 0.001f)
        assertEquals(0f, BrainMath.advance(0.1f, 10_000, false), 0f)
        assertEquals(0.3f, BrainMath.advance(0.3f, -5, true), 0f)
    }

    @Test fun denyutHalusTanpaKilauDanUtuhTiapDenyutan() {
        var t = 0f
        while (t < 6f) {
            assertTrue("t=$t", BrainMath.pulse(t) in 1f..1.046f)
            assertTrue("t=$t", BrainMath.pulseAlpha(t) in 0.93f..1f)
            t += 0.02f
        }
        // Mulai dan akhir tiap denyutan tepat di skala 1,0 dan warna paling redup: tanpa lompatan saat terbentuk maupun saat memisah.
        for (b in 0..BrainMath.HOLD_BEATS) {
            assertEquals(1f, BrainMath.pulse(b * BrainMath.PULSE_PERIOD_S), 0.001f)
            assertEquals(0.93f, BrainMath.pulseAlpha(b * BrainMath.PULSE_PERIOD_S), 0.001f)
        }
    }

    @Test fun detakLubDubKuatLaluLembutLaluJeda() {
        val period = BrainMath.PULSE_PERIOD_S
        val lub = BrainMath.beat(period * 0.15f)
        val dub = BrainMath.beat(period * 0.42f)
        val jeda = BrainMath.beat(period * 0.80f)
        assertEquals(1f, lub, 0.001f)           // puncak detak pertama
        assertTrue("dub lebih lembut dari lub: $dub", dub in 0.4f..0.6f)
        assertEquals(0f, jeda, 0f)              // jeda tenang sebelum detak berikutnya
        // Periodik dan mulus: tidak ada lompatan antar-frame 16 ms.
        var prev = BrainMath.beat(0f)
        var t = 0.016f
        while (t < 3f) {
            val v = BrainMath.beat(t)
            assertTrue("lompatan di t=$t", Math.abs(v - prev) < 0.15f)
            assertEquals(v, BrainMath.beat(t + period), 0.001f)
            prev = v; t += 0.016f
        }
    }

    @Test fun otakTidakMenetapMemisahLaluMenyatuLagi() {
        // Belum cukup lama: tetap.
        assertNull(BrainMath.cycleDir(true, 1f, BrainMath.HOLD_MS - 1))
        assertNull(BrainMath.cycleDir(false, 0f, BrainMath.FLOAT_MS - 1))
        // Setelah beberapa denyutan: memisah. Setelah melayang sebagai bola: menyatu lagi.
        assertEquals(false, BrainMath.cycleDir(true, 1f, BrainMath.HOLD_MS))
        assertEquals(true, BrainMath.cycleDir(false, 0f, BrainMath.FLOAT_MS))
        // Di tengah gerak tidak pernah berbalik arah.
        assertNull(BrainMath.cycleDir(true, 0.6f, 999_999))
        assertNull(BrainMath.cycleDir(false, 0.4f, 999_999))
        assertEquals(4800L, BrainMath.HOLD_MS)   // 4 denyutan x 1,2 dtk
    }

    @Test fun simulasiSiklusBergantianBolaDanOtakBerulang() {
        var p = 0f
        var fwd = true
        var waited = 0L
        var formed = 0
        var split = 0
        var atEndPrev = false
        repeat(60_000 / 16) {
            p = BrainMath.advance(p, 16, fwd)
            val atEnd = (fwd && p >= 1f) || (!fwd && p <= 0f)
            if (!atEnd) { waited = 0L; atEndPrev = false }
            else {
                if (!atEndPrev) { if (fwd) formed++ else split++ }
                atEndPrev = true
                waited += 16
                BrainMath.cycleDir(fwd, p, waited)?.let { fwd = it; waited = 0L; atEndPrev = false }
            }
        }
        assertTrue("otak terbentuk berulang: $formed", formed >= 5)
        assertTrue("memisah menjadi bola berulang: $split", split >= 4)
    }

    @Test fun bolaMendaratDiDalamBentukOtak() {
        // Belahan kiri, belahan kanan, dan bagian bawah: semua di dalam satu lebar otak (|x|,|y| < 1) dan simetris kiri-kanan.
        for (i in 0..2) {
            assertTrue(Math.abs(BrainMath.TX[i]) < 1f)
            assertTrue(Math.abs(BrainMath.TY[i]) < 1f)
        }
        assertEquals(-BrainMath.TX[0], BrainMath.TX[1], 0f)
        assertEquals(BrainMath.TY[0], BrainMath.TY[1], 0f)
        assertEquals(0f, BrainMath.TX[2], 0f)
    }

    @Test fun tahapPenalaranMengikutiKeadaanAgenNyata() {
        assertEquals(0, deepStageFor(AgentState.CallingModel(1), analyzing = true))
        assertEquals(1, deepStageFor(AgentState.Planning, false))
        assertEquals(1, deepStageFor(AgentState.CallingModel(2), false))
        assertEquals(2, deepStageFor(AgentState.RunningTool("bash"), false))
        assertEquals(2, deepStageFor(AgentState.AwaitingApproval("rm"), false))
        assertEquals(3, deepStageFor(AgentState.Verifying, false))
        assertNull(deepStageFor(AgentState.Idle, false))
        assertNull(deepStageFor(AgentState.Cancelled, false))
        assertEquals(4, DEEP_STAGE_TEXT.size)
        assertEquals("Analyzing...", DEEP_STAGE_TEXT[0])
        assertEquals("Verifying...", DEEP_STAGE_TEXT[3])
    }
}
