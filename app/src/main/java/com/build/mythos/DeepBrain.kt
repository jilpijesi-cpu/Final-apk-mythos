package com.build.mythos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Penalaran mendalam: bola oranye pada ikon status live menyebar, bergerak terkoordinasi, lalu menyatu menjadi satu ikon otak
// oranye pekat (dua belahan + lipatan korteks) yang berdenyut halus. Dimatikan: otak terurai kembali menjadi bola-bola asal.
// Tanpa cahaya/glow/kilau pada otak. Berkas ini: matematika murni (teruji), pembuat bitmap otak, dan pemetaan tahap penalaran
// dari keadaan agen yang NYATA. Penggambaran ada di ClaudeDotsView; teks dua baris di LiveStatusLine.

internal object BrainMath {
    const val FORM_MS = 1500L
    const val UNFORM_MS = 1000L
    /** Siklus selama penalaran mendalam: bola -> otak -> berdenyut [HOLD_BEATS] kali -> memisah jadi bola -> melayang [FLOAT_MS] -> menyatu lagi. */
    const val PULSE_PERIOD_S = 1.2f
    const val HOLD_BEATS = 4
    const val HOLD_MS = 4800L // = HOLD_BEATS x PULSE_PERIOD_S x 1000 (ditulis literal agar bebas galat pembulatan Float)
    const val FLOAT_MS = 2200L
    /** Posisi akhir tiga bola (satuan: setengah lebar otak; y ke bawah): belahan kiri, belahan kanan, bawah. */
    val TX = floatArrayOf(-0.30f, 0.30f, 0f)
    val TY = floatArrayOf(-0.05f, -0.05f, 0.34f)

    private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
    fun ease(x: Float): Float { val f = clamp01(x); return f * f * (3f - 2f * f) }

    /** Kemajuan morf: maju (membentuk) atau mundur (terurai); hasil selalu 0..1. */
    fun advance(p: Float, dtMs: Long, forward: Boolean): Float {
        val d = dtMs.coerceAtLeast(0L).toFloat()
        return if (forward) (p + d / FORM_MS).coerceAtMost(1f) else (p - d / UNFORM_MS).coerceAtLeast(0f)
    }

    /** Seberapa jauh bola menyebar keluar dari orbit: 0 di p=0, puncak di tengah fase sebar, kembali 0 saat menyatu. */
    fun spread(p: Float): Float = if (p >= 0.55f) 0f else sin(PI.toFloat() * clamp01(p / 0.55f))

    /** Arah sebar bola ke-[i] (radian): tiga arah berjarak 120 derajat. */
    fun spreadAngle(i: Int): Float = (-90f + 120f * i) * PI.toFloat() / 180f

    /** Bobot gerak menuju posisi bentuk otak (mulai setelah sebar). */
    fun converge(p: Float): Float = ease((p - 0.30f) / 0.50f)

    /** Pengali ukuran bola: 1 pada p=0 (sama dengan mode normal), membesar saat menyatu, lalu menyusut habis ke otak. */
    fun dotScale(p: Float): Float = when {
        p <= 0f -> 1f
        p < 0.30f -> 1f + 0.25f * ease(p / 0.30f)
        p < 0.80f -> 1.25f + 0.45f * ease((p - 0.30f) / 0.50f)
        p < 0.97f -> 1.70f * (1f - ease((p - 0.80f) / 0.17f))
        else -> 0f
    }

    /** Keburaman otak: muncul bersamaan dengan bola yang menyusut. */
    fun brainAlpha(p: Float): Float = ease((p - 0.62f) / 0.33f)

    /** Satu sinus-kuadrat dengan bobot: 0 di luar [a, b], puncak di tengah. Mulus (turunan 0) di kedua ujung. */
    private fun bump(u: Float, a: Float, b: Float): Float {
        if (u <= a || u >= b) return 0f
        val s = sin(PI.toFloat() * (u - a) / (b - a))
        return s * s
    }

    /** Bentuk satu detak 0..1 dalam satu periode: "lub" kuat lalu "dub" lebih lembut, kemudian jeda tenang. Tepat 0 di awal dan akhir. */
    fun beat(tSec: Float): Float {
        val u = ((tSec / PULSE_PERIOD_S) % 1f + 1f) % 1f
        return (bump(u, 0f, 0.30f) + 0.55f * bump(u, 0.30f, 0.55f)).coerceIn(0f, 1f)
    }

    /** Denyut sejak otak terbentuk (tSec = detik sejak terbentuk): skala 1,0..1,045 mengikuti detak jantung. Mulai dan selesai di 1,0 tiap denyut. */
    fun pulse(tSec: Float): Float = 1f + 0.045f * beat(tSec)

    /** Intensitas warna ikut denyut: 0,93 saat tenang, 1,0 di puncak detak. */
    fun pulseAlpha(tSec: Float): Float = 0.93f + 0.07f * beat(tSec)

    /**
     * Keputusan siklus di ujung gerak. [forward] true = sedang menuju otak. Sudah berdenyut cukup lama sebagai otak -> false (memisah);
     * sudah cukup lama melayang sebagai bola -> true (menyatu lagi); selain itu null (tetap).
     */
    fun cycleDir(forward: Boolean, p: Float, waitedMs: Long): Boolean? = when {
        forward && p >= 1f && waitedMs >= HOLD_MS -> false
        !forward && p <= 0f && waitedMs >= FLOAT_MS -> true
        else -> null
    }
}

/** Tahap penalaran (baris bawah) dari keadaan agen nyata; null = tidak ada perubahan. */
internal val DEEP_STAGE_TEXT = arrayOf("Analyzing...", "Planning...", "Executing...", "Verifying...")

internal fun deepStageFor(s: AgentState, analyzing: Boolean): Int? {
    if (analyzing) return 0
    return when (s) {
        is AgentState.Planning, is AgentState.CallingModel -> 1
        is AgentState.RunningTool, is AgentState.AwaitingApproval -> 2
        is AgentState.Verifying -> 3
        else -> null
    }
}

internal fun MainActivity.notifyDeepStage() {
    val st = deepStageFor(agentState, deepAnalyzing) ?: return
    runOnUiThread {
        try { zone.deepStage(st) } catch (e: UninitializedPropertyAccessException) { /* tampilan belum dibangun */ }
    }
}

/** Ikon garis mata mengintai + 3 titik kecil di sekitarnya untuk tombol/indikator Penalaran mendalam. */
internal object BrainGlyph {
    fun draw(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val path = Path()
        val hw = s * 0.34f
        val hh = s * 0.18f
        path.moveTo(cx - hw, cy)
        path.cubicTo(cx - hw * 0.45f, cy - hh * 1.25f, cx + hw * 0.45f, cy - hh * 1.25f, cx + hw, cy)
        path.cubicTo(cx + hw * 0.45f, cy + hh * 1.25f, cx - hw * 0.45f, cy + hh * 1.25f, cx - hw, cy)
        path.close()
        c.drawPath(path, p)
        c.drawCircle(cx, cy, s * 0.095f, p)
        val prevStyle = p.style
        p.style = Paint.Style.FILL
        val dr = (s * 0.045f).coerceAtLeast(1.4f)
        c.drawCircle(cx - hw * 0.72f, cy - hh * 1.25f, dr, p)
        c.drawCircle(cx + hw * 0.78f, cy - hh * 0.95f, dr, p)
        c.drawCircle(cx + hw * 0.25f, cy + hh * 1.35f, dr, p)
        p.style = prevStyle
    }
}

/** Ikon otak oranye pekat di baris live: dua belahan dari gabungan lobus, celah tengah, dan lipatan korteks yang dilubangi. */
internal object BrainShape {
    private val LOBES = arrayOf(
        floatArrayOf(31f, 22f, 19f, 14f), floatArrayOf(21f, 40f, 15f, 16f), floatArrayOf(19f, 58f, 15f, 16f),
        floatArrayOf(27f, 75f, 17f, 13f), floatArrayOf(40f, 84f, 10f, 7f), floatArrayOf(38f, 45f, 14f, 36f)
    )
    // Lipatan (belahan kiri; kanan dicerminkan): M x y lalu rangkaian kurva kubik (6 angka per kurva).
    private val FOLDS = arrayOf(
        floatArrayOf(14f, 34f, 24f, 28f, 28f, 40f, 40f, 33f),
        floatArrayOf(10f, 50f, 20f, 43f, 26f, 56f, 38f, 48f),
        floatArrayOf(12f, 66f, 22f, 60f, 28f, 72f, 40f, 64f),
        floatArrayOf(26f, 16f, 32f, 26f, 24f, 34f, 30f, 44f, 34f, 52f, 28f, 60f, 34f, 70f),
        floatArrayOf(22f, 80f, 30f, 74f, 36f, 80f, 42f, 76f)
    )

    fun hemisphere(mirror: Boolean): Path {
        val u = Path()
        val tmp = Path()
        val r = RectF()
        for (l in LOBES) {
            val cx = if (mirror) 100f - l[0] else l[0]
            r.set(cx - l[2], l[1] - l[3], cx + l[2], l[1] + l[3])
            tmp.reset(); tmp.addOval(r, Path.Direction.CW)
            if (u.isEmpty) u.set(tmp) else u.op(tmp, Path.Op.UNION)
        }
        return u
    }

    fun fold(f: FloatArray, mirror: Boolean): Path {
        fun x(v: Float) = if (mirror) 100f - v else v
        val p = Path()
        p.moveTo(x(f[0]), f[1])
        var i = 2
        while (i + 5 < f.size) {
            p.cubicTo(x(f[i]), f[i + 1], x(f[i + 2]), f[i + 3], x(f[i + 4]), f[i + 5]); i += 6
        }
        return p
    }

    /** Bitmap otak oranye pekat berukuran [size] px; lipatan dan celah tengah transparan. Dibuat sekali, dipakai ulang tiap frame. */
    fun bitmap(size: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(size / 100f, size / 100f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawPath(hemisphere(false), fill)
        c.drawPath(hemisphere(true), fill)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (f in FOLDS) { c.drawPath(fold(f, false), clear); c.drawPath(fold(f, true), clear) }
        clear.strokeWidth = 5f
        c.drawLine(50f, 4f, 50f, 96f, clear) // celah antar belahan
        return bmp
    }
}

/**
 * Tombol penalaran di kolom input: otak oranye pekat yang SAMA dengan di baris live (BrainShape + warna Palette.ORANGE_DOT),
 * berdenyut tanpa henti selama tampil (= penalaran mendalam aktif) dengan denyut BrainMath yang sama. Tanpa glow.
 * Berhenti menggambar sendiri saat disembunyikan/dilepas; denyut dimulai lagi dari skala 1,0 tiap kali tampil.
 * Bila animasi sistem dimatikan (aksesibilitas), otak tampil diam.
 */
internal class BrainPulseView(context: Context) : View(context) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ORANGE_DOT }
    private val eyePath = Path()
    private val rnd = java.util.Random()
    private val orbitPh = FloatArray(3) { rnd.nextFloat() * 2f * PI.toFloat() }
    private val orbitW = FloatArray(3) { 1.4f + rnd.nextFloat() * 1.1f }
    private val tiltPh = rnd.nextFloat() * 2f * PI.toFloat()
    private val dotOut = FloatArray(3)
    private val dotTmp = FloatArray(3)
    private var styleA = 0
    private var styleB = 1
    private var styleBlendStart = 0L
    private var nextStyleAt = 0L
    private var t0 = 0L
    private var lastDraw = 0L
    private var still = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        still = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) { false }
        t0 = 0L
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val now = SystemClock.uptimeMillis()
        if (t0 == 0L || now - lastDraw > 250L) {
            t0 = now
            styleBlendStart = now
            nextStyleAt = now + 2200L
        }
        lastDraw = now
        val t = if (still) 0f else (now - t0) / 1000f
        val cx = w / 2f
        val cy = h / 2f
        val half = minOf(w, h) * 0.42f * BrainMath.pulse(t)
        val eyeHalfW = half * 0.88f
        val eyeHalfH = eyeHalfW * MYTHOS_EYE_H_RATIO

        drawWatchfulMythosEye(
            c = c,
            cx = cx,
            cy = cy,
            halfW = eyeHalfW,
            halfH = eyeHalfH,
            timeMs = if (still) 0L else now,
            inkColor = Palette.INK,
            pupilFillColor = Palette.BG,
            strokePaint = strokePaint,
            fillPaint = fillPaint,
            path = eyePath
        )

        if (now >= nextStyleAt) {
            styleA = styleB
            styleB = ClaudeDotsMath.nextStyle(styleA, rnd.nextInt(1000))
            styleBlendStart = now
            nextStyleAt = now + 2000L + rnd.nextInt(2400)
        }
        val m = ClaudeDotsMath.blendWeight(now - styleBlendStart, ClaudeDotsMath.STYLE_BLEND_MS)
        val scaleToBox = half / 8.5f
        for (i in 0..2) {
            ClaudeDotsMath.orbitDotBlend(styleA, styleB, m, i, t, orbitPh, orbitW, tiltPh, dotOut, dotTmp)
            val x = cx + dotOut[0] * scaleToBox
            val y = cy + dotOut[1] * scaleToBox
            val r = (dotOut[2] * scaleToBox * 0.95f).coerceAtLeast(2f)
            c.drawCircle(x, y, r, dotPaint)
        }
        if (!still) postInvalidateOnAnimation()
    }
}
