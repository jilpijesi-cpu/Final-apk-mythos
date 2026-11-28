package com.build.mythos.cc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusyTextTest {
    @Test fun jenisDariKodeDanPesan() {
        assertEquals(BusyKind.RATE, busyKindOf(429, "rate limit"))
        assertEquals(BusyKind.BUSY, busyKindOf(503, "The model is overloaded"))
        assertEquals(BusyKind.NETWORK, busyKindOf(-1, "Jaringan: timeout"))
    }

    @Test fun kalimatRamahTanpaKodeDanHitungMundur() {
        for (k in BusyKind.values()) for (n in 0..4) {
            val s = busyLine(k, n)
            assertFalse(s.contains("sibuk", ignoreCase = true))
            assertFalse(s.contains(Regex("\\d")))
            assertFalse(s.contains('\n'))
            assertTrue(s.length <= 64)
        }
    }

    @Test fun variasiBerputar() {
        assertEquals(busyLine(BusyKind.BUSY, 0), busyLine(BusyKind.BUSY, 3))
        assertFalse(busyLine(BusyKind.BUSY, 0) == busyLine(BusyKind.BUSY, 1))
    }
}
