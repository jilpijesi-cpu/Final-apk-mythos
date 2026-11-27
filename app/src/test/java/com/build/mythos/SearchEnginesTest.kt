package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

// CATATAN: fixture HTML di bawah ditulis tangan berdasarkan bentuk halaman yang diketahui, BUKAN tangkapan asli dari tiap mesin.
// Tes ini membuktikan logika parser dan urutan cadangan; kecocokan dengan halaman asli hanya bisa dipastikan di perangkat.
class SearchEnginesTest {
    @Before fun reset() = SearchEngines.resetCooldown()

    private fun b64url(s: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test fun ddgLiteMemasangkanCuplikanDanMelewatiIklan() {
        val html = """
            <table>
            <tr><td>1.&nbsp;</td><td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fcs&amp;rut=a" class='result-link'>Kontak <b>Resmi</b></a></td></tr>
            <tr><td>&nbsp;</td><td class='result-snippet'>Nomor <b>CS</b> resmi &amp; jam kerja.</td></tr>
            <tr><td>2.&nbsp;</td><td><a rel="nofollow" href="https://duckduckgo.com/y.js?ad=1" class='result-link'>Iklan</a></td></tr>
            <tr><td>&nbsp;</td><td class='result-snippet'>Cuplikan iklan.</td></tr>
            <tr><td>3.&nbsp;</td><td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fcontoh.id%2Fberita&amp;rut=b" class='result-link'>Berita</a></td></tr>
            </table>
        """.trimIndent()
        val r = SearchEngines.parseDdgLite(html)
        assertEquals(2, r.size)
        assertEquals("https://example.com/cs", r[0].url)
        assertEquals("Kontak Resmi", r[0].title)
        assertEquals("Nomor CS resmi & jam kerja.", r[0].snippet)
        assertEquals("https://contoh.id/berita", r[1].url)
        assertEquals("", r[1].snippet)
    }

    @Test fun bingMembukaTautanPengalihanBase64() {
        val target = "https://example.com/a?b=1&c=2"
        val href = "https://www.bing.com/ck/a?!&amp;&amp;p=abc&amp;u=a1${b64url(target)}&amp;ntb=1"
        val html = """<li class="b_algo"><h2><a href="$href" h="ID=SERP,5">Kontak <strong>Resmi</strong></a></h2>
            <div class="b_caption"><p class="b_lineclamp2">Nomor CS resmi.</p></div></li>
            <li class="b_algo"><h2><a href="https://contoh.id/langsung">Langsung</a></h2></li>"""
        val r = SearchEngines.parseBing(html)
        assertEquals(2, r.size)
        assertEquals(target, r[0].url)
        assertEquals("Kontak Resmi", r[0].title)
        assertEquals("Nomor CS resmi.", r[0].snippet)
        assertEquals("https://contoh.id/langsung", r[1].url)
    }

    @Test fun bingMenolakTautanInternalDanPengalihanBukanHttp() {
        assertNull(SearchEngines.unwrapBing("https://www.bing.com/images/search?q=x"))
        assertNull(SearchEngines.unwrapBing("https://www.bing.com/ck/a?u=a1${b64url("ftp://x.y/z")}"))
        assertNull(SearchEngines.unwrapBing("/relatif"))
    }

    @Test fun braveMembacaBlokWebDanMelewatiTautanBrave() {
        val html = """
            <div class="snippet svelte-abc" data-pos="1" data-type="web"><div class="result-wrapper">
              <a href="https://search.brave.com/goggles" class="x">Goggles</a>
              <a href="https://example.com/cs" class="l1"><div class="title search-snippet-title">Kontak <b>Resmi</b></div></a>
              <div class="snippet-description">Nomor CS resmi.</div></div></div>
            <div class="snippet" data-type="videos"><a href="https://video.example/v">Video</a></div>
        """.trimIndent()
        val r = SearchEngines.parseBrave(html)
        assertEquals(1, r.size)
        assertEquals("https://example.com/cs", r[0].url)
        assertEquals("Kontak Resmi", r[0].title)
        assertEquals("Nomor CS resmi.", r[0].snippet)
    }

    @Test fun yahooMembukaTautanRU() {
        assertEquals(
            "https://contoh.id/berita",
            SearchEngines.unwrapYahoo("https://r.search.yahoo.com/_ylt=x/RV=2/RE=1/RO=10/RU=https%3a%2f%2fcontoh.id%2fberita/RK=2/RS=abc-")
        )
        assertNull(SearchEngines.unwrapYahoo("https://search.yahoo.com/search?p=x"))
        val html = """<div class="algo"><div class="compTitle"><a href="https://r.search.yahoo.com/_ylt=x/RV=2/RO=10/RU=https%3a%2f%2fexample.com%2fcs/RK=2/RS=abc-" class="d-ib"><span>example.com</span><h3 class="title"><span>Kontak Resmi</span></h3></a></div>
            <div class="compText aAbs"><p>Nomor CS resmi.</p></div></div>"""
        val r = SearchEngines.parseYahoo(html)
        assertEquals(1, r.size)
        assertEquals("https://example.com/cs", r[0].url)
        assertEquals("Kontak Resmi", r[0].title)
        assertEquals("Nomor CS resmi.", r[0].snippet)
    }

    @Test fun mojeekMembacaJudulDanCuplikan() {
        val html = """<ul class="results-standard"><li><a class="ob" href="https://example.com/cs">example.com</a>
            <h2><a class="title" href="https://example.com/cs">Kontak Resmi</a></h2><p class="s">Nomor <b>CS</b> resmi.</p></li></ul>"""
        val r = SearchEngines.parseMojeek(html)
        assertEquals(1, r.size)
        assertEquals("Kontak Resmi", r[0].title)
        assertEquals("Nomor CS resmi.", r[0].snippet)
    }

    @Test fun wikipediaDanMarginaliaMembacaJson() {
        val wiki = """{"query":{"search":[{"title":"Contoh (uji)","snippet":"<span class=\"searchmatch\">Contoh</span> adalah halaman uji."},{"title":""}]}}"""
        val w = SearchEngines.parseWikipedia(wiki, "id")
        assertEquals(1, w.size)
        assertEquals("https://id.wikipedia.org/wiki/Contoh_%28uji%29", w[0].url)
        assertEquals("Contoh adalah halaman uji.", w[0].snippet)
        assertTrue(SearchEngines.parseWikipedia("bukan json", "id").isEmpty())

        val marg = """{"results":[{"url":"https://kecil.example/a","title":"Situs <b>Kecil</b>","description":"Uraian."},{"url":"javascript:x","title":"Jahat"}]}"""
        val m = SearchEngines.parseMarginalia(marg)
        assertEquals(1, m.size)
        assertEquals("Situs Kecil", m[0].title)
        assertTrue(SearchEngines.parseMarginalia("{}").isEmpty())
    }

    @Test fun hasilJaringanLokalDibuang() {
        val html = """<li class="b_algo"><h2><a href="http://192.168.0.1/admin">Router</a></h2></li>"""
        assertTrue(SearchEngines.parseBing(html).isEmpty())
    }

    @Test fun pindahKeMesinBerikutnyaBilaYangPertamaGagalAtauKosong() {
        val wiki = """{"query":{"search":[{"title":"Contoh","snippet":"Isi."}]}}"""
        val asked = mutableListOf<String>()
        val out = SearchEngines.search("contoh", { url ->
            asked.add(url)
            when {
                url.contains("html.duckduckgo.com") -> throw IOException("putus")
                url.contains("id.wikipedia.org") -> wiki
                else -> "<html></html>"
            }
        })
        assertEquals("Wikipedia (id)", out.engine)
        assertEquals(1, out.results.size)
        assertTrue(out.notes.any { it.startsWith("DuckDuckGo:") })
        assertTrue(out.notes.any { it.contains("tidak ada hasil") })
        assertNotNull(asked.firstOrNull { it.contains("bing.com") })
    }

    @Test fun mesinYangBaruGagalDilewatiSelamaPendinginan() {
        val wiki = """{"query":{"search":[{"title":"Contoh","snippet":"Isi."}]}}"""
        var ddgCalls = 0
        val fetch = { url: String ->
            when {
                url.contains("html.duckduckgo.com") -> { ddgCalls++; throw IOException("putus") }
                url.contains("id.wikipedia.org") -> wiki
                else -> "<html></html>"
            }
        }
        SearchEngines.search("contoh", fetch)
        val second = SearchEngines.search("contoh", fetch)
        assertEquals(1, ddgCalls)
        assertTrue(second.notes.any { it.contains("dilewati") })
    }

    @Test fun tlsGagalDiTigaMesinMenghentikanPencarianDanMenjelaskan() {
        var calls = 0
        val out = SearchEngines.search("contoh", { calls++; throw SSLHandshakeException("Chain validation failed") })
        assertNull(out.engine)
        assertEquals(3, calls)
        assertTrue(out.notes.last().contains("perangkat"))
    }

    @Test fun anggaranWaktuMenghentikanPencarian() {
        var t = 0L
        val engines = listOf(
            SearchEngines.Engine("A", { it }, { emptyList() }),
            SearchEngines.Engine("B", { it }, { emptyList() })
        )
        val out = SearchEngines.search("x", { throw IOException("lambat") }, { t += 30_000; t }, engines)
        assertNull(out.engine)
        assertTrue(out.notes.any { it.contains("waktu habis") })
    }

    @Test fun galatTlsMenampilkanRantaiPenyebab() {
        val e = SSLHandshakeException("Chain validation failed").apply {
            initCause(java.security.cert.CertPathValidatorException("timestamp check failed"))
        }
        val m = Cx.friendly(e)
        assertTrue(m.contains("Chain validation failed"))
        assertTrue(m.contains("timestamp check failed"))
    }

    @Test fun rssMembacaBingDanGoogleNewsSertaMembuangAlamatLokal() {
        val target = "https://www.bca.co.id/id/tentang-bca/hubungi-kami"
        val bingLink = "https://www.bing.com/ck/a?u=a1${b64url(target)}&amp;ntb=1"
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0"><channel>
              <item>
                <title><![CDATA[Hubungi Kami &amp; Halo BCA 1500888]]></title>
                <link>$bingLink</link>
                <description><![CDATA[Layanan resmi <b>Halo BCA</b> 1500888.]]></description>
              </item>
              <item>
                <title>Berita Resmi</title>
                <link>https://contoh.id/halo-bca</link>
                <description>Informasi resmi &amp; kontak.</description>
              </item>
              <item>
                <title>Lokal</title>
                <link>http://127.0.0.1/admin</link>
                <description>Harus dibuang.</description>
              </item>
            </channel></rss>
        """.trimIndent()
        val r = SearchEngines.parseRss(xml)
        assertEquals(2, r.size)
        assertEquals(target, r[0].url)
        assertEquals("Hubungi Kami & Halo BCA 1500888", r[0].title)
        assertEquals("Layanan resmi Halo BCA 1500888.", r[0].snippet)
        assertEquals("https://contoh.id/halo-bca", r[1].url)
    }

    @Test fun bingMembacaTautanDiDalamSpanPadaH2() {
        val html = """<li class="b_algo"><h2><span><a href="https://contoh.id/nested">Judul Nested</a></span></h2><p>Cuplikan.</p></li>"""
        val r = SearchEngines.parseBing(html)
        assertEquals(1, r.size)
        assertEquals("https://contoh.id/nested", r[0].url)
        assertEquals("Judul Nested", r[0].title)
    }
}
