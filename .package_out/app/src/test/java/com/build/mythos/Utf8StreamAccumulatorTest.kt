package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class Utf8StreamAccumulatorTest {
    @Test
    fun preservesUtf8CharactersSplitAcrossArbitraryByteBoundaries() {
        val expected = "Halo 😀 café — Bahasa Indonesia 日本語"
        val bytes = expected.toByteArray(StandardCharsets.UTF_8)
        val accumulator = Utf8StreamAccumulator()
        val emitted = StringBuilder()
        bytes.forEach { emitted.append(accumulator.append(byteArrayOf(it))) }
        emitted.append(accumulator.finish())
        assertEquals(expected, emitted.toString())
        assertEquals(expected, accumulator.snapshot())
    }

    @Test
    fun appendsOnlyNewTextAndSupportsReset() {
        val accumulator = Utf8StreamAccumulator()
        assertEquals("Halo ", accumulator.append("Halo ".toByteArray(StandardCharsets.UTF_8)))
        assertEquals("dunia", accumulator.append("dunia".toByteArray(StandardCharsets.UTF_8)))
        assertEquals("Halo dunia", accumulator.snapshot())
        accumulator.reset()
        assertEquals("baru", accumulator.append("baru".toByteArray(StandardCharsets.UTF_8)))
        assertEquals("baru", accumulator.snapshot())
    }
}
