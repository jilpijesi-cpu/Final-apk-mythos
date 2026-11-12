package com.mythos.app.agent

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaFirstTest {
    @After fun reset() { QuotaFirst.enabled = false }

    @Test fun matiSecaraBawaanFlashDiAtasLite() {
        assertTrue(modelScore("gemini-2.5-flash") > modelScore("gemini-2.5-flash-lite"))
    }

    @Test fun aktifLiteDiAtasFlashDanPro() {
        QuotaFirst.enabled = true
        assertTrue(modelScore("gemini-2.5-flash-lite") > modelScore("gemini-2.5-flash"))
        assertTrue(modelScore("gemini-2.5-flash") > modelScore("gemini-2.5-pro"))
    }

    @Test fun tidakMenyentuhPenyediaLain() {
        val before = modelScore("gpt-4.1-mini")
        QuotaFirst.enabled = true
        assertTrue(modelScore("gpt-4.1-mini") == before)
    }
}
