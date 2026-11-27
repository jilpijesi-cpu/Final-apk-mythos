package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {
    private fun fake(vararg parts: String) = parts.joinToString("") // rakit saat jalan agar tidak ada token utuh di repo

    @Test fun bearerDanTokenUmumDisamarkan() {
        val gh = fake("ghp", "_", "A".repeat(36))
        val out = redactSecrets("Authorization: Bearer abcdef1234567890 gh=$gh")
        assertFalse(out.contains("abcdef1234567890"))
        assertFalse(out.contains(gh))
        assertTrue(out.contains("Bearer [DISEMBUNYIKAN]"))
    }

    @Test fun passwordDanQueryKeyDisamarkan() {
        val out = redactSecrets("gagal https://api.x.com/v1?key=RAHASIA123&a=1 password=hunter2 token: abc.def")
        assertFalse(out.contains("RAHASIA123"))
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("abc.def"))
        assertTrue(out.contains("a=1"))
    }

    @Test fun kredensialDiUrlDanPathRumahDisamarkan() {
        val out = redactSecrets("https://budi:sandi@host.com/x di /home/budi/proyek")
        assertFalse(out.contains("sandi"))
        assertTrue(out.contains("/home/[pengguna]/proyek"))
    }

    @Test fun teksBiasaTidakBerubah() {
        val s = "java.lang.NullPointerException at MainActivity.kt:42 pada thread main"
        assertEquals(s, redactSecrets(s))
    }
}
