package com.build.mythos

import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pembaca layar (TalkBack): jadikan tampilan ini tombol buka/tutup. TalkBack membacakan "Terbuka"/"Tertutup" (Android 11+)
 * dan petunjuk "ketuk dua kali untuk memperluas/menciutkan". [isExpanded] dibaca setiap kali TalkBack meminta info,
 * jadi selalu mengikuti keadaan terkini.
 */
internal fun View.a11yExpandable(isExpanded: () -> Boolean) {
    setAccessibilityDelegate(object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            val open = isExpanded()
            val c = host.context
            info.className = "android.widget.Button"
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK,
                    c.getString(if (open) R.string.a11y_collapse else R.string.a11y_expand)
                )
            )
            if (Build.VERSION.SDK_INT >= 30) {
                info.stateDescription = c.getString(if (open) R.string.a11y_state_open else R.string.a11y_state_closed)
            }
        }
    })
}

/** Pembaca layar: tandai baris pilihan sebagai tombol yang sedang dipilih atau tidak, supaya "dipilih" ikut dibacakan. */
internal fun View.a11ySelected(isSelectedNow: Boolean) {
    setAccessibilityDelegate(object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = "android.widget.Button"
            info.isSelected = isSelectedNow
        }
    })
}
