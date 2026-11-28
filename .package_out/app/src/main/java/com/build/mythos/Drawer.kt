package com.build.mythos

import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// Mode Chat samaran dan lembar "Tambahkan ke chat".
// Menu samping (drawer) sudah dihapus karena seluruh fungsinya dipindah ke MenuSheet.kt.

// ------------------------------------------------------------------ Chat samaran

/** Hidupkan/matikan Chat samaran: pesan tidak disimpan ke riwayat di perangkat. */
internal fun MainActivity.setIncognito(on: Boolean) {
    if (on == incognito) return
    if (busy) { toast("Tunggu proses selesai"); return }
    if (on && (hist.isNotEmpty() || chatBox.childCount > 0)) {
        toast("Mulai chat baru dulu untuk memakai Chat samaran")
        return
    }
    if (!on && (hist.isNotEmpty() || chatBox.childCount > 0)) newChat() // chat samaran tidak pernah ditulis ke disk, jadi aman
    // Saat keluar: newChat() sudah membuang checkpoint, pratinjau gambar, dan salinan lampiran. Panggilan ini menjamin checkpoint
    // di memori ikut hilang walau layar sudah kosong. incognito harus masih true selama newChat() agar gambar ikut dihapus.
    if (!on) clearAgentCheckpoint()
    incognito = on
    incognitoWriteWarned = false
    applyIncognitoLook()
}

internal fun MainActivity.applyIncognitoLook() {
    topTitle.visibility = if (incognito) View.VISIBLE else View.GONE
    ghostCircleBg.setColor(if (incognito) Palette.STRONG else Palette.CARD)
    ghostCircleBg.setStroke(dp(1), if (incognito) Palette.STRONG else Palette.FIELD_BORDER)
    ghostIcon.color = if (incognito) onColor(Palette.STRONG) else Palette.INK
    emptyNormal.visibility = if (incognito) View.GONE else View.VISIBLE
    emptyIncognito.visibility = if (incognito) View.VISIBLE else View.GONE
    refreshLeakOsintLook()
}

internal fun MainActivity.showIncognitoInfo() {
    AlertDialog.Builder(this)
        .setTitle("Chat samaran")
        .setMessage(
            "Percakapan di Chat samaran tidak ditulis ke riwayat di perangkat ini, jadi hilang saat Anda keluar dari mode ini atau menutup aplikasi.\n\n" +
                "Pesan tetap dikirim ke penyedia AI yang Anda pilih agar bisa dijawab, dan file yang dibuat agen di folder kerja tidak ikut dihapus."
        )
        .setPositiveButton("Mengerti", null)
        .show()
}

// ------------------------------------------------------------------ Lembar "Tambahkan ke chat"

/** Ambil foto dengan aplikasi kamera; hasilnya masuk sebagai lampiran lewat [MainActivity.onActivityResult] (kode 80). */
internal fun MainActivity.takePhoto() {
    try {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "mythos_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MYTHOS")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
        if (uri == null) { toast("Kamera tidak tersedia"); return }
        cameraUri = uri
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(i, 80)
    } catch (e: Exception) {
        toast("Kamera tidak tersedia")
    }
}

internal fun MainActivity.showAddSheet() {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    fun close(then: () -> Unit) { d.dismiss(); then() }

    fun tile(glyph: Int, label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = pressable(rounded(Palette.TILE, 24))
        addView(GlyphView(context, glyph, Palette.INK).apply { strokeDp = 1.5f }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(TextView(context).apply {
            text = label
            textSize = Typo.TITLE_S
            setTextColor(Palette.INK)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        setOnClickListener { close(onClick) }
    }

    fun row(glyph: Int, tone: Int, label: String, trailing: View?, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(rounded(Palette.NAV, 26))
        setPadding(dp(16), 0, dp(14), 0)
        addView(GlyphView(context, glyph, tone).apply { strokeDp = 1.5f }, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(TextView(context).apply {
            text = label
            textSize = Typo.TITLE_S
            setTextColor(tone)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        if (trailing != null) addView(trailing, LinearLayout.LayoutParams(WRAP, WRAP))
        setOnClickListener { onClick() }
    }

    fun trailingText(t: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = t
            textSize = Typo.BODY
            setTextColor(Palette.INK_SOFT)
        })
        addView(GlyphView(context, GlyphView.CHEVRON_R, Palette.INK_SOFT).apply { strokeDp = 1.4f },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(6) })
    }

    val deepSwitch = android.widget.Switch(this).apply { isChecked = deepOn(); isClickable = false }

    val header = FrameLayout(this).apply {
        addView(TextView(context).apply {
            text = "Tambahkan ke chat"
            textSize = Typo.TITLE_S
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.INK)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(MATCH, dp(44), Gravity.CENTER_VERTICAL))
        addView(FrameLayout(context).apply {
            contentDescription = "Tutup"
            background = pressable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.NAV)
                setStroke(dp(1), Palette.BORDER)
            })
            addView(GlyphView(context, GlyphView.CLOSE, Palette.INK).apply { strokeDp = 1.5f }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { d.dismiss() }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
    }

    val tiles = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(tile(GlyphView.CAMERA, "Kamera") { takePhoto() }, LinearLayout.LayoutParams(0, dp(100), 1f))
        addView(tile(GlyphView.IMAGE, "Foto") { pickImage() }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { marginStart = dp(8) })
        addView(tile(GlyphView.FILE_UP, "File") { pickFile() }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { marginStart = dp(8) })
    }

    val workdirName = workdir().substringAfterLast('/').ifBlank { "MYTHOS" }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.BG, 34)
        setPadding(dp(16), dp(10), dp(16), dp(20))
        addView(View(context).apply { background = rounded(Palette.DIVIDER, 3) },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        addView(header, LinearLayout.LayoutParams(MATCH, dp(44)))
        val contentCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(tiles, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(24) })
            if (hasRecentPhotoAccess()) {
                addView(buildRecentStrip { uri -> d.dismiss(); importFile(uri) },
                    LinearLayout.LayoutParams(MATCH, dp(92)).apply { topMargin = dp(16) })
            } else {
                addView(row(GlyphView.PHOTOS, Palette.LINK_BLUE, "Tampilkan foto terbaru", null) { close { askRecentPhotoAccess() } },
                    LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(16) })
            }
            addView(row(GlyphView.PROJECT, Palette.INK, "Tambahkan ke proyek", trailingText(workdirName)) { close { showSettings() } },
                LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(14) })
            addView(row(GlyphView.BRAIN, Palette.INK, "Penalaran mendalam", deepSwitch) {
                val on = !deepOn()
                setDeep(on)
                deepSwitch.isChecked = on
                toast(if (on) "Penalaran mendalam aktif" else "Penalaran mendalam dimatikan")
            }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(14) })
        }
        // UI-08: bungkus dalam ScrollView agar baris bawah tidak terpotong di layar kecil atau mode lanskap.
        addView(android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(contentCol, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    d.setContentView(FrameLayout(this).apply {
        setPadding(dp(6), 0, dp(6), dp(6))
        addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
    })
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        setDimAmount(0.28f)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
    }
    d.show()
}
