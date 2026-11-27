package com.build.mythos

import android.graphics.Color

// Logo asli tiap konektor (Gmail, Telegram, WhatsApp) untuk Hub Koneksi dan Pengaturan > Koneksi.
// Memakai mekanisme yang sama dengan logo penyedia AI: diunduh dari tautan SEKALI, disimpan di filesDir/provider_logos
// (dipakai ulang 30 hari, tetap tampil saat offline). Selama belum ada atau gagal diunduh, lencana huruf berwarna yang tampil.

internal object ConnectorLogos {
    class Info(val domain: String, val letter: String, val color: Int)

    private val INFO = mapOf(
        "email" to Info("mail.google.com", "M", 0xFFEA4335.toInt()),            // Gmail
        "telegram" to Info("telegram.org", "T", 0xFF229ED9.toInt()),            // Telegram
        "leakosint" to Info("leakosint.com", "L", 0xFF475569.toInt()),         // LeakOSINT
        "wa_hp" to Info("whatsapp.com", "W", 0xFF25D366.toInt()),               // WhatsApp
        "whatsapp" to Info("business.whatsapp.com", "B", 0xFF128C7E.toInt())    // WhatsApp Business
    )

    fun info(id: String): Info = INFO[id] ?: Info("", "?", Color.GRAY)

    /** Tautan gambar logo (layanan ikon situs Google, 128 px). Ganti di sini bila ingin sumber logo lain. */
    fun url(id: String): String? = INFO[id]?.let { "https://www.google.com/s2/favicons?domain=${it.domain}&sz=128" }

    /** Kunci cache; diawali "konektor_" agar tidak bertabrakan dengan nama penyedia AI. */
    fun cacheKey(id: String) = "konektor_$id"
}

/** Lencana logo konektor [id]: huruf berwarna dulu, diganti logo asli begitu termuat. */
internal fun MainActivity.connectorBadge(id: String): BadgeView {
    val i = ConnectorLogos.info(id)
    val b = BadgeView(this, i.letter, i.color)
    val key = ConnectorLogos.cacheKey(id)
    ProviderLogos.cached(key)?.let { b.setLogo(it); return b }
    ConnectorLogos.url(id)?.let { url -> loadLogo(key, url) { bmp -> b.setLogo(bmp) } }
    return b
}
