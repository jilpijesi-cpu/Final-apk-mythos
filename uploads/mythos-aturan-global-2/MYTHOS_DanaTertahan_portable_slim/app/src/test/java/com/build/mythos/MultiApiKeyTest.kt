package com.build.mythos

import com.build.mythos.cc.FailoverClient
import com.build.mythos.cc.KeyPool
import com.build.mythos.cc.KeySpec
import com.build.mythos.cc.LlmClient
import com.build.mythos.cc.LlmException
import com.build.mythos.cc.Msg
import com.build.mythos.cc.ProviderKind
import com.build.mythos.cc.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiApiKeyTest {

    @Test
    fun parseApiKeysMemecahBarisKomaDanHapusDuplikat() {
        val raw = """
            Bearer "AIzaSyKeySatu1234567890"
            AIzaSyKeyDua12345678901, 'AIzaSyKeyTiga1234567890'; AIzaSyKeySatu1234567890
            
        """.trimIndent()
        assertEquals(
            listOf("AIzaSyKeySatu1234567890", "AIzaSyKeyDua12345678901", "AIzaSyKeyTiga1234567890"),
            parseApiKeys(raw),
        )
        assertEquals(
            "AIzaSyKeySatu1234567890\nAIzaSyKeyDua12345678901\nAIzaSyKeyTiga1234567890",
            serializeApiKeys(raw),
        )
    }

    @Test
    fun detectProviderMengenaliMultiKeyGemini() {
        val multi = "AIzaSyAkunPertama1234567890\nAIzaSyAkunKedua123456789012"
        assertEquals("Gemini", detectProvider(multi))
        assertNull(detectProvider("AIzaSySpasi Di Tengah123456"))
    }

    @Test
    fun ujiModelAktifSatuPerSatuPadaKunciSamaLaluBertahanPadaYangMerespons() = runBlocking {
        // Urutan per kunci: key-1 mencoba M1 -> M2 -> M3, lalu baru key-2 jika semua model key-1 kena limit.
        val specs = listOf(
            KeySpec("#1", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "key-1", "gemini-2.5-pro"),
            KeySpec("#1", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "key-1", "gemini-2.5-flash"),
            KeySpec("#2", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "key-2", "gemini-2.5-pro"),
        )
        var now = 1_000L
        val pool = KeyPool(specs, clock = { now }, rotateEvery = 0, sticky = true)
        val calls = mutableListOf<String>()
        val client = FailoverClient(
            pool = pool,
            factory = { cfg ->
                object : LlmClient {
                    override suspend fun complete(system: String, messages: List<Msg>): String {
                        calls.add("${cfg.apiKey}:${cfg.model}")
                        if (cfg.apiKey == "key-1" && cfg.model == "gemini-2.5-pro") {
                            throw LlmException("HTTP 429 rate limit", retryable = true, code = 429)
                        }
                        return "ok-${cfg.apiKey}-${cfg.model}"
                    }

                    override suspend fun streamText(system: String, messages: List<Msg>, onText: (String) -> Unit): String {
                        val out = complete(system, messages)
                        onText(out)
                        return out
                    }
                }
            },
            sleep = { now += it },
        )

        // Panggilan 1: key-1:pro kena 429 -> langsung coba model aktif berikutnya di key-1 (flash) -> langsung dipakai!
        val r1 = client.streamText("sys", listOf(Msg(Role.USER, "halo"))) {}
        // Panggilan 2 (bahkan setelah cooldown 60 dtk lewat): tetap bertahan (sticky) di key-1:flash karena masih sehat!
        now += 70_000L
        val r2 = client.complete("sys", listOf(Msg(Role.USER, "lanjut")))
        assertEquals("ok-key-1-gemini-2.5-flash", r1)
        assertEquals("ok-key-1-gemini-2.5-flash", r2)
        assertEquals(listOf("key-1:gemini-2.5-pro", "key-1:gemini-2.5-flash", "key-1:gemini-2.5-flash"), calls)
    }

    @Test
    fun kunci401DinonaktifkanDiSemuaModelDanLangsungPindahKunciBerikutnya() = runBlocking {
        val specs = listOf(
            KeySpec("#1", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "bad-key", "gemini-2.5-pro"),
            KeySpec("#1", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "bad-key", "gemini-2.5-flash"),
            KeySpec("#2", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "good-key", "gemini-2.5-pro"),
        )
        val pool = KeyPool(specs, rotateEvery = 0, sticky = true)
        val calls = mutableListOf<String>()
        val client = FailoverClient(
            pool = pool,
            factory = { cfg ->
                object : LlmClient {
                    override suspend fun complete(system: String, messages: List<Msg>): String {
                        calls.add("${cfg.apiKey}:${cfg.model}")
                        if (cfg.apiKey == "bad-key") throw LlmException("HTTP 401 unauthorized", retryable = false, code = 401)
                        return "ok"
                    }
                }
            },
        )
        assertEquals("ok", client.complete("sys", listOf(Msg(Role.USER, "tes"))))
        assertEquals(listOf("bad-key:gemini-2.5-pro", "good-key:gemini-2.5-pro"), calls)
    }

    @Test
    fun deteksiKunciMatiMencakupHttp400KhasGeminiDan401() {
        org.junit.Assert.assertTrue(com.build.mythos.cc.isDeadKeyError(400, "API key not valid. Please pass a valid API key."))
        org.junit.Assert.assertTrue(com.build.mythos.cc.isDeadKeyError(400, "API_KEY_EXPIRED"))
        org.junit.Assert.assertTrue(com.build.mythos.cc.isDeadKeyError(401, "Unauthorized"))
        org.junit.Assert.assertFalse(com.build.mythos.cc.isDeadKeyError(400, "Invalid JSON payload"))
        assertEquals(KeyHealthState.ALIVE, classifyKeyProbeResponse(200, "{}").first)
        assertEquals(KeyHealthState.RATE_LIMITED, classifyKeyProbeResponse(429, "rate limit").first)
        assertEquals(KeyHealthState.DEAD, classifyKeyProbeResponse(400, "API_KEY_INVALID").first)
        assertEquals("AIza…7890", maskApiKey("AIzaSyContoh1234567890"))
    }

    @Test
    fun failoverClientMemanggilOnDeadKeySaatGeminiHttp400ApiKeyInvalid() = runBlocking {
        val specs = listOf(
            KeySpec("#1", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "dead-gemini-key", "gemini-2.5-pro"),
            KeySpec("#1", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "dead-gemini-key", "gemini-2.5-flash"),
            KeySpec("#2", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "live-gemini-key", "gemini-2.5-pro"),
        )
        val pool = KeyPool(specs, rotateEvery = 0, sticky = true)
        val reportedDead = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val client = FailoverClient(
            pool = pool,
            factory = { cfg ->
                object : LlmClient {
                    override suspend fun complete(system: String, messages: List<Msg>): String {
                        calls.add("${cfg.apiKey}:${cfg.model}")
                        if (cfg.apiKey == "dead-gemini-key") {
                            throw LlmException("API key not valid. Please pass a valid API key.", retryable = false, code = 400)
                        }
                        return "ok-live"
                    }
                }
            },
            onDeadKey = { k, lbl, _ -> reportedDead.add("$lbl:$k") },
        )
        assertEquals("ok-live", client.complete("sys", listOf(Msg(Role.USER, "tes"))))
        assertEquals(listOf("#1:dead-gemini-key"), reportedDead)
        assertEquals(listOf("dead-gemini-key:gemini-2.5-pro", "live-gemini-key:gemini-2.5-pro"), calls)
    }

    @Test
    fun removeKeysFromRawMenghapusKunciMatiDanMenyisakanKunciAktif() {
        val raw = "AIzaSyKunciSatu1234567890\nAIzaSyKunciMati1234567890\nAIzaSyKunciTiga1234567890"
        val rem = removeKeysFromRaw(raw, setOf("AIzaSyKunciMati1234567890"))
        assertEquals(listOf("AIzaSyKunciSatu1234567890", "AIzaSyKunciTiga1234567890"), rem)
    }
}
