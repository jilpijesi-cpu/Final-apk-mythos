package com.build.mythos

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Layar sederhana yang menampilkan penyebab crash. Sengaja tidak memakai Palette/tema aplikasi agar tidak ikut crash. */
class CrashActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withLockedDisplay())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(lockDisplayConfiguration(newConfig))
        resources.enforceLockedDisplayMetrics()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resources.enforceLockedDisplayMetrics()
        val trace = redactSecrets(intent?.getStringExtra(CrashReporter.EXTRA_TRACE).orEmpty()).ifEmpty { "(tidak ada keterangan)" }
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val night = isNightMode(resources.configuration.uiMode)
        val bgCol = if (night) 0xFF1A1918.toInt() else Color.WHITE
        val inkCol = if (night) 0xFFECEAE5.toInt() else Color.BLACK
        val softCol = if (night) 0xFF9E9C95.toInt() else Color.DKGRAY

        val title = TextView(this).apply {
            text = "Aplikasi berhenti karena galat"
            textSize = Typo.TITLE
            setTextColor(inkCol)
            typeface = Typeface.DEFAULT_BOLD
        }
        val hint = TextView(this).apply {
            text = "Kunci/token sudah disamarkan otomatis, tetapi periksa teks ini dulu. Tekan Salin lalu kirim ke pengembang."
            textSize = Typo.BODY_S
            setTextColor(softCol)
            setPadding(0, px(4), 0, px(8))
        }
        val body = TextView(this).apply {
            text = trace
            textSize = Typo.CAPTION
            typeface = Typeface.MONOSPACE
            setTextColor(inkCol)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(body) }
        val copy = Button(this).apply {
            text = "Salin"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
                Toast.makeText(this@CrashActivity, "Tersalin", Toast.LENGTH_SHORT).show()
            }
        }
        val close = Button(this).apply {
            text = "Tutup"
            setOnClickListener { finishAndRemoveTask() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(copy)
            addView(close)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgCol)
            setPadding(px(16), px(40), px(16), px(16))
            addView(title)
            addView(hint)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons)
        }
        setContentView(root)
    }
}
