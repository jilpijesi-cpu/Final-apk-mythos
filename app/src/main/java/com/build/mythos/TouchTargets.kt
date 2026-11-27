package com.build.mythos

import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView

/** Ukuran area sentuh minimum yang direkomendasikan pedoman aksesibilitas Android (F-23). */
internal const val MIN_TOUCH_TARGET_DP = 48

/** Hitung ukuran hitbox efektif tombol (dp) beserta perluasan TouchPadDelegate (dapat diuji di JVM). */
internal fun effectiveTouchTargetDp(
    widthDp: Int, heightDp: Int,
    leftPadDp: Int, topPadDp: Int, rightPadDp: Int, bottomPadDp: Int
): Pair<Int, Int> = (widthDp + leftPadDp + rightPadDp) to (heightDp + topPadDp + bottomPadDp)

/**
 * Perluas area ketuk beberapa tombol anak tanpa mengubah ukuran atau gambarnya. Pedoman aksesibilitas Android
 * menganjurkan area ketuk sekitar 48dp; tombol bulat 36dp di kolom input terlalu kecil untuk jari.
 *
 * Dipasang di [host] (induk yang lebih besar dari tombol, mis. kolom input berbantalan). Ketukan yang jatuh di luar
 * tombol tetapi di dalam area perluasan diteruskan ke tombol itu sebagai ketukan di tengahnya. Ketukan yang tepat
 * mengenai tombol atau anak lain tetap ditangani seperti biasa (TouchDelegate hanya dipakai untuk sisa ketukan).
 * Posisi tombol dibaca saat sentuhan terjadi, jadi tombol yang disembunyikan atau berpindah tempat selalu benar.
 */
internal class TouchPadDelegate(private val host: ViewGroup) : TouchDelegate(Rect(), host) {
    private class Pad(val view: View, val left: Int, val top: Int, val right: Int, val bottom: Int)

    private val pads = ArrayList<Pad>()
    private val tmp = Rect()
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private var target: Pad? = null

    /** Tambah [view] dengan perluasan (dp) ke tiap sisi. */
    fun add(view: View, leftDp: Int, topDp: Int, rightDp: Int, bottomDp: Int) {
        val d = host.resources.displayMetrics.density
        fun px(v: Int) = (v * d + 0.5f).toInt()
        pads.add(Pad(view, px(leftDp), px(topDp), px(rightDp), px(bottomDp)))
    }

    private fun contains(p: Pad, x: Int, y: Int): Boolean {
        val v = p.view
        if (!v.isAttachedToWindow) return false
        tmp.set(0, 0, v.width, v.height)
        try {
            host.offsetDescendantRectToMyCoords(v, tmp)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return x >= tmp.left - p.left && x < tmp.right + p.right && y >= tmp.top - p.top && y < tmp.bottom + p.bottom
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.toInt()
        val y = event.y.toInt()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            target = pads.firstOrNull { it.view.visibility == View.VISIBLE && it.view.isEnabled && it.view.width > 0 && contains(it, x, y) }
        }
        val p = target ?: return false
        val v = p.view
        val e = MotionEvent.obtain(event)
        if (contains(p, x, y)) e.setLocation(v.width / 2f, v.height / 2f) // dianggap tepat di tombol
        else e.setLocation(-slop * 2f, -slop * 2f)                        // jari bergeser keluar: batalkan ketukan
        val handled = v.dispatchTouchEvent(e)
        e.recycle()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) target = null
        return handled
    }
}

/** Delegasi perluasan area ketuk milik induk ini (dibuat bila belum ada). */
internal fun ViewGroup.touchPads(): TouchPadDelegate {
    val existing = touchDelegate
    if (existing is TouchPadDelegate) return existing
    return TouchPadDelegate(this).also { touchDelegate = it }
}

/**
 * Tambah tinggi area ketuk [extraDp] di atas dan di bawah teks ini tanpa menggemukkan gambarnya: bantalan vertikal
 * bertambah dan latar digeser ke tengah dengan InsetDrawable, jadi pil/chip tampil sama seperti sebelumnya.
 * Panggil SEKALI, sesudah latar dan bantalan diatur. Akibatnya tinggi baris bertambah 2 x [extraDp].
 */
internal fun TextView.extendTouchVertical(extraDp: Int) {
    val e = (extraDp * resources.displayMetrics.density + 0.5f).toInt()
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    val bg = background
    // Memasang InsetDrawable membuat View menimpa bantalan dengan bantalan drawable; karena itu bantalan diatur SESUDAH latar.
    if (bg != null) background = InsetDrawable(bg, 0, e, 0, e)
    setPadding(l, t + e, r, b + e)
}
