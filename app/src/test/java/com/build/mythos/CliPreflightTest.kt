package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliPreflightTest {
    @Test fun successAndDefiniteFailure() {
        assertEquals(true, preflightVerdict("exit=0\n"))
        assertEquals(false, preflightVerdict("exit=1\n"))
        assertEquals(false, preflightVerdict("exit=127\ngemini: command not found"))
    }

    @Test fun coldTermuxAndKilledProcessesAreNotTreatedAsLoggedOut() {
        // Ini yang dulu membuat chat Gemini CLI gagal "kadang-kadang": timeout 15 dtk dibaca sebagai belum masuk.
        assertNull(preflightVerdict("exit=124 Timeout: perintah melewati batas 15 detik dan dihentikan paksa (prosesnya sudah mati).\nCek dulu hasil sebagian."))
        assertNull(preflightVerdict("exit=124 Timeout: perintah melewati batas 15 detik"))
        assertNull(preflightVerdict("exit=137\n"))
        assertNull(preflightVerdict("exit=143\n"))
        assertNull(preflightVerdict("ERR: permission denied\nexit=-1\n"))
        assertNull(preflightVerdict("Gagal memanggil Termux: boom"))
        assertNull(preflightVerdict("Tidak ada hasil."))
        assertNull(preflightVerdict(""))
    }

    @Test fun cacheExpiresAndCanBeInvalidated() {
        var t = 0L
        val c = CliAuthCache(ttlMs = 1000L) { t }
        assertFalse(c.isFresh("gemini"))
        c.markOk("gemini")
        t = 999L
        assertTrue(c.isFresh("gemini"))
        t = 1000L
        assertFalse(c.isFresh("gemini"))
        c.markOk("gemini")
        c.invalidate("gemini")
        assertFalse(c.isFresh("gemini"))
        assertFalse(c.isFresh("lain"))
    }
}
