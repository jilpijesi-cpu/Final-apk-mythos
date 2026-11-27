package com.build.mythos

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Layar "Catatan perintah": perintah yang pernah disetujui, kapan, bagaimana, dan hasilnya. */
class CommandLogActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private val fmt = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale("id", "ID"))
    private var loadGen = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(s: String) = Color.parseColor(s)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.withLockedDisplay())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(lockDisplayConfiguration(newConfig))
        resources.enforceLockedDisplayMetrics()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncAppFontPrefs(this)
        resources.enforceLockedDisplayMetrics()
        applyTheme(this)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        window.applyBarAppearance(navLight = true)

        val back = TextView(this).apply {
            text = "‹"
            textSize = Typo.NAV_GLYPH
            gravity = Gravity.CENTER
            setTextColor(Palette.TEXT_MAIN)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Catatan perintah"
            textSize = Typo.TITLE
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Palette.TEXT_MAIN)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val clear = TextView(this).apply {
            text = "Hapus"
            textSize = Typo.BODY
            gravity = Gravity.CENTER
            setTextColor(Palette.ICON)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { confirmClear() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(clear, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        }
        count = TextView(this).apply {
            textSize = Typo.CAPTION
            setTextColor(Palette.TEXT_SOFT)
            setPadding(dp(20), 0, dp(20), dp(8))
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(count, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        applyFontWeightTree(root)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()   // segarkan: agen bisa mencatat perintah baru saat layar ini ditinggalkan
    }

    private fun render() {
        val gen = ++loadGen
        Thread {
            val entries = CommandLog.load(this)
            runOnUiThread {
                if (isFinishing || isDestroyed || gen != loadGen) return@runOnUiThread
                bindEntries(entries)
            }
        }.start()
    }

    private fun bindEntries(entries: List<LogEntry>) {
        list.removeAllViews()
        count.text = if (entries.isEmpty()) "" else "${entries.size} catatan terakhir (maks ${CommandLog.MAX_KEEP}). Ketuk untuk melihat selengkapnya."
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Belum ada perintah yang disetujui."
                textSize = Typo.BODY
                gravity = Gravity.CENTER
                setTextColor(Palette.TEXT_SOFT)
                setPadding(0, dp(64), 0, 0)
            })
            return
        }
        for (e in entries) list.addView(card(e), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
    }

    private fun chip(text: String, bg: Int, fg: Int) = TextView(this).apply {
        this.text = text
        textSize = Typo.CAPTION
        setTextColor(fg)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(bg) }
    }

    private fun card(e: LogEntry): View {
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val cmd = TextView(this).apply {
            text = e.command
            typeface = Typeface.MONOSPACE
            textSize = Typo.SMALL
            setTextColor(Palette.TEXT_MAIN)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        val result = TextView(this).apply {
            text = (if (e.ok) "✓ " else "✗ ") + e.result.ifBlank { if (e.ok) "Berhasil" else "Gagal" }
            typeface = Typeface.MONOSPACE
            textSize = Typo.CAPTION
            setTextColor(if (e.ok) Palette.GREEN else Palette.RED)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = fmt.format(Date(e.time))
                textSize = Typo.CAPTION
                setTextColor(Palette.TEXT_SOFT)
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(chip(e.risk.name, e.risk.color, onColor(e.risk.color)))
        }
        val how = TextView(this).apply {
            text = e.mode.label + if (e.kind == "perintah") "" else " · ${e.kind}"
            textSize = Typo.CAPTION
            setTextColor(Palette.TEXT_SOFT)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Palette.CARD); setStroke(dp(1), Palette.BORDER) }
            addView(meta, LinearLayout.LayoutParams(match, wrap))
            addView(cmd, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
            addView(how, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
            addView(result, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
        }
        if (e.description.isNotBlank()) {
            // keterangan dari model: hanya info, jadi diberi label supaya tidak dikira penilaian aplikasi
            box.addView(TextView(this).apply {
                text = "Kata model: ${e.description}"
                textSize = Typo.CAPTION
                setTextColor(Palette.TEXT_SOFT)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, 2, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
        }
        var open = false
        box.setOnClickListener {
            open = !open
            val n = if (open) Int.MAX_VALUE else 3
            cmd.maxLines = n
            result.maxLines = n
            cmd.setTextIsSelectable(open)
        }
        return box
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Hapus semua catatan perintah? Ini tidak bisa dibatalkan.")
            .setPositiveButton("Hapus") { _, _ ->
                Thread {
                    CommandLog.clear(this)
                    runOnUiThread { if (!isFinishing && !isDestroyed) render() }
                }.start()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
