package com.build.mythos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

// Logo penyedia AI di dropdown "Hubungkan AI Anda": diunduh dari internet SEKALI, disimpan di filesDir/provider_logos
// (dipakai ulang 30 hari, tetap tampil saat offline). Selama belum ada / gagal diunduh, lencana huruf tetap tampil.

internal object ProviderLogos {
    private val mem = ConcurrentHashMap<String, Bitmap>()
    private val locks = ConcurrentHashMap<String, Any>()
    const val MAX_BYTES = 512 * 1024
    const val STALE_MS = 30L * 24 * 60 * 60 * 1000

    fun cached(name: String): Bitmap? = mem[name]
    fun put(name: String, b: Bitmap) { mem[name] = b }
    fun lockFor(name: String): Any = locks.getOrPut(name) { Any() }
}

private fun MainActivity.logoFile(name: String) = File(File(filesDir, "provider_logos"), WorkspaceList.cacheName(name) + ".png")

private const val LOGO_MAX_DIM = 1024

private fun decodeLogo(f: File): Bitmap? = try {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, opts)
    if (opts.outWidth !in 1..LOGO_MAX_DIM || opts.outHeight !in 1..LOGO_MAX_DIM) null
    else BitmapFactory.decodeFile(f.path)?.takeIf { it.width > 0 && it.height > 0 }
} catch (e: Exception) {
    null
}

/** Unduh [url] ke [dest] bila isinya gambar yang valid dan kecil (<512 KB, maks 1024x1024 px). Hanya HTTPS. */
private fun downloadLogo(url: String, dest: File): Bitmap? {
    if (!url.startsWith("https://")) return null
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mythos")
        if (c.responseCode != 200) return null
        if (!c.contentType.orEmpty().startsWith("image/")) return null
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        c.inputStream.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > ProviderLogos.MAX_BYTES) return null
                out.write(buf, 0, n)
            }
        }
        val bytes = out.toByteArray()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth !in 1..LOGO_MAX_DIM || opts.outHeight !in 1..LOGO_MAX_DIM) return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width > 0 && it.height > 0 } ?: return null
        val dir = dest.parentFile ?: return bmp
        dir.mkdirs()
        val tmp = File.createTempFile("logo-", ".tmp", dir)
        try {
            tmp.writeBytes(bytes)
            java.nio.file.Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return bmp
    } finally {
        c.disconnect()
    }
}

/**
 * Muat logo [name]: memori, lalu cache disk, lalu unduh bila belum ada atau sudah lewat 30 hari. [onReady] dipanggil di
 * thread UI (tidak dipanggil bila tidak ada logo untuk penyedia itu atau gagal; lencana huruf tetap dipakai).
 */
internal fun MainActivity.loadProviderLogo(name: String, onReady: (Bitmap) -> Unit) {
    ProviderLogos.cached(name)?.let { onReady(it); return }
    val url = ProviderCatalog.logoUrl(name) ?: return
    loadLogo(name, url, onReady)
}

/** Sama seperti [loadProviderLogo] tetapi untuk tautan [url] apa pun; [name] jadi kunci cache memori dan disk. */
internal fun MainActivity.loadLogo(name: String, url: String, onReady: (Bitmap) -> Unit) {
    ProviderLogos.cached(name)?.let { onReady(it); return }
    bg {
        try {
            synchronized(ProviderLogos.lockFor(name)) {
                ProviderLogos.cached(name)?.let { b -> runOnUiThread { if (!isFinishing && !isDestroyed) onReady(b) }; return@synchronized }
                val file = logoFile(name)
                val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() <= ProviderLogos.STALE_MS
                var bmp = if (file.isFile) decodeLogo(file) else null
                if (bmp == null || !fresh) {
                    // Gagal mengunduh ulang tidak apa-apa selama masih ada salinan lama.
                    downloadLogo(url, file)?.let { bmp = it }
                }
                val ready = bmp ?: return@synchronized
                ProviderLogos.put(name, ready)
                runOnUiThread { if (!isFinishing && !isDestroyed) onReady(ready) }
            }
        } catch (e: Exception) {
            logQuiet("ProviderLogos.kt", e)
        }
    }
}
