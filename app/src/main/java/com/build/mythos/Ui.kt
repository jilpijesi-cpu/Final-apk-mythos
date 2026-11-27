package com.build.mythos

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

// Pembantu tampilan bersama (bentuk, tombol, lembar bawah) agar semua layar memakai bahasa visual yang sama.
// Fungsi-fungsi di sini adalah ekstensi MainActivity.

/** Latar membulat dengan garis tepi opsional. */
internal fun MainActivity.rounded(color: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(strokeDp), stroke)
    }

/** Bungkus [bg] dengan efek riak saat disentuh; riak mengikuti sudut membulat latarnya. */
internal fun pressable(bg: Drawable?): Drawable {
    val mask: Drawable = when (bg) {
        is GradientDrawable -> (bg.constantState?.newDrawable()?.mutate() as? GradientDrawable)
            ?.apply { setColor(Color.WHITE) } ?: ColorDrawable(Color.WHITE)
        else -> ColorDrawable(Color.WHITE)
    }
    val rippleColor = if (Palette.dark) 0x26FFFFFF else 0x1F000000
    return RippleDrawable(ColorStateList.valueOf(rippleColor), bg, mask)
}

/** Tombol isi penuh (aksi utama). */
internal fun MainActivity.filledButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = Typo.BODY
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(Color.WHITE)
    gravity = Gravity.CENTER
    background = pressable(rounded(Palette.BRAND, 24))
    setPadding(dp(24), dp(12), dp(24), dp(12))
    setOnClickListener { onClick() }
}

/** Tombol sekunder: abu lembut tanpa garis. */
internal fun MainActivity.softButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = Typo.BODY
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(Palette.INK)
    gravity = Gravity.CENTER
    background = pressable(rounded(Palette.FIELD, 24))
    setPadding(dp(24), dp(12), dp(24), dp(12))
    setOnClickListener { onClick() }
}

/** Label kecil di atas kolom isian. */
internal fun MainActivity.fieldLabel(t: String): TextView = TextView(this).apply {
    text = t
    textSize = Typo.CAPTION
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(Palette.INK_SOFT)
    setPadding(dp(4), dp(14), 0, dp(6))
}

/** Gaya kolom isian: latar abu lembut, sudut 14dp, tanpa garis bawah bawaan. */
internal fun MainActivity.styleField(v: TextView) {
    v.background = rounded(Palette.FIELD, 14)
    v.setPadding(dp(14), dp(12), dp(14), dp(12))
    v.textSize = Typo.BODY
    v.setTextColor(Palette.INK)
    v.setHintTextColor(Palette.INK_SOFT)
}

/**
 * Lembar bawah (bottom sheet) bersudut membulat. [content] digulir otomatis bila lebih tinggi dari layar.
 * Mengembalikan dialognya agar pemanggil bisa menutup atau memberi pendengar.
 */
internal fun MainActivity.showSheet(title: String, subtitle: String? = null, content: View, footer: View? = null): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val grabber = View(this).apply { background = rounded(Palette.DIVIDER, 3) }
    val head = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title
            textSize = Typo.DISPLAY
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.INK)
        })
        if (!subtitle.isNullOrBlank()) addView(TextView(context).apply {
            text = subtitle
            textSize = Typo.BODY_S
            setTextColor(Palette.INK_SOFT)
            setPadding(0, dp(2), 0, 0)
        })
    }
    val maxSheetH = (resources.displayMetrics.heightPixels * 0.86f).toInt()
    val body = object : LinearLayout(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val hMode = MeasureSpec.getMode(heightMeasureSpec)
            val hSize = MeasureSpec.getSize(heightMeasureSpec)
            val capped = when (hMode) {
                MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxSheetH, MeasureSpec.AT_MOST)
                else -> MeasureSpec.makeMeasureSpec(minOf(hSize, maxSheetH), MeasureSpec.AT_MOST)
            }
            super.onMeasure(widthMeasureSpec, capped)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(20))
        background = GradientDrawable().apply {
            setColor(Palette.SURFACE)
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
    applyFontWeightTree(body)
    d.setContentView(body)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        setDimAmount(0.4f)
        softInputResize()
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
    }
    d.show()
    return d
}
