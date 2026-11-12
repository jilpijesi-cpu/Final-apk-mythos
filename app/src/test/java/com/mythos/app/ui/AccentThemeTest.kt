package com.mythos.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccentThemeTest {
    @After fun reset() = AccentTheme.select("default")

    @Test fun bawaanTetapSamaDenganTampilanLama() {
        AccentTheme.select("default")
        assertEquals(Color(0xFFD77656), MythosColors.Accent)
        assertEquals(Color(0xFFBF5837), MythosColors.Send)
    }

    @Test fun memilihWarnaMengubahAksenDanTombolKirim() {
        AccentTheme.select("biru")
        assertEquals("biru", AccentTheme.key)
        assertEquals(Color(0xFF3A66F0), MythosColors.Accent)
        assertNotEquals(MythosColors.Accent, MythosColors.Send)
    }

    @Test fun kunciTakDikenalKembaliKeBawaan() {
        AccentTheme.select("tidak-ada")
        assertEquals("default", AccentTheme.key)
    }

    @Test fun sepuluhWarnaDitambahBawaanDanKunciUnik() {
        assertEquals(11, AccentTheme.options.size)
        assertEquals(AccentTheme.options.size, AccentTheme.options.map { it.key }.toSet().size)
    }
}
