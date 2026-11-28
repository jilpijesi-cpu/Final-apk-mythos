package com.build.mythos

import java.net.URI

// Daftar sumber web yang dibuka agen pada satu giliran (cari_web dan baca_web), untuk chip "N sumber" di bawah balasan.
// Murni Kotlin tanpa android.* supaya bisa diuji sebagai tes JVM (SourceLogTest).

internal data class WebSource(val title: String, val url: String) {
    val host: String get() = SourceLog.hostOf(url)
}

internal object SourceLog {
    private val items = LinkedHashMap<String, WebSource>()

    /** Nama situs tanpa "www."; kosong bila URL tidak valid. */
    fun hostOf(url: String): String =
        try { URI(url.trim()).host.orEmpty().removePrefix("www.") } catch (e: Exception) { "" }

    /** Catat satu sumber. Hanya http/https; URL yang sama (tanpa # dan garis miring akhir) dihitung sekali. */
    @Synchronized fun add(title: String, url: String) {
        val u = url.trim()
        if (!(u.startsWith("https://") || u.startsWith("http://"))) return
        if (hostOf(u).isEmpty()) return
        val key = u.substringBefore('#').trimEnd('/')
        val t = title.replace(Regex("\\s+"), " ").trim().ifBlank { hostOf(u) }
        val old = items[key]
        // Judul dari halaman yang dibaca lebih baik daripada judul bawaan (nama host).
        if (old == null || old.title == old.host) items[key] = WebSource(t.take(120), u)
    }

    @Synchronized fun snapshot(): List<WebSource> = items.values.toList()

    @Synchronized fun clear() = items.clear()
}
