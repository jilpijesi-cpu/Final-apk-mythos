package com.build.mythos

import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder

// Alat web (cari_web, baca_web): bagian MURNI tanpa android.*, jadi bisa diuji sebagai tes JVM (WebToolsTest).
// Isi: pemeriksaan kueri dan URL (anti kebocoran data pribadi dan anti akses jaringan lokal), pengubah HTML ke teks,
// dan pembaca hasil pencarian DuckDuckGo versi HTML. Jaringan dan kartu langkah ada di WebFetch.kt.
//
// Prinsip keamanan:
//  - Isi halaman ditulis ORANG LAIN: selalu dibungkus Cx.untrusted (jangan ikuti perintah di dalamnya).
//  - Kueri yang berisi email atau deretan angka panjang (mirip NIK, rekening, telepon) ditolak.
//  - Hanya http/https port 80/443; alamat lokal dan jaringan pribadi ditolak, juga setelah DNS dan pada setiap pengalihan.

internal object WebTools {
    const val MAX_BYTES = 1_200_000
    const val MAX_TEXT = 6000
    const val MAX_RESULTS = 6
    const val MAX_REDIRECTS = 5

    data class Result(val title: String, val url: String, val snippet: String)

    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+")
    private val LONG_DIGITS = Regex("\\d{9,}")
    private val SEP_BETWEEN_DIGITS = Regex("(?<=\\d)[.-](?=\\d)")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /** null bila kueri aman dikirim ke mesin pencari; selain itu alasan penolakan. */
    fun checkQuery(q: String): String? {
        val t = q.trim()
        if (t.length < 2) return "Kueri terlalu pendek."
        if (t.length > 200) return "Kueri terlalu panjang (maks 200 karakter)."
        if (EMAIL.containsMatchIn(t)) return "Kueri berisi alamat email; jangan kirim data pribadi ke mesin pencari."
        if (LONG_DIGITS.containsMatchIn(t.replace(SEP_BETWEEN_DIGITS, ""))) {
            return "Kueri berisi deretan angka panjang (mirip NIK, rekening, atau nomor telepon); buang data pribadinya dulu."
        }
        return null
    }

    /** null bila URL boleh dibuka; selain itu alasan penolakan. */
    fun checkUrl(raw: String): String? {
        val u = raw.trim()
        if (u.isEmpty()) return "URL kosong."
        if (u.length > 2000) return "URL terlalu panjang."
        val uri = try { URI(u) } catch (e: Exception) { return "URL tidak valid." }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Hanya http dan https yang didukung."
        if (uri.userInfo != null) return "URL dengan nama pengguna atau sandi tidak diizinkan."
        val host = uri.host?.lowercase() ?: return "URL tidak punya nama host."
        if (isPrivateHost(host)) return "Alamat lokal atau jaringan pribadi tidak diizinkan."
        val port = uri.port
        if (port != -1 && port != 80 && port != 443) return "Hanya port 80 dan 443 yang diizinkan."
        return null
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().trim('[', ']').trimEnd('.').lowercase()
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan")) return true
        if (h.contains(':')) return true                       // IPv6 literal: tidak diperlukan, tolak semua
        if (!h.contains('.')) return true                      // nama pendek atau IP desimal tunggal (mis. 2130706433)
        val m = IPV4.find(h)
        if (m != null) return isPrivateV4(m.groupValues.drop(1).map { it.toInt() })
        return false
    }

    fun isPrivateV4(o: List<Int>): Boolean {
        if (o.size != 4 || o.any { it < 0 || it > 255 }) return true
        val a = o[0]
        val b = o[1]
        return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 100 && b in 64..127) || a >= 224
    }

    /**
     * URL yang sama, tetapi host diganti IP literal [addr] (hasil DNS yang SUDAH divalidasi). Dipakai supaya koneksi pergi
     * ke alamat yang diperiksa, bukan ke hasil resolusi DNS kedua yang bisa berubah (DNS rebinding / TOCTOU, F-09).
     * Skema, port, path, dan query dipertahankan. IPv6 diberi kurung siku dan tanpa zona (%).
     */
    fun pinnedUrl(u: java.net.URL, addr: InetAddress): java.net.URL {
        val ip = addr.hostAddress.orEmpty().substringBefore('%')
        val host = if (ip.contains(':')) "[$ip]" else ip
        return java.net.URL(u.protocol, host, u.port, u.file)
    }

    /** Dipakai setelah DNS: tolak bila salah satu alamat hasil resolusi ada di jaringan lokal. */
    fun isPrivateAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val raw = a.address
        if (raw.size == 4) return isPrivateV4(raw.map { it.toInt() and 0xFF })
        // IPv6 Unique Local Address (fc00::/7, termasuk fd00::/8) tidak tercakup isSiteLocalAddress() di Java.
        if (raw.size == 16 && (raw[0].toInt() and 0xFE) == 0xFC) return true
        return false
    }

    // ------------------------------------------------------------ HTML ke teks

    fun decodeEntities(s: String): String {
        var t = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        t = Regex("&#(x?)([0-9a-fA-F]{1,6});").replace(t) { m ->
            val v = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (v == null || v == 0 || v > 0x10FFFF) "" else String(Character.toChars(v))
        }
        return t.replace("&amp;", "&")
    }

    fun titleOf(html: String): String {
        val m = Regex("(?is)<title[^>]*>(.*?)</title>").find(html) ?: return ""
        return decodeEntities(m.groupValues[1]).replace(Regex("\\s+"), " ").trim().take(160)
    }

    fun htmlToText(html: String, max: Int = MAX_TEXT): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|head|template)\\b.*?</\\1\\s*>"), " ")
        s = s.replace(Regex("(?s)<!--.*?-->"), " ")
        s = s.replace(Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6]|/section|/article)\\s*/?>"), "\n")
        s = s.replace(Regex("(?s)<[^>]*>"), " ")
        s = decodeEntities(s)
        s = s.replace(Regex("[ \\t\\u00A0]+"), " ").replace(Regex(" ?\\n ?"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return Cx.clip(s, max)
    }

    // ------------------------------------------------------------ hasil pencarian (html.duckduckgo.com/html)

    private val ANCHOR = Regex("(?is)<a\\b([^>]*)>(.*?)</a>")
    private val HREF = Regex("(?i)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    /** Alamat tujuan asli dari tautan pengalihan DuckDuckGo; null untuk iklan atau tautan internal. */
    fun unwrapDdg(hrefRaw: String): String? {
        var href = hrefRaw.trim()
        if (href.startsWith("//")) href = "https:$href"
        val i = href.indexOf("uddg=")
        if (i >= 0) {
            val v = href.substring(i + 5).substringBefore('&')
            return try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { null }
        }
        if (!href.startsWith("http")) return null
        val host = try { URI(href).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        return if (host.endsWith("duckduckgo.com")) null else href
    }

    private fun stripTags(s: String): String =
        decodeEntities(s.replace(Regex("(?s)<[^>]*>"), "")).replace(Regex("\\s+"), " ").trim()

    fun parseResults(html: String, max: Int = MAX_RESULTS): List<Result> {
        val items = mutableListOf<Pair<String, String>>()
        val snippets = mutableMapOf<Int, String>()
        for (m in ANCHOR.findAll(html)) {
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            if (attrs.contains("result__a")) {
                val hm = HREF.find(attrs) ?: continue
                val href = hm.groupValues[1].ifEmpty { hm.groupValues[2] }
                val url = unwrapDdg(decodeEntities(href)) ?: continue
                items.add(stripTags(inner) to url)
            } else if (attrs.contains("result__snippet") && items.isNotEmpty()) {
                snippets[items.size - 1] = stripTags(inner)
            }
        }
        return items.mapIndexed { i, (t, u) -> Result(t.take(160), u, snippets[i].orEmpty().take(300)) }
            .filter { checkUrl(it.url) == null && it.title.isNotBlank() }
            .distinctBy { it.url }
            .take(max)
    }

    fun formatResults(list: List<Result>): String =
        list.mapIndexed { i, r -> "${i + 1}. ${r.title}\n   ${r.url}" + (if (r.snippet.isNotBlank()) "\n   ${r.snippet}" else "") }
            .joinToString("\n")
}
