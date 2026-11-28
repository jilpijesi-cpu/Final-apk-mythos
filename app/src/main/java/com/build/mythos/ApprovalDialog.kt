package com.build.mythos

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

data class ApprovalRequest(
    val command: String,
    val description: String // dari model; hanya penjelasan, BUKAN dasar penilaian risiko
)

/**
 * Dialog persetujuan: penjelasan sederhana, label risiko berwarna, lalu perintah asli.
 * "Ya, semua" muncul untuk semua perintah dan berlaku untuk sisa tugas.
 * Panggil dari thread UI.
 */
fun showApprovalDialog(
    act: Activity,
    req: ApprovalRequest,
    onYes: () -> Unit,
    onYesAll: () -> Unit,
    onNo: () -> Unit,
    offerAll: Boolean? = null,          // null = tombol hanya untuk risiko READ
    allLabel: String = "Ya, semua",
    warning: String? = null,            // peringatan area sensitif, tampil merah
    simple: Boolean = false,            // true: judul dan tombol seadanya (Izinkan / Tolak)
    card: android.view.View? = null     // kartu khusus (mis. draf email); bila ada, menggantikan penjelasan, label risiko, dan perintah
): AlertDialog {
    val risk = classify(req.command)
    val d = act.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val box = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(8))
    }
    if (card != null) box.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    // 1. Penjelasan sederhana (sembunyikan bila kosong agar dialog tetap ringkas)
    if (card == null && req.description.isNotBlank()) box.addView(TextView(act).apply {
        text = req.description
        textSize = Typo.BODY_L
        setTextColor(Palette.INK)
        setLineSpacing(0f, 1.2f)
    })
    // 2. Label risiko berwarna
    if (card == null) box.addView(TextView(act).apply {
        text = risk.label
        textSize = Typo.SMALL
        setTextColor(darkOf(risk.color))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(tint(risk.color, 0.82f))
        }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = if (req.description.isNotBlank()) dp(10) else 0
    })
    if (warning != null) {
        box.addView(TextView(act).apply {
            text = " $warning"
            textSize = Typo.BODY_S
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Palette.RED)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
    }
    // 3. Perintah/detail asli di dalam kotak ringkas (maks 180dp) yang bisa digulir, agar dialog tetap simpel.
    if (card == null) {
        val maxDetailH = dp(180)
        val cmdScroll = object : android.widget.ScrollView(act) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val hMode = MeasureSpec.getMode(heightMeasureSpec)
                val hSize = MeasureSpec.getSize(heightMeasureSpec)
                val capped = when (hMode) {
                    MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxDetailH, MeasureSpec.AT_MOST)
                    else -> MeasureSpec.makeMeasureSpec(minOf(hSize, maxDetailH), MeasureSpec.AT_MOST)
                }
                super.onMeasure(widthMeasureSpec, capped)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            isNestedScrollingEnabled = true
            background = GradientDrawable().apply {
                setColor(Palette.FIELD)
                cornerRadius = dp(12).toFloat()
            }
            addView(TextView(act).apply {
                text = req.command
                typeface = Typeface.MONOSPACE
                textSize = Typo.CAPTION
                setTextColor(Palette.INK)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        box.addView(cmdScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
    }

    val b = AlertDialog.Builder(act)
        .setTitle(if (card != null) "Kirim email ini?" else if (simple) "Izinkan?" else "Jalankan di Termux?")
        .setView(if (card != null) android.widget.ScrollView(act).apply { addView(box) } else box)
        .setCancelable(false)
        .setNegativeButton("Tolak") { _, _ -> onNo() }
    if (card == null) b.setPositiveButton(if (simple) "Izinkan" else "Jalankan") { _, _ -> onYes() }
    else if (card is EmailDraftCardView) b.setPositiveButton("Kirim") { _, _ -> card.commitEdits(); onYes() }  // kartu sama persis dengan acuan: tidak ada tombol di dalamnya
    if (card == null && (offerAll ?: (risk == Risk.READ))) b.setNeutralButton(allLabel) { _, _ -> onYesAll() }
    val dlg = b.show()
    if (card is EmailDraftCardView) {
        // Kolom edit ditambahkan setelah dialog tampil; tanpa ini papan ketik tidak muncul di dalam dialog.
        dlg.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dlg.window?.softInputResize()
    }
    return dlg
}
