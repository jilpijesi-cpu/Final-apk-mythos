package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StartupHealthTest {
    @Test fun bukaanBaruSetelahUpgradeMenampilkanModeUpgradedLaluStabilSetelahMarkHealthy() {
        val initial = BootState(lastBootCode = 57L, unhealthyBoots = 0, lastGoodCode = 57L, pendingCode = 58L)
        val d1 = decideBoot(initial, currentCode = 58L)
        assertEquals(BootMode.UPGRADED, d1.mode)
        assertEquals(1, d1.next.unhealthyBoots)
        assertNotNull(d1.message)

        val healthy = markHealthy(d1.next, currentCode = 58L)
        assertEquals(58L, healthy.lastGoodCode)
        assertEquals(0, healthy.unhealthyBoots)
        assertEquals(0L, healthy.pendingCode)

        val dAfter = decideBoot(healthy, currentCode = 58L)
        assertEquals(BootMode.NORMAL, dAfter.mode)
        assertNull(dAfter.message)
    }

    @Test fun gagalBertahanBerulangKaliMemicuModeUnstable() {
        var state = BootState(lastBootCode = 58L, unhealthyBoots = MAX_UNHEALTHY_BOOTS, lastGoodCode = 57L, pendingCode = 58L)
        val decision = decideBoot(state, currentCode = 58L)
        assertEquals(BootMode.UNSTABLE, decision.mode)
        assertNotNull(decision.message)
    }
}
