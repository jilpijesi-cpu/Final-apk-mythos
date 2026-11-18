package com.kita.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.style.LineBackgroundSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

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

class IconView(ctx: Context, t: Int, c: Int = Palette.TEXT_MAIN) : View(ctx) {
    var type: Int = t
        set(v) { field = v; invalidate() }
    var color: Int = c
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

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
        const val GRID = 12
        const val BLOB_TERM = 13
        const val FILE = 14
        const val FOLDER = 15
        const val GEAR = 16
        const val ARCHIVE = 17
        const val DOWNLOAD = 18
        const val GLOBE = 19
        const val CHECK = 20
        const val CROSS = 21
        const val NEW_CHAT = 22
        const val CHEVRON_DOWN = 23
        const val BRAIN = 24
        const val BOOK = 25
        const val WRITE = 26
        const val BACK = 27
        const val PHONE = 28
        const val DOTS_V = 29
        const val SMILEY = 30
        const val CLIP = 31
        const val DOUBLE_CHECK = 32
        const val RING = 33
    }

    var working: Boolean = false
        set(v) { field = v; if (v) startWorking() else stopWorking() }
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
        val iconStroke = 1.8f * resources.displayMetrics.density
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
                c.drawLine(cx - s * 0.28f, cy, cx + s * 0.28f, cy, p)
                c.drawLine(cx, cy - s * 0.28f, cx, cy + s * 0.28f, p)
            }
            MIC -> {
                c.drawRoundRect(RectF(cx - s * 0.1f, cy - s * 0.3f, cx + s * 0.1f, cy + s * 0.05f), s * 0.1f, s * 0.1f, p)
                c.drawArc(RectF(cx - s * 0.2f, cy - s * 0.16f, cx + s * 0.2f, cy + s * 0.2f), 0f, 180f, false, p)
                c.drawLine(cx, cy + s * 0.2f, cx, cy + s * 0.32f, p)
            }
            SEND -> {
                c.drawLine(cx, cy + s * 0.24f, cx, cy - s * 0.24f, p)
                c.drawLine(cx - s * 0.2f, cy - s * 0.04f, cx, cy - s * 0.24f, p)
                c.drawLine(cx + s * 0.2f, cy - s * 0.04f, cx, cy - s * 0.24f, p)
            }
        }
    }
}