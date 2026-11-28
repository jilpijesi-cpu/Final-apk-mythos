package com.build.mythos.cc

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regresi: Gemini 3 ke atas menolak (400) functionCall di riwayat tanpa thoughtSignature. Jalur native dulu tidak
 * menangkap maupun mengirim balik tanda itu, sehingga langkah kedua selalu ditolak lalu agen diam-diam turun ke mode teks.
 */
class GeminiSignatureTest {
    @Before fun resetBefore() { NativeSupport.reset() }
    @After fun resetAfter() { NativeSupport.reset() }
    private fun cfg(model: String) = ProviderConfig(ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "k", model)

    private fun history(): List<Msg> {
        val call = ToolCall("toolu_1", "Read", JSONObject().put("file_path", "a.kt"))
        return listOf(
            Msg(Role.USER, "baca a.kt"),
            Msg(Role.ASSISTANT, Protocol.render("", listOf(call))),
            Msg(Role.USER, Protocol.result(call, true, "isi file")),
        )
    }

    private fun modelCallPart(body: JSONObject): JSONObject {
        val contents = body.getJSONArray("contents")
        for (i in 0 until contents.length()) {
            val c = contents.getJSONObject(i)
            if (c.getString("role") == "model") return c.getJSONArray("parts").getJSONObject(0)
        }
        throw AssertionError("tidak ada giliran model: $contents")
    }

    @Test fun tandaTanganAsliDikirimBalik() {
        val body = geminiNativeBody(cfg("gemini-3-pro"), "sys", history(), emptyList(), mapOf("toolu_1" to "SIG_ASLI"))
        val part = modelCallPart(body)
        assertTrue(part.has("functionCall"))
        assertEquals("SIG_ASLI", part.getString("thoughtSignature"))
    }

    @Test fun gemini3TanpaTandaTanganMemakaiNilaiPengganti() {
        val part = modelCallPart(geminiNativeBody(cfg("gemini-3-pro"), "sys", history(), emptyList()))
        assertEquals(GEMINI_SKIP_SIGNATURE, part.getString("thoughtSignature"))
    }

    @Test fun modelLamaTanpaTandaTanganTidakDikirimiApaPun() {
        val part = modelCallPart(geminiNativeBody(cfg("gemini-2.5-flash"), "sys", history(), emptyList()))
        assertFalse(part.has("thoughtSignature"))
    }

    @Test fun awalanModelsDiabaikan() {
        assertEquals(GEMINI_SKIP_SIGNATURE, geminiCallSignature("models/gemini-3.8-flash", null, first = true))
        assertEquals(GEMINI_SKIP_SIGNATURE, geminiCallSignature("gemini-4-hydrogen", null, first = true))
        assertNull(geminiCallSignature("gemini-2.0-flash", null, first = true))
        assertNull(geminiCallSignature("gemini-3-pro", null, first = false))   // panggilan paralel kedua tak butuh
        assertEquals("S", geminiCallSignature("gemini-2.5-pro", "S", first = false))
    }

    @Test fun aliranMenangkapTandaTanganFunctionCall() {
        val acc = GeminiStream { }
        acc.feed(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"Read","args":{"file_path":"a.kt"}},"thoughtSignature":"SIG1"}]},"finishReason":"STOP"}]}"""
        )
        val r = acc.result()
        assertEquals(1, r.calls.size)
        assertEquals("Read", r.calls[0].name)
        assertEquals("SIG1", r.calls[0].signature)
    }

    @Test fun aliranTanpaTandaTanganMenghasilkanNull() {
        val acc = GeminiStream { }
        acc.feed("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"Read","args":{}}}]}}]}""")
        assertNull(acc.result().calls[0].signature)
    }

    @Test fun bagianPikiranTidakMasukKeTeks() {
        val acc = GeminiStream { }
        acc.feed("""{"candidates":[{"content":{"parts":[{"text":"rahasia","thought":true},{"text":"jawaban"}]}}]}""")
        assertEquals("jawaban", acc.result().text)
    }

    // ------------------------------------------------------------ penolakan native tidak boleh senyap

    private class FakeClient(private val raw: String) : LlmClient {
        override suspend fun complete(system: String, messages: List<Msg>): String = raw
    }

    @Test fun penolakan400MengalihkanKeTeksDanMencatatAlasannya() = runBlocking {
        NativeSupport.reset()
        val c = cfg("gemini-3-pro")
        val req = TurnRequest("sys", "sysN", listOf(Msg(Role.USER, "hai")), listOf(NativeTool("Read", "baca", JSONObject().put("type", "object"))))
        val r = nativeOrText(c, FakeClient("halo"), req) {
            throw LlmException("HTTP 400: Function call is missing a thought_signature", false, 400)
        }
        assertTrue(r is TurnResult.Raw)
        assertEquals("halo", (r as TurnResult.Raw).text)
        assertFalse(NativeSupport.enabled(c))
        val notices = NativeSupport.drainNotices()
        assertEquals(1, notices.size)
        assertTrue(notices[0], notices[0].contains("gemini-3-pro"))
        assertTrue(notices[0], notices[0].contains("thought_signature"))
        assertTrue(NativeSupport.drainNotices().isEmpty())
        NativeSupport.reset()
    }

    @Test fun galatSelain400Dst422TidakMenolakNative() = runBlocking {
        NativeSupport.reset()
        val c = cfg("gemini-3-pro")
        val req = TurnRequest("sys", "sysN", listOf(Msg(Role.USER, "hai")), listOf(NativeTool("Read", "baca", JSONObject().put("type", "object"))))
        var thrown: LlmException? = null
        try {
            nativeOrText(c, FakeClient("x"), req) { throw LlmException("HTTP 429: kuota", true, 429) }
        } catch (e: LlmException) {
            thrown = e
        }
        assertEquals(429, thrown?.code)
        assertTrue(NativeSupport.enabled(c))
        assertTrue(NativeSupport.drainNotices().isEmpty())
        NativeSupport.reset()
    }

    @Test fun resetMenghapusPenolakanDanPemberitahuan() {
        val c = cfg("gemini-3-pro")
        NativeSupport.deny(c, "HTTP 400")
        NativeSupport.reset()
        assertTrue(NativeSupport.enabled(c))
        assertTrue(NativeSupport.drainNotices().isEmpty())
    }
}
