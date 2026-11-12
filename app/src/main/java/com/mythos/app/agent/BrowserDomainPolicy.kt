package com.mythos.app.agent

import java.net.URI
import java.util.Locale

/**
 * Daftar situs tepercaya untuk Browser Agent. Agen hanya boleh membaca/mengisi halaman yang hostnya ada di sini
 * (atau yang sudah diizinkan pengguna lewat dialog selama sesi). Situs lain butuh persetujuan eksplisit, jadi halaman
 * hasil pengalihan atau tautan jebakan tidak bisa langsung dibaca atau diisi agen.
 *
 * Aturan pencocokan: host harus SAMA dengan domain tepercaya atau subdomain-nya ("kontak157.ojk.go.id" cocok dengan
 * "ojk.go.id", tetapi "evilojk.go.id" dan "ojk.go.id.evil.com" tidak). Hanya https yang diterima.
 */
object BrowserDomainPolicy {
    val DEFAULT_TRUSTED: List<String> = listOf("ojk.go.id", "bi.go.id")

    /** Host huruf kecil tanpa titik akhir, atau null bila bukan URL https yang valid. */
    fun hostOf(url: String): String? {
        val u = try { URI(url.trim()) } catch (e: Exception) { return null }
        if (!"https".equals(u.scheme, ignoreCase = true)) return null
        val h = u.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        return h.takeIf { it.isNotEmpty() }
    }

    fun matches(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    /**
     * [extraHosts] = host yang diizinkan pengguna pada sesi ini; dicocokkan PERSIS (tanpa subdomain),
     * supaya izin untuk satu host tidak ikut membuka host lain.
     */
    fun isAllowed(url: String, extraHosts: Collection<String> = emptyList(), allowAll: Boolean = false): Boolean {
        val host = hostOf(url) ?: return false
        if (allowAll) return true
        if (DEFAULT_TRUSTED.any { matches(host, it) }) return true
        return extraHosts.any { it.lowercase(Locale.ROOT).trimEnd('.') == host }
    }
}
