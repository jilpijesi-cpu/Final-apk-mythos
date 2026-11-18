package com.kita.agent

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal fun MainActivity.dp(px: Int): Int = (px * resources.displayMetrics.density + 0.5f).toInt()

internal fun MainActivity.rounded(color: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(strokeDp), stroke)
    }

internal fun pressable(bg: Drawable?): Drawable {
    val mask: Drawable = when (bg) {
        is GradientDrawable -> (bg.constantState?.newDrawable()?.mutate() as? GradientDrawable)
            ?.apply { setColor(Color.WHITE) } ?: ColorDrawable(Color.WHITE)
        else -> ColorDrawable(Color.WHITE)
    }
    return RippleDrawable(ColorStateList.valueOf(0x1F000000), bg, mask)
}

internal fun MainActivity.filledButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 15f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(Color.WHITE)
    gravity = Gravity.CENTER
    background = pressable(rounded(Palette.BRAND, 24))
    setPadding(dp(24), dp(12), dp(24), dp(12))
    setOnClickListener { onClick() }
}

internal fun MainActivity.softButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 15f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(Palette.INK)
    gravity = Gravity.CENTER
    background = pressable(rounded(Palette.FIELD, 24))
    setPadding(dp(24), dp(12), dp(24), dp(12))
    setOnClickListener { onClick() }
}

internal fun MainActivity.fieldLabel(t: String): TextView = TextView(this).apply {
    text = t
    textSize = 12.5f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(Palette.INK_SOFT)
    setPadding(dp(4), dp(14), 0, dp(6))
}

internal fun MainActivity.styleField(v: TextView) {
    v.background = rounded(Palette.FIELD, 14)
    v.setPadding(dp(14), dp(12), dp(14), dp(12))
    v.textSize = 15f
    v.setTextColor(Palette.INK)
    v.setHintTextColor(Palette.INK_SOFT)
}

internal fun MainActivity.showSheet(title: String, subtitle: String? = null, content: View, footer: View? = null): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val grabber = View(this).apply { background = rounded(Palette.DIVIDER, 3) }
    val head = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.INK)
        })
        if (!subtitle.isNullOrBlank()) addView(TextView(context).apply {
            text = subtitle
            textSize = 13.5f
            setTextColor(Palette.INK_SOFT)
            setPadding(0, dp(2), 0, 0)
        })
    }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(20))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            val r = dp(28).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        addView(grabber, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })
        addView(head, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP, 1f))
        if (footer != null) addView(footer, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }
    d.setContentView(body)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        setDimAmount(0.4f)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
    }
    d.show()
    return d
}