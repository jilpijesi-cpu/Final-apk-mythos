package com.build.mythos

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Lembar menu yang dibuka tombol "apps" di baris ikon kolom input. Menggantikan drawer samping:
// chat baru, riwayat, model AI, penalaran mendalam, proyek, code, artefak, dan pengaturan semuanya ada di sini.

internal fun MainActivity.showMenuSheet() {
    hideKeyboard()
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
            textSize = Typo.BODY
            setTextColor(Palette.INK)
            maxLines = 1
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        setOnClickListener { close(onClick) }
    }

    fun trailing(t: CharSequence): View = TextView(this).apply {
        text = t
        textSize = Typo.BODY_S
        setTextColor(Palette.INK_SOFT)
        maxLines = 1
        maxWidth = dp(170)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    fun row(
        glyph: Int, label: String, trail: View? = null, selected: Boolean = false,
        onLong: (() -> Unit)? = null, onClick: () -> Unit
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(rounded(if (selected) Palette.TILE else Palette.NAV, 22, if (selected) 0 else Palette.FIELD_BORDER, 1))
        setPadding(dp(16), 0, dp(14), 0)
        addView(GlyphView(context, glyph, Palette.INK).apply { strokeDp = 1.5f }, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(TextView(context).apply {
            text = label
            textSize = Typo.BODY_L
            setTextColor(Palette.INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
        if (trail != null) addView(trail, LinearLayout.LayoutParams(WRAP, WRAP))
        setOnClickListener { hapticTap(); onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }

    fun sectionTitle(t: String): View = TextView(this).apply {
        text = t
        textSize = Typo.BODY
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(6), dp(20), 0, dp(8))
    }

    val header = FrameLayout(this).apply {
        addView(TextView(context).apply {
            text = "Menu"
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
        addView(tile(GlyphView.NEW_CHAT_LINE, "Chat baru") { if (incognito) setIncognito(false) else newChat() },
            LinearLayout.LayoutParams(0, dp(92), 1f))
        addView(tile(GlyphView.CHATS, "Semua chat") { showAllChats() },
            LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginStart = dp(8) })
    }

    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        fun add(v: View, topDp: Int = 10) = addView(v, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(topDp) })

        addView(tiles, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })

        addView(sectionTitle("AI"))
        add(row(GlyphView.BRAIN, "Model AI", trailing(modelLabel.text)) { close { pickModel() } }, 0)
        add(row(GlyphView.SLIDERS, "Penyedia AI & kunci", trailing(ProviderCatalog.label(fallbackProv ?: prov))) { close { showApiKeyDialog() } })
        add(
            row(
                GlyphView.GHOST_SOLID,
                "Chat samaran",
                trailing(if (incognito) "Aktif" else "Nonaktif"),
                selected = incognito
            ) { close { setIncognito(!incognito) } }
        )
        add(
            row(
                GlyphView.GLOBE,
                "MYTHOS LeakOSINT",
                trailing(if (leakOsintMode()) "Aktif" else "Nonaktif"),
                selected = leakOsintMode()
            ) { close { setLeakOsintMode(!leakOsintMode()) } }
        )

        addView(sectionTitle("Jelajahi"))
        add(row(GlyphView.PROJECT, "Ruang kerja") { close { showAgentWorkspacePage() } }, 0)
        add(row(GlyphView.PROJECT, "Proyek") { close { showProjectsPage() } })
        add(row(GlyphView.CODE, "Code") { close { startActivity(Intent(this@showMenuSheet, CommandLogActivity::class.java)) } })
        add(row(GlyphView.ARTIFACT, "Artefak") { close { showArtifactsPage() } })

    }

    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.BG, 34)
        setPadding(dp(16), dp(10), dp(16), dp(20))
        addView(View(context).apply { background = rounded(Palette.DIVIDER, 3) },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        addView(header, LinearLayout.LayoutParams(MATCH, dp(44)))
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    d.setContentView(FrameLayout(this).apply {
        setPadding(dp(6), dp(48), dp(6), dp(6))   // sisakan ruang di atas supaya lembar tinggi tidak menempel status bar
        addView(body, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
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
