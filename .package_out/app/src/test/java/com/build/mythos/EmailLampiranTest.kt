package com.build.mythos

import java.util.Base64
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Email berlampiran: pembuat MIME multipart, rincian lampiran Gmail, halaman daftar, dan pembantu nama/ukuran berkas. */
class EmailLampiranTest {
    private val pdf = "%PDF-1.4 isi percobaan".toByteArray(Charsets.US_ASCII)
    private val fixedDate = Date(1_700_000_000_000L)

    private fun build(atts: List<MailAttachment>, body: String = "Halo, ini laporannya.") =
        MailBuilder.buildWithAttachments("saya@gmail.com", listOf("budi@contoh.id"), emptyList(), "Laporan", body, atts, fixedDate, "abc-123")

    // ------------------------------------------------ membangun email

    @Test fun tanpaLampiranSamaDenganEmailTeksBiasa() {
        val a = build(emptyList())
        val b = MailBuilder.build("saya@gmail.com", listOf("budi@contoh.id"), emptyList(), "Laporan", "Halo, ini laporannya.", fixedDate, "abc-123")
        assertEquals(b, String(a, Charsets.US_ASCII))
    }

    @Test fun multipartBisaDibacaKembaliOlehParserEmail() {
        val raw = build(listOf(MailAttachment("laporan.pdf", "application/pdf", pdf)))
        val m = Mime.parseMail(raw)
        assertEquals("Laporan", m.subject)
        assertTrue(m.text, m.text.contains("Halo, ini laporannya."))
        assertEquals(listOf("laporan.pdf"), m.attachments)
    }

    @Test fun isiLampiranTersimpanUtuhDalamBase64() {
        val raw = String(build(listOf(MailAttachment("laporan.pdf", "application/pdf", pdf))), Charsets.US_ASCII)
        assertTrue(raw.contains(Base64.getEncoder().encodeToString(pdf)))
        assertTrue(raw.contains("Content-Type: multipart/mixed; boundary=\"=_mythos_abc123\""))
        assertTrue(raw.contains("Content-Disposition: attachment; filename=\"laporan.pdf\""))
        assertTrue(raw.trimEnd().endsWith("--=_mythos_abc123--"))
    }

    @Test fun hasilSelaluAsciiMurniMeskipunNamaDanIsiUnicode() {
        val raw = build(listOf(MailAttachment("laporan tahun ini ✓.pdf", "application/pdf", pdf)), body = "Café — ✓")
        assertTrue("semua byte harus ASCII", raw.all { it.toInt() in 0..127 })
        val s = String(raw, Charsets.US_ASCII)
        assertTrue(s, s.contains("filename*=UTF-8''laporan%20tahun%20ini%20%E2%9C%93.pdf"))
        assertTrue(s, s.contains("name*=UTF-8''laporan%20tahun%20ini%20%E2%9C%93.pdf"))
        assertFalse("nama ASCII cadangan akan menimpa nama asli di sebagian parser", s.contains("filename=\"laporan"))
    }

    @Test fun namaBerkasBerbahayaDiBersihkanDiHeader() {
        val s = String(build(listOf(MailAttachment("..\\..\\x\"\r\nBcc: a@b.com.pdf", "application/pdf", pdf))), Charsets.US_ASCII)
        assertFalse("tidak boleh ada header Bcc hasil suntikan", s.contains("\r\nBcc:"))
    }

    @Test fun tipeBerkasMenyisipkanHeaderDitolak() {
        try {
            build(listOf(MailAttachment("a.pdf", "application/pdf\r\nBcc: x@y.com", pdf)))
            fail("harus ditolak")
        } catch (e: ConnectorException) { /* diharapkan */ }
    }

    @Test fun batasJumlahDanUkuranLampiranDitegakkan() {
        val banyak = (1..11).map { MailAttachment("f$it.txt", "text/plain", ByteArray(10)) }
        try { build(banyak); fail("11 lampiran harus ditolak") } catch (e: ConnectorException) { /* diharapkan */ }
        val besar = listOf(
            MailAttachment("a.bin", "application/octet-stream", ByteArray(10 * 1024 * 1024)),
            MailAttachment("b.bin", "application/octet-stream", ByteArray(10 * 1024 * 1024))
        )
        try { build(besar); fail("20 MB harus ditolak") } catch (e: ConnectorException) { /* diharapkan */ }
    }

    @Test fun tebakTipeBerkas() {
        assertEquals("application/pdf", MailBuilder.guessMime("A.PDF"))
        assertEquals("image/jpeg", MailBuilder.guessMime("foto.jpeg"))
        assertEquals("application/octet-stream", MailBuilder.guessMime("tanpa_ekstensi"))
        assertEquals("application/octet-stream", MailBuilder.guessMime("aneh.xyz"))
    }

    // ------------------------------------------------ nama, daftar, ukuran

    @Test fun namaBerkasAman() {
        assertEquals("passwd", MailBuilder.safeFileName("../../etc/passwd"))
        assertEquals("c.txt", MailBuilder.safeFileName("a\\b\\c.txt"))
        assertEquals("lampiran", MailBuilder.safeFileName("   "))
        assertEquals("lampiran", MailBuilder.safeFileName("../"))
        assertEquals("a_b_c.pdf", MailBuilder.safeFileName("a<b>c.pdf"))
        val panjang = MailBuilder.safeFileName("x".repeat(300) + ".pdf")
        assertTrue(panjang.length <= 120)
        assertTrue(panjang.endsWith(".pdf"))
    }

    @Test fun daftarLampiranDariArgumenModel() {
        assertEquals(emptyList<String>(), parseAttachmentList(""))
        assertEquals(listOf("a.pdf", "out/b.png"), parseAttachmentList("a.pdf; out/b.png"))
        assertEquals(listOf("a.pdf", "b.png"), parseAttachmentList("a.pdf\nb.png\n"))
        assertEquals(listOf("a.pdf", "b.png"), parseAttachmentList("[\"a.pdf\", \"b.png\"]"))
        assertEquals(listOf("a.pdf"), parseAttachmentList("a.pdf;a.pdf"))
    }

    @Test fun ukuranBerkasRamahDibaca() {
        assertEquals("512 B", fmtFileSize(512))
        assertEquals("48 KB", fmtFileSize(48 * 1024L))
        assertEquals("3.2 MB", fmtFileSize((3.2 * 1024 * 1024).toLong() + 1000))
        assertEquals("ukuran tidak diketahui", fmtFileSize(-1))
    }

    // ------------------------------------------------ Gmail API: lampiran, folder, halaman

    private fun b64url(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test fun rincianLampiranGmailMemuatIdUkuranDanTipe() {
        val msg = JSONObject().put("id", "1935ab12cd34ef56").put("payload", JSONObject()
            .put("headers", JSONArray().put(JSONObject().put("name", "Cc").put("value", "tim@contoh.id")))
            .put("parts", JSONArray()
                .put(JSONObject().put("mimeType", "text/plain").put("body", JSONObject().put("data", b64url("Isi surat"))))
                .put(JSONObject().put("mimeType", "application/pdf").put("filename", "tagihan.pdf")
                    .put("body", JSONObject().put("attachmentId", "ANGjdJ_abc-123").put("size", 48213)))))
        val m = GmailApiClient.parseFullMessage(msg)
        assertEquals(1, m.attachmentInfo.size)
        val a = m.attachmentInfo[0]
        assertEquals("tagihan.pdf", a.filename)
        assertEquals("application/pdf", a.mimeType)
        assertEquals(48213L, a.size)
        assertEquals("ANGjdJ_abc-123", a.attachmentId)
        assertEquals("tim@contoh.id", m.cc)
        assertEquals("Isi surat", m.text)
    }

    @Test fun lampiranTeksTidakMasukKeIsiEmail() {
        val msg = JSONObject().put("id", "1935ab12cd34ef56").put("payload", JSONObject()
            .put("parts", JSONArray()
                .put(JSONObject().put("mimeType", "text/plain").put("body", JSONObject().put("data", b64url("Isi surat"))))
                .put(JSONObject().put("mimeType", "text/plain").put("filename", "catatan.txt")
                    .put("body", JSONObject().put("data", b64url("ISI LAMPIRAN RAHASIA")).put("attachmentId", "ATT1").put("size", 20)))))
        val m = GmailApiClient.parseFullMessage(msg)
        assertEquals("Isi surat", m.text)
        assertEquals(listOf("catatan.txt"), m.attachments)
    }

    @Test fun folderDrafDanPentingDipetakanKeLabelGmail() {
        assertEquals("DRAFT", GmailApiClient.labelForFolder("draf"))
        assertEquals("DRAFT", GmailApiClient.labelForFolder("DRAFT"))
        assertEquals("SENT", GmailApiClient.labelForFolder("terkirim"))
        assertEquals("IMPORTANT", GmailApiClient.labelForFolder("penting"))
        assertNull(GmailApiClient.labelForFolder("semua"))
    }

    @Test fun tokenHalamanDibersihkanDanDivalidasi() {
        assertEquals("", GmailApiClient.cleanPageToken(""))
        assertEquals("09876543210123456789", GmailApiClient.cleanPageToken(" \"09876543210123456789\" "))
        assertNotNull(GmailApiClient.cleanPageToken("abc_DEF-123="))
        assertNull(GmailApiClient.cleanPageToken("../../etc"))
        assertNull(GmailApiClient.cleanPageToken("a&b=c"))
    }

    @Test fun alatLampiranTerdaftarSebagaiAlatEmail() {
        assertTrue("email_lampiran" in CONNECTOR_TOOL_NAMES)
        assertEquals("email", connectorOf("email_lampiran"))
    }

    // ------------------------------------------------ perbaikan audit

    @Test fun lampiranKecilTertanamDiBadanPesanBisaDiambilTanpaUnduh() {
        val msg = JSONObject().put("id", "1935ab12cd34ef56").put("payload", JSONObject()
            .put("parts", JSONArray()
                .put(JSONObject().put("mimeType", "text/plain").put("body", JSONObject().put("data", b64url("Isi surat"))))
                .put(JSONObject().put("mimeType", "text/plain").put("filename", "kecil.txt")
                    .put("body", JSONObject().put("data", b64url("halo lampiran")).put("size", 13)))))
        val m = GmailApiClient.parseFullMessage(msg)
        assertEquals("Isi surat", m.text)
        assertEquals(listOf("kecil.txt"), m.attachments)
        assertEquals(1, m.attachmentInfo.size)
        val a = m.attachmentInfo[0]
        assertEquals("", a.attachmentId)
        assertEquals(13L, a.size)
        assertEquals("halo lampiran", String(a.inlineData!!, Charsets.UTF_8))
    }

    @Test fun lampiranBesarTetapMemakaiAttachmentIdTanpaIsiTertanam() {
        val msg = JSONObject().put("id", "1935ab12cd34ef56").put("payload", JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("mimeType", "application/pdf").put("filename", "besar.pdf")
                .put("body", JSONObject().put("attachmentId", "ATT9").put("size", 999)))))
        val a = GmailApiClient.parseFullMessage(msg).attachmentInfo.single()
        assertEquals("ATT9", a.attachmentId)
        assertNull(a.inlineData)
    }

    @Test fun folderTakDikenalDiketahuiTetapiNamaUmumDiterima() {
        assertTrue(GmailApiClient.isKnownFolder(""))
        assertTrue(GmailApiClient.isKnownFolder(" Inbox "))
        assertTrue(GmailApiClient.isKnownFolder("[Gmail]/All Mail"))
        assertTrue(GmailApiClient.isKnownFolder("starred"))
        assertFalse(GmailApiClient.isKnownFolder("Inbx"))
        assertFalse(GmailApiClient.isKnownFolder("Kerja"))
    }

    @Test fun setiapNamaFolderDikenalPunyaPemetaanLabelAtauSemuaSurat() {
        for (f in listOf("inbox", "sent", "draft", "spam", "trash", "starred", "important")) {
            assertNotNull(f, GmailApiClient.labelForFolder(f))
        }
        for (f in listOf("all", "semua", "all mail")) assertNull(GmailApiClient.labelForFolder(f))
    }
}
