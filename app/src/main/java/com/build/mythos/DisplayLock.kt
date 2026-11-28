package com.build.mythos

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Penguncian skala layar & pengaturan Ukuran Font + Ketebalan Font internal aplikasi.
 * Tampilan MYTHOS tidak terpengaruh setelan Ukuran Font / Ukuran Tampilan sistem Android,
 * melainkan mengikuti slider Ukuran Font & Ketebalan Font di menu Pengaturan MYTHOS.
 */
internal const val FIXED_FONT_SCALE = 0.90f
internal const val PREF_FONT_SCALE_STEP = "ui_font_scale_step"
internal const val PREF_FONT_WEIGHT_PROGRESS = "ui_font_weight_progress"

internal const val DEFAULT_FONT_SCALE_STEP = 1      // Default (0.90f — ringkas & nyaman di layar mobile)
internal const val DEFAULT_FONT_WEIGHT_PROGRESS = 35 // Default (weight 400)

internal val FONT_SCALE_STEPS = floatArrayOf(0.80f, 0.90f, 1.00f, 1.12f, 1.24f)
internal val FONT_SCALE_LABELS = arrayOf("Kecil", "Default", "Sedang", "Besar", "Sangat Besar")

@Volatile internal var activeFontScale: Float = FONT_SCALE_STEPS[DEFAULT_FONT_SCALE_STEP]
@Volatile internal var activeFontWeight: Int = 400

internal fun fontScaleForStep(step: Int): Float =
    FONT_SCALE_STEPS[step.coerceIn(0, FONT_SCALE_STEPS.lastIndex)]

internal fun fontScaleLabelForStep(step: Int): String =
    FONT_SCALE_LABELS[step.coerceIn(0, FONT_SCALE_LABELS.lastIndex)]

/** Petakan posisi slider 0..100 ke bobot font 300..700 dengan titik 35 = 400 (Default). */
internal fun fontWeightForProgress(progress: Int): Int {
    val p = progress.coerceIn(0, 100)
    return if (p <= DEFAULT_FONT_WEIGHT_PROGRESS) {
        300 + (p * 100 + DEFAULT_FONT_WEIGHT_PROGRESS / 2) / DEFAULT_FONT_WEIGHT_PROGRESS
    } else {
        val span = 100 - DEFAULT_FONT_WEIGHT_PROGRESS
        400 + ((p - DEFAULT_FONT_WEIGHT_PROGRESS) * 300 + span / 2) / span
    }
}

internal fun fontWeightLabelForProgress(progress: Int): String {
    val p = progress.coerceIn(0, 100)
    return when {
        p < 20 -> "Tipis"
        p <= 45 -> "Default"
        p <= 68 -> "Sedang"
        p <= 85 -> "Tebal"
        else -> "Sangat Tebal"
    }
}

/** Muat pilihan Ukuran Font & Ketebalan Font dari SharedPreferences "cfg". */
internal fun syncAppFontPrefs(context: Context) {
    try {
        val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val step = prefs.getInt(PREF_FONT_SCALE_STEP, DEFAULT_FONT_SCALE_STEP)
        val weightProgress = prefs.getInt(PREF_FONT_WEIGHT_PROGRESS, DEFAULT_FONT_WEIGHT_PROGRESS)
        activeFontScale = fontScaleForStep(step)
        activeFontWeight = fontWeightForProgress(weightProgress)
    } catch (_: Exception) {
        // Fallback aman bila dipanggil sebelum storage tersedia
    }
}

/** Terapkan bobot font pilihan pengguna ke Typeface (API 28+ presisi 300..700, API 26-27 fallback normal/bold). */
internal fun applyAppFontWeight(base: Typeface, weight: Int = activeFontWeight): Typeface {
    val w = weight.coerceIn(100, 900)
    if (w == 400 && !base.isBold) return base
    return if (Build.VERSION.SDK_INT >= 28) {
        val effective = if (base.isBold) maxOf(w, 700) else w
        Typeface.create(base, effective, base.isItalic)
    } else {
        when {
            base.isBold || w >= 600 -> Typeface.create(base, if (base.isItalic) Typeface.BOLD_ITALIC else Typeface.BOLD)
            else -> base
        }
    }
}

/** Terapkan ketebalan font pilihan pengguna secara rekursif pada seluruh TextView di pohon tampilan. */
internal fun applyFontWeightTree(view: View, weight: Int = activeFontWeight) {
    if (weight == 400) return
    if (view is TextView) {
        val base = view.typeface ?: Typeface.DEFAULT
        view.typeface = applyAppFontWeight(base, weight)
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            applyFontWeightTree(view.getChildAt(i), weight)
        }
    }
}

/** Kembalikan DPI bawaan pabrik perangkat bila tersedia, agar Display Size zoom sistem tidak membesarkan UI. */
internal fun stableDensityDpi(currentDpi: Int, deviceStableDpi: Int): Int =
    if (deviceStableDpi > 0) deviceStableDpi else currentDpi

/** Hitung ulang lebar/tinggi dp proporsional ketika densityDpi dikembalikan ke DPI stabil pabrik. */
internal fun normalizedScreenDp(currentDp: Int, currentDpi: Int, stableDpi: Int, minDp: Int): Int {
    if (currentDp <= 0 || currentDpi <= 0 || stableDpi <= 0 || currentDpi == stableDpi) return currentDp
    return (currentDp * (currentDpi.toFloat() / stableDpi.toFloat()) + 0.5f).toInt().coerceAtLeast(minDp)
}

/** Buat salinan Configuration dengan fontScale sesuai setelan internal MYTHOS dan densityDpi terkunci ke DPI pabrik. */
internal fun lockDisplayConfiguration(
    base: Configuration,
    deviceStableDpi: Int = DisplayMetrics.DENSITY_DEVICE_STABLE,
    fontScale: Float = activeFontScale
): Configuration {
    val cfg = Configuration(base)
    cfg.fontScale = fontScale
    val stableDpi = stableDensityDpi(cfg.densityDpi, deviceStableDpi)
    if (stableDpi > 0 && cfg.densityDpi != stableDpi) {
        val oldDpi = cfg.densityDpi
        if (cfg.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
            cfg.screenWidthDp = normalizedScreenDp(cfg.screenWidthDp, oldDpi, stableDpi, 320)
        }
        if (cfg.screenHeightDp != Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
            cfg.screenHeightDp = normalizedScreenDp(cfg.screenHeightDp, oldDpi, stableDpi, 480)
        }
        if (cfg.smallestScreenWidthDp != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
            cfg.smallestScreenWidthDp = normalizedScreenDp(cfg.smallestScreenWidthDp, oldDpi, stableDpi, 320)
        }
        cfg.densityDpi = stableDpi
    }
    return cfg
}

/** Bungkus Context dengan konfigurasi font & ukuran tampilan terkunci (dipakai di attachBaseContext dan overlay). */
internal fun Context.withLockedDisplay(): Context {
    syncAppFontPrefs(this)
    val locked = lockDisplayConfiguration(resources.configuration, fontScale = activeFontScale)
    return createConfigurationContext(locked)
}

/**
 * Tegakkan ulang fontScale internal dan DisplayMetrics DPI stabil pada Resources aktif
 * (penting saat Activity mendeklarasikan configChanges="density|fontScale").
 */
@Suppress("DEPRECATION")
internal fun Resources.enforceLockedDisplayMetrics(
    deviceStableDpi: Int = DisplayMetrics.DENSITY_DEVICE_STABLE,
    fontScale: Float = activeFontScale
) {
    val locked = lockDisplayConfiguration(configuration, deviceStableDpi, fontScale)
    val dm = DisplayMetrics().apply { setTo(displayMetrics) }
    val stableDpi = stableDensityDpi(dm.densityDpi, deviceStableDpi)
    if (stableDpi > 0) {
        dm.densityDpi = stableDpi
        dm.density = stableDpi / 160f
    }
    dm.scaledDensity = dm.density * fontScale
    updateConfiguration(locked, dm)
}

/**
 * Slider bergaya bersih (garis biru aktif + abu-abu nonaktif + titik langkah opsional + thumb cincin putih-biru)
 * untuk pengaturan Ukuran Font (5 titik langkah) dan Ketebalan Font (halus 0..100).
 */
internal class FontSliderView(
    ctx: Context,
    private val maxValue: Int,
    private val showTicks: Boolean,
    initialValue: Int,
    private val onChanged: (Int) -> Unit
) : View(ctx) {

    var value: Int = initialValue.coerceIn(0, maxValue)
        set(v) {
            val clamped = v.coerceIn(0, maxValue)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private fun px(dp: Float) = dp * density

    private val accentColor: Int get() = if (Palette.dark) 0xFF4C8DFF.toInt() else 0xFF1A6BFF.toInt()
    private val trackBgColor: Int get() = if (Palette.dark) 0xFF3E3D3A.toInt() else 0xFFE5E6EA.toInt()
    private val tickInactiveColor: Int get() = if (Palette.dark) 0xFF6B6964.toInt() else 0xFFC0C2C8.toInt()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = px(44f).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    private fun trackBounds(): Pair<Float, Float> {
        val pad = px(14f)
        val start = paddingLeft + pad
        val end = (width - paddingRight - pad).coerceAtLeast(start + 1f)
        return start to end
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (start, end) = trackBounds()
        val cy = height / 2f
        val span = end - start
        val frac = if (maxValue <= 0) 0f else value.toFloat() / maxValue.toFloat()
        val thumbX = start + span * frac

        trackPaint.strokeWidth = px(4.5f)
        trackPaint.color = trackBgColor
        canvas.drawLine(start, cy, end, cy, trackPaint)

        trackPaint.color = accentColor
        canvas.drawLine(start, cy, thumbX, cy, trackPaint)

        if (showTicks && maxValue > 0) {
            val r = px(4.5f)
            for (i in 0..maxValue) {
                val tx = start + span * (i.toFloat() / maxValue.toFloat())
                tickPaint.color = if (i <= value) accentColor else tickInactiveColor
                canvas.drawCircle(tx, cy, r, tickPaint)
            }
        }

        val thumbR = px(10f)
        thumbFillPaint.color = if (Palette.dark) Palette.SURFACE else Color.WHITE
        canvas.drawCircle(thumbX, cy, thumbR, thumbFillPaint)
        thumbRingPaint.color = accentColor
        thumbRingPaint.strokeWidth = px(4f)
        canvas.drawCircle(thumbX, cy, thumbR, thumbRingPaint)
    }

    private fun updateFromX(x: Float) {
        val (start, end) = trackBounds()
        val frac = ((x - start) / (end - start)).coerceIn(0f, 1f)
        val next = (frac * maxValue + 0.5f).toInt().coerceIn(0, maxValue)
        if (next != value) {
            value = next
            onChanged(next)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
