package com.build.mythos.cc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiThinkingTest {
    @Test fun gemini3Dan4MemakaiLevelMedium() {
        assertEquals("medium", geminiNativeThinking("gemini-4-hydrogen")!!.getString("thinkingLevel"))
        assertEquals("medium", geminiNativeThinking("gemini-3.8-flash")!!.getString("thinkingLevel"))
        assertNotNull(geminiNativeThinking("models/gemini-4-flash"))
    }

    @Test fun gemini25MemakaiAnggaranSedang() {
        assertEquals(8192, geminiNativeThinking("gemini-2.5-pro")!!.getInt("thinkingBudget"))
        assertEquals(4096, geminiNativeThinking("gemini-2.5-flash")!!.getInt("thinkingBudget"))
        assertNull(geminiNativeThinking("gemini-2.5-flash-lite"))
    }

    @Test fun varianMediaDanModelLainTidakDiubah() {
        assertNull(geminiNativeThinking("gemini-4-flash-image"))
        assertNull(geminiNativeThinking("gemini-4-flash-live"))
        assertNull(geminiNativeThinking("gemini-2.0-flash"))
        assertNull(geminiNativeThinking("openrouter/free"))
        assertNull(geminiNativeThinking("gpt-5"))
    }
}
