package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelChoicesTest {
    @Test
    fun semuaPilihanValidDanTidakGanda() {
        val values = GEMINI_CLI_MODEL_CHOICES.map { it.value }
        assertEquals(values.size, values.toSet().size)
        for (v in values) assertTrue("tidak valid: $v", isValidGeminiCliModel(v))
    }

    @Test
    fun pilihanPertamaAdalahBawaan() {
        assertEquals(DEFAULT_GEMINI_CLI_MODEL, GEMINI_CLI_MODEL_CHOICES.first().value)
    }

    @Test
    fun keempatAliasCliAdaDiDaftar() {
        val values = GEMINI_CLI_MODEL_CHOICES.map { it.value }
        for (alias in listOf("auto", "pro", "flash", "flash-lite")) assertTrue(alias, alias in values)
    }

    @Test
    fun indeksMenandaiPilihanSaatIni() {
        assertEquals(2, geminiModelChoiceIndex("flash"))
        assertEquals(0, geminiModelChoiceIndex("auto"))
    }

    @Test
    fun modelYangDiketikSendiriMenunjukKeOpsiKetikSendiri() {
        assertEquals(GEMINI_CLI_MODEL_CHOICES.size, geminiModelChoiceIndex("gemini-9.9-eksperimen"))
    }

    @Test
    fun setiapPilihanBisaMasukKePerintahCli() {
        val quote: (String) -> String = { "'" + it + "'" }
        for (c in GEMINI_CLI_MODEL_CHOICES) {
            val cmd = geminiCliCommandWithModel("gemini -p {prompt}", c.value, quote)
            assertEquals("gemini --model '" + c.value + "' -p {prompt}", cmd)
        }
    }
}
