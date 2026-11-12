package com.mythos.app.agent

import kotlin.math.abs

/**
 * patch13: pengaman putaran WebFetch. Situs berbasis JavaScript sering mengembalikan halaman yang SAMA untuk alamat apa pun
 * (/login, /daftar, /register -> beranda). Penjaga duplikat di Agent hanya menangkap URL yang persis sama, jadi model terus
 * menebak alamat baru sampai batas 300 langkah. Di sini: (1) halaman yang hampir identik dengan yang sudah dibuka diberi catatan,
 * (2) jumlah alamat berbeda per situs dibatasi per tugas. Satu instance per tugas ([Agent.RunState]).
 */
internal class WebFetchGuard(
    private val softLimit: Int = 5,
    private val hardLimit: Int = 8,
    private val similarLimit: Int = 3,
) {
    private class Page(val url: String, val title: String, val total: Int)

    private val urls = HashMap<String, LinkedHashSet<String>>()   // situs -> alamat berbeda yang sudah dicoba
    private val pages = HashMap<String, MutableList<Page>>()      // situs -> halaman yang berhasil dibuka
    private val similarHits = HashMap<String, Int>()              // situs -> berapa kali halaman baru ternyata mirip yang lama

    /** Pesan penolakan bila alamat BARU di situs ini tidak boleh dibuka lagi; null bila boleh. Alamat yang sama (lanjutan offset) selalu boleh. */
    fun before(rawUrl: String): String? {
        val (site, key) = parse(rawUrl) ?: return null
        val seen = urls[site]
        if (seen != null && key in seen) return null
        if ((similarHits[site] ?: 0) >= similarLimit) {
            return "WebFetch refused: several different addresses on $site already returned the same page, so this site serves one page for any path. " +
                "Do not guess more URLs. Use the links actually listed on a page you opened, use WebSearch for another source, or answer with what you have."
        }
        if ((seen?.size ?: 0) >= hardLimit) {
            return "WebFetch refused: you already tried ${seen!!.size} different addresses on $site in this task. Stop opening more pages there. " +
                "Answer with what you found (say which URLs you actually opened), or use WebSearch for a different source."
        }
        return null
    }

    /** Dipanggil sesudah WebFetch dijalankan (berhasil atau gagal). Mengembalikan catatan sistem untuk ditempel di hasil, atau "". */
    fun after(rawUrl: String, ok: Boolean, text: String): String {
        val (site, key) = parse(rawUrl) ?: return ""
        val firstTime = urls.getOrPut(site) { LinkedHashSet() }.add(key)
        if (!ok || !firstTime) return ""
        val title = TITLE_RE.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val total = TOTAL_RE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: text.length
        val list = pages.getOrPut(site) { ArrayList() }
        val twin = list.firstOrNull { it.title.isNotEmpty() && it.title == title && abs(it.total - total) <= maxOf(50, it.total / 100) }
        list.add(Page(key, title, total))
        val count = urls.getValue(site).size
        return when {
            twin != null -> {
                similarHits[site] = (similarHits[site] ?: 0) + 1
                "\n\n[System note: this page is nearly identical to ${twin.url} (same title, same length). This site probably serves the same page for different addresses. " +
                    "Do not guess more URLs here; follow links actually listed on a page, or use WebSearch.]"
            }
            count >= softLimit ->
                "\n\n[System note: you have now opened $count different addresses on $site. If nothing new is appearing, stop and answer with what you have.]"
            else -> ""
        }
    }

    companion object {
        private val TITLE_RE = Regex("""(?m)^Judul: (.*)$""")
        private val TOTAL_RE = Regex("""(?m)^Bagian: karakter \d+\S\d+ dari (\d+)""")
        private val TWO_PART_SLD = setOf("co", "com", "go", "or", "ac", "web", "sch", "net", "org")

        /** (situs, kunci alamat) atau null bila bukan alamat yang bisa dibaca. Situs = domain induk, jadi help.flip.id dan flip.id dihitung satu. */
        internal fun parse(rawUrl: String): Pair<String, String>? {
            var u = rawUrl.trim()
            if (u.isEmpty()) return null
            if (!u.contains("://")) u = "https://$u"
            val noFragment = u.substringBefore('#')
            val host = noFragment.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase().removePrefix("www.")
            if (host.isEmpty()) return null
            val labels = host.split('.')
            val site = when {
                labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in TWO_PART_SLD -> labels.takeLast(3).joinToString(".")
                labels.size >= 2 -> labels.takeLast(2).joinToString(".")
                else -> host
            }
            val rest = noFragment.substringAfter("://").substringAfter('/', "")
            val key = "$host/$rest".trimEnd('/').lowercase()
            return site to key
        }
    }
}
