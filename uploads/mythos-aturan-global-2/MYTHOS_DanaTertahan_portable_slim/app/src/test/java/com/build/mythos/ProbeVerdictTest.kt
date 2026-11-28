package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeVerdictTest {
    private val now = 1_000_000_000L
    private fun empty() = ProbeOutcome(emptyList(), emptyList(), emptyMap())

    @Test fun kodeDigolongkanBenar() {
        assertEquals(ProbeVerdict.GOOD, classifyProbe(200))
        assertEquals(ProbeVerdict.BUSY, classifyProbe(429))
        assertEquals(ProbeVerdict.BUSY, classifyProbe(503))
        assertEquals(ProbeVerdict.UNAVAILABLE, classifyProbe(404))
        assertEquals(ProbeVerdict.UNAVAILABLE, classifyProbe(400))
        assertEquals(ProbeVerdict.UNKNOWN, classifyProbe(-1))
        assertEquals(ProbeVerdict.UNKNOWN, classifyProbe(401))
        assertEquals(ProbeVerdict.UNKNOWN, classifyProbe(408))
    }

    @Test fun modelGagalDisembunyikanDanPulihMunculLagi() {
        val awal = mergeProbeResults(empty(), listOf("m1" to 200, "m2" to 404, "m3" to 429), now)
        assertEquals(listOf("m1"), awal.good)
        assertEquals(listOf("m3"), awal.busy)
        assertEquals(404, awal.bad["m2"]!!.code)

        // m2 kini bisa dipakai, m3 sudah pulih, m1 sekarang tidak berhak
        val lalu = mergeProbeResults(awal, listOf("m2" to 200, "m3" to 200, "m1" to 403), now + 1)
        assertTrue("m2" in lalu.good)
        assertTrue("m3" in lalu.good)
        assertFalse("m1" in lalu.good)
        assertEquals(403, lalu.bad["m1"]!!.code)
        assertFalse(lalu.bad.containsKey("m2"))
        assertTrue(lalu.busy.isEmpty())
    }

    @Test fun hasilTakTentuTidakMengubahStatus() {
        val awal = mergeProbeResults(empty(), listOf("m1" to 200, "m2" to 404), now)
        val lalu = mergeProbeResults(awal, listOf("m1" to -1, "m2" to 401), now + 1)
        assertEquals(listOf("m1"), lalu.good)
        assertTrue(lalu.bad.containsKey("m2"))
    }

    @Test fun awalanModelsDianggapSama() {
        val awal = mergeProbeResults(empty(), listOf("models/gemini-x" to 200), now)
        val lalu = mergeProbeResults(awal, listOf("gemini-x" to 404), now + 1)
        assertTrue(lalu.good.isEmpty())
        assertEquals(1, lalu.bad.size)
    }

    @Test fun kapanLayakDicobaUlang() {
        assertFalse(shouldRetryUnavailable(404, now, now + 60L * 60 * 1000))          // 404: tunggu 24 jam
        assertTrue(shouldRetryUnavailable(404, now, now + GONE_RETRY_MS))
        assertFalse(shouldRetryUnavailable(400, now, now + 10L * 60 * 1000))          // lainnya: tunggu 1 jam
        assertTrue(shouldRetryUnavailable(400, now, now + BAD_RETRY_MS))
        assertTrue(shouldRetryUnavailable(400, 0L, now))                              // belum pernah dicatat
        assertTrue(shouldRetryUnavailable(400, now + 5_000L, now))                    // jam mundur: coba ulang
    }
}
