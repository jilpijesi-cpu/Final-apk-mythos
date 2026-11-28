package com.build.mythos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.style.ImageSpan
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.BitmapFactory
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Outline
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView

// Menambah gelembung pesan, catatan, kartu pasang-APK dan kartu undo ke layar chat.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.insertBeforeStatus(v: View, lp: LinearLayout.LayoutParams) {
    val idx = if (statusRoot.parent === chatBox) chatBox.indexOfChild(statusRoot) else -1
    val zoneIdx = if (zone.parent === chatBox) chatBox.indexOfChild(zone) else -1
    val insertAt = listOf(idx, zoneIdx).filter { it >= 0 }.minOrNull()
    if (insertAt != null) chatBox.addView(v, insertAt, lp) else chatBox.addView(v, lp)
}

/**
 * Dulu menambah header "AI Agent · status" di tiap balasan; itu mengulang baris aktivitas dan membuat layar ramai.
 * Sekarang balasan tampil bersih; status hanya ada di satu pil aktivitas. Fungsi dipertahankan agar pemanggil lama aman.
 */
@Suppress("UNUSED_PARAMETER")
internal fun MainActivity.decorateAgentBubble(box: LinearLayout, active: Boolean) {
    box.findViewWithTag<View>("ai_action_header")?.let { box.removeView(it) }
}

internal fun MainActivity.refreshAgentBubbleHeader() {
    streamBox?.let { decorateAgentBubble(it, busy) }
}

/** Waktu muncul tiap huruf yang sedang mengalir, untuk efek memudar masuk yang mulus per huruf. */
private object RevealClock {
    private var at = LongArray(2048)
    private var n = 0
    private var lastRevealAt = 0L

    fun reset() { n = 0; lastRevealAt = 0L }

    fun mark(count: Int, now: Long) {
        if (count < n) { n = count; return }
        if (count > at.size) at = at.copyOf(maxOf(count, at.size * 2))
        for (i in n until count) at[i] = now
        if (count > n) lastRevealAt = now
        n = count
    }

    fun ageAt(i: Int, now: Long): Long = if (i in 0 until n) now - at[i] else Long.MAX_VALUE

    /** Masih ada huruf yang belum selesai memudar? Dipakai agar huruf terakhir tidak tiba-tiba "pop" penuh. */
    fun animating(now: Long): Boolean = n > 0 && now - lastRevealAt < StreamSmoother.FADE_MS
}

private var smoothFromCodex = false

/**
 * Pengali kecepatan ketik untuk teks yang masih harus selesai sebelum kejadian alat berikutnya (percepatan, bukan loncat instan).
 * Diatur oleh CodexActivityUi; 1.0 = kecepatan profil biasa.
 */
@Volatile internal var streamBoost = 1.0

/** Segmen teks sedang diketik dan harus ditutup (tanpa tombol Salin) begitu ketikannya selesai. Tidak memblokir thread agen. */
@Volatile internal var segmentEndPending = false

/** Pengguna mengetuk teks yang sedang mengalir: tampilkan sisanya sekarang juga. */
@Volatile private var streamSkip = false

private fun MainActivity.updateStreamingText(box: LinearLayout, text: String, fade: Boolean = false) {
    // Tampilkan hanya teks yang benar-benar diterima. Penanda Markdown yang belum ditutup disembunyikan
    // (hideOpenMarkup) supaya "**" mentah tidak berkedip lalu berubah jadi tebal.
    val tv = box.findViewWithTag<TextView>("ai_stream_text") ?: TextView(this).apply {
        tag = "ai_stream_text"
        styleAssistantText(this)
        movementMethod = android.text.method.LinkMovementMethod.getInstance()
        // Ketuk teks yang sedang mengalir untuk langsung melihat sisanya.
        setOnClickListener { if (typewriterRunnable != null) streamSkip = true }
        box.addView(this, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    val spanned = android.text.SpannableStringBuilder(mdToSpannable(hideOpenMarkup(text)))
    if (fade) {
        // Tiap huruf memudar masuk menurut umurnya sendiri (ease-out ~260 ms), bukan tiga tingkat kaku.
        val now = SystemClock.uptimeMillis()
        val n = spanned.length
        var d = 0
        while (d < n && d < StreamTempo.current.fadeReach) {
            val age = RevealClock.ageAt(text.length - 1 - d, now)
            if (age >= StreamSmoother.FADE_MS) break
            val pos = n - 1 - d
            if (!spanned[pos].isWhitespace()) {
                spanned.setSpan(
                    android.text.style.ForegroundColorSpan((Palette.INK and 0x00FFFFFF) or (StreamSmoother.fadeAlpha(age) shl 24)),
                    pos, pos + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            d++
        }
    }
    tv.text = spanned
}

private var logoBmp: Bitmap? = null
private val userImgCache = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Kursor mata Mythos yang berkedip dan mengawasi di ujung teks AI yang sedang mengetik. */
private fun MainActivity.logoCursor(textSizePx: Float): Drawable {
    val h = (textSizePx * 0.78f).toInt().coerceAtLeast(1)
    val w = (h * 1.55f).toInt().coerceAtLeast(1)
    return WatchfulEyeCursorDrawable(SystemClock.uptimeMillis(), Palette.INK, Palette.BG).apply {
        setBounds(0, 0, w, h)
    }
}

private class WatchfulEyeCursorDrawable(
    private val timeMs: Long,
    private val inkColor: Int,
    private val pupilColor: Int
) : Drawable() {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePath = android.graphics.Path()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val halfW = b.width() * 0.44f
        val halfH = minOf(b.height() * 0.44f, halfW * MYTHOS_EYE_H_RATIO)
        drawWatchfulMythosEye(
            c = canvas,
            cx = b.exactCenterX(),
            cy = b.exactCenterY(),
            halfW = halfW,
            halfH = halfH,
            timeMs = timeMs,
            inkColor = inkColor,
            pupilFillColor = pupilColor,
            strokePaint = strokePaint,
            fillPaint = fillPaint,
            path = eyePath
        )
    }
    override fun setAlpha(alpha: Int) { strokePaint.alpha = alpha; fillPaint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { strokePaint.colorFilter = cf; fillPaint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun MainActivity.addMsg(kind: Int, text: String, images: List<HistImage> = emptyList(), pin: Boolean = true) = runOnUiThread {
    emptyState.visibility = View.GONE
    if (kind == USER) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = MsgMeta(USER, text)
            setOnLongClickListener { showMsgMenu(this); true }
            background = GradientDrawable().apply {
                setColor(Palette.BUBBLE)
                cornerRadius = dp(16).toFloat()
                setStroke(1, Palette.BUBBLE_BORDER)
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        images.forEach { image ->
            val p = image.previewPath
            val f = if (!p.isNullOrBlank()) File(p) else null
            if (f != null && f.isFile) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                val maxW = dp(220).toFloat()
                val maxH = dp(392).toFloat() // muat rasio penuh 9:16 tanpa memotong gambar asli
                val bw = bounds.outWidth.coerceAtLeast(1).toFloat()
                val bh = bounds.outHeight.coerceAtLeast(1).toFloat()
                val scale = minOf(maxW / bw, maxH / bh)
                val targetW = (bw * scale).toInt().coerceAtLeast(dp(72))
                val targetH = (bh * scale).toInt().coerceAtLeast(dp(72))
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    contentDescription = "Gambar terlampir"
                    background = rounded(Palette.FIELD, 12)
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, out: android.graphics.Outline) {
                            out.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat())
                        }
                    }
                    clipToOutline = true
                }
                val cacheKey = "${f.absolutePath}:${targetW}x${targetH}"
                val cached = userImgCache.get(cacheKey)
                if (cached != null && !cached.isRecycled) {
                    iv.setImageBitmap(cached)
                } else {
                    bg {
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
                            sample *= 2
                        }
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val decoded = try { BitmapFactory.decodeFile(f.absolutePath, opts) } catch (_: Throwable) { null }
                        if (decoded != null) {
                            userImgCache.put(cacheKey, decoded)
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) iv.setImageBitmap(decoded)
                            }
                        }
                    }
                }
                box.addView(iv, LinearLayout.LayoutParams(targetW, targetH).apply { bottomMargin = dp(8) })
            } else {
                box.addView(TextView(this).apply {
                    this.text = "[Gambar sudah dihapus dari penyimpanan]"
                    textSize = Typo.CAPTION
                    setTextColor(Palette.INK)
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(6) })
            }
        }
        // Lampiran file tampil sebagai kartu file asli (ikon + nama + jenis berkas), bukan sekadar chip huruf polos.
        val shown = AttachmentBubble.split(text)
        shown.files.forEach { name ->
            val ext = name.substringAfterLast('.', "").uppercase().take(6)
            val isArchive = ext in setOf("ZIP", "RAR", "7Z", "TAR", "GZ", "APK")
            val typeLabel = when (ext) {
                "ZIP", "RAR", "7Z", "TAR", "GZ" -> "Arsip · $ext"
                "APK" -> "Aplikasi · APK"
                "PDF" -> "Dokumen · PDF"
                "DOC", "DOCX" -> "Dokumen · $ext"
                "XLS", "XLSX", "CSV" -> "Tabel · $ext"
                "PPT", "PPTX" -> "Presentasi · $ext"
                "HTML", "HTM" -> "Halaman Web · HTML"
                "TXT", "MD", "LOG" -> "Teks · $ext"
                "JSON", "XML", "KT", "JAVA", "PY", "JS" -> "Kode · $ext"
                "" -> "File terlampir"
                else -> "File · $ext"
            }
            val iconKind = if (isArchive) IconView.ARCHIVE else IconView.FILE
            val accent = accentFor(iconKind)
            val fileCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = "Lampiran file: $name"
                background = rounded(Palette.CARD, 12, Palette.BORDER, 1)
                setPadding(dp(10), dp(8), dp(12), dp(8))
            }
            val iconTile = FrameLayout(this).apply {
                background = rounded(tint(accent, 0.86f), 10)
                addView(
                    IconView(this@addMsg, iconKind, accent),
                    FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                )
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val nameTv = TextView(this).apply {
                this.text = name
                textSize = Typo.BODY_S
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setTextColor(Palette.INK)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val metaTv = TextView(this).apply {
                this.text = typeLabel
                textSize = Typo.CAPTION
                setTextColor(Palette.INK_SOFT)
                setSingleLine(true)
            }
            textCol.addView(nameTv, LinearLayout.LayoutParams(WRAP, WRAP))
            textCol.addView(metaTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(1) })
            fileCard.addView(iconTile, LinearLayout.LayoutParams(dp(36), dp(36)))
            fileCard.addView(textCol, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
            box.addView(fileCard, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(8) })
        }
        if (shown.text.isNotBlank()) {
            box.addView(TextView(this).apply {
                this.text = shown.text
                textSize = Typo.BODY
                typeface = applyAppFontWeight(android.graphics.Typeface.DEFAULT)
                setTextColor(Palette.INK)
                setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(16)
            gravity = Gravity.END
            marginStart = dp(39)
        }
        insertBeforeStatus(box, lp)
        if (pin) {
            pinToTop(box)
            return@runOnUiThread
        }
    } else {
        val box = newAiBox()
        fillMarkdown(box, text)
        decorateAgentBubble(box, active = false)
    }
    scrollDown()
}

internal fun MainActivity.newAiBox(): LinearLayout {
    // Balasan teks menutup kartu aktivitas yang sedang berjalan; langkah berikutnya membuka kartu baru.
    zone.feed.seal()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = MsgMeta(AI, "")
        background = null
        setPadding(dp(4), dp(4), dp(4), dp(2))
    }
    box.setOnLongClickListener { showMsgMenu(box); true }
    insertBeforeStatus(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
    return box
}

/**
 * Jawaban lengkap (jalur agen / fallback) ditampilkan lewat penghalus yang sama dengan stream Codex: huruf mengalir
 * dengan kecepatan adaptif dan memudar masuk, bukan muncul sekaligus atau dalam gumpalan besar.
 */
internal fun MainActivity.streamUpdate(text: String) = runOnUiThread {
    emptyState.visibility = View.GONE
    // Segmen sebelumnya masih diketik dan menunggu ditutup: tutup sekarang agar teks baru tidak menimpanya.
    if (segmentEndPending) { segmentEndPending = false; streamSegmentEnd() }
    // Jawaban sebelumnya masih menunggu selesai diketik: tuntaskan dulu agar tidak tertimpa.
    if (streamBox != null && streamEnded && streamDirectRendering) finalizeDirectStream()
    if (streamBox == null) {
        typewriterHandler.removeCallbacksAndMessages(null)
        typewriterRunnable = null
        typewriterShown = ""
        typewriterTarget = ""
        streamText = ""
        streamEnded = false
        streamCopyAdded = false
        streamBox = newAiBox()
    }
    val box = streamBox ?: return@runOnUiThread
    streamDirectRendering = true
    streamText = text
    typewriterTarget = text
    // Bila teks baru bukan kelanjutan teks yang sudah tampil, mundur ke awalan yang sama.
    if (!text.startsWith(typewriterShown)) {
        val m = minOf(text.length, typewriterShown.length)
        var i = 0
        while (i < m && text[i] == typewriterShown[i]) i++
        typewriterShown = text.substring(0, i)
    }
    streamEnded = false
    decorateAgentBubble(box, active = true)
    smoothFromCodex = false
    startSmoothing()
}

/**
 * Codex mengirim delta nyata. Gambar tepat teks yang baru diterima, tanpa menambahkan efek ketik sintetis
 * yang dapat tertinggal dari output model. Elemen pesan tetap sama dan hanya isi TextView yang berubah.
 */
internal fun MainActivity.streamAppend(delta: String) {
    if (delta.isEmpty()) return
    // Tandai sebelum dispatch ke main thread agar jalur fallback final tidak membuat respons duplikat.
    codexStreamOutputStarted = true
    runOnUiThread {
        emptyState.visibility = View.GONE
        // Segmen sebelumnya belum selesai diketik tetapi teks baru sudah tiba: tutup yang lama, mulai kotak baru.
        if (segmentEndPending) { segmentEndPending = false; streamSegmentEnd() }
        if (streamBox == null) {
            typewriterHandler.removeCallbacksAndMessages(null)
            typewriterRunnable = null
            typewriterShown = ""
            typewriterTarget = ""
            streamText = ""
            streamEnded = false
            streamCopyAdded = false
            streamBox = newAiBox()
        }
        val box = streamBox ?: return@runOnUiThread
        streamDirectRendering = true
        codexStreamOutputStarted = true
        streamText += delta
        typewriterTarget += delta
        streamEnded = false
        decorateAgentBubble(box, active = true)
        // Teks diterima bergelombang; tampilkan per frame dengan kecepatan adaptif (StreamSmoother).
        codexActivityUi.onTextActive()
        smoothFromCodex = true
        startSmoothing()
    }
}

/**
 * Saat jawaban difinalkan, blok kode dan tabel (ViewGroup) muncul memudar satu per satu, bukan semuanya sekaligus.
 * Teks biasa tidak disentuh supaya tidak berkedip. Nol pada profil Normal/Cepat (langsung muncul).
 */
private fun MainActivity.fadeInBlocks(box: LinearLayout) {
    val p = StreamTempo.current
    if (p.blockFadeMs <= 0L) return
    var order = 0
    for (i in 0 until box.childCount) {
        val child = box.getChildAt(i)
        if (child !is android.view.ViewGroup || child.tag == "ai_action_header") continue
        child.alpha = 0f
        child.animate().alpha(1f).setStartDelay(order * p.blockStaggerMs).setDuration(p.blockFadeMs).start()
        order++
    }
}

/**
 * Tutup segmen teks live giliran ini TANPA memblokir thread agen. Bila huruf masih mengalir, penutupan ditunda sampai
 * ketikan selesai (dikerjakan penghalus); bila tidak, ditutup sekarang. Urutan terhadap baris aktivitas alat dijaga
 * oleh antrean CodexActivityUi, yang tidak menjalankan kejadian alat selama teks masih diketik.
 */
internal fun MainActivity.requestSegmentEnd() = runOnUiThread {
    if (streamBox == null) return@runOnUiThread
    if (typewriterRunnable != null) {
        segmentEndPending = true
    } else {
        streamSegmentEnd()
        zone.setLiveVisible(true)
    }
}

/** Menjalankan satu loop frame yang mengejar [typewriterTarget] secara halus. Aman dipanggil berulang. */
internal fun MainActivity.startSmoothing() {
    if (typewriterRunnable != null) return
    if (typewriterShown.isEmpty()) RevealClock.reset()
    var last = SystemClock.uptimeMillis()
    val pacer = StreamSmoother.Pacer()
    streamSkip = false
    val tick = object : Runnable {
        override fun run() {
            val box = streamBox
            if (box == null) { typewriterRunnable = null; return }
            val now = SystemClock.uptimeMillis()
            val dt = (now - last).coerceAtLeast(1L)
            last = now
            val target = typewriterTarget
            val shown = typewriterShown.length.coerceAtMost(target.length)
            pacer.boost = streamBoost
            val next = if (streamSkip) target.length else StreamSmoother.nextShown(pacer, target, shown, dt)
            val caughtUp = next >= target.length
            if (next > shown) {
                typewriterShown = target.substring(0, next)
                RevealClock.mark(next, now)
                updateStreamingText(box, typewriterShown, fade = true)
                scrollDown()
            } else if (!streamSkip && RevealClock.animating(now)) {
                // Teks sudah habis dikejar, tetapi huruf terakhir masih memudar masuk: lanjutkan sampai tuntas.
                updateStreamingText(box, typewriterShown, fade = true)
            }
            if (!caughtUp || (!streamSkip && RevealClock.animating(now))) {
                typewriterHandler.postDelayed(this, StreamSmoother.FRAME_MS)
            } else {
                typewriterRunnable = null
                streamSkip = false
                updateStreamingText(box, typewriterShown, fade = false)
                if (smoothFromCodex || codexActivityUi.hasQueuedEvents()) codexActivityUi.onTextSettled()
                // streamEnd() sudah dipanggil saat huruf masih mengalir: selesaikan sekarang, tanpa memotong ketikan.
                if (segmentEndPending) {
                    segmentEndPending = false
                    streamSegmentEnd()
                    zone.setLiveVisible(true)
                } else if (streamEnded && streamDirectRendering) finalizeDirectStream()
            }
        }
    }
    typewriterRunnable = tick
    typewriterHandler.post(tick)
}

/**
 * Akhiri satu item pesan Codex sebelum baris aktivitas alat berikutnya. Pesan selesai tetap berada di urutan
 * transcript; balasan berikutnya membuat pesan baru setelah baris aktivitas, seperti alur video referensi.
 */
internal fun MainActivity.streamSegmentEnd() = runOnUiThread {
    val box = streamBox
    segmentEndPending = false
    if (box == null) return@runOnUiThread
    typewriterHandler.removeCallbacksAndMessages(null)
    typewriterRunnable = null
    val finalText = typewriterTarget
    if (finalText.isNotBlank()) {
        fillMarkdown(box, finalText, withCopy = false, streaming = false)
        fadeInBlocks(box)
        decorateAgentBubble(box, active = false)
    } else {
        (box.parent as? android.view.ViewGroup)?.removeView(box)
    }
    streamBox = null
    streamText = ""
    typewriterShown = ""
    typewriterTarget = ""
    streamEnded = false
    streamCopyAdded = false
    scrollDown()
}

/** Ganti teks mengalir dengan hasil akhir berformat penuh (blok kode, tabel, tombol Salin) dan bersihkan keadaan stream. */
internal fun MainActivity.finalizeDirectStream() {
    typewriterHandler.removeCallbacksAndMessages(null)
    typewriterRunnable = null
    val box = streamBox
    val finalText = typewriterTarget.ifBlank { streamText }
    segmentEndPending = false
    streamBoost = 1.0
    if (box != null && finalText.isNotBlank()) {
        fillMarkdown(box, finalText, withCopy = false, streaming = false)
        fadeInBlocks(box)
        decorateAgentBubble(box, active = false)
        if (!streamCopyAdded) {
            streamCopyAdded = true
            addCopyRow(box, finalText)
        }
    }
    streamBox = null
    streamText = ""
    typewriterShown = ""
    typewriterTarget = ""
    streamEnded = false
    streamCopyAdded = false
    streamDirectRendering = false
    scrollDown()
}

internal fun MainActivity.streamEnd() = runOnUiThread {
    if (streamDirectRendering) {
        // Masih ada huruf yang mengalir: biarkan penghalus menyelesaikannya, lalu ia memanggil finalizeDirectStream().
        if (typewriterRunnable != null && streamBox != null) {
            streamEnded = true
            return@runOnUiThread
        }
        finalizeDirectStream()
        return@runOnUiThread
    }
    streamEnded = true
    if (streamBox == null || streamText.isBlank()) {
        typewriterHandler.removeCallbacksAndMessages(null)
        typewriterRunnable = null
        streamBox = null
        streamText = ""
        typewriterShown = ""
        typewriterTarget = ""
    } else if (typewriterRunnable == null) {
        // Pengaman jika streamEnd tiba setelah ticker terhenti akibat siklus UI.
        streamUpdate(typewriterTarget)
        streamEnded = true
    }
}

/**
 * Pemberitahuan kecil sistem berjalan senyap di latar belakang (tidak ditampilkan ke layar chat)
 * agar tampilan obrolan tetap bersih dan rapi, sementara seluruh logika agen tetap bekerja normal.
 */
internal fun MainActivity.addNote(t: String, tag: Any? = null) {
    if (t.isNotBlank()) android.util.Log.d("MYTHOS-Note", "[${tag ?: "note"}] ${redactSecrets(t)}")
}


/** Tampilkan gambar hasil tool di layar chat; bitmap di-decode di thread latar dengan inSampleSize. */
internal fun MainActivity.addImageCard(path: String, caption: String) = runOnUiThread {
    val file = File(path)
    if (!file.isFile) return@runOnUiThread
    emptyState.visibility = View.GONE

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Palette.CARD)
            cornerRadius = dp(12).toFloat()
            setStroke(1, Palette.BORDER)
        }
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    val radiusPx = dp(10).toFloat()
    val iv = ImageView(this).apply {
        adjustViewBounds = true
        maxHeight = dp(320)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Gambar hasil: $caption"
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        setOnClickListener { openGeneratedImage(file, caption) }
    }
    card.addView(iv, LinearLayout.LayoutParams(MATCH, WRAP))

    if (caption.isNotBlank()) {
        val tv = TextView(this).apply {
            text = caption
            textSize = Typo.CAPTION
            setTextColor(Palette.INK_SOFT)
            setPadding(dp(4), dp(6), dp(4), dp(2))
        }
        card.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    insertBeforeStatus(card, LinearLayout.LayoutParams(MATCH, WRAP).apply {
        topMargin = dp(12)
        marginStart = dp(4)
        marginEnd = dp(4)
    })
    scrollDown()

    bg {
        val bmp = decodeSampledBitmap(file, GambarTools.PREVIEW_MAX_SIDE) ?: return@bg
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                bmp.recycle()
                return@runOnUiThread
            }
            iv.setImageBitmap(bmp)
            scrollDown()
        }
    }
}

private fun decodeSampledBitmap(file: File, maxSide: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
    else {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = GambarTools.computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }
} catch (e: Exception) {
    null
}

private fun MainActivity.openGeneratedImage(file: File, caption: String) {
    if (!file.isFile) {
        toast("File gambar sudah tidak tersedia")
        return
    }
    val shareDir = File(cacheDir, "share")
    val inShare = try {
        file.canonicalPath.startsWith(shareDir.canonicalPath + File.separator)
    } catch (e: Exception) {
        false
    }
    if (inShare) {
        val uri = Uri.Builder().scheme("content").authority("$packageName.share").appendPath(file.name).build()
        val mime = WorkspaceList.mimeFor(file.name)
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            return
        } catch (_: Exception) {
            // ActivityNotFoundException atau SecurityException: lanjut ke dialog pratinjau penuh di dalam aplikasi.
        }
    }
    bg {
        val bmp = decodeSampledBitmap(file, GambarTools.PREVIEW_MAX_SIDE) ?: return@bg
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                bmp.recycle()
                return@runOnUiThread
            }
            val fullIv = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Gambar hasil: $caption"
                setImageBitmap(bmp)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            AlertDialog.Builder(this)
                .setTitle(caption.ifBlank { "Gambar hasil" })
                .setView(android.widget.ScrollView(this).apply { addView(fullIv) })
                .setPositiveButton("Tutup", null)
                .show()
        }
    }
}

/** UI-04: warna teks kontras di atas latar [tint]: lebih gelap di mode terang, lebih cerah di mode gelap. */
internal fun darkOf(c: Int): Int {
    if (Palette.dark) return mixArgb(c, 0xFFFFFFFF.toInt(), 0.45f)
    val r = ((c shr 16) and 0xFF) * 6 / 10
    val g = ((c shr 8) and 0xFF) * 6 / 10
    val b = (c and 0xFF) * 6 / 10
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
