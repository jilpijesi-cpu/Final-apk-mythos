package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PesanTesTest {
    @Test fun usesGivenName() {
        val t = pesanTesBawaan("Jefri Nicholee")
        assertTrue(t.contains("Mythos AI, asisten pribadi Jefri Nicholee."))
        assertTrue(t.startsWith("Halo,"))
    }

    @Test fun fallsBackToDefaultName() {
        assertEquals(pesanTesBawaan(null), pesanTesBawaan("   "))
        assertTrue(pesanTesBawaan(null).contains(NAMA_TAMPIL_BAWAAN))
    }

    @Test fun nameCannotInjectQuotesOrNewlines() {
        val t = pesanTesBawaan("A\"B\nC")
        assertTrue(!t.contains("\"") && t.lines().size == 3)
    }
}
