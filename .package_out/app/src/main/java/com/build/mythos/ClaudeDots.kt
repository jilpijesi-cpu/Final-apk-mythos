package com.build.mythos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Ikon titik "hidup" pada baris status live. Diukur dari rekaman layar Claude (Screen_Recording_20261026_183644.mp4,
// kerapatan layar ~2,75) dengan perpindahan "menyusut ke satu titik -> bertahan -> mengembang". Gerak SENGAJA tidak berulang:
//  - ORBIT: tiga titik mengorbit dengan kecepatan sendiri-sendiri, kemiringan orbit bergelombang, fase awal acak tiap kali
//    muncul, sehingga pola tidak pernah sama persis. Ukuran: orbit mendatar 7,5dp, titik 1,1-2,0dp (lebih besar saat di depan).
//  - GRID: empat titik di sudut persegi; tiap "denyut" memilih acak pasangan diagonal mana yang berputar 180 derajat sambil
//    merapat ke pusat (arah, kedalaman, durasi, dan jeda acak; kadang kedua pasangan berputar berlawanan).
// Variasi gerak tambahan: ORBIT berganti gaya (miring / angka delapan / bernapas / gelombang) tiap beberapa detik dengan
// peralihan halus; GRID memilih jenis denyut acak (putar 180 / napas / putar 90 / riak berurutan).

internal object ClaudeDotsMath {
    const val DENSITY_REF = 2.75f          // kerapatan layar rekaman, untuk mengubah piksel -> dp
    const val GRID_R_DP = 21.2f / DENSITY_REF
    const val GRID_DOT_DP = 1.9f
    const val PERIOD_MS = 630L             // satu putaran pasangan titik
    const val PULSE_DELAY_MS = 360L        // jeda setelah mengembang sebelum putaran pertama

    private val U = floatArrayOf(0f, .11f, .19f, .29f, .365f, .44f, .51f, .59f, .67f, .83f, 1f)
    private val ROT = floatArrayOf(0f, .04f, .08f, .19f, .36f, .70f, .78f, .87f, .90f, .96f, 1f)
    private val RADIUS_PX = floatArrayOf(21.2f, 21.2f, 15.5f, 10.2f, 8.5f, 12.6f, 16.1f, 20.2f, 20.9f, 21.2f, 21.2f)

    private fun table(ys: FloatArray, x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        for (i in 1 until U.size) {
            if (v <= U[i]) {
                val f = (v - U[i - 1]) / (U[i] - U[i - 1])
                return ys[i - 1] + (ys[i] - ys[i - 1]) * f
            }
        }
        return ys.last()
    }

    /** Sudut (derajat, sumbu y ke bawah) pasangan titik yang berputar; u = 0..1 dalam satu putaran. */
    fun pairAngleDeg(u: Float): Float = 45f - 180f * table(ROT, u)

    /** Jarak pasangan itu dari pusat (dp). */
    fun pairRadiusDp(u: Float): Float = table(RADIUS_PX, u) / DENSITY_REF

    /** Posisi dalam putaran (0..1) untuk waktu [sinceExpandMs] sejak mengembang; -1 bila belum mulai berputar. */
    fun pulsePhase(sinceExpandMs: Long): Float {
        if (sinceExpandMs < PULSE_DELAY_MS) return -1f
        return ((sinceExpandMs - PULSE_DELAY_MS) % PERIOD_MS).toFloat() / PERIOD_MS
    }

    /** Kemajuan putaran 0..1 (0 = belum berputar, 1 = sudah setengah putaran penuh). */
    fun rot(u: Float): Float = table(ROT, u)

    /** Seberapa dalam titik merapat ke pusat pada posisi u: 0 = jarak penuh, 1 = paling dekat. */
    fun bump(u: Float): Float = (RADIUS_PX[0] - table(RADIUS_PX, u)) / (RADIUS_PX[0] - RADIUS_PX[4])

    /** Titik orbit ke-[i]: kembalikan (x dp, y dp, jari-jari dp) relatif ke pusat pada detik ke-[ts]; [ph] = fase awal, [w] = kecepatan (rad/dtk). */
    fun orbitDot(i: Int, ts: Float, ph: FloatArray, w: FloatArray, tiltPh: Float): FloatArray =
        FloatArray(3).also { orbitDotStyle(0, i, ts, ph, w, tiltPh, it) }

    // ---- Variasi gaya orbit

    /** Jumlah gaya orbit: 0 = miring (asli), 1 = angka delapan, 2 = bernapas, 3 = gelombang. */
    const val ORBIT_STYLES = 4
    /** Lama peralihan halus antar gaya (ms). */
    const val STYLE_BLEND_MS = 800L

    /** Gaya berikutnya: selalu berbeda dari [current]; [r] bilangan acak non-negatif. */
    fun nextStyle(current: Int, r: Int): Int = (current + 1 + r % (ORBIT_STYLES - 1)) % ORBIT_STYLES

    /** Bobot peralihan 0..1 (smoothstep) setelah [sinceMs] dari [durMs]. */
    fun blendWeight(sinceMs: Long, durMs: Long): Float {
        val f = (sinceMs.toFloat() / durMs).coerceIn(0f, 1f)
        return f * f * (3f - 2f * f)
    }

    /** Seperti [orbitDot] untuk gaya [style]; hasil (x dp, y dp, jari-jari dp) ditulis ke [out] (tanpa alokasi). */
    fun orbitDotStyle(style: Int, i: Int, ts: Float, ph: FloatArray, w: FloatArray, tiltPh: Float, out: FloatArray) {
        val tau = 2f * PI.toFloat()
        when (style) {
            1 -> { // angka delapan: tiga titik saling mengejar di sepanjang lintasan "8"
                val th = ph[i] + w[i] * 0.6f * ts
                out[0] = 7.5f * sin(th)
                out[1] = 3.8f * sin(2f * th)
                out[2] = 1.55f + 0.45f * cos(th)
            }
            2 -> { // bernapas: orbit datar yang jari-jarinya mengembang dan menyusut bergantian per titik
                val th = ph[i] + w[i] * 0.8f * ts
                val rad = 7.5f * (0.55f + 0.45f * (0.5f + 0.5f * sin(tau * ts / 2.4f + i * 0.9f)))
                val z = sin(th)
                out[0] = rad * cos(th)
                out[1] = rad * 0.6f * z
                out[2] = 1.55f + 0.45f * z
            }
            3 -> { // gelombang: tiga titik berdampingan naik turun bergantian sambil sedikit goyang
                val wave = tau * ts / 1.1f - i * 1.3f
                out[0] = (i - 1) * 5.2f + 1.6f * sin(w[i] * 0.5f * ts + ph[i])
                out[1] = 4.0f * sin(wave)
                out[2] = 1.6f + 0.4f * sin(wave + 1f)
            }
            else -> { // miring (asli)
                val th = ph[i] + w[i] * ts + 0.35f * sin(0.9f * ts + i * 2.1f)
                val tiltWave = 0.5f - 0.5f * cos(tau * ts / 1.8f + tiltPh)
                val tiltMax = 4.4f * (0.55f + 0.45f * sin(tau * ts / 7.3f + tiltPh))
                val z = sin(th)
                out[0] = 7.5f * cos(th)
                out[1] = tiltMax * tiltWave * z
                out[2] = 1.55f + 0.45f * z
            }
        }
    }

    /** Campuran gaya [a] dan [b] dengan bobot [m] (0 = a, 1 = b). [tmp] = penampung sementara berukuran 3. */
    fun orbitDotBlend(
        a: Int, b: Int, m: Float, i: Int, ts: Float, ph: FloatArray, w: FloatArray, tiltPh: Float,
        out: FloatArray, tmp: FloatArray
    ) {
        orbitDotStyle(a, i, ts, ph, w, tiltPh, out)
        if (a == b || m <= 0f) return
        orbitDotStyle(b, i, ts, ph, w, tiltPh, tmp)
        for (j in 0..2) out[j] += (tmp[j] - out[j]) * m
    }

    // ---- Variasi denyut GRID

    /** Jenis denyut: 0 = putar 180 (asli), 1 = napas serentak, 2 = putar 90 berlawanan, 3 = riak berurutan. */
    const val GRID_KINDS = 4

    /** Pilih jenis denyut dari bilangan acak [r] (0..99): 35% asli, 25% napas, 20% putar 90, 20% riak. */
    fun pulseKind(r: Int): Int = when {
        r < 35 -> 0
        r < 60 -> 1
        r < 80 -> 2
        else -> 3
    }

    /** Lama denyut (ms) menurut jenis; riak butuh lebih panjang agar keempat titik sempat bergerak. */
    fun pulseDuration(kind: Int, r: Int): Long = if (kind == 3) 800L + r else 520L + r

    /**
     * Keadaan titik grid ke-[k] (0..3, sudut dasar 45 + 90k) pada denyut [kind], posisi [u] (0..1, negatif = belum mulai).
     * [dirK] = arah putar titik ini (+1/-1), [active] = hanya dipakai jenis 0, [rMinPx] = kedalaman terdekat (piksel acuan).
     * Hasil ke [out]: sudut (derajat), jarak dari pusat (dp), pengali ukuran titik.
     */
    fun gridDot(kind: Int, k: Int, u: Float, dirK: Float, rMinPx: Float, active: Boolean, out: FloatArray) {
        val base = 45f + 90f * k
        val full = GRID_R_DP
        val rMin = rMinPx / DENSITY_REF
        out[0] = base; out[1] = full; out[2] = 1f
        if (u < 0f) return
        when (kind) {
            1 -> {
                val b = bump(u)
                out[1] = full - (full - rMin) * b
                out[2] = 1f + 0.3f * b
            }
            2 -> {
                out[0] = base + dirK * 90f * rot(u)
                out[1] = full - (full - rMin) * 0.6f * bump(u)
            }
            3 -> {
                val uk = ((u - k * 0.14f) / 0.58f).coerceIn(0f, 1f)
                val b = bump(uk)
                out[0] = base + dirK * 90f * rot(uk)
                out[1] = full - (full - rMin) * b
                out[2] = 1f + 0.2f * b
            }
            else -> if (active) {
                out[0] = base + dirK * 180f * rot(u)
                out[1] = full - (full - rMin) * bump(u)
            }
        }
    }
}

internal class ClaudeDotsView(ctx: Context) : View(ctx) {
    companion object {
        const val ORBIT = 0
        const val GRID = 1
        private const val COLLAPSE_MS = 120L
        private const val MERGE_MS = 100L
        private const val HOLD_MS = 260L
        private const val EXPAND_MS = 300L
        private const val ORANGE = Palette.ORANGE_DOT
    }

    private enum class Phase { SHOW, COLLAPSE, HOLD, EXPAND }

    private class Pulse(val start: Long, val dur: Long, val pair: Int, val dir: Float, val rMin: Float, val both: Boolean, val kind: Int)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE }
    private val rnd = java.util.Random()
    private var pulse: Pulse? = null
    private var orbitPh = FloatArray(3)
    private var orbitW = FloatArray(3)
    private var tiltPh = 0f
    // Gaya orbit: berganti acak tiap beberapa detik; peralihan memakai campuran halus dari gaya A ke gaya B.
    private var styleA = 0
    private var styleB = 0
    private var styleBlendStart = 0L
    private var nextStyleAt = 0L
    private val dotOut = FloatArray(3)
    private val dotTmp = FloatArray(3)
    private var mode = -1
    private var target = -1
    private var phase = Phase.HOLD
    private var phaseStart = 0L
    private var modeStart = 0L

    // Penalaran mendalam: bola menyebar -> menyatu menjadi ikon otak (BrainMath/BrainShape). brainP 0 = bola normal, 1 = otak.
    private var brainTarget = false   // mode penalaran mendalam aktif (diminta aplikasi)
    private var brainDir = false      // arah gerak saat ini: true = menyatu jadi otak, false = memisah jadi bola
    private var brainAnnounced = false // pembentukan pertama sudah dilaporkan; siklus berikutnya tidak melapor lagi
    private var waitStart = 0L        // sejak kapan diam di ujung (otak berdenyut / bola melayang); 0 = sedang bergerak
    private var brainP = 0f
    private var brainLast = 0L
    private var brainBmp: Bitmap? = null
    private val brainPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val brainDst = RectF()
    private val eyeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePath = android.graphics.Path()
    /** Dipanggil (di thread UI) saat otak selesai terbentuk (true) atau bola asal pulih sepenuhnya (false). */
    var onBrainSettled: ((Boolean) -> Unit)? = null

    override fun onDetachedFromWindow() {
        brainBmp?.recycle()
        brainBmp = null
        super.onDetachedFromWindow()
    }

    fun brainFormed(): Boolean = brainTarget && brainP >= 1f

    /** Aktifkan/matikan otak. [animate] false = langsung ke keadaan akhir (dipakai bila ikon dibuat ulang saat otak sudah terbentuk). */
    fun setBrain(on: Boolean, animate: Boolean = true) {
        brainTarget = on
        brainDir = on
        waitStart = 0L
        brainLast = 0L
        if (!animate) brainP = if (on) 1f else 0f
        invalidate()
    }


    /** Ganti jenis ikon. Bila berbeda dari yang tampil: menyusut ke satu titik, bertahan sebentar, lalu mengembang. */
    fun setMode(m: Int) {
        if (m == target) return
        target = m
        val now = SystemClock.uptimeMillis()
        when {
            mode == -1 -> startExpand(m, now)
            phase == Phase.SHOW || phase == Phase.EXPAND -> { phase = Phase.COLLAPSE; phaseStart = now }
            else -> {} // sedang menyusut / bertahan: akan mengembang ke target terbaru
        }
        invalidate()
    }

    private fun startExpand(m: Int, now: Long) {
        mode = m
        phase = Phase.EXPAND
        phaseStart = now
        modeStart = now
        // Setiap kali ikon muncul, gerak dibuat baru: fase, kecepatan, dan denyut acak.
        orbitPh = floatArrayOf(PI.toFloat(), 0f, (rnd.nextFloat() * 2f - 1f) * 1.2f + PI.toFloat() / 2f)
        orbitW = floatArrayOf(
            3.6f + rnd.nextFloat() * 1.8f,
            3.0f + rnd.nextFloat() * 2.2f,
            4.0f + rnd.nextFloat() * 2.4f
        )
        tiltPh = rnd.nextFloat() * 2f * PI.toFloat()
        styleA = rnd.nextInt(ClaudeDotsMath.ORBIT_STYLES)
        styleB = styleA
        styleBlendStart = now
        nextStyleAt = now + 2200L + rnd.nextInt(2800)
        pulse = nextPulse(now + ClaudeDotsMath.PULSE_DELAY_MS)
    }

    private fun nextPulse(start: Long): Pulse {
        val kind = ClaudeDotsMath.pulseKind(rnd.nextInt(100))
        return Pulse(
            start = start,
            dur = ClaudeDotsMath.pulseDuration(kind, rnd.nextInt(if (kind == 3) 300 else 260)),
            pair = rnd.nextInt(2),
            dir = if (rnd.nextBoolean()) 1f else -1f,
            rMin = 6.5f + rnd.nextFloat() * 3.5f,
            both = rnd.nextInt(4) == 0,
            kind = kind
        )
    }

    private fun tick(now: Long) {
        val el = now - phaseStart
        when (phase) {
            Phase.COLLAPSE -> if (el >= COLLAPSE_MS + MERGE_MS) { phase = Phase.HOLD; phaseStart = now }
            Phase.HOLD -> if (el >= HOLD_MS) startExpand(target, now)
            Phase.EXPAND -> if (el >= EXPAND_MS) phase = Phase.SHOW
            Phase.SHOW -> {}
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (mode == -1) return
        val now = SystemClock.uptimeMillis()
        val dp = resources.displayMetrics.density
        if (brainTarget || brainP > 0f) {
            drawBrainFrame(c, now, dp)
            postInvalidateOnAnimation()
            return
        }
        tick(now)
        val cx = width / 2f
        val cy = height / 2f
        val el = now - phaseStart
        when (phase) {
            Phase.COLLAPSE -> {
                if (el < COLLAPSE_MS) {
                    val f = el.toFloat() / COLLAPSE_MS
                    val s = 1f - f * f
                    drawShape(c, mode, cx, cy, dp, s, 1.9f, now)
                } else {
                    val f = ((el - COLLAPSE_MS).toFloat() / MERGE_MS).coerceIn(0f, 1f)
                    paint.alpha = 255
                    c.drawCircle(cx, cy, (2.4f + (1.4f - 2.4f) * f) * dp, paint)
                }
            }
            Phase.HOLD -> {
                val h = (el.toFloat() / HOLD_MS).coerceIn(0f, 1f)
                paint.alpha = 255
                c.drawCircle(cx, cy, (1.4f - 0.6f * sin(PI.toFloat() * h)) * dp, paint)
            }
            Phase.EXPAND -> {
                val f = (el.toFloat() / EXPAND_MS).coerceIn(0f, 1f)
                val s = 1f - (1f - f) * (1f - f) * (1f - f)
                drawShape(c, mode, cx, cy, dp, s, 1.4f + 0.5f * s, now)
            }
            Phase.SHOW -> drawShape(c, mode, cx, cy, dp, 1f, ClaudeDotsMath.GRID_DOT_DP, now)
        }
        postInvalidateOnAnimation()
    }

    /** Satu frame morf. Pada p=0 gambar sama persis dengan orbit normal (ukuran, posisi, warna). */
    private fun drawBrainFrame(c: Canvas, now: Long, dp: Float) {
        val dt = if (brainLast == 0L) 0L else (now - brainLast).coerceIn(0L, 64L)
        brainLast = now
        val prev = brainP
        brainP = BrainMath.advance(brainP, dt, brainDir)
        val p = brainP
        // Siklus: di ujung (otak utuh / bola) berhenti sebentar, lalu balik arah. Hanya selama mode aktif; dimatikan = turun terus ke bola.
        if (brainTarget) {
            val atEnd = (brainDir && p >= 1f) || (!brainDir && p <= 0f)
            if (!atEnd) waitStart = 0L
            else {
                if (waitStart == 0L) waitStart = now
                BrainMath.cycleDir(brainDir, p, now - waitStart)?.let { brainDir = it; waitStart = 0L }
            }
        } else {
            brainDir = false
            waitStart = 0L
        }
        val cx = width / 2f
        val cy = height / 2f
        val half = minOf(width, height) * 0.46f
        val ts = (now - modeStart) / 1000f
        paint.alpha = 255
        // Mode Penalaran mendalam: ikon mata Mythos mengintai di tengah, dikelilingi 3 bola oranye yang bergerak acak di dekat mata.
        if (now >= nextStyleAt) {
            styleA = styleB
            styleB = ClaudeDotsMath.nextStyle(styleA, rnd.nextInt(1000))
            styleBlendStart = now
            nextStyleAt = now + 2000L + rnd.nextInt(2600)
        }
        val m = ClaudeDotsMath.blendWeight(now - styleBlendStart, ClaudeDotsMath.STYLE_BLEND_MS)
        val ba = BrainMath.brainAlpha(p)
        val eyePresence = BrainMath.ease(p)

        // 1. Gambar mata Mythos mengintai (berkedip & bola mata melirik) di pusat
        if (ba > 0.01f) {
            val formed = p >= 1f
            var s = 0.82f + 0.18f * BrainMath.ease(ba)
            var alpha = ba
            if (formed) {
                val pt = if (waitStart != 0L) (now - waitStart) / 1000f else 0f
                s *= BrainMath.pulse(pt)
                alpha = BrainMath.pulseAlpha(pt)
            }
            // Serasi dengan tinggi teks status 17sp di wadah 40x32dp (~19dp x 9.5dp) agar tidak kebesaran dan tidak terpotong.
            val eyeHalfW = minOf(half * 0.68f, 9.5f * dp) * s
            val eyeHalfH = eyeHalfW * MYTHOS_EYE_H_RATIO
            val aInt = (255 * alpha).toInt().coerceIn(0, 255)
            val inkWithAlpha = (Palette.INK and 0x00FFFFFF) or (aInt shl 24)
            val bgWithAlpha = (Palette.BG and 0x00FFFFFF) or (aInt shl 24)
            drawWatchfulMythosEye(
                c = c,
                cx = cx,
                cy = cy,
                halfW = eyeHalfW,
                halfH = eyeHalfH,
                timeMs = now,
                inkColor = inkWithAlpha,
                pupilFillColor = bgWithAlpha,
                strokePaint = eyeStrokePaint,
                fillPaint = eyeFillPaint,
                path = eyePath
            )
        }

        // 2. Tiga bola oranye melayang acak mengitari mata secara proporsional di dalam wadah 40x32dp.
        val sp = BrainMath.spread(p) * 2.5f * dp
        val orbitExpand = 1f + 0.42f * eyePresence
        val orbitYExpand = 1f + 0.55f * eyePresence
        val dotRadiusScale = 1f - 0.12f * eyePresence
        val maxX = (width / 2f - 2.4f * dp).coerceAtLeast(1f)
        val maxY = (height / 2f - 2.4f * dp).coerceAtLeast(1f)
        paint.color = ORANGE
        paint.alpha = 255
        for (i in 0..2) {
            ClaudeDotsMath.orbitDotBlend(styleA, styleB, m, i, ts, orbitPh, orbitW, tiltPh, dotOut, dotTmp)
            val a = BrainMath.spreadAngle(i)
            val x = (dotOut[0] * dp * orbitExpand + cos(a) * sp).coerceIn(-maxX, maxX)
            val y = (dotOut[1] * dp * orbitYExpand + sin(a) * sp).coerceIn(-maxY, maxY)
            val rad = (dotOut[2] * dp * dotRadiusScale).coerceIn(1.2f * dp, 1.95f * dp)
            c.drawCircle(cx + x, cy + y, rad, paint)
        }
        if (prev < 1f && p >= 1f && brainTarget && !brainAnnounced) { brainAnnounced = true; post { onBrainSettled?.invoke(true) } }
        if (prev > 0f && p <= 0f && !brainTarget) { brainAnnounced = false; post { onBrainSettled?.invoke(false) } }
    }

    private fun drawShape(c: Canvas, m: Int, cx: Float, cy: Float, dp: Float, scale: Float, dotDp: Float, now: Long) {
        if (m == GRID) drawGrid(c, cx, cy, dp, scale, dotDp, now) else drawOrbit(c, cx, cy, dp, scale, now)
    }

    private fun drawGrid(c: Canvas, cx: Float, cy: Float, dp: Float, scale: Float, dotDp: Float, now: Long) {
        paint.alpha = 255
        var pl = pulse ?: nextPulse(now).also { pulse = it }
        if (now - (pl.start + pl.dur) > 2000L) {
            pl = nextPulse(now)
            pulse = pl
        } else {
            while (now >= pl.start + pl.dur) { pl = nextPulse(pl.start + pl.dur + 80L + rnd.nextInt(800)); pulse = pl }
        }
        val u = if (now < pl.start) -1f else (now - pl.start).toFloat() / pl.dur
        val r = dotDp * dp
        // Titik k = 0..3 berada di sudut 45/135/225/315; k genap = pasangan 0, k ganjil = pasangan 1.
        for (k in 0..3) {
            val pairOfDot = k % 2
            val active = pl.both || pairOfDot == pl.pair
            val dirK = if (pairOfDot == pl.pair) pl.dir else -pl.dir
            ClaudeDotsMath.gridDot(pl.kind, k, u, dirK, pl.rMin, active, dotOut)
            val a = Math.toRadians(dotOut[0].toDouble())
            c.drawCircle(
                cx + (dotOut[1] * dp * scale * cos(a)).toFloat(),
                cy + (dotOut[1] * dp * scale * sin(a)).toFloat(),
                r * dotOut[2], paint
            )
        }
    }

    private fun drawOrbit(c: Canvas, cx: Float, cy: Float, dp: Float, scale: Float, now: Long) {
        paint.alpha = 255
        val ts = (now - modeStart) / 1000f
        if (now >= nextStyleAt) {
            styleA = styleB
            styleB = ClaudeDotsMath.nextStyle(styleA, rnd.nextInt(1000))
            styleBlendStart = now
            nextStyleAt = now + 2200L + rnd.nextInt(2800)
        }
        val m = ClaudeDotsMath.blendWeight(now - styleBlendStart, ClaudeDotsMath.STYLE_BLEND_MS)
        for (i in 0..2) {
            ClaudeDotsMath.orbitDotBlend(styleA, styleB, m, i, ts, orbitPh, orbitW, tiltPh, dotOut, dotTmp)
            c.drawCircle(cx + dotOut[0] * dp * scale, cy + dotOut[1] * dp * scale, dotOut[2] * dp, paint)
        }
    }
}
