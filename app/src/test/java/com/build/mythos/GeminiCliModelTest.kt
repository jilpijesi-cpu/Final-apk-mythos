package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCliModelTest {
    private val quote: (String) -> String = { "'" + it.replace("'", "'\\''") + "'" }

    @Test
    fun emptySelectionDefaultsToAuto() {
        assertEquals("auto", normalizeGeminiCliModel("  "))
        assertTrue(isValidGeminiCliModel("auto"))
    }

    @Test
    fun manualModelIsPassedAsCliArgument() {
        assertEquals(
            "gemini --model 'gemini-3.5-flash-lite' -p {prompt}",
            geminiCliCommandWithModel("gemini -p {prompt}", "gemini-3.5-flash-lite", quote)
        )
    }

    @Test
    fun autoModelUsesGeminiCliNativeRouting() {
        assertEquals(
            "gemini --model 'auto' -p {prompt}",
            geminiCliCommandWithModel("gemini -p {prompt}", "auto", quote)
        )
    }

    @Test
    fun staleModelFlagIsReplacedInsteadOfDuplicated() {
        assertEquals(
            "gemini --model 'flash' -p {prompt}",
            geminiCliCommandWithModel("gemini -m gemini-old -p {prompt}", "flash", quote)
        )
        assertEquals(
            "gemini --model 'pro' -p {prompt}",
            geminiCliCommandWithModel("gemini --model=old -p {prompt}", "pro", quote)
        )
    }

    @Test
    fun unsafeModelTextIsRejected() {
        assertFalse(isValidGeminiCliModel("model; rm -rf ~"))
        assertFalse(isValidGeminiCliModel("--help"))
        assertNull(geminiCliCommandWithModel("gemini -p {prompt}", "x;touch /tmp/pwned", quote))
    }

    @Test
    fun commandMustBeGeminiPromptInvocation() {
        assertNull(geminiCliCommandWithModel("claude -p {prompt}", "auto", quote))
        assertNull(geminiCliCommandWithModel("gemini --version", "auto", quote))
    }

    @Test
    fun absoluteGeminiExecutablePathIsSupported() {
        assertEquals(
            "/data/data/com.termux/files/usr/bin/gemini --model 'auto' -p {prompt}",
            geminiCliCommandWithModel("/data/data/com.termux/files/usr/bin/gemini -p {prompt}", "auto", quote)
        )
    }

    @Test
    fun longPromptFlagWithEqualsIsSupported() {
        assertEquals(
            "gemini --model 'flash' --prompt={prompt}",
            geminiCliCommandWithModel("gemini --prompt={prompt}", "flash", quote)
        )
    }

    @Test
    fun existingLongPromptFlagIsSupported() {
        assertEquals(
            "gemini --model 'pro' --prompt {prompt}",
            geminiCliCommandWithModel("gemini --prompt {prompt}", "pro", quote)
        )
    }

    @Test
    fun customModelIdentifiersArePassedAsSingleQuotedArgument() {
        assertEquals(
            "gemini --model 'publishers/google/models/gemini-2.5-pro' -p {prompt}",
            geminiCliCommandWithModel("gemini -p {prompt}", "publishers/google/models/gemini-2.5-pro", quote)
        )
    }

    @Test
    fun oldQuotedModelAndOtherFlagsAreHandled() {
        assertEquals(
            "gemini --sandbox --model 'flash-lite' -p {prompt}",
            geminiCliCommandWithModel("gemini --sandbox --model='old model' -p {prompt}", "flash-lite", quote)
        )
    }

    @Test
    fun overlyLongModelIdentifierIsRejected() {
        assertFalse(isValidGeminiCliModel("gemini-" + "a".repeat(130)))
    }

    @Test
    fun environmentPrefixAndCliFlagsArePreserved() {
        assertEquals(
            "GEMINI_DEBUG=1 gemini --sandbox --model 'flash' -p {prompt}",
            geminiCliCommandWithModel("GEMINI_DEBUG=1 gemini --sandbox -p {prompt}", "flash", quote)
        )
    }
}
