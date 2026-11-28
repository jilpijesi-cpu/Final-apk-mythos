package com.build.mythos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCallTest {
    // ---- jeda percobaan ulang
    @Test fun jedaEksponensialTanpaPetunjuk() {
        assertEquals(2, retryWaitSeconds(null, 1))
        assertEquals(4, retryWaitSeconds(null, 2))
        assertEquals(8, retryWaitSeconds(null, 3))
        assertEquals(16, retryWaitSeconds(null, 4))
    }

    @Test fun jedaDibatasiMaksimal() =
        assertEquals(MAX_RETRY_WAIT_SEC, retryWaitSeconds(null, 5))

    @Test fun petunjukServerDipakaiBilaAda() {
        assertEquals(5, retryWaitSeconds(5, 1))
        assertEquals(MAX_RETRY_WAIT_SEC, retryWaitSeconds(MAX_RETRY_WAIT_SEC, 3))
    }

    @Test fun petunjukTerlaluLamaDialihkan() {
        assertFalse(retryHintTooLong(null))
        assertFalse(retryHintTooLong(MAX_RETRY_WAIT_SEC))
        assertTrue(retryHintTooLong(MAX_RETRY_WAIT_SEC + 1))
    }

    // ---- argumen alat
    @Test fun jsonValidDiparse() =
        assertEquals(1, parseToolArgs("{\"a\":1}", "err").getInt("a"))

    @Test fun teksKosongMenjadiObjekKosong() =
        assertEquals(0, parseToolArgs("  ", "err").length())

    @Test fun jsonRusakMenjadiError() =
        assertEquals("err", parseToolArgs("{rusak", "err").getString("__error"))

    @Test fun responsTerpotongTanpaArgumenMenjadiError() =
        assertEquals("err", toolArgsOf("", true, "err").getString("__error"))

    @Test fun tanpaArgumenTakTerpotongBukanError() =
        assertFalse(toolArgsOf("", false, "err").has("__error"))

    @Test fun terpotongTapiAdaIsiTetapDiparse() =
        assertEquals("x", toolArgsOf("{\"k\":\"x\"}", true, "err").getString("k"))

    // ---- jsonString
    @Test fun jsonStringMenanganiNullHilangDanAngka() {
        val o = JSONObject("{\"a\":null,\"b\":\"x\",\"c\":5}")
        assertEquals("", jsonString(o, "a"))
        assertEquals("", jsonString(o, "tidakada"))
        assertEquals("x", jsonString(o, "b"))
        assertEquals("5", jsonString(o, "c"))
    }
}
