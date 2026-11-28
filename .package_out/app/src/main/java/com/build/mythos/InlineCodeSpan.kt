package com.build.mythos

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.style.ReplacementSpan

// Tampilan `kode inline` seperti di aplikasi Claude: teks monospace biru di atas kotak abu-abu bersudut membulat
// dengan garis tepi tipis. BackgroundColorSpan bawaan Android hanya bisa persegi tanpa jarak, jadi digambar sendiri.
// Keterbatasan: ReplacementSpan tidak bisa terpotong ke baris berikutnya, maka teks panjang (> MAX_CHARS) memakai
// tampilan cadangan di Markdown.kt (latar persegi, teks biru).

internal class InlineCodeSpan(private val density: Float) : ReplacementSpan() {
    private val rect = RectF()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun dp(v: Float) = v * density

    private fun applyStyle(paint: Paint): Pair<Typeface?, Float> {
        val saved = paint.typeface to paint.textSize
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = saved.second * TEXT_SCALE
        return saved
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val saved = applyStyle(paint)
        val w = paint.measureText(text, start, end)
        if (fm != null) {
            val padY = kotlin.math.ceil(dp(PAD_Y_DP + 0.5f).toDouble()).toInt()
            val cur = paint.fontMetricsInt
            fm.ascent = minOf(fm.ascent, cur.ascent - padY)
            fm.top = minOf(fm.top, cur.top - padY)
            fm.descent = maxOf(fm.descent, cur.descent + padY)
            fm.bottom = maxOf(fm.bottom, cur.bottom + padY)
        }
        paint.typeface = saved.first
        paint.textSize = saved.second
        return kotlin.math.ceil((w + 2 * dp(PAD_X_DP) + 2 * dp(MARGIN_DP)).toDouble()).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val saved = applyStyle(paint)
        val w = paint.measureText(text, start, end)
        val fm = paint.fontMetrics
        val left = x + dp(MARGIN_DP)
        rect.set(left, y + fm.ascent - dp(PAD_Y_DP), left + w + 2 * dp(PAD_X_DP), y + fm.descent + dp(PAD_Y_DP))
        val r = dp(RADIUS_DP)
        boxPaint.style = Paint.Style.FILL
        boxPaint.color = Palette.FIELD
        canvas.drawRoundRect(rect, r, r, boxPaint)
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = dp(1f)
        boxPaint.color = Palette.FIELD_BORDER
        canvas.drawRoundRect(rect, r, r, boxPaint)
        val oldColor = paint.color
        paint.color = Palette.LINK_BLUE
        canvas.drawText(text, start, end, left + dp(PAD_X_DP), y.toFloat(), paint)
        paint.color = oldColor
        paint.typeface = saved.first
        paint.textSize = saved.second
    }

    companion object {
        const val MAX_CHARS = 24
        private const val TEXT_SCALE = 0.9f
        private const val PAD_X_DP = 5f
        private const val PAD_Y_DP = 1.5f
        private const val MARGIN_DP = 1f
        private const val RADIUS_DP = 6f
    }
}
