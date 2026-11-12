package com.mythos.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** patch12: teks yang mengalir tidak boleh membuat layar berkedip atau melompat. */
class StreamingStabilityTest {
    private val design get() = File("src/main/java/com/mythos/app/ui/Design.kt").readText()
    private val chat get() = File("src/main/java/com/mythos/app/ui/ChatScreen.kt").readText()

    @Test fun penandaJudulYangBelumLengkapDisembunyikan() {
        assertFalse(cleanForDisplay("Halo\n##").trimEnd().endsWith("#"))
        assertFalse(cleanForDisplay("Halo\n## ").trimEnd().endsWith("#"))
    }

    @Test fun penandaButirDanKodeYangBelumLengkapDisembunyikan() {
        assertFalse(cleanForDisplay("Halo\n-").trimEnd().endsWith("-"))
        assertFalse(cleanForDisplay("Halo\n2.").trimEnd().endsWith("2."))
        assertFalse(cleanForDisplay("Halo\n``").trimEnd().endsWith("`"))
    }

    @Test fun barisYangSudahBerisiTetapTampil() {
        assertTrue(cleanForDisplay("Halo\n## Judul").contains("Judul"))
        assertTrue(cleanForDisplay("Halo\n- butir").contains("butir"))
    }

    @Test fun hurufYangSudahTampilTidakDiketikUlang() {
        assertFalse(design.contains("if (n > text.length) n = text.length"))
    }

    @Test fun koreksiGulirMenungguAnimasiPenyematan() {
        assertTrue(chat.contains("pinAnimating[0] = true"))
        assertTrue(chat.contains("if (pinAnimating[0] || listState.isScrollInProgress) return@LaunchedEffect"))
    }
}
