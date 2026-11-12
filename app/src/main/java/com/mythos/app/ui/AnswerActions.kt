package com.mythos.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val ActionGrey = Color(0xFF6B6965)
private val URL_RE = Regex("""https?://([A-Za-z0-9.-]+)[^\s"')>\]]*""")

/** Sumber web yang dibuka/dicari pada giliran ini: URL unik (jumlah) dan domain unik (untuk ikon). */
internal data class AnswerSources(val urls: List<String>, val hosts: List<String>) {
    val isEmpty get() = urls.isEmpty()
}

/** Kumpulkan sumber dari langkah WebSearch/WebFetch sejak pesan pengguna terakhir sebelum jawaban [finalId]. */
internal fun sourcesFor(chat: List<ChatItem>, finalId: Long): AnswerSources {
    val end = chat.indexOfFirst { it.id == finalId }.let { if (it < 0) chat.size else it }
    val urls = LinkedHashSet<String>()
    val hosts = LinkedHashSet<String>()
    for (i in end - 1 downTo 0) {
        val it = chat[i]
        if (it is UserItem) break
        if (it is ToolItem && (it.tool == "WebSearch" || it.tool == "WebFetch")) {
            URL_RE.findAll(it.summary + "\n" + it.preview).forEach { m ->
                val host = m.groupValues[1].removePrefix("www.").lowercase(Locale.US)
                if ('.' in host) {
                    urls.add(m.value.substringBefore('#').trimEnd('/', '.', ','))
                    hosts.add(host)
                }
            }
        }
    }
    return AnswerSources(urls.toList(), hosts.toList())
}

/** Teks pengguna terakhir, untuk tombol "Ulangi". */
internal fun lastUserText(chat: List<ChatItem>): String? = (chat.lastOrNull { it is UserItem } as? UserItem)?.text?.takeIf { it.isNotBlank() }

/**
 * Baris aksi di akhir jawaban AI: salin, bagikan, bacakan, suka, tidak suka, ulangi, dan ikon + jumlah sumber web.
 * Suka/tidak suka hanya penanda di layar (tidak dikirim ke mana pun).
 */
@Composable
internal fun AnswerActions(text: String, sources: AnswerSources, onRetry: (() -> Unit)?) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var vote by remember(text) { mutableStateOf(0) }   // 1 = suka, -1 = tidak suka
    var speaking by remember { mutableStateOf(false) }
    val engine = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        onDispose {
            engine[0]?.stop()
            engine[0]?.shutdown()
            engine[0] = null
        }
    }
    fun speak() {
        if (speaking) {
            engine[0]?.stop()
            speaking = false
            return
        }
        speaking = true
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx) { status ->
            val e = tts
            if (status != TextToSpeech.SUCCESS || e == null) {
                speaking = false
                Toast.makeText(ctx, "Pembaca suara tidak tersedia", Toast.LENGTH_SHORT).show()
                return@TextToSpeech
            }
            e.language = Locale("id", "ID")
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { speaking = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { speaking = false }
            })
            e.speak(text.take(3900), TextToSpeech.QUEUE_FLUSH, null, "mythos-answer")
        }
        engine[0]?.shutdown()
        engine[0] = tts
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).offset(x = (-9).dp),   // ikon sejajar dengan tepi kiri teks jawaban (PATCH7: ikon 18dp dalam sasaran 36dp)
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        ActionIcon(Icons.Outlined.ContentCopy, "Salin") {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(ctx, "Disalin", Toast.LENGTH_SHORT).show()
        }
        ActionIcon(Icons.Outlined.IosShare, "Bagikan") {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
            runCatching { ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        ActionIcon(Icons.Outlined.VolumeUp, if (speaking) "Hentikan suara" else "Bacakan", active = speaking) { speak() }
        ActionIcon(if (vote == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Suka", active = vote == 1) { vote = if (vote == 1) 0 else 1 }
        ActionIcon(if (vote == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "Tidak suka", active = vote == -1) { vote = if (vote == -1) 0 else -1 }
        if (onRetry != null) ActionIcon(Icons.Outlined.Refresh, "Ulangi") { onRetry() }
        if (!sources.isEmpty) {
            // PATCH7: ikon sumber + jumlahnya di BARIS YANG SAMA dengan ikon aksi, rata kanan, dan lebih kecil.
            Spacer(Modifier.weight(1f))
            val shown = sources.hosts.take(2)
            Box(Modifier.size(20.dp + 12.dp * (shown.size - 1).coerceAtLeast(0), 20.dp)) {
                shown.forEachIndexed { i, h -> Box(Modifier.offset(x = 12.dp * i)) { Favicon(h, 20.dp) } }
            }
            Spacer(Modifier.width(6.dp))
            Text("${sources.urls.size} sumber", maxLines = 1, style = TextStyle(fontSize = 12.sp, color = ActionGrey))
            Spacer(Modifier.width(9.dp))
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = if (active) MythosColors.Ink else ActionGrey, modifier = Modifier.size(18.dp))
    }
}

private val faviconCache = ConcurrentHashMap<String, ImageBitmap>()
private val faviconClient by lazy { OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build() }

/** Ikon situs bundar. Diambil dari layanan favicon Google (hanya nama domain yang dikirim); gagal -> huruf pertama domain. */
@Composable
private fun Favicon(host: String, size: Dp) {
    val bmp by produceState<ImageBitmap?>(faviconCache[host], host) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url("https://www.google.com/s2/favicons?domain=$host&sz=64").build()
                    faviconClient.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                    }
                }.getOrNull()?.also { faviconCache[host] = it }
            }
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.White).border(1.dp, MythosColors.Background, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) Image(b, contentDescription = null, modifier = Modifier.fillMaxSize().padding(2.dp))
        else Text(host.take(1).uppercase(Locale.US), style = TextStyle(fontSize = 10.sp, color = ActionGrey))
    }
}
