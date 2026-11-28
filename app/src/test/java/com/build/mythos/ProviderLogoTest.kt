package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLogoTest {
    @Test fun setiapPenyediaBawaanPunyaLogoKecualiKustom() {
        val names = listOf("OpenAI", "Anthropic", "Gemini", "OpenRouter", "Groq", "DeepSeek", "Ollama lokal",
            "Codex (ChatGPT)", "Gemini CLI (Google)", "Claude Code (Claude.ai)", "CLI Termux")
        for (n in names) assertNotNull("logo $n", ProviderCatalog.logoUrl(n))
        assertNull(ProviderCatalog.logoUrl("Kustom (OpenAI)"))
    }

    @Test fun alamatLogoSelaluHttpsDanDomainBenar() {
        assertEquals("https://www.google.com/s2/favicons?domain=openai.com&sz=128", ProviderCatalog.logoUrl("OpenAI"))
        for (n in listOf("Gemini", "Gemini CLI (Google)", "Claude Code (Claude.ai)")) {
            assertTrue(ProviderCatalog.logoUrl(n)!!.startsWith("https://"))
        }
        // "Gemini" dan "Gemini CLI" tidak boleh tertukar
        assertEquals("aistudio.google.com", ProviderCatalog.logoDomain("Gemini"))
        assertEquals("gemini.google.com", ProviderCatalog.logoDomain("Gemini CLI (Google)"))
    }
}
