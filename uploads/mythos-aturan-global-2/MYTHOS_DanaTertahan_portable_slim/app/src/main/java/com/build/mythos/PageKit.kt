package com.build.mythos

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Pembantu halaman layar penuh untuk drawer (Semua chat, Proyek, Artefak). Fungsi-fungsi di sini adalah ekstensi MainActivity.

/** Halaman layar penuh: [top] untuk pencarian/penyaring, [info] baris keterangan kecil, [list] isi yang bisa digulir. */
internal class PageHandle(val dialog: Dialog, val list: LinearLayout, val info: TextView, val top: LinearLayout) {
    val showing: Boolean get() = dialog.isShowing
    fun dismiss() = dialog.dismiss()
}

internal fun MainActivity.pageDialog(title: String, actionLabel: String? = null, onAction: ((PageHandle) -> Unit)? = null): PageHandle {
    val d = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    val back = TextView(this).apply {
        text = "‹"
        textSize = Typo.NAV_GLYPH
        gravity = Gravity.CENTER
        setTextColor(Palette.INK)
        contentDescription = "Kembali"
        setOnClickListener { d.dismiss() }
    }
    val titleTv = TextView(this).apply {
        text = title
        textSize = Typo.TITLE
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        setPadding(dp(4), 0, dp(4), 0)
    }
    val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val info = TextView(this).apply {
        textSize = Typo.CAPTION
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(20), dp(4), dp(20), dp(8))
    }
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(24))
    }
    val handle = PageHandle(d, list, info, top)
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(titleTv, LinearLayout.LayoutParams(0, dp(44), 1f))
        if (actionLabel != null && onAction != null) addView(TextView(context).apply {
            text = actionLabel
            textSize = Typo.BODY
            gravity = Gravity.CENTER
            setTextColor(Palette.INK)
            background = pressable(rounded(Palette.TILE, 20))
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { onAction(handle) }
        }, LinearLayout.LayoutParams(WRAP, dp(36)))
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Palette.BG)
        addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }
    d.setContentView(root)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Palette.BG))
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        softInputResize()
        statusBarColor = Palette.BG
        navigationBarColor = Palette.BG
        applyBarAppearance(navLight = true)
    }
    d.show()
    return handle
}

/** Satu kartu baris: ikon, judul, keterangan, dan label kecil opsional di kanan. */
internal fun MainActivity.pageRow(
    glyph: Int, title: String, sub: String, badge: String? = null,
    onLong: (() -> Unit)? = null, onClick: () -> Unit
): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = pressable(rounded(Palette.CARD, 14, Palette.BORDER, 1))
    setPadding(dp(14), dp(12), dp(14), dp(12))
    addView(GlyphView(context, glyph, Palette.INK).apply { strokeDp = 1.5f }, LinearLayout.LayoutParams(dp(24), dp(24)))
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title
            textSize = Typo.BODY_L
            setTextColor(Palette.INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (sub.isNotBlank()) addView(TextView(context).apply {
            text = sub
            textSize = Typo.CAPTION
            setTextColor(Palette.INK_SOFT)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
    }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
    if (badge != null) addView(TextView(context).apply {
        text = badge
        textSize = Typo.CAPTION
        setTextColor(Palette.INK)
        background = rounded(Palette.TILE, 8)
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
    setOnClickListener { onClick() }
    if (onLong != null) setOnLongClickListener { onLong(); true }
}

internal val ROW_PARAMS_BOTTOM = 8

internal fun MainActivity.addRow(h: PageHandle, row: View) {
    h.list.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(ROW_PARAMS_BOTTOM) })
}

internal fun MainActivity.addEmptyNote(h: PageHandle, text: String) {
    h.list.addView(TextView(this).apply {
        this.text = text
        textSize = Typo.BODY
        gravity = Gravity.CENTER
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(8), dp(64), dp(8), 0)
    }, LinearLayout.LayoutParams(MATCH, WRAP))
}

/** Pilihan pil kecil (mis. Aktif / Arsip): yang terpilih berlatar gelap. */
internal fun MainActivity.pill(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = Typo.BODY_S
    gravity = Gravity.CENTER
    setTextColor(if (selected) onColor(Palette.STRONG) else Palette.INK)   // terpilih: latar kontras tema (gelap di terang, terang di gelap)
    background = pressable(rounded(if (selected) Palette.STRONG else Palette.TILE, 18))
    setPadding(dp(16), 0, dp(16), 0)
    setOnClickListener { onClick() }
}

internal fun MainActivity.pillRow(vararg pills: TextView): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    setPadding(dp(16), dp(4), dp(16), dp(8))
    pills.forEachIndexed { i, p ->
        addView(p, LinearLayout.LayoutParams(WRAP, dp(36)).apply { if (i > 0) marginStart = dp(8) })
    }
}

private val DATE_FMT = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id", "ID"))
private val HOUR_FMT = SimpleDateFormat("HH:mm", Locale("id", "ID"))

/** Waktu lengkap, mis. "27 Okt 2026, 14:05". [ms] dalam milidetik. */
internal fun fmtDateTime(ms: Long): String = synchronized(DATE_FMT) { DATE_FMT.format(Date(ms)) }

/** "Hari ini, 14:05", "Kemarin, 09:10", "3 hari lalu", atau tanggal lengkap. */
internal fun fmtChatTime(updated: Long, now: Long = System.currentTimeMillis()): String {
    val label = ChatStore.dayLabel(updated, now, TimeZone.getDefault().getOffset(now).toLong())
    return when {
        label.isEmpty() -> fmtDateTime(updated)
        label == "Hari ini" || label == "Kemarin" -> label + ", " + synchronized(HOUR_FMT) { HOUR_FMT.format(Date(updated)) }
        else -> label
    }
}
