package com.build.mythos

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test regresi untuk temuan audit menyeluruh (C-02, H-03, H-04, M-08). */
class AuditRemediationTest {

    @Test fun findMultiExecDestruktifTidakLolosSebagaiReadAtauSafe() {
        val cmd = "find . -exec echo {} \\; -exec rm -rf {} \\;"
        assertEquals(Risk.DELETE, classify(cmd))
        val assessment = assessCommand(cmd)
        assertTrue("find multi-exec destruktif tidak boleh SAFE", assessment.decision != Decision.SAFE)
    }

    @Test fun redirectKeDevNullTxtTetapDianggapWrite() {
        assertEquals(Risk.WRITE, classify("echo rahasia > /dev/null.txt"))
        assertEquals(Risk.READ, classify("echo halo > /dev/null"))
    }

    @Test fun redactSecretsMenyamarkanFormatJsonDanHeaderSensitif() {
        val raw = """{"token": "tok_rahasia_999", "apiKey": "key_rahasia_888", "cookie": "sid=abc123"}"""
        val out = redactSecrets(raw)
        assertFalse(out.contains("tok_rahasia_999"))
        assertFalse(out.contains("key_rahasia_888"))
        assertFalse(out.contains("sid=abc123"))
    }

    @Test fun webToolsMemblokirIpv6UniqueLocalAddress() {
        val ula = InetAddress.getByName("fd00::1")
        assertTrue("IPv6 ULA fd00::/8 wajib dianggap alamat privat", WebTools.isPrivateAddress(ula))
        val ulaFc = InetAddress.getByName("fc12::34")
        assertTrue("IPv6 ULA fc00::/7 wajib dianggap alamat privat", WebTools.isPrivateAddress(ulaFc))
    }

    @Test fun outputLimiterTidakMembelahKarakterUtf8Multibyte() {
        val src = "halo ✓ dunia" // '✓' adalah 3 byte UTF-8 (indeks 5..7)
        val (cut, truncated) = OutputLimiter.limit(src, 6)
        assertTrue(truncated)
        assertFalse("Hasil potongan tidak boleh memuat karakter rusak U+FFFD", cut.contains('\uFFFD'))
    }
    @Test fun openAiNativeBodyHilangkanContentKosongSaatToolCalls() {
        val cfg = com.build.mythos.cc.Presets.openAi("k", "amazon/nova-lite-v1")
        val msgs = listOf(
            com.build.mythos.cc.Msg(com.build.mythos.cc.Role.USER, "Cek email"),
            com.build.mythos.cc.Msg(
                com.build.mythos.cc.Role.ASSISTANT,
                "<tool_use>{\"id\":\"call_1\",\"name\":\"email_daftar\",\"input\":{\"cari\":\"semua\"}}</tool_use>"
            ),
            com.build.mythos.cc.Msg(
                com.build.mythos.cc.Role.USER,
                "<tool_result id=\"call_1\" name=\"email_daftar\" ok=\"true\">Daftar kosong</tool_result>"
            )
        )
        val body = com.build.mythos.cc.openAiNativeBody(cfg, "sys", msgs, emptyList())
        val arr = body.getJSONArray("messages")
        val assistantMsg = arr.getJSONObject(2)
        assertEquals("assistant", assistantMsg.getString("role"))
        assertFalse("content kosong tidak boleh dikirim bersama tool_calls", assistantMsg.has("content"))
        assertEquals(1, assistantMsg.getJSONArray("tool_calls").length())
    }

    @Test fun bridgeToolNativeDescriptionBersihTanpaPrefiksJson() {
        val schema = org.json.JSONObject().put("type", "object").put("properties", org.json.JSONObject())
        val tool = com.build.mythos.cc.AppBridgeTool(
            "email_daftar",
            "Daftar email di kotak surat pengguna.",
            schema,
            com.build.mythos.cc.Effect.READ,
            1_000L
        ) { true to "" }
        val spec = com.build.mythos.cc.ToolSchemas.specs(com.build.mythos.cc.ToolRegistry(listOf(tool))).single()
        assertFalse(spec.description.startsWith("email_daftar {"))
        assertEquals("Daftar email di kotak surat pengguna.", spec.description)
    }
    @Test fun gmailApiNormalizeQueryDanIdBersih() {
        assertEquals("", GmailApiClient.normalizeQuery(""))
        assertEquals("", GmailApiClient.normalizeQuery("semua"))
        assertEquals("", GmailApiClient.normalizeQuery("\"semua\""))
        assertEquals("", GmailApiClient.normalizeQuery("terbaru"))
        assertEquals("", GmailApiClient.normalizeQuery("all"))
        assertEquals("is:unread", GmailApiClient.normalizeQuery("belum_dibaca"))
        assertEquals("is:unread", GmailApiClient.normalizeQuery("unread"))
        assertEquals("from:GoDaddy", GmailApiClient.normalizeQuery("dari:GoDaddy"))
        assertEquals("subject:tagihan", GmailApiClient.normalizeQuery("subjek:tagihan"))
        assertEquals("INBOX", GmailApiClient.labelForFolder(""))
        assertEquals(null, GmailApiClient.labelForFolder("[Gmail]/All Mail"))
        assertEquals("1935ab12cd34ef56", GmailApiClient.cleanMessageId("[id 1935ab12cd34ef56]"))
        assertEquals("1935ab12cd34ef56", GmailApiClient.cleanMessageId("1935ab12cd34ef56"))
        assertEquals(null, GmailApiClient.cleanMessageId("../../etc/passwd"))
    }

    @Test fun gmailApiParseFullMessageMultipartBase64Url() {
        val bodyB64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("Halo dari Gmail API".toByteArray(Charsets.UTF_8))
        val msg = org.json.JSONObject()
            .put("id", "1935ab12cd34ef56")
            .put("snippet", "Halo singkat")
            .put("payload", org.json.JSONObject()
                .put("headers", org.json.JSONArray()
                    .put(org.json.JSONObject().put("name", "From").put("value", "GoDaddy <info@godaddy.com>"))
                    .put(org.json.JSONObject().put("name", "To").put("value", "user@gmail.com"))
                    .put(org.json.JSONObject().put("name", "Subject").put("value", "Ringkasan November"))
                    .put(org.json.JSONObject().put("name", "Date").put("value", "Mon, 23 Nov 2026 07:38:00 +0700")))
                .put("parts", org.json.JSONArray()
                    .put(org.json.JSONObject().put("mimeType", "text/plain").put("body", org.json.JSONObject().put("data", bodyB64)))
                    .put(org.json.JSONObject().put("mimeType", "application/pdf").put("filename", "bukti.pdf"))))
        val parsed = GmailApiClient.parseFullMessage(msg)
        assertEquals("GoDaddy <info@godaddy.com>", parsed.from)
        assertEquals("Ringkasan November", parsed.subject)
        assertEquals("Halo dari Gmail API", parsed.text)
        assertEquals(listOf("bukti.pdf"), parsed.attachments)
    }
}
