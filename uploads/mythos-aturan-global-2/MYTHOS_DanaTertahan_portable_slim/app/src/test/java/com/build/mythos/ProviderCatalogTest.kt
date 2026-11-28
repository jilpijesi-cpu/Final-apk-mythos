package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {
    @Test fun anthropicMemilihSonnetTerbaru() {
        val m = listOf("claude-haiku-5-5", "claude-sonnet-4-6", "claude-sonnet-5-5", "claude-opus-5-5")
        assertEquals("claude-sonnet-5-5", ProviderCatalog.recommend("Anthropic", m))
    }

    @Test fun anthropicTanpaSonnetTurunKeOpusLaluHaiku() {
        assertEquals("claude-opus-5-5", ProviderCatalog.recommend("Anthropic", listOf("claude-haiku-5-5", "claude-opus-5-5")))
        assertEquals("claude-haiku-5-5", ProviderCatalog.recommend("Anthropic", listOf("claude-haiku-5-5")))
    }

    @Test fun openAiMemilihGptTerbaruTanpaVarianKecilDanBukanModelSuara() {
        val m = listOf("gpt-4o", "gpt-4o-mini", "gpt-5-mini", "gpt-5", "gpt-5-2025-08-07", "text-embedding-3-large", "whisper-1", "gpt-4o-audio-preview", "gpt-5-codex")
        assertEquals("gpt-5", ProviderCatalog.recommend("OpenAI", m))
    }

    @Test fun openAiVersiLebihBaruMenang() {
        assertEquals("gpt-5.1", ProviderCatalog.recommend("OpenAI", listOf("gpt-5", "gpt-5.1", "gpt-4o")))
    }

    @Test fun openAiHanyaMiniMakaMiniDipakai() {
        assertEquals("gpt-4o-mini", ProviderCatalog.recommend("OpenAI", listOf("gpt-4o-mini", "tts-1")))
    }

    @Test fun geminiMemilihFlashTerbaruBukanLiteAtauGambar() {
        val m = listOf("models/gemini-2.5-pro", "models/gemini-2.5-flash", "models/gemini-2.5-flash-lite", "models/gemini-2.5-flash-image", "models/gemini-3.8-flash")
        assertEquals("models/gemini-3.8-flash", ProviderCatalog.recommend("Gemini", m))
    }

    @Test fun geminiHindariVersiPreviewBilaAdaYangStabil() {
        val m = listOf("gemini-3.9-flash-preview", "gemini-3.8-flash")
        assertEquals("gemini-3.8-flash", ProviderCatalog.recommend("Gemini", m))
        assertEquals("gemini-3.9-flash-preview", ProviderCatalog.recommend("Gemini", listOf("gemini-3.9-flash-preview")))
    }

    @Test fun geminiTanpaFlashPakaiProDanLiteTerakhir() {
        assertEquals("gemini-2.5-pro", ProviderCatalog.recommend("Gemini", listOf("gemini-2.5-pro", "gemini-2.5-flash-lite")))
        assertEquals("gemini-2.5-flash-lite", ProviderCatalog.recommend("Gemini", listOf("gemini-2.5-flash-lite")))
    }

    @Test fun deepSeekMemilihChatBukanReasoner() {
        assertEquals("deepseek-chat", ProviderCatalog.recommend("DeepSeek", listOf("deepseek-reasoner", "deepseek-chat")))
    }

    @Test fun penyediaTanpaAturanAtauTanpaKecocokanMengembalikanNull() {
        assertNull(ProviderCatalog.recommend("Groq", listOf("llama-3.3-70b")))
        assertNull(ProviderCatalog.recommend("OpenAI", listOf("text-embedding-3-small", "whisper-1")))
        assertNull(ProviderCatalog.recommend("Anthropic", emptyList()))
    }

    @Test fun jenisPenyedia() {
        assertEquals(ProviderKind.API, ProviderCatalog.kindOf("OpenAI", "openai"))
        assertEquals(ProviderKind.LOCAL, ProviderCatalog.kindOf("Ollama lokal", "openai"))
        assertEquals(ProviderKind.CUSTOM, ProviderCatalog.kindOf("Kustom (OpenAI)", "openai"))
        assertEquals(ProviderKind.CLI, ProviderCatalog.kindOf("CLI Termux", "cli"))
    }

    @Test fun urutanTampilPenyediaApiDuluSisanyaMenyusut() {
        val all = listOf("Gemini", "OpenAI", "Anthropic", "OpenRouter", "Groq", "DeepSeek", "Ollama lokal", "Kustom (OpenAI)", "CLI Termux")
        val o = ProviderCatalog.displayOrder(all)
        assertEquals(all.toSet(), o.toSet())
        assertEquals(listOf("OpenAI", "Anthropic", "Gemini", "OpenRouter", "Groq", "DeepSeek"), o.take(6))
        assertEquals(listOf("Ollama lokal", "Kustom (OpenAI)", "CLI Termux"), o.drop(6))
    }

    @Test fun tombolHubungkanHanyaAktifBilaIsianWajibLengkap() {
        assertFalse(ProviderCatalog.canConnect(null, false, "sk-abcdefghijkl", "", ""))
        assertFalse(ProviderCatalog.canConnect(ProviderKind.API, true, "", "", ""))
        assertFalse(ProviderCatalog.canConnect(ProviderKind.API, true, "abc", "", ""))
        assertTrue(ProviderCatalog.canConnect(ProviderKind.API, true, "  sk-abcdefghijkl  ", "", ""))
        assertFalse(ProviderCatalog.canConnect(ProviderKind.LOCAL, true, "", "", ""))
        assertTrue(ProviderCatalog.canConnect(ProviderKind.LOCAL, true, "", "http://127.0.0.1:11434/v1", ""))
        assertFalse(ProviderCatalog.canConnect(ProviderKind.CUSTOM, true, "k1234567", "http://x/v1", ""))
        assertTrue(ProviderCatalog.canConnect(ProviderKind.CUSTOM, true, "k1234567", "http://x/v1", "m"))
        assertTrue(ProviderCatalog.canConnect(ProviderKind.CLI, true, "", "", ""))
    }

    @Test fun modelOtomatisHanyaBilaBelumAdaPilihan() {
        assertTrue(ProviderCatalog.shouldAutoPick(""))
        assertTrue(ProviderCatalog.shouldAutoPick("  "))
        assertFalse(ProviderCatalog.shouldAutoPick("claude-haiku-5-5"))
    }

    @Test fun modelAwalInstan() {
        assertEquals("claude-sonnet-5-5", ProviderCatalog.startingModel("Anthropic", ""))
        assertEquals("deepseek-chat", ProviderCatalog.startingModel("DeepSeek", ""))
        assertEquals("gemini-3.8-flash", ProviderCatalog.startingModel("Gemini", "gemini-3.8-flash"))
        assertEquals("", ProviderCatalog.startingModel("OpenAI", ""))
    }

    @Test fun lencanaSetiapPenyediaAda() {
        for (n in listOf("OpenAI", "Anthropic", "Gemini", "OpenRouter", "Groq", "DeepSeek", "Ollama lokal", "Kustom (OpenAI)", "CLI Termux")) {
            val b = ProviderCatalog.badge(n)
            assertTrue(b.first.isNotEmpty())
        }
    }
}
