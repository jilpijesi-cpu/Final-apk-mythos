package com.build.mythos

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

// Dialog "Hubungkan penyedia AI": satu kartu bersih di tengah layar menggantikan bagian penyedia/kunci/model
// di menu Pengaturan yang lama. Pilih penyedia, tempel kunci, ketuk Hubungkan; model terbaik penyedia itu dipilih
// otomatis (ProviderCatalog.recommend, lalu divalidasi autoSetupModels). Aturan murninya ada di ProviderCatalog.kt.

/** Ikon kunci (garis, tanpa isi): lingkaran kepala + batang + dua gigi, dimiringkan 45 derajat. */
private class KeyGlyphView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = resources.displayMetrics.density
        p.strokeWidth = 2.6f * d
        p.color = Palette.BRAND
        val cx = w / 2f; val cy = h / 2f; val u = minOf(w, h)
        cv.save()
        cv.rotate(-45f, cx, cy)
        cv.drawCircle(cx - u * 0.27f, cy, u * 0.14f, p)
        cv.drawLine(cx - u * 0.13f, cy, cx + u * 0.30f, cy, p)
        cv.drawLine(cx + u * 0.16f, cy, cx + u * 0.16f, cy + u * 0.11f, p)
        cv.drawLine(cx + u * 0.29f, cy, cx + u * 0.29f, cy + u * 0.11f, p)
        cv.restore()
    }
}

/** Ikon mata untuk menampilkan/menyembunyikan kunci: dicoret saat tersembunyi. */
private class EyeView(c: Context) : View(c) {
    var shown = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = resources.displayMetrics.density
        p.strokeWidth = 1.8f * d
        p.color = Palette.INK_SOFT
        val l = w * 0.14f; val r = w * 0.86f; val cy = h / 2f; val a = h * 0.30f
        path.reset()
        path.moveTo(l, cy)
        path.quadTo(w / 2f, cy - a * 1.7f, r, cy)
        path.quadTo(w / 2f, cy + a * 1.7f, l, cy)
        cv.drawPath(path, p)
        cv.drawCircle(w / 2f, cy, h * 0.12f, p)
        if (!shown) cv.drawLine(w * 0.22f, h * 0.80f, w * 0.78f, h * 0.20f, p)
    }
}

/** Lencana penyedia: logo asli bila sudah termuat (di atas lingkaran putih agar terbaca di tema gelap), selain itu lingkaran berwarna dengan huruf awal. */
internal class BadgeView(c: Context, private var text: String, private var color: Int) : View(c) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var logo: Bitmap? = null
    fun setLogo(b: Bitmap?) { logo = b; invalidate() }
    /** Ganti huruf/warna cadangan (dan buang logo lama) saat penyedia berganti. */
    fun setBadge(t: String, col: Int) { text = t; color = col; logo = null; invalidate() }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val l = logo
        if (l != null && !l.isRecycled && l.width > 0 && l.height > 0) {
            val r = minOf(w, h) / 2f
            bg.color = Color.WHITE
            cv.drawCircle(w / 2f, h / 2f, r, bg)
            val box = r * 1.56f
            val scale = minOf(box / l.width, box / l.height)
            val bw = l.width * scale / 2f
            val bh = l.height * scale / 2f
            dst.set(w / 2f - bw, h / 2f - bh, w / 2f + bw, h / 2f + bh)
            cv.drawBitmap(l, null, dst, bmpPaint)
            return
        }
        bg.color = color
        cv.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f, bg)
        tp.textSize = minOf(w, h) * if (text.length > 1) 0.40f else 0.54f
        cv.drawText(text, w / 2f, h / 2f - (tp.descent() + tp.ascent()) / 2f, tp)
    }
}

/** Pasang logo asli ke [badge]: langsung bila sudah ada di memori, selain itu begitu selesai dimuat. */
private fun MainActivity.bindLogo(name: String, badge: BadgeView) {
    ProviderLogos.cached(name)?.let { badge.setLogo(it); return }
    loadProviderLogo(name) { badge.setLogo(it) }
}

/** Tombol logo penyedia di baris ikon kolom input: tampilkan logo penyedia AI yang aktif. Dipanggil updateModelLabel(). */
internal fun MainActivity.refreshProviderBadge() {
    val b = providerBadgeOrNull() ?: return
    val name = fallbackProv ?: prov
    val (letter, color) = ProviderCatalog.badge(name)
    b.setBadge(letter, color)
    (b.parent as? View)?.contentDescription = "Ganti AI: " + ProviderCatalog.label(name)
    ProviderLogos.cached(name)?.let { b.setLogo(it); return }
    loadProviderLogo(name) { bmp -> if ((fallbackProv ?: prov) == name) b.setLogo(bmp) }
}

internal fun MainActivity.showApiKeyDialog() {
    val names = ProviderCatalog.displayOrder(presets.map { it.name })
    names.forEach { n -> loadProviderLogo(n) { } } // muat di latar supaya logo sudah siap saat dropdown dibuka
    fun kindOf(n: String) = ProviderCatalog.kindOf(n, preset(n).fmt)

    var selected: String? = prov.takeIf { !needsKey() || cfgKey().isNotEmpty() }
    var loading = false
    var geminiModel = DEFAULT_GEMINI_CLI_MODEL
    var curLogin: CliLogin? = null
    var popup: PopupWindow? = null
    var dialog: Dialog? = null

    // ---- kepala: tombol tutup, ikon, judul, keterangan
    val closeBtn = FrameLayout(this).apply {
        background = pressable(rounded(Color.TRANSPARENT, 20))
        addView(IconView(context, IconView.CROSS, Palette.INK), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        contentDescription = "Tutup"
        setOnClickListener { dialog?.dismiss() }
    }
    val iconTile = FrameLayout(this).apply {
        background = rounded(Palette.CARD, 20, Palette.BORDER, 1)
        addView(KeyGlyphView(context), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
    }
    val title = TextView(this).apply {
        text = "Hubungkan AI Anda"
        textSize = Typo.DISPLAY
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER
    }
    val desc = TextView(this).apply {
        text = "Gunakan kunci API Anda sendiri. Model terbaik untuk penyedia yang dipilih akan dipakai otomatis."
        textSize = Typo.BODY_S
        setTextColor(Palette.INK_SOFT)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.1f)
    }

    // ---- dropdown penyedia
    val provSlot = FrameLayout(this)
    val provText = TextView(this).apply {
        textSize = Typo.BODY
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    val chevron = IconView(this, IconView.CHEVRON_DOWN, Palette.INK_SOFT)
    val provField = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(rounded(Palette.FIELD, 14))
        setPadding(dp(14), 0, dp(14), 0)
        addView(provSlot, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
        addView(provText, LinearLayout.LayoutParams(0, MATCH, 1f))
        addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)))
    }

    // ---- kunci API (mendukung 1 atau banyak kunci, dipisah baris baru / koma)
    val key = EditText(this).apply {
        hint = "Masukkan 1 atau beberapa kunci API (1 per baris / pisah koma)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = false
        minLines = 1
        maxLines = 5
        gravity = Gravity.TOP or Gravity.START
    }
    styleField(key)
    key.setPadding(dp(14), dp(12), dp(48), dp(12))
    val eye = EyeView(this)
    val eyeBox = FrameLayout(this).apply {
        addView(eye, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        contentDescription = "Tampilkan atau sembunyikan kunci"
        setOnClickListener {
            eye.shown = !eye.shown
            val sel = key.selectionEnd
            key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                if (eye.shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            key.setSelection(sel.coerceIn(0, key.text.length))
        }
    }
    val keyField = FrameLayout(this).apply {
        addView(key, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(eyeBox, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
    }
    val detectNote = TextView(this).apply {
        textSize = Typo.CAPTION; setTextColor(Palette.INK_SOFT); setPadding(dp(4), dp(6), 0, 0); visibility = View.GONE
    }
    val checkKeyBtn = TextView(this).apply {
        text = "Periksa kunci"
        textSize = Typo.CAPTION
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.BRAND)
        background = pressable(rounded(Palette.FIELD, 10, Palette.BORDER, 1))
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }
    val removeDeadBtn = TextView(this).apply {
        text = "Hapus kunci mati"
        textSize = Typo.CAPTION
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.RED)
        background = pressable(rounded(Palette.FIELD, 10, Palette.RED, 1))
        setPadding(dp(12), dp(6), dp(12), dp(6))
        visibility = View.GONE
    }
    val keyCheckRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, 0)
        addView(checkKeyBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(removeDeadBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
    }
    val keyHealthBox = TextView(this).apply {
        textSize = Typo.CAPTION
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(4), dp(6), dp(4), 0)
        setLineSpacing(0f, 1.2f)
        visibility = View.GONE
    }
    var lastDeadKeys: Set<String> = emptySet()
    val keySection = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionLabel("Kunci API"))
        addView(keyField, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(detectNote)
        addView(keyCheckRow)
        addView(keyHealthBox)
    }

    // ---- alamat API (Ollama lokal / kustom) dan nama model (kustom)
    val base = EditText(this).apply {
        hint = "Alamat API (base URL)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        isSingleLine = true
    }
    styleField(base)
    val baseTrustNote = TextView(this).apply {
        textSize = Typo.CAPTION
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(4), dp(6), dp(4), 0)
        setLineSpacing(0f, 1.2f)
        text = "Pastikan host API kustom tepercaya: API key, prompt, riwayat percakapan, dan gambar terpilih akan dikirim ke host tersebut."
    }
    val baseSection = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionLabel("Alamat API"))
        addView(base, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(baseTrustNote)
    }
    val customModel = EditText(this).apply {
        hint = "Nama model"
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
    }
    styleField(customModel)
    val customModelSection = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionLabel("Model"))
        addView(customModel, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    // ---- CLI berlangganan: status login, tombol masuk, model Gemini CLI
    val loginStatus = TextView(this).apply {
        textSize = Typo.BODY_S; setTextColor(Palette.INK_SOFT); setPadding(dp(4), dp(10), 0, 0)
    }
    val loginBtn = softButton("") { }
    val geminiModelText = TextView(this).apply {
        textSize = Typo.BODY; setTextColor(Palette.INK); gravity = Gravity.CENTER_VERTICAL; maxLines = 1
    }
    val geminiModelField = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(rounded(Palette.FIELD, 14))
        setPadding(dp(14), 0, dp(14), 0)
        addView(geminiModelText, LinearLayout.LayoutParams(0, MATCH, 1f))
        addView(IconView(context, IconView.CHEVRON_DOWN, Palette.INK_SOFT), LinearLayout.LayoutParams(dp(18), dp(18)))
    }
    val geminiModelBlock = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionLabel("Model Gemini CLI"))
        addView(geminiModelField, LinearLayout.LayoutParams(MATCH, dp(48)))
    }
    val cliSection = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(geminiModelBlock)
        addView(loginStatus)
        addView(loginBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
    }

    // ---- keterangan model otomatis
    val autoNote = TextView(this).apply {
        textSize = Typo.CAPTION; setTextColor(Palette.INK_SOFT); setPadding(dp(4), dp(10), dp(4), 0)
    }

    // ---- tombol
    val cancelBtn = TextView(this).apply {
        text = "Batalkan"
        textSize = Typo.BODY
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER
        background = pressable(rounded(Palette.SURFACE, 14, Palette.BORDER, 1))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { dialog?.dismiss() }
    }
    val connectBtn = TextView(this).apply {
        text = "Hubungkan"
        textSize = Typo.BODY
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    fun updateConnect() {
        val sel = selected
        val ok = ProviderCatalog.canConnect(
            sel?.let { kindOf(it) }, sel != null, serializeApiKeys(key.text.toString()), base.text.toString(), customModel.text.toString()
        )
        connectBtn.isEnabled = ok
        connectBtn.alpha = 1f
        connectBtn.background = if (ok) pressable(rounded(Palette.BRAND, 14))
        else rounded(if (Palette.dark) 0xFF4A4845.toInt() else 0xFF8E8E8E.toInt(), 14)
    }

    fun checkLogin() {
        val c = curLogin ?: return
        loginStatus.text = "Memeriksa login…"
        bg {
            val st = cliStatus(c, useCache = false)
            runOnUiThread {
                if (curLogin !== c) return@runOnUiThread
                loginStatus.text = when {
                    st == CliStatus.OK -> "✓ Sudah masuk dengan ${c.account}"
                    st == CliStatus.UNKNOWN -> "Termux belum menjawab. Buka Termux sebentar, lalu ketuk di sini untuk memeriksa lagi."
                    st == CliStatus.NOT_READY && c.name == "Codex (ChatGPT)" -> "CLI Codex Termux yang valid belum siap. Ketuk tombol untuk memasang atau memperbaikinya."
                    st == CliStatus.NOT_READY -> "CLI belum terpasang. Ketuk tombol di bawah untuk memulai."
                    else -> "Belum masuk. Ketuk tombol di bawah untuk memulai."
                }
            }
        }
    }
    loginStatus.setOnClickListener { checkLogin() }
    loginBtn.setOnClickListener {
        curLogin?.let { c ->
            cliSetup(c) { msg -> runOnUiThread { if (curLogin === c) loginStatus.text = msg } }
        }
    }

    fun geminiTitle(v: String): String = GEMINI_CLI_MODEL_CHOICES.firstOrNull { it.value == v }?.title ?: v

    /** Ringkasan model di bawah form: model apa yang akan dipakai tanpa pengguna memilih. */
    fun noteFor(n: String): String {
        lockedModelFor(n)?.let { return "Model: $it (gratis, tetap untuk penyedia ini)." }
        val stored = pvOf(n, "model")
        if (!ProviderCatalog.shouldAutoPick(stored)) return "Model saat ini: $stored. Ganti kapan saja lewat pilihan model di kolom chat."
        val start = ProviderCatalog.startingModel(n, preset(n).model)
        return if (start.isNotBlank()) "Model rekomendasi dipilih otomatis: $start."
        else "Model terbaik dari daftar akun Anda dipilih otomatis setelah terhubung."
    }

    fun applySelection(n: String?, keepKey: Boolean) {
        selected = n
        provSlot.removeAllViews()
        if (n == null) {
            provText.text = "Pilih penyedia"
            provText.setTextColor(Palette.INK_SOFT)
        } else {
            val (letter, color) = ProviderCatalog.badge(n)
            val badge = BadgeView(this, letter, color).also { bindLogo(n, it) }
            provSlot.addView(badge, FrameLayout.LayoutParams(MATCH, MATCH))
            provText.text = ProviderCatalog.label(n)
            provText.setTextColor(Palette.INK)
        }
        val kind = n?.let { kindOf(it) }
        keySection.visibility = if (kind == ProviderKind.API || kind == ProviderKind.CUSTOM) View.VISIBLE else View.GONE
        baseSection.visibility = if (kind == ProviderKind.LOCAL || kind == ProviderKind.CUSTOM) View.VISIBLE else View.GONE
        customModelSection.visibility = if (kind == ProviderKind.CUSTOM) View.VISIBLE else View.GONE
        cliSection.visibility = if (kind == ProviderKind.CLI) View.VISIBLE else View.GONE
        autoNote.visibility = if (kind == ProviderKind.API) View.VISIBLE else View.GONE
        if (n != null) {
            loading = true
            if (!keepKey) {
                val legacy = if (n == "Gemini") readStoredKey("key") else ""
                key.setText(pvOf(n, "key").ifEmpty { legacy })
            }
            if (kind == ProviderKind.LOCAL || kind == ProviderKind.CUSTOM) {
                base.setText(pvOf(n, "base").ifEmpty { preset(n).base })
            }
            if (kind == ProviderKind.CUSTOM) customModel.setText(pvOf(n, "model"))
            loading = false
            if (kind == ProviderKind.API) autoNote.text = noteFor(n)
            curLogin = cliLoginOf(n)
            geminiModelBlock.visibility = if (n == "Gemini CLI (Google)") View.VISIBLE else View.GONE
            if (n == "Gemini CLI (Google)") {
                geminiModel = normalizeGeminiCliModel(pvOf(n, "model"))
                geminiModelText.text = geminiTitle(geminiModel)
            }
            val lg = curLogin
            loginStatus.visibility = if (lg != null) View.VISIBLE else View.GONE
            loginBtn.visibility = if (lg != null) View.VISIBLE else View.GONE
            if (lg != null) { loginBtn.text = "Masuk dengan ${lg.account}"; checkLogin() }
            else if (kind == ProviderKind.CLI) {
                loginStatus.visibility = View.VISIBLE
                loginStatus.text = "Perintah CLI dijalankan lewat Termux. Pastikan sudah terpasang dan masuk."
            }
        } else {
            curLogin = null
        }
        val loadedKeys = parseApiKeys(key.text.toString())
        val loadedCount = loadedKeys.size
        val knownDead = DeadKeyStore.countDead(this, loadedKeys)
        keyHealthBox.visibility = View.GONE
        removeDeadBtn.visibility = View.GONE
        lastDeadKeys = emptySet()
        if (keepKey && n != null) {
            // pertahankan pesan deteksi yang baru ditampilkan
        } else if (knownDead > 0 && (kind == ProviderKind.API || kind == ProviderKind.CUSTOM)) {
            detectNote.text = "⚠️ $knownDead dari $loadedCount kunci terdeteksi mati. Ketuk Periksa kunci untuk membersihkan."
            detectNote.visibility = View.VISIBLE
        } else if (loadedCount > 1 && (kind == ProviderKind.API || kind == ProviderKind.CUSTOM)) {
            detectNote.text = "$loadedCount kunci API tersimpan (failover otomatis saat kena batas)"
            detectNote.visibility = View.VISIBLE
        } else {
            detectNote.visibility = View.GONE
        }
        updateConnect()
    }

    // ---- popup pilihan penyedia
    fun hideIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(provField.windowToken, 0)
    }
    fun showProviderPopup() {
        hideIme()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        var lastGroup = -1
        for (n in names) {
            val k = kindOf(n)
            val group = when (k) { ProviderKind.API -> 0; ProviderKind.LOCAL, ProviderKind.CUSTOM -> 1; ProviderKind.CLI -> 2 }
            if (lastGroup != -1 && group != lastGroup) {
                list.addView(View(this).apply { setBackgroundColor(Palette.DIVIDER) },
                    LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(4) })
            }
            lastGroup = group
            val (letter, color) = ProviderCatalog.badge(n)
            val rowBadge = BadgeView(this, letter, color).also { bindLogo(n, it) }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pressable(if (n == selected) rounded(Palette.BUBBLE, 12) else null)
                setPadding(dp(16), 0, dp(16), 0)
                addView(rowBadge, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(14) })
                addView(TextView(context).apply {
                    text = ProviderCatalog.label(n)
                    textSize = Typo.BODY
                    setTextColor(Palette.INK)
                    maxLines = 1
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                if (n == selected) addView(IconView(context, IconView.CHECK, Palette.BRAND), LinearLayout.LayoutParams(dp(18), dp(18)))
                setOnClickListener {
                    hapticTap()
                    popup?.dismiss()
                    if (n != selected) { detectNote.visibility = View.GONE; applySelection(n, keepKey = false) }
                }
            }, LinearLayout.LayoutParams(MATCH, dp(48)))
        }
        val maxH = dp(300)
        val scroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(list) }
        val pw = PopupWindow(scroll, provField.width, WRAP, true).apply {
            setBackgroundDrawable(rounded(Palette.SURFACE, 16, Palette.BORDER, 1))
            isOutsideTouchable = true
            elevation = dp(10).toFloat()
            setOnDismissListener { chevron.animate().rotation(0f).setDuration(150).start(); popup = null }
        }
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(provField.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
        val ph = scroll.measuredHeight
        val loc = IntArray(2)
        provField.getLocationOnScreen(loc)
        val below = resources.displayMetrics.heightPixels - (loc[1] + provField.height)
        val yoff = if (below >= ph + dp(16)) dp(6) else -(provField.height + ph + dp(6))
        popup = pw
        chevron.animate().rotation(180f).setDuration(150).start()
        pw.showAsDropDown(provField, 0, yoff)
    }
    provField.setOnClickListener { if (popup?.isShowing == true) popup?.dismiss() else showProviderPopup() }

    // ---- model Gemini CLI: pilihan dari daftar atau ketik sendiri
    geminiModelField.setOnClickListener {
        val labels = (GEMINI_CLI_MODEL_CHOICES.map { it.menuLabel() } + "Ketik sendiri…\nModel lain yang tidak ada di daftar.").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih model Gemini CLI")
            .setSingleChoiceItems(labels, geminiModelChoiceIndex(geminiModel)) { dlg, which ->
                dlg.dismiss()
                if (which < GEMINI_CLI_MODEL_CHOICES.size) {
                    geminiModel = GEMINI_CLI_MODEL_CHOICES[which].value
                    geminiModelText.text = geminiTitle(geminiModel)
                } else {
                    val et = EditText(this).apply { hint = "Nama atau ID model"; setText(geminiModel); setSelection(text.length); isSingleLine = true }
                    AlertDialog.Builder(this)
                        .setTitle("Model Gemini CLI")
                        .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) })
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Pakai") { _, _ ->
                            val v = normalizeGeminiCliModel(et.text.toString())
                            if (isValidGeminiCliModel(v)) { geminiModel = v; geminiModelText.text = geminiTitle(v) }
                            else toast("Gunakan nama/ID model tanpa spasi atau karakter shell")
                        }
                        .show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---- tempel kunci: penyedia dikenali otomatis dari bentuk kunci
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (loading) return
            val raw = s?.toString().orEmpty()
            val count = parseApiKeys(raw).size
            val hit = detectProvider(raw)
            val sel = selected
            val canSwitch = sel == null || kindOf(sel) == ProviderKind.API
            if (hit != null && hit in names && hit != sel && canSwitch) {
                applySelection(hit, keepKey = true)
                detectNote.text = if (count > 1) "Terdeteksi: ${ProviderCatalog.label(hit)} · $count kunci (rotasi otomatis)"
                else "Terdeteksi: ${ProviderCatalog.label(hit)}"
                detectNote.visibility = View.VISIBLE
            } else if (count > 1) {
                detectNote.text = "$count kunci API aktif (rotasi otomatis saat kena batas)"
                detectNote.visibility = View.VISIBLE
            } else if (hit == null || hit == sel) {
                if (hit == null) detectNote.visibility = View.GONE
            }
            updateConnect()
        }
    }
    key.addTextChangedListener(watcher)
    val plain = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { if (!loading) updateConnect() }
    }
    base.addTextChangedListener(plain)
    customModel.addTextChangedListener(plain)

    // ---- Hubungkan: simpan, pilih model rekomendasi otomatis, validasi di latar
    fun connect(customHostConfirmed: Boolean = false) {
        val p = selected ?: return
        val kind = kindOf(p)
        if (!connectBtn.isEnabled) return
        if (p == "Gemini CLI (Google)" && !isValidGeminiCliModel(geminiModel)) {
            toast("Nama model Gemini CLI tidak valid"); return
        }
        if (kind == ProviderKind.CUSTOM || kind == ProviderKind.LOCAL) {
            val rawBase = base.text.toString().trim()
            val targetHost = try {
                validateBaseUrl(rawBase)
            } catch (e: Exception) {
                toast(e.message ?: "Alamat API tidak valid")
                return
            }
            if (kind == ProviderKind.CUSTOM && !customHostConfirmed) {
                val trustedHosts = prefs.getStringSet("trusted_custom_hosts", emptySet()).orEmpty()
                if (targetHost !in trustedHosts) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Konfirmasi host provider kustom")
                        .setMessage(
                            "Anda akan menghubungkan MYTHOS ke host kustom:\n\n$targetHost\n\n" +
                                "Kunci API (header Bearer), prompt, riwayat percakapan, dan gambar terpilih akan dikirim ke host ini. " +
                                "Lanjutkan hanya jika Anda memercayai operator server tersebut."
                        )
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Percayai & Hubungkan") { _, _ ->
                            val updated = HashSet(trustedHosts).apply { add(targetHost) }
                            prefs.edit().putStringSet("trusted_custom_hosts", updated).apply()
                            connect(customHostConfirmed = true)
                        }
                        .show()
                    return
                }
            }
        }
        val storedModel = pvOf(p, "model")
        val ed = prefs.edit().putString("prov", p)
        var savedKeyCount = 0
        if (kind == ProviderKind.API || kind == ProviderKind.CUSTOM) {
            val parsedKeys = parseApiKeys(key.text.toString())
            savedKeyCount = parsedKeys.size
            val enc = Secret.encrypt(parsedKeys.joinToString("\n"))
            if (enc.isSuccess) {
                ed.putString("p_${p}_key", enc.getOrThrow())
            } else {
                toast("API key tidak dapat diamankan oleh Android Keystore. Key tidak disimpan; masukkan ulang nanti.")
                return
            }
        }
        if (kind == ProviderKind.LOCAL || kind == ProviderKind.CUSTOM) ed.putString("p_${p}_base", base.text.toString().trim())
        if (kind == ProviderKind.CUSTOM) ed.putString("p_${p}_model", customModel.text.toString().trim())
        if (p == "Gemini CLI (Google)") ed.putString("p_${p}_model", normalizeGeminiCliModel(geminiModel))
        val autoPick = kind == ProviderKind.API && lockedModelFor(p) == null && ProviderCatalog.shouldAutoPick(storedModel)
        if (autoPick) ed.putString("p_${p}_model", ProviderCatalog.startingModel(p, preset(p).model))
        ed.apply()
        prov = p
        dialog?.dismiss()
        resetFailover()
        CcKeyPoolCache.clear()
        connectBtn.hapticTap()
        toast(if (savedKeyCount > 1) "Terhubung ke ${ProviderCatalog.label(p)} ($savedKeyCount kunci)" else "Terhubung ke ${ProviderCatalog.label(p)}")
        if (prefs.getBoolean("termux_sync_enabled", false)) {
            bg {
                val r = try { pushTermuxSettings() } catch (e: Exception) { "Gagal: ${e.message}" }
                if (!r.startsWith("Berhasil:")) runOnUiThread { toast("Sinkronisasi Termux gagal. Buka Pengaturan > Sinkronisasi dua arah Termux untuk memeriksa.") }
            }
        }
        if (kind == ProviderKind.CLI) return
        if (autoPick) {
            // Langkah cepat: satu panggilan daftar model akun, pilih rekomendasi penyedia, lalu validasi penuh di latar.
            bg {
                try {
                    val pick = ProviderCatalog.recommend(p, fetchModelsCached())
                    if (pick != null && prov == p) prefs.edit().putString("p_${p}_model", pick).apply()
                } catch (e: Exception) { logQuiet("ApiKeyDialog.kt", e) }
                runOnUiThread { updateModelLabel(); autoSetupModels(force = true) }
            }
        } else autoSetupModels(force = true)
    }
    var keyCheckGen = 0
    checkKeyBtn.setOnClickListener {
        val p = selected ?: return@setOnClickListener
        val keysToCheck = parseApiKeys(key.text.toString())
        if (keysToCheck.isEmpty()) { toast("Masukkan minimal 1 kunci API dulu"); return@setOnClickListener }
        val baseUrl = if (kindOf(p) == ProviderKind.CUSTOM || kindOf(p) == ProviderKind.LOCAL)
            base.text.toString().trim().ifEmpty { preset(p).base }
        else preset(p).base
        val myGen = ++keyCheckGen
        checkKeyBtn.isEnabled = false
        checkKeyBtn.text = "Memeriksa…"
        removeDeadBtn.visibility = View.GONE
        keyHealthBox.text = "Memeriksa ${keysToCheck.size} kunci API…"
        keyHealthBox.visibility = View.VISIBLE
        bg {
            val results = checkApiKeysHealth(p, baseUrl, keysToCheck)
            runOnUiThread {
                if (myGen != keyCheckGen || dialog?.isShowing != true || isFinishing || isDestroyed) return@runOnUiThread
                checkKeyBtn.isEnabled = true
                checkKeyBtn.text = "Periksa kunci"
                val dead = results.filter { it.state == KeyHealthState.DEAD }
                val deadSet = dead.map { it.key }.toSet()
                lastDeadKeys = emptySet()
                val lines = results.map { r ->
                    val mark = when (r.state) {
                        KeyHealthState.ALIVE -> "✓"
                        KeyHealthState.RATE_LIMITED -> "⏳"
                        KeyHealthState.DEAD -> "✕"
                        KeyHealthState.ERROR -> "?"
                    }
                    val suffix = if (r.state == KeyHealthState.DEAD) " — dihapus otomatis" else ""
                    "$mark #${r.index} (${r.masked}): ${r.detail}$suffix"
                }.toMutableList()
                if (deadSet.isNotEmpty()) {
                    val remaining = removeKeysFromRaw(key.text.toString(), deadSet)
                    loading = true
                    key.setText(remaining.joinToString("\n"))
                    loading = false
                    removeDeadApiKeysFromPrefs(this, p, deadSet)
                    CcKeyPoolCache.clear()
                    updateConnect()
                    lines.add("${deadSet.size} kunci mati langsung dihapus. Tersisa ${remaining.size} kunci.")
                    toast("${deadSet.size} kunci mati langsung dihapus")
                }
                keyHealthBox.text = lines.joinToString("\n")
                keyHealthBox.visibility = View.VISIBLE
                removeDeadBtn.visibility = View.GONE
            }
        }
    }
    removeDeadBtn.setOnClickListener {
        if (lastDeadKeys.isEmpty()) return@setOnClickListener
        val remaining = parseApiKeys(key.text.toString()).filter { it !in lastDeadKeys }
        val removedCount = lastDeadKeys.size
        lastDeadKeys = emptySet()
        loading = true
        key.setText(remaining.joinToString("\n"))
        loading = false
        removeDeadBtn.visibility = View.GONE
        keyHealthBox.text = "$removedCount kunci mati dihapus. Tersisa ${remaining.size} kunci."
        updateConnect()
        toast("$removedCount kunci mati dihapus")
    }
    connectBtn.setOnClickListener { connect() }

    // ---- susun kartu
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(cancelBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(connectBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
    }
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = rounded(Palette.SURFACE, 28)
        setPadding(dp(22), dp(12), dp(22), dp(22))
        addView(FrameLayout(context).apply {
            addView(closeBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(iconTile, LinearLayout.LayoutParams(dp(76), dp(76)).apply { bottomMargin = dp(18) })
        addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(desc, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(6) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionLabel("Penyedia"))
            addView(provField, LinearLayout.LayoutParams(MATCH, dp(52)))
            addView(keySection)
            addView(baseSection)
            addView(customModelSection)
            addView(cliSection)
            addView(autoNote)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(22) })
    }
    val root = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = false
        setPadding(dp(14), dp(14), dp(14), dp(14))
        clipToPadding = false
        addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    d.setContentView(root)
    d.window?.apply {
        addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.CENTER)
        setDimAmount(0.45f)
        softInputResize()
        if (Build.VERSION.SDK_INT >= 31) {
            addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            attributes = attributes.apply { blurBehindRadius = dp(18) }
        }
    }
    d.setOnDismissListener { popup?.dismiss(); onLoginRefresh = null }
    dialog = d
    onLoginRefresh = { checkLogin() }
    applySelection(selected, keepKey = false)
    d.show()
    // Masuk halus: memudar dan naik sedikit.
    card.alpha = 0f; card.scaleX = 0.96f; card.scaleY = 0.96f; card.translationY = dp(14).toFloat()
    card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(220)
        .setInterpolator(DecelerateInterpolator()).start()
}

private fun MainActivity.sectionLabel(t: String): TextView = TextView(this).apply {
    text = t
    textSize = Typo.BODY_L
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(Palette.INK)
    setPadding(dp(2), dp(18), 0, dp(8))
}
