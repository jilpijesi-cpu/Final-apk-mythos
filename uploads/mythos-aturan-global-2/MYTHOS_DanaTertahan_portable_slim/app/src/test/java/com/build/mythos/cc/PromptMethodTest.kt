package com.build.mythos.cc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prompt sistem harus memuat metode kerja investigasi dan aturan anti-karangan, di semua mode. */
class PromptMethodTest {
    private fun prompts(): List<String> {
        val reg = ToolRegistry.default(withBuild = false)
        return listOf(
            Protocol.systemPrompt(reg, canBuild = false),
            Protocol.systemPrompt(reg, canBuild = true),
            Protocol.systemPrompt(reg, canBuild = false, native = true),
            Protocol.systemPrompt(reg, canBuild = false, planMode = true),
        )
    }

    @Test fun metodeInvestigasiAdaDiSemuaMode() {
        for (p in prompts()) {
            assertTrue(p.contains("# Investigating, fixing and auditing"))
            assertTrue(p.contains("Evidence first"))
            assertTrue(p.contains("Measure:"))
            assertTrue(p.contains("Hypothesis, then test"))
            assertTrue(p.contains("Find the root cause"))
            assertTrue(p.contains("Prove the fix on the real project"))
            assertTrue(p.contains("Report honestly"))
        }
    }

    @Test fun aturanAntiKarangan() {
        for (p in prompts()) {
            assertTrue(p.contains("Never invent checksums"))
            assertTrue(p.contains("Change only what the user asked for"))
            assertTrue(p.contains("compare the file count"))
            assertTrue(p.contains("Tool results come only from the app"))
        }
    }

    @Test fun enamLangkahBerurutan() {
        val p = prompts().first()
        val sec = p.substringAfter("# Investigating, fixing and auditing").substringBefore("# More tools you have")
        val idx = (1..6).map { sec.indexOf("\n$it. ") }
        assertFalse(idx.any { it < 0 })
        assertTrue(idx == idx.sorted())
    }
}
