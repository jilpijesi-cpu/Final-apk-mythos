package com.build.mythos

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

// Draf email tampil LANGSUNG di chat (bukan di dialog konfirmasi). Kartunya sama dengan EmailDraftCardView
// (bisa diedit lewat ikon pensil); di bawahnya ada tombol Kirim / Tolak. Pengiriman tetap menunggu tombol Kirim
// karena pesan dikirim atas nama pengguna dan tidak bisa ditarik kembali.

/**
 * Tambahkan draf [draft] ke chat. [onYes]/[onNo] dipanggil sekali saja, di thread UI.
 * Mengembalikan fungsi penutup: dipanggil bila tidak ada jawaban (waktu habis / layar ditutup) agar tombolnya tidak menipu.
 */
internal fun MainActivity.showInlineEmailDraft(
    draft: EmailDraft, warning: String?, onYes: () -> Unit, onNo: () -> Unit
): () -> Unit {
    val card = EmailDraftCardView(this, draft)
    val done = AtomicBoolean(false)
    // UI-01: perhitungkan padding chatBox (32dp) + margin box (24dp) = 56dp agar kartu & tombol Kirim tidak terpotong di tepi kanan.
    val maxW = minOf(resources.displayMetrics.widthPixels - dp(56), dp(480)).coerceAtLeast(dp(220))

    val status = TextView(this).apply {
        textSize = Typo.BODY_S
        setTextColor(Palette.INK)
        visibility = View.GONE
        setPadding(dp(4), dp(8), 0, 0)
    }
    val warn = warning?.let {
        TextView(this).apply {
            text = it
            textSize = Typo.CAPTION
            setTextColor(Palette.RED)
            setPadding(dp(4), dp(8), 0, 0)
        }
    }

    fun pill(label: String, fill: Int, ink: Int) = TextView(this).apply {
        text = label
        textSize = Typo.BODY_S
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ink)
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(10), dp(22), dp(10))
        background = pressable(rounded(fill, 20))
        isClickable = true
        isFocusable = true
    }
    val send = pill("Kirim", 0xFF1A73E8.toInt(), Color.WHITE)
    val reject = pill("Tolak", Palette.FIELD, Palette.INK)
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(10), 0, 0)
        addView(reject)
        addView(send, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
    }

    fun settle(text: String) {
        buttons.visibility = View.GONE
        status.text = text
        status.visibility = View.VISIBLE
    }
    send.setOnClickListener {
        if (!done.compareAndSet(false, true)) return@setOnClickListener
        card.commitEdits() // hasil edit ikut terkirim
        settle("Mengirim email…")
        onYes()
    }
    reject.setOnClickListener {
        if (!done.compareAndSet(false, true)) return@setOnClickListener
        settle("Draf ditolak, email tidak dikirim.")
        onNo()
    }

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        warn?.let { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
        addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    insertBeforeStatus(box, LinearLayout.LayoutParams(maxW, WRAP).apply {
        setMargins(dp(12), dp(6), dp(12), dp(6))
    })
    scrollDown()
    return {
        if (done.compareAndSet(false, true)) settle("Draf tidak dijawab, email tidak dikirim.")
    }
}
