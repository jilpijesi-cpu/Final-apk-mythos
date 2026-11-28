package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTextAttachmentTest {

    @Test fun shouldConvert_respectsThresholdsAndSkipsLeakOsint() {
        assertFalse(LongTextAttachment.shouldConvert("Halo apa kabar", leakModeActive = false))
        assertTrue(LongTextAttachment.shouldConvert("a".repeat(LongTextAttachment.MIN_CHARS), leakModeActive = false))
        assertTrue(LongTextAttachment.shouldConvert((1..25).joinToString("\n") { "baris $it" }, leakModeActive = false))

        val manyLines = (1..30).joinToString("\n") { "NAMA$it 02" }
        assertFalse("Mode LeakOSINT tidak boleh diubah jadi file", LongTextAttachment.shouldConvert(manyLines, leakModeActive = true))
        assertFalse("Perintah /cek banyak baris tidak boleh diubah jadi file", LongTextAttachment.shouldConvert("/cek\n$manyLines", leakModeActive = false))
        assertFalse("Perintah /leak banyak baris tidak boleh diubah jadi file", LongTextAttachment.shouldConvert("/leak\n$manyLines", leakModeActive = false))
    }

    @Test fun detectExtension_identifiesCommonFormats() {
        assertEquals("html", LongTextAttachment.detectExtension("<!DOCTYPE html><html><body>Halo</body></html>"))
        assertEquals("json", LongTextAttachment.detectExtension("""{"nama":"Budi","aktif":true}"""))
        assertEquals("kt", LongTextAttachment.detectExtension("package com.example\nfun main() {}"))
        assertEquals("py", LongTextAttachment.detectExtension("def hitung(x):\n    return x * 2"))
        assertEquals("txt", LongTextAttachment.detectExtension("Catatan rapat panjang biasa tanpa kode."))
    }

    @Test fun buildSummaryAndDigest_areAccurate() {
        val sample = "baris 1\nbaris 2\nbaris 3"
        val summary = LongTextAttachment.buildSummary(sample)
        assertTrue(summary.contains("3 baris"))
        assertEquals(sample, LongTextAttachment.buildDigest(sample))

        val huge = "x".repeat(LongTextAttachment.DIGEST_MAX_CHARS + 500)
        val digest = LongTextAttachment.buildDigest(huge)
        assertTrue(digest.contains("baca file lengkap dari disk"))
    }
}
