package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliGuideTest {
    private val gemini = "Gemini CLI (Google)"
    private val codex = "Codex (ChatGPT)"

    @Test
    fun panduanAmanDimasukkanKeStringJavaScript() {
        val g = CLI_NARRATION_GUIDE
        assertFalse("tanda kutip ganda merusak string JS wrapper Codex", g.contains('"'))
        assertFalse("backslash merusak string JS", g.contains('\\'))
        assertFalse("tanda dolar diperlakukan sebagai templat Kotlin", g.contains('$'))
        assertFalse("baris baru memutus string JS", g.contains('\n'))
        assertTrue(g.endsWith("Permintaan pengguna: "))
    }

    @Test
    fun geminiMendapatPanduanSamaDenganCodex() {
        val p = cliPromptFor(gemini, "Lakukan audit", false, false)
        assertEquals(CLI_NARRATION_GUIDE + "Lakukan audit", p)
    }

    @Test
    fun providerLainTidakBerubah() {
        assertEquals("Lakukan audit", cliPromptFor(codex, "Lakukan audit", false, false))
    }

    @Test
    fun awalanPenalaranMendalamTetapDiikutiPesan() {
        val p = cliPromptFor(gemini, "Perbaiki bug", true, false)
        assertTrue(p.startsWith(CLI_NARRATION_GUIDE))
        assertTrue(p.endsWith("Perbaiki bug"))
        assertTrue(p.contains(DEEP_CLI_PREFIX))
    }

    @Test fun apiNarrationGuideMemastikanTeksNonThoughtSebelumAlat() {
        assertTrue(API_NARRATION_GUIDE.contains("teks respons biasa"))
        assertTrue(API_NARRATION_GUIDE.contains("bertahap"))
    }

    @Test fun apiNarrationGuideMengaturKapanMenulisDanBentuknya() {
        val g = API_NARRATION_GUIDE
        assertTrue(g.contains("PINDAH TAHAP"))
        assertTrue(g.contains("JANGAN tulis teks di antaranya"))
        assertTrue(g.contains("Jangan memulai dengan"))
        assertTrue(g.contains("judul baris langkah"))
        assertTrue(g.contains("JAWABAN AKHIR"))
    }
}
