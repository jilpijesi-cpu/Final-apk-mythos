package com.build.mythos

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.style.LineBackgroundSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/** Latar penuh selebar baris untuk tampilan diff. */
internal class DiffBg(private val color: Int) : LineBackgroundSpan {
    override fun drawBackground(
        c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lnum: Int
    ) {
        val old = p.color
        p.color = color
        c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), p)
        p.color = old
    }
}

/** Ikon sederhana yang digambar lewat kode (tanpa file gambar). */
class IconView(ctx: Context, t: Int, c: Int = Palette.TEXT_MAIN) : View(ctx) {
    var type: Int = t
        set(v) { field = v; invalidate() }
    var color: Int = c
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    // Objek gambar dipakai ulang di onDraw supaya tidak membuat objek baru tiap frame (DrawAllocation).
    private val rectTmp = RectF()
    private val pathA = Path()
    private val pathB = Path()
    private fun rf(l: Float, t: Float, r: Float, b: Float): RectF { rectTmp.set(l, t, r, b); return rectTmp }
    // Efek garis putus ikon NEW_CHAT: dibuat sekali per ukuran, bukan tiap frame.
    private var dashFx: DashPathEffect? = null
    private var dashCirc = -1f
    private fun dashFor(circ: Float): DashPathEffect {
        val cached = dashFx
        if (cached != null && dashCirc == circ) return cached
        return DashPathEffect(floatArrayOf(0.01f, circ / 14f - 0.01f), 0f).also { dashFx = it; dashCirc = circ }
    }

    companion object {
        const val MENU = 0
        const val PLUS = 1
        const val MIC = 2
        const val SEND = 3
        const val WAVE = 4
        const val NEW = 5
        const val CHEVRON = 6
        const val TERMINAL = 7
        const val CODE = 8
        const val CLOCK = 9
        const val TRASH = 10
        const val STOP = 11
        const val GRID = 12 // empat lingkaran: mencari / mengumpulkan informasi
        const val BLOB_TERM = 13 // awan berisi >_ : perintah terminal umum
        const val FILE = 14 // lembar dokumen: membaca file
        const val FOLDER = 15 // folder: melihat/mengatur isi folder
        const val GEAR = 16 // roda gigi: build (berputar saat berjalan)
        const val ARCHIVE = 17 // kotak arsip: zip/tar
        const val DOWNLOAD = 18 // panah ke bawah: pasang paket / unduh
        const val GLOBE = 19 // bola dunia: jaringan (curl, wget)
        const val CHECK = 20 // centang: uji / verifikasi
        const val CROSS = 21 // silang: gagal
        const val NEW_CHAT = 22 // lingkaran putus-putus + kapsul miring: chat baru
        const val CHEVRON_DOWN = 23 // panah kecil ke bawah
        const val BRAIN = 24 // analisis / berpikir
        const val BOOK = 25 // membaca dan memahami file
        const val WRITE = 26 // menulis atau mengubah file
        const val MENU3 = 27 // tiga garis, garis bawah lebih pendek (menu / riwayat)
        const val KEBAB = 28 // tiga titik vertikal (opsi)
        const val CHAT_PLUS = 29 // gelembung chat terisi dengan tanda + (chat baru)
        const val STOP_BOX = 30 // kotak berongga (hentikan)
        const val GHOST = 31 // hantu: menu chat (chat baru, pengaturan)
    }

    /** true = ikon "sedang bekerja": berdenyut (roda gigi: berputar). Berhenti sendiri saat dilepas dari layar. */
    var working: Boolean = false
        set(v) { field = v; if (v) { if (isAttachedToWindow) startWorking() } else stopWorking() }
    private var workAnim: ValueAnimator? = null

    private fun startWorking() {
        workAnim?.cancel()
        val spin = type == GEAR
        workAnim = (if (spin) ValueAnimator.ofFloat(0f, 360f) else ValueAnimator.ofFloat(0.35f, 1f)).apply {
            duration = if (spin) 1800L else 800L
            repeatCount = ValueAnimator.INFINITE
            if (spin) interpolator = LinearInterpolator() else repeatMode = ValueAnimator.REVERSE
            addUpdateListener { if (spin) rotation = it.animatedValue as Float else alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopWorking() {
        workAnim?.cancel()
        workAnim = null
        alpha = 1f
        rotation = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (working && workAnim == null) startWorking()
    }

    override fun onDetachedFromWindow() {
        workAnim?.cancel()
        workAnim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val iconStroke = 1.8f * resources.displayMetrics.density // satu ketebalan untuk semua ikon
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = iconStroke
        p.color = color
        when (type) {
            MENU -> {
                c.drawLine(cx - s * 0.22f, cy - s * 0.075f, cx + s * 0.22f, cy - s * 0.075f, p)
                c.drawLine(cx - s * 0.22f, cy + s * 0.075f, cx + s * 0.22f, cy + s * 0.075f, p)
            }
            PLUS -> {
                p.strokeWidth = iconStroke
                c.drawLine(cx - s * 0.28f, cy, cx + s * 0.28f, cy, p)
                c.drawLine(cx, cy - s * 0.28f, cx, cy + s * 0.28f, p)
            }
            MIC -> {
                p.strokeWidth = iconStroke
                c.drawRoundRect(rf(cx - s * 0.1f, cy - s * 0.3f, cx + s * 0.1f, cy + s * 0.05f), s * 0.1f, s * 0.1f, p)
                c.drawArc(rf(cx - s * 0.2f, cy - s * 0.16f, cx + s * 0.2f, cy + s * 0.2f), 0f, 180f, false, p)
                c.drawLine(cx, cy + s * 0.2f, cx, cy + s * 0.32f, p)
            }
            SEND -> {
                c.drawLine(cx, cy + s * 0.24f, cx, cy - s * 0.24f, p)
                c.drawLine(cx - s * 0.2f, cy - s * 0.04f, cx, cy - s * 0.24f, p)
                c.drawLine(cx + s * 0.2f, cy - s * 0.04f, cx, cy - s * 0.24f, p)
            }
            WAVE -> {
                p.strokeWidth = iconStroke
                c.drawLine(cx - s * 0.18f, cy - s * 0.1f, cx - s * 0.18f, cy + s * 0.1f, p)
                c.drawLine(cx, cy - s * 0.24f, cx, cy + s * 0.24f, p)
                c.drawLine(cx + s * 0.18f, cy - s * 0.14f, cx + s * 0.18f, cy + s * 0.14f, p)
            }
            NEW -> {
                c.drawRoundRect(rf(cx - s * 0.26f, cy - s * 0.2f, cx + s * 0.2f, cy + s * 0.26f), s * 0.08f, s * 0.08f, p)
                p.strokeWidth = iconStroke
                c.drawLine(cx - s * 0.04f, cy + s * 0.04f, cx + s * 0.26f, cy - s * 0.26f, p)
            }
            CHEVRON -> {
                c.drawLine(cx - s * 0.1f, cy - s * 0.2f, cx + s * 0.1f, cy, p)
                c.drawLine(cx + s * 0.1f, cy, cx - s * 0.1f, cy + s * 0.2f, p)
            }
            TERMINAL -> {
                p.strokeWidth = iconStroke
                c.drawRoundRect(rf(cx - s * 0.32f, cy - s * 0.24f, cx + s * 0.32f, cy + s * 0.24f), s * 0.06f, s * 0.06f, p)
                c.drawLine(cx - s * 0.18f, cy - s * 0.09f, cx - s * 0.08f, cy, p)
                c.drawLine(cx - s * 0.08f, cy, cx - s * 0.18f, cy + s * 0.09f, p)
                c.drawLine(cx, cy + s * 0.1f, cx + s * 0.16f, cy + s * 0.1f, p)
            }
            CODE -> {
                c.drawLine(cx - s * 0.12f, cy - s * 0.16f, cx - s * 0.28f, cy, p)
                c.drawLine(cx - s * 0.28f, cy, cx - s * 0.12f, cy + s * 0.16f, p)
                c.drawLine(cx + s * 0.12f, cy - s * 0.16f, cx + s * 0.28f, cy, p)
                c.drawLine(cx + s * 0.28f, cy, cx + s * 0.12f, cy + s * 0.16f, p)
                c.drawLine(cx + s * 0.04f, cy - s * 0.2f, cx - s * 0.04f, cy + s * 0.2f, p)
            }
            CLOCK -> {
                p.strokeWidth = iconStroke
                c.drawCircle(cx, cy, s * 0.3f, p)
                c.drawLine(cx, cy, cx, cy - s * 0.16f, p)
                c.drawLine(cx, cy, cx + s * 0.12f, cy + s * 0.06f, p)
            }
            TRASH -> {
                p.strokeWidth = iconStroke
                // tutup dan gagang
                c.drawLine(cx - s * 0.27f, cy - s * 0.17f, cx + s * 0.27f, cy - s * 0.17f, p)
                c.drawLine(cx - s * 0.08f, cy - s * 0.17f, cx - s * 0.08f, cy - s * 0.27f, p)
                c.drawLine(cx + s * 0.08f, cy - s * 0.17f, cx + s * 0.08f, cy - s * 0.27f, p)
                c.drawLine(cx - s * 0.08f, cy - s * 0.27f, cx + s * 0.08f, cy - s * 0.27f, p)
                // badan tempat sampah
                c.drawLine(cx - s * 0.2f, cy - s * 0.17f, cx - s * 0.16f, cy + s * 0.28f, p)
                c.drawLine(cx - s * 0.16f, cy + s * 0.28f, cx + s * 0.16f, cy + s * 0.28f, p)
                c.drawLine(cx + s * 0.16f, cy + s * 0.28f, cx + s * 0.2f, cy - s * 0.17f, p)
                // garis di dalam
                c.drawLine(cx - s * 0.06f, cy - s * 0.06f, cx - s * 0.06f, cy + s * 0.18f, p)
                c.drawLine(cx + s * 0.06f, cy - s * 0.06f, cx + s * 0.06f, cy + s * 0.18f, p)
            }
            GRID -> {
                p.strokeWidth = iconStroke
                val d = s * 0.17f
                val r = s * 0.1f
                c.drawCircle(cx - d, cy - d, r, p)
                c.drawCircle(cx + d, cy - d, r, p)
                c.drawCircle(cx - d, cy + d, r, p)
                c.drawCircle(cx + d, cy + d, r, p)
            }
            BLOB_TERM -> {
                // badan awan bergelombang (6 tonjolan) + tanda >_
                val path = pathA.apply { rewind() }
                val n = 72
                for (i in 0..n) {
                    val a = 2.0 * Math.PI * i / n
                    val r = s * 0.36f * (1f + 0.07f * cos(6.0 * a).toFloat())
                    val x = cx + r * cos(a).toFloat()
                    val y = cy + r * sin(a).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                p.strokeWidth = iconStroke
                c.drawPath(path, p)
                c.drawLine(cx - s * 0.17f, cy - s * 0.1f, cx - s * 0.06f, cy, p)
                c.drawLine(cx - s * 0.06f, cy, cx - s * 0.17f, cy + s * 0.1f, p)
                c.drawLine(cx + s * 0.02f, cy + s * 0.1f, cx + s * 0.16f, cy + s * 0.1f, p)
            }
            FILE -> {
                p.strokeWidth = iconStroke
                val path = pathA.apply { rewind() }
                path.moveTo(cx - s * 0.2f, cy - s * 0.3f)
                path.lineTo(cx + s * 0.08f, cy - s * 0.3f)
                path.lineTo(cx + s * 0.22f, cy - s * 0.16f)
                path.lineTo(cx + s * 0.22f, cy + s * 0.3f)
                path.lineTo(cx - s * 0.2f, cy + s * 0.3f)
                path.close()
                c.drawPath(path, p)
                c.drawLine(cx + s * 0.08f, cy - s * 0.3f, cx + s * 0.08f, cy - s * 0.16f, p)
                c.drawLine(cx + s * 0.08f, cy - s * 0.16f, cx + s * 0.22f, cy - s * 0.16f, p)
                c.drawLine(cx - s * 0.1f, cy + s * 0.02f, cx + s * 0.12f, cy + s * 0.02f, p)
                c.drawLine(cx - s * 0.1f, cy + s * 0.16f, cx + s * 0.12f, cy + s * 0.16f, p)
            }
            FOLDER -> {
                p.strokeWidth = iconStroke
                val path = pathA.apply { rewind() }
                path.moveTo(cx - s * 0.3f, cy - s * 0.2f)
                path.lineTo(cx - s * 0.1f, cy - s * 0.2f)
                path.lineTo(cx, cy - s * 0.1f)
                path.lineTo(cx + s * 0.3f, cy - s * 0.1f)
                path.lineTo(cx + s * 0.3f, cy + s * 0.22f)
                path.lineTo(cx - s * 0.3f, cy + s * 0.22f)
                path.close()
                c.drawPath(path, p)
            }
            GEAR -> {
                p.strokeWidth = iconStroke
                c.drawCircle(cx, cy, s * 0.19f, p)
                c.drawCircle(cx, cy, s * 0.07f, p)
                for (i in 0..7) {
                    val a = i * Math.PI / 4
                    val ca = cos(a).toFloat()
                    val sa = sin(a).toFloat()
                    c.drawLine(cx + s * 0.22f * ca, cy + s * 0.22f * sa, cx + s * 0.31f * ca, cy + s * 0.31f * sa, p)
                }
            }
            ARCHIVE -> {
                p.strokeWidth = iconStroke
                c.drawRoundRect(rf(cx - s * 0.3f, cy - s * 0.28f, cx + s * 0.3f, cy - s * 0.1f), s * 0.04f, s * 0.04f, p)
                c.drawRoundRect(rf(cx - s * 0.24f, cy - s * 0.1f, cx + s * 0.24f, cy + s * 0.28f), s * 0.04f, s * 0.04f, p)
                c.drawLine(cx - s * 0.08f, cy + s * 0.04f, cx + s * 0.08f, cy + s * 0.04f, p)
            }
            DOWNLOAD -> {
                c.drawLine(cx, cy - s * 0.26f, cx, cy + s * 0.1f, p)
                c.drawLine(cx - s * 0.14f, cy - s * 0.04f, cx, cy + s * 0.1f, p)
                c.drawLine(cx + s * 0.14f, cy - s * 0.04f, cx, cy + s * 0.1f, p)
                c.drawLine(cx - s * 0.26f, cy + s * 0.12f, cx - s * 0.26f, cy + s * 0.27f, p)
                c.drawLine(cx - s * 0.26f, cy + s * 0.27f, cx + s * 0.26f, cy + s * 0.27f, p)
                c.drawLine(cx + s * 0.26f, cy + s * 0.27f, cx + s * 0.26f, cy + s * 0.12f, p)
            }
            GLOBE -> {
                p.strokeWidth = iconStroke
                c.drawCircle(cx, cy, s * 0.3f, p)
                c.drawOval(rf(cx - s * 0.12f, cy - s * 0.3f, cx + s * 0.12f, cy + s * 0.3f), p)
                c.drawLine(cx - s * 0.3f, cy, cx + s * 0.3f, cy, p)
            }
            CHECK -> {
                p.strokeWidth = iconStroke
                c.drawLine(cx - s * 0.22f, cy + s * 0.02f, cx - s * 0.07f, cy + s * 0.17f, p)
                c.drawLine(cx - s * 0.07f, cy + s * 0.17f, cx + s * 0.24f, cy - s * 0.16f, p)
            }
            CROSS -> {
                p.strokeWidth = iconStroke
                c.drawLine(cx - s * 0.19f, cy - s * 0.19f, cx + s * 0.19f, cy + s * 0.19f, p)
                c.drawLine(cx + s * 0.19f, cy - s * 0.19f, cx - s * 0.19f, cy + s * 0.19f, p)
            }
            NEW_CHAT -> {
                val r = s * 0.36f
                val circ = (2.0 * Math.PI * r).toFloat()
                p.strokeWidth = iconStroke * 1.1f
                p.pathEffect = dashFor(circ)
                c.drawCircle(cx, cy, r, p)
                p.pathEffect = null
                p.strokeWidth = iconStroke
                c.save()
                c.rotate(-45f, cx, cy)
                c.drawRoundRect(rf(cx - s * 0.2f, cy - s * 0.085f, cx + s * 0.2f, cy + s * 0.085f), s * 0.085f, s * 0.085f, p)
                c.restore()
            }
            CHEVRON_DOWN -> {
                c.drawLine(cx - s * 0.2f, cy - s * 0.1f, cx, cy + s * 0.1f, p)
                c.drawLine(cx, cy + s * 0.1f, cx + s * 0.2f, cy - s * 0.1f, p)
            }
            BRAIN -> BrainGlyph.draw(c, cx, cy, s, p)   // sumber gambar yang sama dengan otak morf di baris live
            BOOK -> {
                c.drawLine(cx, cy - s * 0.25f, cx, cy + s * 0.27f, p)
                c.drawRoundRect(rf(cx - s * 0.3f, cy - s * 0.22f, cx - s * 0.03f, cy + s * 0.22f), s * 0.04f, s * 0.04f, p)
                c.drawRoundRect(rf(cx + s * 0.03f, cy - s * 0.22f, cx + s * 0.3f, cy + s * 0.22f), s * 0.04f, s * 0.04f, p)
            }
            WRITE -> {
                c.drawLine(cx - s * 0.22f, cy + s * 0.23f, cx + s * 0.22f, cy - s * 0.22f, p)
                c.drawLine(cx - s * 0.27f, cy + s * 0.28f, cx - s * 0.12f, cy + s * 0.23f, p)
                c.drawLine(cx + s * 0.15f, cy - s * 0.29f, cx + s * 0.27f, cy - s * 0.17f, p)
                c.drawLine(cx - s * 0.26f, cy + s * 0.29f, cx + s * 0.27f, cy + s * 0.29f, p)
            }
            MENU3 -> {
                // Tiga garis gaya Claude: dua garis penuh, garis bawah separuh panjang.
                c.drawLine(cx - s * 0.3f, cy - s * 0.24f, cx + s * 0.3f, cy - s * 0.24f, p)
                c.drawLine(cx - s * 0.3f, cy, cx + s * 0.3f, cy, p)
                c.drawLine(cx - s * 0.3f, cy + s * 0.24f, cx, cy + s * 0.24f, p)
            }
            GHOST -> {
                p.strokeWidth = iconStroke
                val ghost = pathA.apply {
                    rewind()
                    moveTo(cx - s * 0.34f, cy + s * 0.36f)
                    lineTo(cx - s * 0.34f, cy - s * 0.06f)
                    arcTo(rf(cx - s * 0.34f, cy - s * 0.40f, cx + s * 0.34f, cy + s * 0.28f), 180f, 180f)
                    lineTo(cx + s * 0.34f, cy + s * 0.36f)
                    lineTo(cx + s * 0.17f, cy + s * 0.27f)
                    lineTo(cx, cy + s * 0.36f)
                    lineTo(cx - s * 0.17f, cy + s * 0.27f)
                    close()
                }
                c.drawPath(ghost, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx - s * 0.12f, cy - s * 0.07f, s * 0.04f, p)
                c.drawCircle(cx + s * 0.12f, cy - s * 0.07f, s * 0.04f, p)
            }
            KEBAB -> {
                p.style = Paint.Style.FILL
                for (i in -1..1) c.drawCircle(cx, cy + i * s * 0.2f, s * 0.05f, p)
            }
            CHAT_PLUS -> {
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy - s * 0.02f, s * 0.36f, p)
                val tail = pathB.apply {
                    rewind()
                    moveTo(cx - s * 0.26f, cy + s * 0.2f)
                    lineTo(cx - s * 0.38f, cy + s * 0.38f)
                    lineTo(cx - s * 0.08f, cy + s * 0.31f)
                    close()
                }
                c.drawPath(tail, p)
                p.style = Paint.Style.STROKE
                p.color = Palette.BG
                c.drawLine(cx - s * 0.14f, cy - s * 0.02f, cx + s * 0.14f, cy - s * 0.02f, p)
                c.drawLine(cx, cy - s * 0.16f, cx, cy + s * 0.12f, p)
            }
            STOP_BOX -> {
                c.drawRoundRect(rf(cx - s * 0.17f, cy - s * 0.17f, cx + s * 0.17f, cy + s * 0.17f), s * 0.05f, s * 0.05f, p)
            }
            STOP -> {
                p.style = Paint.Style.FILL
                c.drawRoundRect(rf(cx - s * 0.17f, cy - s * 0.17f, cx + s * 0.17f, cy + s * 0.17f), s * 0.04f, s * 0.04f, p)
            }
        }
    }
}

/** Tiga titik berwarna yang memantul bergantian: penanda "sedang berpikir". Berhenti sendiri saat lepas dari layar. */
class DotsView(ctx: Context, dotColor: Int = Palette.INK) : View(ctx) {   // INK mengikuti tema (dulu hitam, tak terlihat di mode gelap)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(dotColor, dotColor, dotColor)
    private var phase = 0f
    private var anim: ValueAnimator? = null

    private fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cy = height / 2f
        val r = height * 0.11f
        for (i in 0..2) {
            // tiap titik naik-turun dengan jeda sepertiga siklus
            val t = ((phase - i / 3f) % 1f + 1f) % 1f
            val lift = sin(Math.PI * minOf(t * 2f, 1f)).toFloat() * (if (t < 0.5f) 1f else 0f)
            p.color = colors[i]
            c.drawCircle(width * (0.2f + 0.3f * i), cy - lift * height * 0.16f, r * (1f + 0.25f * lift), p)
        }
    }
}

/** Teks dengan efek kilau yang bergerak (shimmer) saat aktif. */
private const val SHIMMER_PERIOD_MS = 2600L
private const val SHIMMER_SWEEP = 0.86f // bagian siklus untuk menyapu; sisanya jeda sebentar sebelum gelombang berikutnya
private const val SHIMMER_BAND = 0.85f   // lebar pita gelombang relatif terhadap lebar teks
private const val SHIMMER_SLANT = 0.3f   // kemiringan pita (seperti berkas cahaya "/"): geser vertikal per geser horizontal

/**
 * Teks dengan gelombang cahaya: pita gradasi berprofil halus (gema redup + puncak terang) menyapu dari kiri ke kanan
 * dengan percepatan mulus (smoothstep), lalu jeda singkat. Kontras antara [shimmerBase] dan [shimmerHi] menentukan kedalaman efek.
 */
class ShimmerText(ctx: Context) : TextView(ctx) {
    private var anim: ValueAnimator? = null
    private var shader: LinearGradient? = null
    private var shaderKey = 0
    private val m = Matrix()
    private var t = 0f
    private var active = false

    /** Periode satu sapuan (ms). Bawaan 2600; mode penalaran mendalam memakai periode lebih pendek agar sapuan lebih intens. */
    var periodMs: Long = SHIMMER_PERIOD_MS
        set(v) { field = v; if (active && isAttachedToWindow) startAnim() }

    /** Warna dasar dan sorot kilau. Bawaan: warna merek. */
    var shimmerBase: Int = tint(Palette.BRAND, 0.5f)
        set(v) { field = v; shader = null; invalidate() }
    var shimmerHi: Int = Palette.BRAND
        set(v) { field = v; shader = null; invalidate() }
    /** Semburat cahaya di tepi depan gelombang; 0 = tanpa semburat. */
    var shimmerGlow: Int = 0
        set(v) { field = v; shader = null; invalidate() }

    fun setShimmer(v: Boolean) {
        active = v
        shader = null
        if (v && isAttachedToWindow) {
            startAnim()
        } else {
            stopAnim()
            paint.shader = null
            invalidate()
        }
    }

    private fun startAnim() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnim() {
        anim?.cancel()
        anim = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (active && width > 0) {
            // Gelombang menyapu sepanjang TEKS (bukan seluruh lebar view), supaya tidak banyak waktu terbuang di ruang kosong.
            val lineW = layout?.takeIf { it.lineCount > 0 }?.getLineWidth(0) ?: width.toFloat()
            val w = lineW.coerceIn(1f, width.toFloat())
            val bandW = w * SHIMMER_BAND
            val key = w.toInt()
            val sh = shader?.takeIf { shaderKey == key } ?: LinearGradient(
                0f, 0f, bandW, bandW * SHIMMER_SLANT, waveColors(shimmerBase, shimmerHi, shimmerGlow), WAVE_STOPS, Shader.TileMode.CLAMP
            ).also { shader = it; shaderKey = key }
            val p = (t / SHIMMER_SWEEP).coerceIn(0f, 1f)
            val e = p * p * (3f - 2f * p)
            m.setTranslate(-bandW + e * (w + bandW), 0f)
            sh.setLocalMatrix(m)
            paint.shader = sh
        }
        super.onDraw(c)
    }
}

/** Logo aplikasi (asterisk hitam; gambar dari res/drawable-nodpi/logo_mark.png). */
class LogoView(ctx: Context) : android.widget.ImageView(ctx) {
    init {
        setImageResource(R.drawable.logo_mark)
        scaleType = ScaleType.FIT_CENTER
    }
}
