package com.mythos.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.mythos.app.ProviderChoice
import com.mythos.app.R

/**
 * Logo penyedia. Untuk mengganti dengan berkas resmi: taruh gambar bernama sama (ic_provider_*) di res/drawable
 * (hapus dulu file .xml dengan nama yang sama bila gambarnya PNG/WebP); kode ini tidak perlu diubah.
 * null = penyedia belum punya logo (baris tampil tanpa ikon).
 */
@DrawableRes
internal fun providerLogo(p: ProviderChoice): Int? = when (p) {
    ProviderChoice.OPENAI -> R.drawable.ic_provider_openai
    ProviderChoice.ANTHROPIC -> R.drawable.ic_provider_anthropic
    ProviderChoice.GEMINI -> R.drawable.ic_provider_gemini
    ProviderChoice.DEEPSEEK -> R.drawable.ic_provider_deepseek
    ProviderChoice.GROQ -> R.drawable.ic_provider_groq
    ProviderChoice.OLLAMA -> R.drawable.ic_provider_ollama
    ProviderChoice.CODEX -> R.drawable.ic_provider_openai   // cadangan; gambar dari tautan dimuat di ProviderLogo()
    ProviderChoice.HUGGINGFACE -> R.drawable.ic_provider_huggingface
    ProviderChoice.GITHUB_MODELS -> R.drawable.ic_provider_github
    ProviderChoice.OPENROUTER -> R.drawable.ic_provider_openrouter
}

/** Digambar dengan Image (bukan Icon) agar warna asli logo tidak ditimpa tint. */
@Composable
internal fun ProviderLogo(p: ProviderChoice, size: Dp, modifier: Modifier = Modifier) {
    // Logo yang diambil dari tautan (HTTPS tetap, di-cache di disk). Gagal/belum selesai -> logo bawaan di bawah.
    val remoteUrl = REMOTE_PROVIDER_LOGOS[p]
    if (remoteUrl != null) {
        val remote = rememberRemoteLogo(remoteUrl)
        if (remote != null) {
            Image(bitmap = remote, contentDescription = null, modifier = modifier.size(size))
            return
        }
    }
    val res = providerLogo(p) ?: return
    Image(painterResource(res), contentDescription = null, modifier = modifier.size(size))   // label ada di teks sebelahnya
}

/** Penyedia yang logonya diambil dari tautan gambar (PNG/WebP, bukan SVG). Daftar tetap di kode: bukan masukan dari luar. */
private val REMOTE_PROVIDER_LOGOS: Map<ProviderChoice, String> = mapOf(
    ProviderChoice.CODEX to "https://cdn.jsdelivr.net/npm/@lobehub/icons-static-png@latest/light/codex-color.png",
)

@Composable
private fun rememberRemoteLogo(url: String): ImageBitmap? {
    val appCtx = LocalContext.current.applicationContext
    var bmp by remember(url) { mutableStateOf(RemoteLogos.cached(url)) }
    LaunchedEffect(url) { if (bmp == null) bmp = RemoteLogos.load(appCtx, url) }
    return bmp
}

/** Unduh sekali, simpan di cache, pakai dari memori setelahnya. Hanya https, maksimal 1 MB, tanpa pengaruh pada fungsi lain bila gagal. */
internal object RemoteLogos {
    private val mem = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

    fun cached(url: String): ImageBitmap? = mem[url]

    suspend fun load(ctx: Context, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        mem[url]?.let { return@withContext it }
        if (!url.startsWith("https://")) return@withContext null
        val file = File(File(ctx.cacheDir, "logos").apply { mkdirs() }, "%08x.img".format(url.hashCode()))
        var bmp = if (file.exists()) runCatching { file.readBytes() }.getOrNull()?.let { decode(it) } else null
        if (bmp == null) {
            val bytes = download(url)
            bmp = bytes?.let { decode(it) }
            if (bmp != null && bytes != null) runCatching { file.writeBytes(bytes) }
        }
        bmp?.also { mem[url] = it }
    }

    private fun decode(b: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()

    private fun download(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 8_000
            if (c.responseCode != 200 || c.contentLength > 1_000_000) null
            else c.inputStream.use { it.readBytes() }.takeIf { it.size in 1..1_000_000 }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Logo konektor (google, gmail, calendar, drive, brave, tavily): dicari saat jalan dari berkas `res/drawable/ic_connector_<nama>`
 * (.xml/.svg-vektor/.png/.webp). Berkas belum ada -> null dan baris tampil dengan ikon bawaan; menaruh berkasnya saja sudah cukup,
 * kode tidak perlu diubah. Aturan keep ada di `res/raw/keep.xml` supaya R8 tidak membuang berkasnya.
 */
@DrawableRes
@Composable
internal fun connectorLogo(name: String): Int? {
    val ctx = LocalContext.current
    return remember(name) {
        ctx.resources.getIdentifier("ic_connector_$name", "drawable", ctx.packageName).takeIf { it != 0 }
    }
}
