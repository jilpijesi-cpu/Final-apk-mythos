package com.mythos.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** patch9: teks balasan AI harus mengikuti gaya font pilihan (sebelumnya MythosSerif dikunci ke FontFamily.Serif). */
class FontAnswerStyleTest {
    @Test fun mythosSerifMengikutiPilihanFontBukanKonstanta() {
        val design = File("src/main/java/com/mythos/app/ui/Design.kt").readText()
        assertFalse("MythosSerif tidak boleh dikunci ke FontFamily.Serif", Regex("""val MythosSerif:\s*FontFamily\s*=\s*FontFamily\.Serif""").containsMatchIn(design))
        assertTrue(design.contains("val MythosSerif: FontFamily get() = FontTheme.serifFamily"))
    }

    @Test fun serifHanyaDipertahankanUntukGayaDefault() {
        val ft = File("src/main/java/com/mythos/app/ui/FontTheme.kt").readText()
        assertTrue(ft.contains("val serifFamily: FontFamily get() = if (current.value.key == \"default\") FontFamily.Serif else current.value.family"))
    }
}
