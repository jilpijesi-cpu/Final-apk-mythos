package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextLimitTest {
    @Test fun deteksiDanPenyusutanAnggaranKonteksSaat413() {
        val body = "Request too large for model: Limit 8000, Requested 24000"
        assertTrue(isContextTooLarge(413, body))
        assertEquals(8000, parseTokenLimit(body))
        assertEquals(24000, parseRequestedTokens(body))

        val next = shrunkBudget(current = 60_000, sentChars = 48_000, limit = 8000, requested = 24000)
        assertTrue(next in CTX_BUDGET_MIN until 48_000)
        assertEquals(LOCAL_CTX_BUDGET, defaultCtxBudget("Ollama lokal"))
    }
}
