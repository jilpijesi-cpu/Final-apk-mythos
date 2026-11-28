package com.build.mythos

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// Baris status ringkas dengan ikon monokrom dan detail opsional.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.buildStatusRow() {
    statusLead = FrameLayout(this)
    statusTitle = ShimmerText(this).apply {
        textSize = Typo.BODY_S
        setTextColor(Palette.INK)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        setShimmer(false)
    }
    statusChevron = IconView(this, IconView.CHEVRON, Palette.TEXT_SOFT)
    statusDetail = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = Typo.CAPTION
        setTextColor(Palette.TEXT_MAIN)
        background = GradientDrawable().apply {
            setColor(Palette.SURFACE)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Palette.BORDER)
        }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        visibility = View.GONE
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(statusLead, LinearLayout.LayoutParams(dp(32), dp(24)))
        addView(statusTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(statusChevron, LinearLayout.LayoutParams(dp(22), dp(22)))
        setOnClickListener { toggleDetail() }
        a11yExpandable { detailOpen }
    }
    statusRoot = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(header)
        addView(statusDetail, LinearLayout.LayoutParams(MATCH, WRAP))
    }
}

internal fun MainActivity.toggleDetail() {
    if (curDetail.isEmpty()) return
    detailOpen = !detailOpen
    statusDetail.visibility = if (detailOpen) View.VISIBLE else View.GONE
    statusChevron.rotation = if (detailOpen) 90f else 0f
    scrollDown()
}

/** Salinan keadaan status, untuk dipulihkan setelah status sementara (persetujuan, hitung mundur, dsb). */
internal class StatusSnap(val kind: Int, val title: String, val detail: String, val quiet: Boolean)

internal fun MainActivity.saveStatus() = StatusSnap(curKind, curTitle, curDetail, curQuiet)

internal fun MainActivity.restoreStatus(s: StatusSnap) = setStatus(s.kind, s.title, s.detail, s.quiet)

/**
 * Ubah ikon + teks status. Aman dipanggil dari thread mana pun.
 * [quiet] = hanya catat keadaan (dipakai pulihkan status) tanpa menampilkan baris status; dipakai saat
 * langkah alat berjalan, karena kartu Aktivitas Agen sudah menampilkannya langsung.
 */
internal fun MainActivity.setStatus(kind: Int, title: String, detail: String = "", quiet: Boolean = false) {
    curKind = kind
    curTitle = title
    curDetail = detail
    curQuiet = quiet
    runOnUiThread {
        emptyState.visibility = View.GONE
        // Aktivitas agen selalu diringkas di satu baris stabil paling atas; detail lengkap ada saat diketuk.
        if (kind != KIND_HIDE) zone.showCompactLine(title, kind)
        refreshAgentBubbleHeader()
        // Selama panel aktivitas tampil, ia yang menggantikan baris status di dalam chat (tidak ditampilkan dua kali).
        if (kind == KIND_HIDE || quiet || zone.isShown) {
            stopStatusTicker()
            statusTitle.setShimmer(false)
            (statusRoot.parent as? ViewGroup)?.removeView(statusRoot)
            leadKind = -2
            return@runOnUiThread
        }
        if (kind != leadKind) {
            statusLead.removeAllViews()
            statusTitle.shimmerBase = Palette.INK
            statusTitle.shimmerHi = Palette.SHIMMER_HI
            if (kind == KIND_DOTS || isLogoKind(kind)) {
                statusLead.addView(DotsView(this), FrameLayout.LayoutParams(MATCH, MATCH))
            } else {
                statusLead.addView(
                    IconView(this, kind, accentFor(kind)).apply { working = kind != IconView.CLOCK },
                    FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER_VERTICAL)
                )
            }
            leadKind = kind
        }
        statusBaseTitle = title
        statusTitle.text = title
        statusTitle.setShimmer(kind != IconView.CLOCK)
        startStatusTicker(title)
        statusDetail.text = detail
        statusChevron.visibility = if (detail.isEmpty()) View.INVISIBLE else View.VISIBLE
        statusDetail.visibility = if (detail.isNotEmpty() && detailOpen) View.VISIBLE else View.GONE
        if (statusRoot.parent == null) chatBox.addView(statusRoot, LinearLayout.LayoutParams(MATCH, WRAP))
        scrollDown()
    }
}

internal fun MainActivity.hideStatus() = runOnUiThread {
    stopStatusTicker()
    statusTitle.setShimmer(false)
    (statusRoot.parent as? ViewGroup)?.removeView(statusRoot)
    detailOpen = false
    statusChevron.rotation = 0f
    leadKind = -2
}

/** Ujung isi chat yang sebenarnya (di luar ruang kosong di bawahnya). */
internal fun MainActivity.contentEnd(): Int {
    val c = scroll.getChildAt(0) ?: return 0
    return c.height - c.paddingBottom
}

/**
 * Gulir otomatis sesuai rekaman acuan: layar diam selama isi baru masih muat di layar (ruang kosong ada di bawahnya);
 * baru mengikuti ke bawah ketika ujung isi melewati tepi bawah layar.
 */
internal fun MainActivity.scrollDown() {
    if (userScrolledUp) return
    scroll.post { startFollow() }
}

/**
 * Pengikut halus: tiap frame layar bergeser sebagian dari jarak yang tertinggal (easing), bukan melompat.
 * Satu baris baru atau blok yang bertambah tinggi jadi gerakan mulus. Berhenti bila isi sudah terlihat,
 * pengguna menggulir ke atas, atau posisi tidak bisa bergeser lagi.
 */
internal fun MainActivity.startFollow() {
    if (followRunning) return
    followRunning = true
    val step = object : Runnable {
        override fun run() {
            if (userScrolledUp) { followRunning = false; return }
            val y = scroll.scrollY
            val dist = (contentEnd() - scroll.height + dp(24)).coerceAtLeast(0) - y
            if (dist <= 0) { followRunning = false; return }
            val move = maxOf(1, (dist * 0.22f).toInt()).coerceAtMost(dist)
            autoScrolling = true
            try { scroll.scrollTo(0, y + move) } finally { autoScrolling = false }
            if (scroll.scrollY == y) { followRunning = false; return }
            scroll.postOnAnimation(this)
        }
    }
    scroll.postOnAnimation(step)
}

internal fun MainActivity.forceScrollDown() {
    userScrolledUp = false
    scrollDownBtn.visibility = View.GONE
    scroll.post { scroll.scrollTo(0, (contentEnd() - scroll.height + dp(24)).coerceAtLeast(0)) }
}

/** Setelah pesan dikirim, pesan pengguna dinaikkan ke bagian paling atas layar (seperti rekaman acuan). */
internal fun MainActivity.pinToTop(v: View) {
    userScrolledUp = false
    scrollDownBtn.visibility = View.GONE
    v.post {
        scroll.post { scroll.scrollTo(0, (v.top - dp(12)).coerceAtLeast(0)) }
    }
}

/**
 * Penghitung waktu jujur: setelah 3 detik di fase yang sama, judul diberi lama tunggu nyata
 * ("Menunggu respons model AI · 12 dtk"). Tidak ada teks karangan tentang apa yang "sedang terjadi".
 */
internal fun MainActivity.startStatusTicker(title: String) {
    stopStatusTicker()
    val startMs = System.currentTimeMillis()
    val task = object : Runnable {
        override fun run() {
            if (!busy || curTitle != title || statusRoot.parent == null) return
            val sec = (System.currentTimeMillis() - startMs) / 1000
            if (sec >= 3) statusTitle.text = "$title · $sec dtk"
            statusTickerHandler.postDelayed(this, 1000)
        }
    }
    statusTickerTask = task
    statusTickerHandler.postDelayed(task, 1000)
}

internal fun MainActivity.stopStatusTicker() {
    statusTickerTask?.let { statusTickerHandler.removeCallbacks(it) }
    statusTickerTask = null
}
