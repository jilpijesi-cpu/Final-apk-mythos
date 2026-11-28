package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReasoningTest {
    // ---- deepStageAction
    @Test
    fun matiTanpaCatatanTidakMelakukanApaApa() {
        assertEquals(DeepAction.NONE, deepStageAction(on = false, stageDone = false, simple = false))
    }

    @Test
    fun matiSetelahTahapSelesaiMembersihkanCatatan() {
        assertEquals(DeepAction.CLEAR, deepStageAction(on = false, stageDone = true, simple = false))
    }

    @Test
    fun aktifBelumSelesaiMenjalankanTahap() {
        assertEquals(DeepAction.RUN, deepStageAction(on = true, stageDone = false, simple = false))
    }

    @Test
    fun aktifSudahSelesaiTidakDiulang() {
        assertEquals(DeepAction.NONE, deepStageAction(on = true, stageDone = true, simple = false))
    }

    @Test
    fun sapaanDilewati() {
        assertEquals(DeepAction.NONE, deepStageAction(on = true, stageDone = false, simple = true))
    }

    // ---- parseDeepNotes
    @Test
    fun kosongMenjadiKosong() {
        assertEquals("", parseDeepNotes("   \n "))
    }

    @Test
    fun sederhanaPersisDibuang() {
        assertEquals("", parseDeepNotes("SEDERHANA"))
        assertEquals("", parseDeepNotes("  sederhana.  "))
        assertEquals("", parseDeepNotes("\n**SEDERHANA**\n"))
    }

    @Test
    fun awalKataSederhananyaBukanPenandaSederhana() {
        val raw = "Sederhananya, tugas ini butuh tiga langkah.\nTujuan: perbaiki build."
        assertEquals(raw, parseDeepNotes(raw))
    }

    @Test
    fun analisisSahDipertahankan() {
        val raw = "Tujuan: perbaiki bug.\nPendekatan: A atau B."
        assertEquals(raw, parseDeepNotes(raw))
    }

    @Test
    fun catatanPanjangDipotongDiBatasKalimat() {
        val kalimat = "Ini kalimat analisis yang cukup panjang untuk menguji pemotongan. "
        val raw = kalimat.repeat(80)
        val out = parseDeepNotes(raw)
        assertTrue(out.length <= 2400)
        assertTrue(out.endsWith("."))
    }

    @Test
    fun cutDeepNotesTidakMemotongTeksPendek() {
        assertEquals("pendek", cutDeepNotes("pendek", 100))
    }

    @Test
    fun cutDeepNotesTanpaBatasWajarMemotongKeras() {
        assertEquals("abcde", cutDeepNotes("abcdefghij", 5))
    }

    // ---- Gemini
    @Test
    fun geminiBudgetSesuaiJenisModel() {
        assertEquals(32768, geminiThinkingBudget("gemini-2.5-pro"))
        assertEquals(24576, geminiThinkingBudget("models/gemini-2.5-flash"))
        assertEquals(24576, geminiThinkingBudget("gemini-2.5-flash-lite"))
        assertNull(geminiThinkingBudget("gemini-2.0-flash"))
    }

    @Test
    fun geminiVarianGambarDanSuaraTanpaThinking() {
        assertNull(geminiThinkingBudget("gemini-2.5-flash-image"))
        assertNull(geminiThinkingBudget("gemini-2.5-flash-preview-tts"))
        assertNull(geminiThinkingLevel("gemini-3-pro-image-preview"))
        assertNull(geminiThinkingConfig("gemini-2.5-flash-live"))
    }

    @Test
    fun gemini3MemakaiLevelBukanBudget() {
        assertEquals("high", geminiThinkingLevel("gemini-3-pro-preview"))
        assertEquals("medium", geminiThinkingLevel("gemini-4-hydrogen"))
        assertNull(geminiThinkingLevel("gemini-4-flash-image"))
        val cfg = geminiThinkingConfig("gemini-3-pro-preview")
        assertNotNull(cfg)
        val tc = cfg!!.getJSONObject("thinkingConfig")
        assertTrue(tc.has("thinkingLevel"))
        assertFalse(tc.has("thinkingBudget"))
    }

    @Test
    fun gemini25MemakaiBudgetBukanLevel() {
        val tc = geminiThinkingConfig("gemini-2.5-pro")!!.getJSONObject("thinkingConfig")
        assertTrue(tc.has("thinkingBudget"))
        assertFalse(tc.has("thinkingLevel"))
    }

    // ---- OpenAI
    @Test
    fun reasoningEffortHanyaUntukOpenAiAsliDanModelReasoning() {
        val base = "https://api.openai.com/v1"
        assertTrue(supportsReasoningEffort("o3-mini", base))
        assertTrue(supportsReasoningEffort("gpt-5", base))
        assertTrue(supportsReasoningEffort("gpt-5-mini", base))
        assertFalse(supportsReasoningEffort("gpt-5-chat-latest", base))
        assertFalse(supportsReasoningEffort("o1-mini", base))
        assertFalse(supportsReasoningEffort("o1-preview", base))
        assertFalse(supportsReasoningEffort("gpt-4o", base))
        assertFalse(supportsReasoningEffort("o3-mini", "https://openrouter.ai/api/v1"))
    }

    // ---- CLI
    @Test
    fun promptCliMenambahkanAwalanHanyaBilaAktifDanBukanSapaan() {
        assertEquals("halo", deepCliPrompt("halo", on = true, simple = true))
        assertEquals("kerjakan", deepCliPrompt("kerjakan", on = false, simple = false))
        assertTrue(deepCliPrompt("kerjakan", on = true, simple = false).endsWith("kerjakan"))
        assertTrue(deepCliPrompt("kerjakan", on = true, simple = false).startsWith(DEEP_CLI_PREFIX))
    }

    // ---- ringkasan riwayat
    @Test
    fun ringkasanRiwayatMemuatPesanDanAlat() {
        val items = listOf(Hist(0, "perbaiki build"), Hist(1, "baik"))
        val d = deepHistoryDigest(items)
        assertTrue(d.contains("Pengguna: perbaiki build"))
        assertTrue(d.contains("AI: baik"))
    }
}
