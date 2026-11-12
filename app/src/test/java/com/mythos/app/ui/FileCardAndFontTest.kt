package com.mythos.app.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileCardAndFontTest {
    @After fun reset() = FontTheme.select("default")

    @Test fun namaKartuTanpaEkstensiDanRapi() {
        assertEquals("Design", prettyFileName("app/src/Design.kt"))
        assertEquals("Shimmer size", prettyFileName("shimmer-size.patch"))
        assertEquals("Bukti Pengaduan OJK", prettyFileName("uploads/Bukti_Pengaduan_OJK.pdf"))
    }

    @Test fun ekstensiDibacaDariNamaBerkas() {
        assertEquals("kt", fileExt("a/b/Design.kt"))
        assertEquals("", fileExt("Makefile"))
        assertEquals("patch", fileExt("x/Shimmer-size.PATCH"))
    }

    @Test fun gayaFontMemilihDanKembaliKeDefault() {
        assertTrue(FontTheme.options.size >= 5)
        FontTheme.select("serif")
        assertEquals("serif", FontTheme.key)
        FontTheme.select("tidak-ada")
        assertEquals("default", FontTheme.key)
    }
}
