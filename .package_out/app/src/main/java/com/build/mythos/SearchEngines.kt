package com.build.mythos

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

// Daftar mesin pencari GRATIS tanpa kunci API untuk WebSearch/cari_web, dengan urutan cadangan.
// Bagian MURNI tanpa android.* (bisa diuji sebagai tes JVM, lihat SearchEnginesTest); jaringan disuntikkan lewat parameter fetch.
//
// Cara kerja: mesin dicoba berurutan sampai ada yang mengembalikan hasil. Mesin yang gagal (galat jaringan/TLS) dilewati
// selama COOLDOWN_MS supaya pencarian berikutnya tidak membuang waktu. Bila tiga mesin berbeda gagal dengan galat TLS,
// pencarian dihentikan: itu hampir pasti masalah perangkat (jam, sertifikat root, jaringan), bukan mesinnya.
//
// CATATAN: parser HTML (Bing, Brave, Yahoo, Mojeek, DuckDuckGo Lite) membaca halaman yang bisa berubah tanpa pemberitahuan.
// Itu sebabnya ada banyak mesin: parser yang rusak hanya menghasilkan daftar kosong, lalu mesin berikutnya dicoba.
// Mesin yang sengaja TIDAK dipasang: Google, Yandex, Baidu, Ecosia, Startpage, Qwant (captcha/JS/token), dan SearXNG
// publik (JSON biasanya dimatikan dan bot diblokir). Mesin berkunci (Brave API, Tavily, Serper, Google CSE) belum ada.

internal object SearchEngines {
    const val COOLDOWN_MS = 45_000L
    const val BUDGET_MS = 40_000L
    private const val TLS_STOP_AFTER = 3

    class Engine(
        val name: String,
        val url: (String) -> String,
        val parse: (String) -> List<WebTools.Result>
    )

    class Outcome(val engine: String?, val results: List<WebTools.Result>, val notes: List<String>)

    private fun enc(q: String): String = URLEncoder.encode(q, "UTF-8")
    private fun encPath(q: String): String = enc(q).replace("+", "%20")

    val ALL: List<Engine> = listOf(
        Engine("Bing RSS", { "https://www.bing.com/search?q=${enc(it)}&setlang=id&cc=ID&format=rss" }, { parseRss(it) }),
        Engine("DuckDuckGo", { "https://html.duckduckgo.com/html/?q=${enc(it)}&kl=id-id" }, { WebTools.parseResults(it) }),
        Engine("DuckDuckGo Lite", { "https://lite.duckduckgo.com/lite/?q=${enc(it)}&kl=id-id" }, { parseDdgLite(it) }),
        Engine("Bing", { "https://www.bing.com/search?q=${enc(it)}&setlang=id&cc=ID" }, { parseBing(it) }),
        Engine("Brave", { "https://search.brave.com/search?q=${enc(it)}&source=web" }, { parseBrave(it) }),
        Engine("Yahoo", { "https://search.yahoo.com/search?p=${enc(it)}" }, { parseYahoo(it) }),
        Engine("Mojeek", { "https://www.mojeek.com/search?q=${enc(it)}" }, { parseMojeek(it) }),
        Engine("Google News RSS", { "https://news.google.com/rss/search?q=${enc(it)}&hl=id&gl=ID&ceid=ID:id" }, { parseRss(it) }),
        Engine(
            "Wikipedia (id)",
            { "https://id.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(it)}&srlimit=6&format=json&utf8=1" },
            { parseWikipedia(it, "id") }
        ),
        Engine(
            "Wikipedia (en)",
            { "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(it)}&srlimit=6&format=json&utf8=1" },
            { parseWikipedia(it, "en") }
        ),
        Engine("Marginalia", { "https://api.marginalia.nu/public/search/${encPath(it)}?count=6" }, { parseMarginalia(it) })
    )

    private val cooldown = ConcurrentHashMap<String, Long>()

    /** Untuk tes: lupakan semua mesin yang sedang "didinginkan". */
    fun resetCooldown() = cooldown.clear()

    /**
     * Coba mesin satu per satu sampai ada hasil. [fetch] mengambil isi sebuah URL (melempar bila gagal).
     * Outcome.engine null berarti semua gagal; Outcome.notes memuat alasan tiap mesin yang dicoba.
     */
    fun search(
        q: String,
        fetch: (String) -> String,
        now: () -> Long = { System.currentTimeMillis() },
        engines: List<Engine> = ALL
    ): Outcome {
        val start = now()
        val notes = mutableListOf<String>()
        var tlsFails = 0
        for (e in engines) {
            if (now() - start > BUDGET_MS) {
                notes.add("waktu habis sebelum mencoba mesin berikutnya")
                break
            }
            val until = cooldown[e.name]
            if (until != null && now() < until) {
                notes.add("${e.name}: dilewati (baru saja gagal)")
                continue
            }
            try {
                val res = e.parse(fetch(e.url(q)))
                if (res.isNotEmpty()) {
                    cooldown.remove(e.name)
                    return Outcome(e.name, res, notes)
                }
                notes.add("${e.name}: tidak ada hasil yang terbaca")
            } catch (ex: Exception) {
                cooldown[e.name] = now() + COOLDOWN_MS
                notes.add("${e.name}: ${Cx.friendly(ex)}")
                if (generateSequence<Throwable>(ex) { it.cause }.any { it is SSLException }) {
                    tlsFails++
                    if (tlsFails >= TLS_STOP_AFTER) {
                        notes.add("TLS gagal di $TLS_STOP_AFTER mesin berbeda: kemungkinan besar jam, sertifikat root, atau jaringan perangkat, bukan mesinnya")
                        break
                    }
                }
            }
        }
        return Outcome(null, emptyList(), notes)
    }

    // ------------------------------------------------------------ alat bantu parser

    private val ANCHOR = Regex("(?is)<a\\b([^>]*)>(.*?)</a>")
    private val HREF = Regex("(?i)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val TAGS = Regex("(?s)<[^>]*>")
    private val SPACES = Regex("\\s+")

    private class Hit(val title: String, val url: String?, val start: Int, val end: Int)

    private fun hrefOf(attrs: String): String? {
        val m = HREF.find(attrs) ?: return null
        return WebTools.decodeEntities(m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
    }

    private fun text(s: String): String =
        WebTools.decodeEntities(s.replace(TAGS, "")).replace(SPACES, " ").trim()

    private fun hostOf(url: String): String =
        try { URI(url).host?.lowercase().orEmpty() } catch (e: Exception) { "" }

    private fun classHas(attrs: String, word: String): Boolean =
        Regex("(?i)\\bclass\\s*=\\s*[\"'][^\"']*\\b" + Regex.escape(word) + "\\b[^\"']*[\"']").containsMatchIn(attrs)

    private fun finish(list: List<WebTools.Result>): List<WebTools.Result> =
        list.filter { WebTools.checkUrl(it.url) == null && it.title.isNotBlank() }
            .distinctBy { it.url }
            .take(WebTools.MAX_RESULTS)

    /** Tiap Hit dipasangkan dengan cuplikan pertama yang muncul SETELAH tautannya dan SEBELUM tautan berikutnya. */
    private fun withSnippets(html: String, hits: List<Hit>, snip: Regex): List<WebTools.Result> {
        val out = ArrayList<WebTools.Result>()
        for ((i, h) in hits.withIndex()) {
            val url = h.url ?: continue
            val limit = if (i + 1 < hits.size) hits[i + 1].start else html.length
            val sm = snip.find(html, h.end)
            val snippet = if (sm != null && sm.range.first < limit) text(sm.groupValues[1]) else ""
            out.add(WebTools.Result(h.title.take(160), url, snippet.take(300)))
        }
        return finish(out)
    }

    // ------------------------------------------------------------ DuckDuckGo Lite (lite.duckduckgo.com/lite)

    fun parseDdgLite(html: String): List<WebTools.Result> {
        val hits = ANCHOR.findAll(html)
            .filter { classHas(it.groupValues[1], "result-link") }
            .map { m ->
                val href = hrefOf(m.groupValues[1])
                Hit(text(m.groupValues[2]), href?.let { WebTools.unwrapDdg(it) }, m.range.first, m.range.last + 1)
            }.toList()
        return withSnippets(html, hits, Regex("(?is)<td[^>]*class\\s*=\\s*[\"']?result-snippet[\"']?[^>]*>(.*?)</td>"))
    }

    // ------------------------------------------------------------ Bing (www.bing.com/search)

    /** Bing membungkus tujuan di parameter u=a1<base64>. Tautan lain ke bing.com ditolak. */
    fun unwrapBing(hrefRaw: String): String? {
        val href = hrefRaw.trim()
        if (!href.startsWith("http")) return null
        if (!hostOf(href).endsWith("bing.com")) return href
        val u = Regex("[?&]u=([^&]+)").find(href)?.groupValues?.get(1) ?: return null
        val v = try { URLDecoder.decode(u, "UTF-8") } catch (e: Exception) { return null }
        if (!v.startsWith("a1")) return null
        return try {
            val raw = v.substring(2).replace('-', '+').replace('_', '/').trim()
            val b64 = when (raw.length % 4) {
                2 -> "$raw=="
                3 -> "$raw="
                else -> raw
            }
            val s = String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
            if (s.startsWith("http")) s else null
        } catch (e: Exception) {
            null
        }
    }

    private val BING_H2 = Regex("(?is)<h2[^>]*>(?:(?!</h2>).)*?<a\\b([^>]*)>(.*?)</a>")
    private val ANY_P = Regex("(?is)<p\\b[^>]*>(.*?)</p>")

    fun parseBing(html: String): List<WebTools.Result> {
        val hits = BING_H2.findAll(html).map { m ->
            val href = hrefOf(m.groupValues[1])
            Hit(text(m.groupValues[2]), href?.let { unwrapBing(it) }, m.range.first, m.range.last + 1)
        }.toList()
        return withSnippets(html, hits, ANY_P)
    }

    // ------------------------------------------------------------ Brave (search.brave.com/search)

    private val BRAVE_BLOCK = Regex("(?is)<div\\b[^>]*data-type\\s*=\\s*[\"']web[\"'][^>]*>")
    private val BRAVE_TITLE = Regex("(?is)class\\s*=\\s*\"[^\"]*\\btitle\\b[^\"]*\"[^>]*>(.*?)</div>")
    private val BRAVE_DESC = Regex("(?is)class\\s*=\\s*\"[^\"]*(?:snippet-description|snippet-content|content)\\b[^\"]*\"[^>]*>(.*?)</div>")

    fun parseBrave(html: String): List<WebTools.Result> {
        val starts = BRAVE_BLOCK.findAll(html).map { it.range.first }.toList()
        val out = ArrayList<WebTools.Result>()
        for ((i, s) in starts.withIndex()) {
            val block = html.substring(s, if (i + 1 < starts.size) starts[i + 1] else html.length)
            val a = ANCHOR.findAll(block).firstOrNull { m ->
                val h = hrefOf(m.groupValues[1])
                h != null && h.startsWith("http") && !hostOf(h).endsWith("brave.com")
            } ?: continue
            val url = hrefOf(a.groupValues[1]) ?: continue
            val title = BRAVE_TITLE.find(a.groupValues[2])?.let { text(it.groupValues[1]) }?.takeIf { it.isNotBlank() }
                ?: text(a.groupValues[2])
            val desc = BRAVE_DESC.find(block, a.range.last + 1)?.let { text(it.groupValues[1]) }.orEmpty()
            out.add(WebTools.Result(title.take(160), url, desc.take(300)))
        }
        return finish(out)
    }

    // ------------------------------------------------------------ Yahoo (search.yahoo.com/search)

    /** Yahoo membungkus tujuan di path .../RU=<url-terkode>/RK=...; tautan lain (iklan, internal) ditolak. */
    fun unwrapYahoo(hrefRaw: String): String? {
        val href = hrefRaw.trim()
        val i = href.indexOf("/RU=")
        if (i < 0) return null
        val v = href.substring(i + 4).substringBefore("/RK=").substringBefore("/RS=")
        val url = try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { return null }
        return if (url.startsWith("http")) url else null
    }

    private val YAHOO_H3 = Regex("(?is)<h3\\b[^>]*>(.*?)</h3>")

    fun parseYahoo(html: String): List<WebTools.Result> {
        val hits = ANCHOR.findAll(html)
            .filter { hrefOf(it.groupValues[1])?.contains("/RU=") == true }
            .map { m ->
                val title = YAHOO_H3.find(m.groupValues[2])?.let { text(it.groupValues[1]) }?.takeIf { it.isNotBlank() }
                    ?: text(m.groupValues[2])
                Hit(title, hrefOf(m.groupValues[1])?.let { unwrapYahoo(it) }, m.range.first, m.range.last + 1)
            }.toList()
        return withSnippets(html, hits, Regex("(?is)<(?:div|p)\\b[^>]*class\\s*=\\s*\"[^\"]*compText[^\"]*\"[^>]*>(.*?)</(?:div|p)>"))
    }

    // ------------------------------------------------------------ Mojeek (www.mojeek.com/search)

    fun parseMojeek(html: String): List<WebTools.Result> {
        val hits = ANCHOR.findAll(html)
            .filter { classHas(it.groupValues[1], "title") }
            .map { m ->
                val href = hrefOf(m.groupValues[1])
                Hit(text(m.groupValues[2]), href?.takeIf { it.startsWith("http") }, m.range.first, m.range.last + 1)
            }.toList()
        return withSnippets(html, hits, Regex("(?is)<p\\b[^>]*class\\s*=\\s*[\"']s[\"'][^>]*>(.*?)</p>"))
    }

    // ------------------------------------------------------------ RSS/XML (Bing RSS, Google News RSS)

    private val RSS_ITEM = Regex("(?is)<item\\b[^>]*>(.*?)</item>")
    private val RSS_TITLE = Regex("(?is)<title\\b[^>]*>(.*?)</title>")
    private val RSS_LINK = Regex("(?is)<link\\b[^>]*>(.*?)</link>")
    private val RSS_DESC = Regex("(?is)<description\\b[^>]*>(.*?)</description>")

    private fun unwrapCdata(s: String): String {
        val t = s.trim()
        return if (t.startsWith("<![CDATA[") && t.endsWith("]]>")) t.substring(9, t.length - 3) else t
    }

    fun parseRss(xml: String): List<WebTools.Result> {
        val out = ArrayList<WebTools.Result>()
        for (m in RSS_ITEM.findAll(xml)) {
            val item = m.groupValues[1]
            val title = text(unwrapCdata(RSS_TITLE.find(item)?.groupValues?.get(1).orEmpty()))
            val rawLink = WebTools.decodeEntities(unwrapCdata(RSS_LINK.find(item)?.groupValues?.get(1).orEmpty())).trim()
            val url = if (hostOf(rawLink).endsWith("bing.com")) unwrapBing(rawLink) else rawLink
            if (url.isNullOrBlank() || !url.startsWith("http")) continue
            val desc = text(unwrapCdata(RSS_DESC.find(item)?.groupValues?.get(1).orEmpty()))
            out.add(WebTools.Result(title.take(160), url, desc.take(300)))
        }
        return finish(out)
    }

    // ------------------------------------------------------------ Wikipedia (API resmi, JSON)

    fun parseWikipedia(json: String, lang: String): List<WebTools.Result> {
        val arr = try { JSONObject(json).getJSONObject("query").getJSONArray("search") } catch (e: Exception) { return emptyList() }
        val out = ArrayList<WebTools.Result>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title")
            if (title.isBlank()) continue
            val url = "https://$lang.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
            out.add(WebTools.Result("$title (Wikipedia)".take(160), url, text(o.optString("snippet")).take(300)))
        }
        return finish(out)
    }

    // ------------------------------------------------------------ Marginalia (API publik, JSON)

    fun parseMarginalia(json: String): List<WebTools.Result> {
        val arr = try { JSONObject(json).getJSONArray("results") } catch (e: Exception) { return emptyList() }
        val out = ArrayList<WebTools.Result>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (!url.startsWith("http")) continue
            out.add(WebTools.Result(text(o.optString("title")).take(160), url, text(o.optString("description")).take(300)))
        }
        return finish(out)
    }
}
