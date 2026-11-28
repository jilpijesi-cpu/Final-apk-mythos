package com.build.mythos

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// Lampiran file: kartu di atas kolom input (badge tipe, nama, tombol ×), lalu keyboard terbuka untuk mengetik perintahnya.

/** Satu lampiran. path masih null selama file disalin ke folder attachments workspace. */
internal class Attachment(val name: String, val previewUri: Uri? = null, @Volatile var path: String? = null, @Volatile var previewPath: String? = null) {
    /** Jenis untuk catatan lampiran aktif: gambar, zip, pdf, docx, teks, atau file. */
    @Volatile var kind: String = attachmentKind(name)

    /** Isi atau manifest yang dibaca otomatis saat file dipilih (kosong bila tidak ada yang bisa dibaca). Ditempel ke pesan saat dikirim. */
    @Volatile var digest: String = ""

    /** Ringkasan satu-dua baris untuk catatan lampiran aktif. */
    @Volatile var summary: String = ""

    /** Folder hasil ekstrak (zip) di Termux, atau null. */
    @Volatile var extractedDir: String? = null
}

/** Gambar ulang deretan kartu; deretan disembunyikan kalau kosong. Panggil dari thread UI. */
internal fun MainActivity.refreshAttachments() {
    attachRow.removeAllViews()
    attachScroll.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
    for (a in attachments) {
        attachRow.addView(attachmentChip(a), LinearLayout.LayoutParams(dp(104), dp(104)).apply { marginEnd = dp(8) })
    }
    updateAction()
}

internal fun MainActivity.clearAttachments() {
    attachments.clear()
    refreshAttachments()
}

/** Fokus ke kolom input dan buka keyboard (agak ditunda karena baru kembali dari pemilih file). */
internal fun MainActivity.focusInput() {
    input.requestFocus()
    input.postDelayed({
        if (isFinishing || isDestroyed || !input.isAttachedToWindow) return@postDelayed
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }, 250)
}

internal fun MainActivity.attachmentChip(a: Attachment): View {
    val ready = a.path != null
    val ext = a.name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
    val card = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(tint(Palette.BRAND, 0.94f))
            setStroke(dp(2), tint(Palette.BRAND, 0.7f))
        }
        alpha = if (ready) 1f else 0.55f   // redup selama masih disalin
        contentDescription = if (ready) "Lampiran ${a.name}" else "Lampiran ${a.name}, sedang disalin"
        // UI-07: potong pratinjau gambar mengikuti sudut membulat kartu 20dp.
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, out: android.graphics.Outline) {
                out.setRoundRect(0, 0, v.width, v.height, dp(20).toFloat())
            }
        }
        clipToOutline = true
    }
    if (a.previewUri != null) {
        val preview = ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            try {
                setImageURI(a.previewUri)
            } catch (ignored: Exception) { logQuiet("Attachments.kt", ignored) }
            alpha = if (ready) 1f else 0.65f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // dibaca lewat deskripsi kartu
        }
        card.addView(preview, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }
    val badge = TextView(this).apply {
        text = if (ready) ext else "SALIN"
        textSize = Typo.CAPTION
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.BRAND, Palette.BRAND_2)
        ).apply { cornerRadius = dp(12).toFloat() }
    }
    card.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
        setMargins(dp(10), dp(10), 0, 0)
    })
    val close = TextView(this).apply {
        text = "✕"
        textSize = Typo.BODY_S
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.SLATE) }, dp(6))   // lingkaran 32dp di area sentuh 44dp
        contentDescription = "Hapus lampiran ${a.name}"
        setOnClickListener { attachments.remove(a); refreshAttachments() }
    }
    // Area sentuh 44dp (pedoman aksesibilitas ~48dp) tanpa mengubah tampilan lingkaran 32dp: lingkaran digambar di dalam wadah.
    card.addView(close, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
        setMargins(0, dp(2), dp(2), 0)
    })
    val hasPreview = a.previewUri != null
    val nameView = TextView(this).apply {
        text = if (ready) a.name else "${a.name}\nMenyiapkan lampiran…"
        textSize = Typo.SMALL
        setTextColor(if (hasPreview) Color.WHITE else Palette.TEXT_MAIN)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        if (hasPreview) {
            setPadding(dp(8), dp(4), dp(8), dp(6))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xB8000000.toInt())
            )
        }
    }
    card.addView(nameView, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
        if (!hasPreview) setMargins(dp(10), 0, dp(10), dp(10))
    })
    return card
}
