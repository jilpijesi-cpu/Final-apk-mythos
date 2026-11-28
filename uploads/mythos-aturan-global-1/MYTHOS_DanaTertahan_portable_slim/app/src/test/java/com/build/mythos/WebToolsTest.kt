package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class WebToolsTest {
    @Test fun queryRejectsPersonalData() {
        assertNull(WebTools.checkQuery("nomor resmi CS Ultra Voucher"))
        assertNull(WebTools.checkQuery("penipuan voucher 2026"))
        assertNotNull(WebTools.checkQuery("pengguna.uji@example.com akun ditangguhkan"))
        assertNotNull(WebTools.checkQuery("NIK 1234567890123456"))
        assertNotNull(WebTools.checkQuery("0812-0000-0000 penipu"))
        assertNotNull(WebTools.checkQuery("a"))
        assertNotNull(WebTools.checkQuery("x".repeat(201)))
    }

    @Test fun urlAllowsPublicAndBlocksLocal() {
        assertNull(WebTools.checkUrl("https://example.com/halaman?q=1"))
        assertNull(WebTools.checkUrl("http://example.com"))
        assertNotNull(WebTools.checkUrl("file:///etc/passwd"))
        assertNotNull(WebTools.checkUrl("ftp://example.com"))
        assertNotNull(WebTools.checkUrl("http://localhost:8080"))
        assertNotNull(WebTools.checkUrl("http://127.0.0.1/"))
        assertNotNull(WebTools.checkUrl("http://192.168.1.1/"))
        assertNotNull(WebTools.checkUrl("http://10.0.0.5/"))
        assertNotNull(WebTools.checkUrl("http://169.254.169.254/latest/meta-data"))
        assertNotNull(WebTools.checkUrl("http://2130706433/"))
        assertNotNull(WebTools.checkUrl("http://[::1]/"))
        assertNotNull(WebTools.checkUrl("https://user:pass@example.com/"))
        assertNotNull(WebTools.checkUrl("https://example.com:8443/"))
        assertNotNull(WebTools.checkUrl("https://router.local/"))
        assertNotNull(WebTools.checkUrl("tidak valid"))
    }

    @Test fun resolvedPrivateAddressesAreBlocked() {
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("0.0.0.0")))
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("224.0.0.1")))
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("fc00::1")))
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("fd00::1")))
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("::1")))
        assertTrue(WebTools.isPrivateAddress(InetAddress.getByName("fe80::1")))
        assertFalse(WebTools.isPrivateAddress(InetAddress.getByName("93.184.216.34")))
        assertFalse(WebTools.isPrivateAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun htmlToTextDropsScriptsAndDecodes() {
        val html = "<html><head><title>Judul &amp; Tes</title></head><body><script>alert(1)</script>" +
            "<style>.a{}</style><h1>Halo</h1><p>Isi &quot;satu&quot;&nbsp;dua</p><p>Baris&#33; &#x41;</p></body></html>"
        val t = WebTools.htmlToText(html)
        assertFalse(t.contains("alert"))
        assertFalse(t.contains(".a{}"))
        assertTrue(t.contains("Halo"))
        assertTrue(t.contains("Isi \"satu\" dua"))
        assertTrue(t.contains("Baris! A"))
        assertEquals("Judul & Tes", WebTools.titleOf(html))
    }

    @Test fun htmlToTextIsCapped() {
        val t = WebTools.htmlToText("<p>" + "x".repeat(20_000) + "</p>", 500)
        assertTrue(t.length < 600)
    }

    @Test fun parsesDuckDuckGoResultsAndSkipsAds() {
        val html = """
            <div class="result results_links">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fcs&amp;rut=abc">Kontak <b>Resmi</b> Example</a>
              <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fcs">Nomor CS resmi &amp; jam operasional.</a>
            </div>
            <div class="result result--ad">
              <a class="result__a" href="https://duckduckgo.com/y.js?ad_provider=x">Iklan</a>
            </div>
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=http%3A%2F%2F192.168.0.1%2Fadmin&amp;rut=z">Router</a>
            </div>
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fcontoh.id%2Fberita&amp;rut=q">Berita Penipuan</a>
              <a class="result__snippet" href="x">Waspada modus baru.</a>
            </div>
        """.trimIndent()
        val r = WebTools.parseResults(html)
        assertEquals(2, r.size)
        assertEquals("https://example.com/cs", r[0].url)
        assertEquals("Kontak Resmi Example", r[0].title)
        assertEquals("Nomor CS resmi & jam operasional.", r[0].snippet)
        assertEquals("https://contoh.id/berita", r[1].url)
        assertTrue(WebTools.formatResults(r).startsWith("1. Kontak Resmi Example"))
    }

    @Test fun emptyHtmlGivesNoResults() {
        assertTrue(WebTools.parseResults("<html><body>Tidak ada</body></html>").isEmpty())
    }

    @Test fun pinnedUrlMemakaiIpTervalidasiDanMempertahankanBagianLain() {
        val u = java.net.URL("https://contoh.id:8443/a/b?x=1&y=2")
        val v4 = InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()))
        assertEquals("https://93.184.216.34:8443/a/b?x=1&y=2", WebTools.pinnedUrl(u, v4).toString())
        val u2 = java.net.URL("https://contoh.id/p")
        assertEquals("https://93.184.216.34/p", WebTools.pinnedUrl(u2, v4).toString())
    }

    @Test fun pinnedUrlIpv6DiberiKurungSiku() {
        val u = java.net.URL("https://contoh.id/p")
        val v6 = InetAddress.getByAddress(ByteArray(16) { if (it == 0) 0x26 else if (it == 1) 0x06 else if (it == 15) 1 else 0 })
        val out = WebTools.pinnedUrl(u, v6).toString()
        assertTrue(out.startsWith("https://[") && out.endsWith("]/p"))
        assertFalse(out.contains("%"))
    }
}
