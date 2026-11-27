package com.build.mythos

import com.build.mythos.cc.LlmClient
import com.build.mythos.cc.LlmException
import com.build.mythos.cc.Msg
import com.build.mythos.cc.Presets
import com.build.mythos.cc.Role
import com.build.mythos.cc.StreamUpdate
import com.build.mythos.cc.TurnResult
import com.build.mythos.cc.anthropicNativeBody
import com.build.mythos.cc.geminiNativeBody
import com.build.mythos.cc.openAiNativeBody
import com.build.mythos.cc.plainStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Jawaban hidup di panel asisten layar: mesin ketik (sama dengan chat) dan giliran polos yang mengalir. */
class AssistStreamTest {
    private var savedTempo = StreamTempo.current

    @Before fun pakaiProfilNormal() { savedTempo = StreamTempo.current; StreamTempo.current = StreamTempo.NORMAL }
    @After fun pulihkanProfil() { StreamTempo.current = savedTempo }

    private val msgs = listOf(Msg(Role.USER, "halo"))

    private val fake = object : LlmClient {
        override suspend fun complete(system: String, messages: List<Msg>): String = "jawaban penuh"
    }

    // ---- mesin ketik

    @Test fun mesinKetikMenghabiskanTeksSebagaiAwalanTanpaLompat() {
        val tw = AssistTypewriter()
        val target = "Halo dunia. Ini jawaban yang cukup panjang untuk diketik pelan-pelan."
        tw.setTarget(target)
        var now = 0L
        var frames = 0
        while (!tw.settled(now) && frames++ < 20_000) {
            now += 16
            tw.tick(now, 16)
            assertTrue(target.startsWith(tw.shown))
        }
        assertEquals(target, tw.shown)
    }

    @Test fun mesinKetikBisaDilewatiDenganKetukan() {
        val tw = AssistTypewriter()
        tw.setTarget("abcdefghij".repeat(30))
        assertTrue(tw.tick(1L, 16L, skip = true))
        assertEquals(tw.target, tw.shown)
        assertTrue(tw.settled(1L, skip = true))
    }

    @Test fun mesinKetikMengikutiSasaranYangBertumbuh() {
        val tw = AssistTypewriter()
        tw.setTarget("Halo")
        var now = 0L
        repeat(100) { now += 16; tw.tick(now, 16) }
        tw.setTarget("Halo dunia")
        repeat(400) { now += 16; tw.tick(now, 16) }
        assertEquals("Halo dunia", tw.shown)
    }

    @Test fun sasaranBukanAwalanMengulangDariNol() {
        val tw = AssistTypewriter()
        tw.setTarget("Halo")
        tw.tick(1L, 16L, skip = true)
        tw.setTarget("Selamat pagi")
        assertEquals("", tw.shown)
    }

    @Test fun hurufBaruMemudarLaluSelesai() {
        val tw = AssistTypewriter()
        tw.setTarget("ab")
        tw.tick(1000L, 16L, skip = true)
        assertTrue(tw.fading(1000L))
        assertTrue(tw.ageAt(0, 1100L) in 0 until StreamSmoother.FADE_MS)
        assertFalse(tw.fading(1000L + StreamSmoother.FADE_MS + 1))
    }

    // ---- giliran polos yang mengalir

    @Test fun plainStreamMeneruskanTeksLengkapDanMengembalikanAkhir() = runBlocking {
        val seen = ArrayList<String>()
        val r = plainStream(fake, "s", msgs, { seen.add(it) }) { up ->
            up(StreamUpdate("Ha", null, ""))
            up(StreamUpdate("Halo", null, ""))
            TurnResult.Structured("Halo", emptyList())
        }
        assertEquals("Halo", r)
        assertEquals(listOf("Ha", "Halo"), seen)
    }

    @Test fun plainStreamKembaliKeCompleteBilaDitolakSebelumAdaTeks() = runBlocking {
        val seen = ArrayList<String>()
        val r = plainStream(fake, "s", msgs, { seen.add(it) }) { _ -> throw LlmException("HTTP 400", false, 400) }
        assertEquals("jawaban penuh", r)
        assertEquals(listOf("jawaban penuh"), seen)
    }

    @Test fun plainStreamTidakMenimpaTeksYangSudahTampil() {
        val seen = ArrayList<String>()
        try {
            runBlocking {
                plainStream(fake, "s", msgs, { seen.add(it) }) { up ->
                    up(StreamUpdate("Ha", null, ""))
                    throw LlmException("HTTP 400", false, 400)
                }
            }
            throw AssertionError("galat harus diteruskan")
        } catch (e: LlmException) {
            assertEquals(listOf("Ha"), seen)
        }
    }

    @Test fun plainStreamGalatLainDiteruskan() {
        try {
            runBlocking { plainStream(fake, "s", msgs, {}) { _ -> throw LlmException("HTTP 401", false, 401) } }
            throw AssertionError("galat harus diteruskan")
        } catch (e: LlmException) {
            assertEquals(401, e.code)
        }
    }

    @Test fun balasanKosongDianggapGalatYangBisaDiulang() {
        try {
            runBlocking { plainStream(fake, "s", msgs, {}) { _ -> TurnResult.Structured("  ", emptyList()) } }
            throw AssertionError("harus galat")
        } catch (e: LlmException) {
            assertTrue(e.retryable)
        }
    }

    // ---- body tanpa alat tidak mengirim "tools" kosong

    @Test fun mesinKetikResetDanBatasIndeksUmurHuruf() {
        val tw = AssistTypewriter()
        assertEquals(Long.MAX_VALUE, tw.ageAt(-1, 1000L))
        assertEquals(Long.MAX_VALUE, tw.ageAt(0, 1000L))
        val panjang = "a".repeat(300)
        tw.setTarget(panjang)
        assertTrue(tw.tick(2000L, 16L, skip = true))
        assertEquals(100L, tw.ageAt(0, 2100L))
        assertEquals(100L, tw.ageAt(299, 2100L))
        assertEquals(Long.MAX_VALUE, tw.ageAt(300, 2100L))
        tw.reset()
        assertEquals("", tw.shown)
        assertFalse(tw.fading(2000L))
        assertEquals(Long.MAX_VALUE, tw.ageAt(0, 2100L))
    }

    @Test fun plainStreamKembaliKeCompleteUntuk404Dan422() = runBlocking {
        for (code in listOf(404, 422)) {
            val seen = ArrayList<String>()
            val r = plainStream(fake, "s", msgs, { seen.add(it) }) { _ -> throw LlmException("HTTP $code", false, code) }
            assertEquals("jawaban penuh", r)
            assertEquals(listOf("jawaban penuh"), seen)
        }
    }

    @Test fun plainStreamMeneruskanGalatBilaFallbackCompleteJugaGagal() {
        val failingClient = object : LlmClient {
            override suspend fun complete(system: String, messages: List<Msg>): String =
                throw LlmException("HTTP 503", true, 503)
        }
        try {
            runBlocking { plainStream(failingClient, "s", msgs, {}) { _ -> throw LlmException("HTTP 400", false, 400) } }
            throw AssertionError("galat fallback harus diteruskan")
        } catch (e: LlmException) {
            assertEquals(503, e.code)
        }
    }

    @Test fun plainStreamMendukungTurnResultRawDanMeneruskanPembatalan() = runBlocking {
        val r = plainStream(fake, "s", msgs, {}) { _ -> TurnResult.Raw("teks mentah") }
        assertEquals("teks mentah", r)
        var cancelled = false
        try {
            plainStream(fake, "s", msgs, {}) { _ -> throw CancellationException("dibatalkan") }
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue("CancellationException wajib diteruskan", cancelled)
    }

    @Test fun bodyTanpaAlatTidakMemuatTools() {
        assertFalse(openAiNativeBody(Presets.openAi("k", "gpt-4o-mini"), "sys", msgs, emptyList()).has("tools"))
        assertFalse(anthropicNativeBody(Presets.anthropic("k", "claude-sonnet-4-6"), "sys", msgs, emptyList()).has("tools"))
        assertFalse(geminiNativeBody(Presets.gemini("k", "gemini-2.5-flash"), "sys", msgs, emptyList()).has("tools"))
    }
}
