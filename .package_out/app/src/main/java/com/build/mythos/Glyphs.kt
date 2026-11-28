package com.build.mythos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ikon garis tipis untuk drawer, lembar "Tambahkan ke chat", dan layar kosong.
 * Digambar pada kisi 24x24 lalu diskalakan, jadi tebal garis sama di semua ukuran.
 */
class GlyphView(ctx: Context, kind: Int, c: Int = Palette.INK) : View(ctx) {
    var kind: Int = kind
        set(v) { field = v; invalidate() }
    var color: Int = c
        set(v) { field = v; invalidate() }

    /** Tebal garis dalam dp. */
    var strokeDp: Float = 1.6f
        set(v) { field = v; invalidate() }

    /** Warna mata untuk hantu padat ([GHOST_SOLID]). */
    var eyeColor: Int = Palette.BG
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    companion object {
        const val CAMERA = 0
        const val IMAGE = 1
        const val FILE_UP = 2
        const val PHOTOS = 3
        const val PROJECT = 4
        const val CODE = 5
        const val ARTIFACT = 6
        const val CHATS = 7
        const val BUBBLE = 8
        const val CHEVRON_R = 9
        const val CLOSE = 10
        const val PLUS = 11
        const val GHOST_SOLID = 12
        const val BRAIN = 13
        const val COPY = 14
        const val SHARE = 15
        const val PLAY = 16
        const val THUMB_UP = 17
        const val THUMB_DOWN = 18
        const val RETRY = 19
        const val GLOBE = 20
        const val PLUS_CIRCLE = 21   // plus dalam lingkaran: tambah lampiran
        const val EYE_OFF = 22       // mata dicoret: Chat samaran
        const val APPS = 23          // tiga kotak + belah ketupat: menu semua pengaturan
        const val SEND_LINE = 24     // pesawat kertas garis: kirim
        const val MIC_LINE = 25      // mikrofon garis tipis
        const val STOP_LINE = 26     // kotak bulat berongga: hentikan
        const val SLIDERS = 27       // pengaturan
        const val NEW_CHAT_LINE = 28 // gelembung + tanda plus: chat baru
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val sc = min(width, height) / 24f
        if (sc <= 0f) return
        c.save()
        c.translate((width - 24f * sc) / 2f, (height - 24f * sc) / 2f)
        c.scale(sc, sc)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = strokeDp * resources.displayMetrics.density / sc
        p.color = color
        draw24(c)
        c.restore()
    }

    private fun poly(c: Canvas, vararg xy: Float) {
        val path = Path()
        path.moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) { path.lineTo(xy[i], xy[i + 1]); i += 2 }
        c.drawPath(path, p)
    }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float) =
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)

    /** Jempol ke atas (kisi 24x24); jempol ke bawah memakai gambar ini dibalik. */
    private fun drawThumb(c: Canvas) {
        rr(c, 3.5f, 10f, 7.5f, 20f, 1.5f)
        poly(c, 7.5f, 11f, 11f, 4.5f, 12.5f, 4.5f, 13.5f, 5.5f, 13.5f, 9.5f, 19f, 9.5f, 20.5f, 11f, 19.5f, 18.5f, 18f, 20f, 7.5f, 20f)
    }

    private fun draw24(c: Canvas) {
        when (kind) {
            CAMERA -> {
                rr(c, 3f, 7.5f, 21f, 19.5f, 3f)
                poly(c, 8f, 7.5f, 9.4f, 5f, 14.6f, 5f, 16f, 7.5f)
                c.drawCircle(12f, 13.5f, 3.3f, p)
            }
            IMAGE -> {
                rr(c, 3.5f, 3.5f, 20.5f, 20.5f, 3f)
                c.drawCircle(9f, 9f, 1.4f, p)
                poly(c, 4f, 17f, 9.5f, 12.5f, 13f, 15.5f, 15.5f, 13f, 20f, 17f)
            }
            FILE_UP -> {
                poly(c, 6.5f, 2.8f, 13.8f, 2.8f, 18.7f, 7.7f, 18.7f, 19.5f)
                poly(c, 6.5f, 2.8f, 5.3f, 3.2f, 5.3f, 20.8f, 6.5f, 21.2f, 17.5f, 21.2f, 18.7f, 20.5f, 18.7f, 19.5f)
                poly(c, 13.8f, 2.8f, 13.8f, 7.7f, 18.7f, 7.7f)
                poly(c, 12f, 17.5f, 12f, 10.8f)
                poly(c, 9.3f, 13.4f, 12f, 10.7f, 14.7f, 13.4f)
            }
            PHOTOS -> {
                rr(c, 8f, 3f, 21f, 16f, 2.6f)
                rr(c, 3f, 8f, 16f, 21f, 2.6f)
                c.drawCircle(7.3f, 12.3f, 1.1f, p)
                poly(c, 3.6f, 18.6f, 7.5f, 15.2f, 10.2f, 17.4f, 12.3f, 15.5f, 15.6f, 18.6f)
            }
            PROJECT -> {
                poly(c, 8.2f, 3.4f, 15.8f, 3.4f)
                poly(c, 6f, 6.8f, 18f, 6.8f)
                rr(c, 4.2f, 9.6f, 19.8f, 20.6f, 2.8f)
            }
            CODE -> {
                poly(c, 8f, 7f, 3.5f, 12f, 8f, 17f)
                poly(c, 16f, 7f, 20.5f, 12f, 16f, 17f)
                poly(c, 13.6f, 5.2f, 10.4f, 18.8f)
            }
            ARTIFACT -> {
                c.drawCircle(7.8f, 16f, 4.2f, p)
                rr(c, 14f, 12.6f, 20.6f, 20.4f, 1.6f)
                val tri = Path().apply {
                    moveTo(8.2f, 9.6f); lineTo(12.2f, 3.4f); lineTo(16.4f, 9.6f)
                }
                c.drawPath(tri, p)
            }
            CHATS -> {
                c.drawCircle(9.8f, 9.8f, 6.3f, p)
                poly(c, 4.9f, 14.2f, 3.4f, 17.4f, 7f, 16.2f)
                val arc = Path()
                arc.addArc(RectF(9f, 9f, 20.6f, 20.6f), -35f, 160f)
                c.drawPath(arc, p)
                poly(c, 17.6f, 19.6f, 20.2f, 21.2f, 19.8f, 18.2f)
            }
            BUBBLE -> {
                c.drawCircle(12f, 11.4f, 8f, p)
                poly(c, 5.8f, 16.6f, 4.2f, 20.4f, 8.8f, 18.9f)
            }
            CHEVRON_R -> poly(c, 9.5f, 5.5f, 16f, 12f, 9.5f, 18.5f)
            CLOSE -> {
                poly(c, 6f, 6f, 18f, 18f)
                poly(c, 18f, 6f, 6f, 18f)
            }
            PLUS -> {
                poly(c, 12f, 5f, 12f, 19f)
                poly(c, 5f, 12f, 19f, 12f)
            }
            BRAIN -> {
                // Mata mengintai + 3 bola oranye di sekitarnya (Penalaran mendalam, koordinat 24x24).
                val eye = Path().apply {
                    moveTo(3.2f, 12.6f)
                    cubicTo(8.54f, 6.45f, 15.46f, 6.45f, 20.8f, 12.6f)
                    cubicTo(15.46f, 18.75f, 8.54f, 18.75f, 3.2f, 12.6f)
                    close()
                }
                c.drawPath(eye, p)
                // Iris hitam tebal + pupil putih (cincin tebal) seperti logo Mythos.
                val eyeStroke = p.strokeWidth
                p.strokeWidth = 2.35f
                c.drawCircle(12f, 12.6f, 2.76f, p)
                p.strokeWidth = eyeStroke
                val prevCol = p.color
                val prevStyle = p.style
                p.color = Palette.ORANGE_DOT
                p.style = Paint.Style.FILL
                c.drawCircle(5.4f, 5.8f, 1.45f, p)
                c.drawCircle(19.0f, 6.2f, 1.45f, p)
                c.drawCircle(16.4f, 19.6f, 1.45f, p)
                p.color = prevCol
                p.style = prevStyle
            }
            COPY -> {
                rr(c, 8.5f, 8.5f, 19.5f, 19.5f, 3f)
                poly(c, 15.5f, 8.5f, 15.5f, 6.5f, 13.5f, 4.5f, 6.5f, 4.5f, 4.5f, 6.5f, 4.5f, 13f, 6.5f, 15.5f, 8.5f, 15.5f)
            }
            SHARE -> {
                poly(c, 12f, 15f, 12f, 4f)
                poly(c, 8f, 8f, 12f, 4f, 16f, 8f)
                poly(c, 5f, 13f, 5f, 18.5f, 6.5f, 20f, 17.5f, 20f, 19f, 18.5f, 19f, 13f)
            }
            PLAY -> poly(c, 8f, 5f, 19f, 12f, 8f, 19f, 8f, 5f)
            THUMB_UP -> drawThumb(c)
            THUMB_DOWN -> {
                c.save()
                c.translate(0f, 24f)
                c.scale(1f, -1f)
                drawThumb(c)
                c.restore()
            }
            GLOBE -> {
                c.drawCircle(12f, 12f, 8.5f, p)
                poly(c, 3.5f, 12f, 20.5f, 12f)
                c.drawOval(RectF(8f, 3.5f, 16f, 20.5f), p)
            }
            RETRY -> {
                c.drawArc(RectF(5f, 5f, 19f, 19f), -50f, 290f, false, p)
                poly(c, 19.5f, 3.5f, 19.5f, 7.5f, 15.5f, 7.5f)
            }
            PLUS_CIRCLE -> {
                c.drawCircle(12f, 12f, 9.2f, p)
                poly(c, 12f, 7.6f, 12f, 16.4f)
                poly(c, 7.6f, 12f, 16.4f, 12f)
            }
            EYE_OFF -> {
                val eye = Path().apply {
                    moveTo(2.8f, 12.6f)
                    cubicTo(5.2f, 7.4f, 11.2f, 6.2f, 15.2f, 8.2f)
                    moveTo(21.2f, 11.4f)
                    cubicTo(19.2f, 16.2f, 13.4f, 18.2f, 8.6f, 16.2f)
                    moveTo(2.8f, 12.6f)
                    cubicTo(3.6f, 14.2f, 5f, 15.5f, 6.8f, 16.2f)
                }
                c.drawPath(eye, p)
                c.drawCircle(9.4f, 12.4f, 2.2f, p)
                poly(c, 4.6f, 3.6f, 19.6f, 20.4f)
                poly(c, 15.2f, 11.2f, 17.6f, 11.2f, 19.4f, 9.6f)
            }
            APPS -> {
                rr(c, 3.6f, 4.4f, 9.4f, 10.2f, 1.1f)
                rr(c, 3.6f, 13.8f, 9.4f, 19.6f, 1.1f)
                rr(c, 13.2f, 13.8f, 19f, 19.6f, 1.1f)
                val dia = Path().apply {
                    moveTo(16.1f, 3.4f); lineTo(20.4f, 7.3f); lineTo(16.1f, 11.2f); lineTo(11.8f, 7.3f); close()
                }
                c.drawPath(dia, p)
            }
            SEND_LINE -> {
                val plane = Path().apply {
                    moveTo(3.2f, 11.4f); lineTo(20.6f, 3.6f); lineTo(14.2f, 20.6f)
                    lineTo(11.4f, 13.4f); close()
                }
                c.drawPath(plane, p)
                poly(c, 11.4f, 13.4f, 20.6f, 3.6f)
            }
            MIC_LINE -> {
                rr(c, 9f, 2.8f, 15f, 14.6f, 3f)
                val arc = Path().apply { addArc(RectF(5.2f, 7.4f, 18.8f, 19.4f), 0f, 180f) }
                c.drawPath(arc, p)
                poly(c, 12f, 19.4f, 12f, 22f)
            }
            STOP_LINE -> rr(c, 6.2f, 6.2f, 17.8f, 17.8f, 2.6f)
            SLIDERS -> {
                poly(c, 4f, 7f, 20f, 7f)
                poly(c, 4f, 17f, 20f, 17f)
                c.drawCircle(9f, 7f, 2.4f, p)
                c.drawCircle(15f, 17f, 2.4f, p)
            }
            NEW_CHAT_LINE -> {
                val bubble = Path().apply {
                    moveTo(12.6f, 4.4f); lineTo(8f, 4.4f)
                    cubicTo(5.8f, 4.4f, 4.4f, 5.8f, 4.4f, 8f); lineTo(4.4f, 14.2f)
                    cubicTo(4.4f, 16.4f, 5.8f, 17.8f, 8f, 17.8f); lineTo(9.4f, 17.8f)
                    lineTo(9.4f, 21f); lineTo(13.4f, 17.8f); lineTo(16f, 17.8f)
                    cubicTo(18.2f, 17.8f, 19.6f, 16.4f, 19.6f, 14.2f); lineTo(19.6f, 12.4f)
                }
                c.drawPath(bubble, p)
                poly(c, 18f, 3f, 18f, 9f)
                poly(c, 15f, 6f, 21f, 6f)
            }
            GHOST_SOLID -> {
                p.style = Paint.Style.FILL
                val s = 24f
                val cx = 12f
                val cy = 12f
                val ghost = Path().apply {
                    moveTo(cx - s * 0.36f, cy + s * 0.38f)
                    lineTo(cx - s * 0.36f, cy - s * 0.04f)
                    arcTo(RectF(cx - s * 0.36f, cy - s * 0.42f, cx + s * 0.36f, cy + s * 0.30f), 180f, 180f)
                    lineTo(cx + s * 0.36f, cy + s * 0.38f)
                    lineTo(cx + s * 0.18f, cy + s * 0.28f)
                    lineTo(cx, cy + s * 0.38f)
                    lineTo(cx - s * 0.18f, cy + s * 0.28f)
                    close()
                }
                c.drawPath(ghost, p)
                p.color = eyeColor
                c.drawCircle(cx - s * 0.12f, cy - s * 0.07f, s * 0.05f, p)
                c.drawCircle(cx + s * 0.12f, cy - s * 0.07f, s * 0.05f, p)
            }
        }
    }
}

/** Keadaan animasi mata Mythos yang mengawasi: [openness] 0.05..1.0 (kedipan), [gazeX]/[gazeY] -1..1 (gerak bola mata). */
internal data class WatchfulEyeState(val openness: Float, val gazeX: Float, val gazeY: Float)

/** Hitung posisi bola mata (melirik kiri/kanan/atas/bawah seperti mengawasi) dan kedipan alami dari waktu [timeMs]. */
internal fun watchfulEyeState(timeMs: Long): WatchfulEyeState {
    // 1. Kedipan alami: siklus 4,6 detik (terbuka penuh, kedip cepat ~220ms; tiap siklus ke-2 ada kedip ganda).
    val blinkCycle = 4600L
    val cycleIdx = (timeMs / blinkCycle)
    val bt = (timeMs % blinkCycle).toFloat()
    fun blinkDip(pos: Float, center: Float, halfDur: Float): Float {
        val d = kotlin.math.abs(pos - center)
        if (d >= halfDur) return 1f
        val u = d / halfDur // 0 di puncak tutup, 1 di tepi terbuka
        val smooth = u * u * (3f - 2f * u)
        return 0.06f + 0.94f * smooth
    }
    var openness = blinkDip(bt, 3900f, 115f)
    if (cycleIdx % 2L == 1L) {
        openness = minOf(openness, blinkDip(bt, 4220f, 100f))
    }

    // 2. Gerak bola mata mengawasi (saccade + jeda mengamati, transisi cubic ease-in-out).
    // Keyframe: (waktu_ms, gazeX, gazeY) dalam siklus 9,6 detik.
    val gazeCycle = 9600L
    val gt = (timeMs % gazeCycle).toFloat()
    val kfT = floatArrayOf(0f, 900f, 1350f, 2600f, 3050f, 4300f, 4750f, 5900f, 6350f, 7500f, 7950f, 9600f)
    val kfX = floatArrayOf(0f, 0f, -0.82f, -0.82f, 0.85f, 0.85f, 0.15f, 0.15f, -0.62f, -0.62f, 0f, 0f)
    val kfY = floatArrayOf(0f, 0f, -0.18f, -0.18f, 0.12f, 0.12f, -0.32f, -0.32f, 0.25f, 0.25f, 0f, 0f)
    var gx = 0f
    var gy = 0f
    for (i in 0 until kfT.size - 1) {
        if (gt >= kfT[i] && gt <= kfT[i + 1]) {
            val span = (kfT[i + 1] - kfT[i]).coerceAtLeast(1f)
            val u = ((gt - kfT[i]) / span).coerceIn(0f, 1f)
            val s = u * u * (3f - 2f * u)
            gx = kfX[i] + (kfX[i + 1] - kfX[i]) * s
            gy = kfY[i] + (kfY[i + 1] - kfY[i]) * s
            break
        }
    }
    return WatchfulEyeState(openness, gx, gy)
}

// Geometri logo Mythos (diukur dari gambar logo; semua relatif terhadap setengah lebar mata luar = 1).
private const val MYTHOS_EYE_CTRL_X = 0.393f        // posisi x titik kontrol kurva kelopak (tumpul di tengah, lancip di ujung)
private const val MYTHOS_EYE_CTRL_Y = 1.32f         // pengali tinggi titik kontrol kurva kelopak
private const val MYTHOS_EYE_INNER_W = 0.727f       // setengah lebar sklera putih / setengah lebar mata luar (sebelum garis pembulat ujung)
private const val MYTHOS_EYE_INNER_H_RATIO = 0.64f  // tinggi sklera putih / tinggi mata luar (sebelum garis pembulat ujung)
private const val MYTHOS_EYE_IRIS_R = 0.447f        // jari-jari iris hitam
private const val MYTHOS_EYE_PUPIL_R = 0.18f        // jari-jari pupil putih
private const val MYTHOS_EYE_TIP_ROUND = 0.05f      // lebar garis pembulat ujung lancip (logo punya ujung tumpul, bukan runcing)

/** Rasio setengah tinggi / setengah lebar mata Mythos saat terbuka penuh (dipakai pemanggil untuk menentukan halfH). */
internal const val MYTHOS_EYE_H_RATIO = 0.53f

private val eyeInnerScratch = Path()

private fun almondPath(p: Path, cx: Float, cy: Float, w: Float, h: Float) {
    p.reset()
    p.moveTo(cx - w, cy)
    p.cubicTo(cx - w * MYTHOS_EYE_CTRL_X, cy - h * MYTHOS_EYE_CTRL_Y, cx + w * MYTHOS_EYE_CTRL_X, cy - h * MYTHOS_EYE_CTRL_Y, cx + w, cy)
    p.cubicTo(cx + w * MYTHOS_EYE_CTRL_X, cy + h * MYTHOS_EYE_CTRL_Y, cx - w * MYTHOS_EYE_CTRL_X, cy + h * MYTHOS_EYE_CTRL_Y, cx - w, cy)
    p.close()
}

/**
 * Gambar ikon mata Mythos persis seperti logo: kelopak almond hitam pekat, dua sklera putih lancip di kiri-kanan,
 * iris hitam bulat besar dengan pupil putih di tengah. Mata berkedip (kelopak menutup) dan bola mata (iris + pupil)
 * melirik mengawasi; sklera kiri/kanan otomatis menyempit-melebar mengikuti arah lirikan. Dipakai pada layar kosong chat
 * ([BurstView]), kursor live saat AI mengetik, dan indikator penalaran.
 *
 * [halfH] = setengah tinggi mata saat terbuka penuh; gunakan `halfW * MYTHOS_EYE_H_RATIO` agar proporsinya sama dengan logo.
 */
internal fun drawWatchfulMythosEye(
    c: Canvas,
    cx: Float,
    cy: Float,
    halfW: Float,
    halfH: Float,
    timeMs: Long,
    inkColor: Int = Palette.INK,
    pupilFillColor: Int = Palette.BG,
    strokePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG),
    fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG),
    path: Path = Path()
) {
    val st = watchfulEyeState(timeMs)
    val outerH = (halfH * st.openness).coerceAtLeast(halfH * 0.05f)
    val inkAlpha = (inkColor ushr 24) and 0xFF
    val ink = inkColor or (0xFF shl 24)
    val bg = pupilFillColor or (0xFF shl 24)

    almondPath(path, cx, cy, halfW, outerH)
    // Sklera ikut menyempit lebih cepat saat berkedip, sehingga mata tertutup benar-benar hitam seperti kelopak menutup.
    almondPath(eyeInnerScratch, cx, cy, halfW * MYTHOS_EYE_INNER_W, outerH * MYTHOS_EYE_INNER_H_RATIO * st.openness)
    val round = (halfW * MYTHOS_EYE_TIP_ROUND).coerceAtLeast(0.6f)

    // Bila ink transparan sebagian (fade), gambar utuh di layer lalu gabungkan sekali supaya tidak ada tumpang-tindih warna.
    val layered = inkAlpha < 255
    val saved = if (layered) {
        c.saveLayerAlpha(cx - halfW - 4f, cy - halfH - 4f, cx + halfW + 4f, cy + halfH + 4f, inkAlpha)
    } else {
        c.save()
    }

    strokePaint.style = Paint.Style.STROKE
    strokePaint.strokeJoin = Paint.Join.ROUND
    strokePaint.strokeCap = Paint.Cap.ROUND
    strokePaint.strokeWidth = round

    // 1. Kelopak luar hitam pekat (ujung kiri/kanan membulat tumpul seperti logo).
    fillPaint.style = Paint.Style.FILL
    fillPaint.color = ink
    c.drawPath(path, fillPaint)
    strokePaint.color = ink
    c.drawPath(path, strokePaint)

    // 2. Sklera putih; ujungnya juga dibulatkan. Dua sliver kiri-kanan terbentuk setelah iris hitam digambar di atasnya.
    if (st.openness > 0.12f) {
        fillPaint.color = bg
        c.drawPath(eyeInnerScratch, fillPaint)
        strokePaint.color = bg
        strokePaint.strokeWidth = round * st.openness
        c.drawPath(eyeInnerScratch, strokePaint)
    }

    // 3. Iris hitam bulat besar (dipotong kelopak luar) lalu pupil putih (dipotong sklera) bergerak melirik.
    val eyeX = cx + st.gazeX * halfW * 0.20f
    val eyeY = cy + st.gazeY * outerH * 0.10f
    c.save()
    c.clipPath(path)
    fillPaint.color = ink
    c.drawCircle(eyeX, eyeY, halfW * MYTHOS_EYE_IRIS_R, fillPaint)
    c.restore()
    c.save()
    c.clipPath(eyeInnerScratch)
    fillPaint.color = bg
    c.drawCircle(eyeX, eyeY, halfW * MYTHOS_EYE_PUPIL_R, fillPaint)
    c.restore()

    c.restoreToCount(saved)
}

/**
 * Ikon mata Mythos pada layar kosong chat: berkedip-kedip alami dan bola matanya bergerak seperti mengawasi.
 */
class BurstView(ctx: Context, private val tone: Int = Palette.INK) : View(ctx) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePath = Path()
    private var reducedMotion = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reducedMotion = try {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val halfW = min(width * 0.43f, height * 0.82f)
        val halfH = halfW * MYTHOS_EYE_H_RATIO
        val tMs = if (reducedMotion) 0L else android.os.SystemClock.uptimeMillis()
        drawWatchfulMythosEye(
            c = c,
            cx = cx,
            cy = cy,
            halfW = halfW,
            halfH = halfH,
            timeMs = tMs,
            inkColor = Palette.INK,
            pupilFillColor = Palette.BG,
            strokePaint = strokePaint,
            fillPaint = fillPaint,
            path = eyePath
        )
        // 30 fps cukup mulus untuk kedip dan lirikan, dan hemat baterai saat layar kosong dibiarkan terbuka lama.
        if (isAttachedToWindow && !reducedMotion) postInvalidateDelayed(33L)
    }
}
