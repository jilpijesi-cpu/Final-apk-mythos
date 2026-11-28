package com.build.mythos

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.UUID

// Video terlampir diubah jadi beberapa gambar (frame) supaya model bisa "melihatnya" lewat jalur gambar yang sudah ada.
// Logika pemilihan waktu ada di objek murni VideoFrames (diuji di VideoFramesTest); pengambilan frame memakai
// MediaMetadataRetriever bawaan Android, jadi tidak butuh ffmpeg atau Termux.

internal object VideoFrames {
    const val MAX_FRAMES = 6
    private const val MIN_GAP_MS = 1500L
    private val EXT = setOf("mp4", "3gp", "mkv", "webm", "mov", "m4v", "avi")

    fun isVideoName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXT

    /**
     * Waktu ambil frame (mikrodetik): satu di tengah tiap segmen sama panjang, sekitar tiap 1,5 detik, maksimal [n].
     * Tengah segmen dipilih agar tidak jatuh di frame hitam awal atau akhir.
     */
    fun timesUs(durationMs: Long, n: Int = MAX_FRAMES): List<Long> {
        if (durationMs <= 0L || n <= 0) return emptyList()
        val k = minOf(n.toLong(), maxOf(1L, durationMs / MIN_GAP_MS)).toInt()
        return (0 until k).map { i -> (durationMs * (2L * i + 1) / (2L * k)) * 1000L }
    }

    /** Nama gambar untuk frame ke-[index] (mulai 1) dari video bernama [videoName]. */
    fun frameName(videoName: String, index: Int): String {
        val stem = videoName.substringBeforeLast('.', videoName).replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        return stem + "_frame" + index + ".jpg"
    }
}

/** Ambil frame video dan simpan sebagai JPEG di filesDir/chat_images (sisi terpanjang maks 1600 px). Kosong bila gagal. */
internal fun MainActivity.extractVideoFrames(uri: Uri): List<String> {
    val r = MediaMetadataRetriever()
    val out = mutableListOf<String>()
    try {
        r.setDataSource(this, uri)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return emptyList()
        val dir = File(filesDir, "chat_images").apply { mkdirs() }
        for (t in VideoFrames.timesUs(dur)) {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1600, 1600)
            } else {
                r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: continue
            var b: Bitmap? = null
            try {
                val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
                b = if (scale < 1f) {
                    Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * scale).toInt()), maxOf(1, (bmp.height * scale).toInt()), true)
                } else bmp
                val f = File(dir, UUID.randomUUID().toString() + ".jpg")
                f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                out.add(f.absolutePath)
            } finally {
                if (b != null && b !== bmp) b.recycle()
                bmp.recycle()
            }
        }
    } catch (ignored: Exception) {
    } catch (ignored: OutOfMemoryError) {
    } finally {
        try { r.release() } catch (ignored: Exception) { logQuiet("VideoFrames.kt", ignored) }
    }
    return out
}

/** Dipanggil importFile untuk video: tambahkan frame sebagai lampiran gambar (di thread latar, lalu UI). */
internal fun MainActivity.attachVideoFrames(uri: Uri, videoName: String) {
    bg {
        val frames = extractVideoFrames(uri)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (frames.isEmpty()) {
                toast("Frame video tidak bisa diambil; kirim tangkapan layar sebagai gantinya")
            } else {
                frames.forEachIndexed { i, p -> attachments.add(Attachment(VideoFrames.frameName(videoName, i + 1), null, p, p)) }
                refreshAttachments()
                toast("${frames.size} frame diambil dari video")
            }
        }
    }
}
