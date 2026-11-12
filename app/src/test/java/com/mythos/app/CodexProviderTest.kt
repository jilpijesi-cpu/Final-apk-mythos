package com.mythos.app

import com.mythos.app.agent.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexProviderTest {
    @Test fun presetCodexMenandaiAppServerLangsungTanpaTokenBridge() {
        val c = UserSettings(provider = ProviderChoice.CODEX, apiKey = " tok123 ", model = "apa-saja").toProviderConfig()
        assertEquals(ProviderKind.OPENAI_COMPATIBLE, c.kind)
        assertEquals("codex://termux/app-server", c.baseUrl)
        assertEquals("", c.apiKey)
        assertEquals("codex-cli", c.model)
    }

    @Test fun tidakMembutuhkanApiKeyKarenaLoginDitanganiCodexCli() {
        assertFalse(ProviderChoice.CODEX.needsKey)
        assertTrue(UserSettings(provider = ProviderChoice.CODEX, apiKey = "").isConfigured)
    }
}
