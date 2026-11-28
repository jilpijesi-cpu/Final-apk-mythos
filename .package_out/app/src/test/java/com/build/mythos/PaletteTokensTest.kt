package com.build.mythos

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTokensTest {
    @After fun pulihkanTema() { Palette.dark = false }

    private fun lum(c: Int): Double {
        fun ch(shift: Int): Double {
            val v = ((c shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val hi = maxOf(lum(a), lum(b))
        val lo = minOf(lum(a), lum(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun pratinjauPerubahanTerbacaDiKeduaTema() {
        for (dark in listOf(false, true)) {
            Palette.dark = dark
            assertTrue("tambah dark=$dark", contrast(Palette.DIFF_ADD_FG, Palette.DIFF_ADD_BG) >= 4.5)
            assertTrue("hapus dark=$dark", contrast(Palette.DIFF_DEL_FG, Palette.DIFF_DEL_BG) >= 4.5)
        }
    }

    @Test fun biruTautanTerbacaDiAtasDrawerDiKeduaTema() {
        for (dark in listOf(false, true)) {
            Palette.dark = dark
            assertTrue("dark=$dark", contrast(Palette.LINK_BLUE, Palette.DRAWER_BG) >= 4.5)
        }
    }

    @Test fun pilTerpilihKontrasDenganLatarDanTeksnyaTerbaca() {
        for (dark in listOf(false, true)) {
            Palette.dark = dark
            assertTrue("latar dark=$dark", contrast(Palette.STRONG, Palette.BG) >= 7.0)
            assertTrue("teks dark=$dark", contrast(onColor(Palette.STRONG), Palette.STRONG) >= 4.5)
        }
    }

    @Test fun tokenBerubahAntarTema() {
        Palette.dark = false
        val light = listOf(Palette.LINK_BLUE, Palette.DIFF_ADD_BG, Palette.DIFF_ADD_FG, Palette.DIFF_DEL_BG, Palette.DIFF_DEL_FG)
        Palette.dark = true
        val night = listOf(Palette.LINK_BLUE, Palette.DIFF_ADD_BG, Palette.DIFF_ADD_FG, Palette.DIFF_DEL_BG, Palette.DIFF_DEL_FG)
        for (i in light.indices) assertNotEquals("token $i", light[i], night[i])
    }

    @Test fun satuSumberWarnaOranyeDanLatarPanel() {
        assertEquals(0xFFD67658.toInt(), Palette.ORANGE_DOT)
        assertEquals(0xFF0F172A.toInt(), Palette.CODE_BG)
        assertEquals(Palette.CODE_BG, Live.BG)
        assertEquals(Palette.ORANGE_DOT, Live.ORANGE)
    }
}
