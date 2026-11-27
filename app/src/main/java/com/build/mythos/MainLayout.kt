package com.build.mythos

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Pembangun tampilan utama dan inisialisasi awal, dipecah dari MainActivity.onCreate (isi tidak berubah).
// Urutan pemanggilan di onCreate dipertahankan: bilah atas, area tengah, baris status, kolom input.

/** Baca pengaturan, jalankan migrasi sekali jalan, dan pulihkan pilihan penyedia / ringkasan konteks. */
internal fun MainActivity.initPrefsAndMigrations() {
    prefs = getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
    StreamTempo.current = StreamTempo.byKey(prefs.getString(StreamTempo.PREF_KEY, null))
    if (!prefs.getBoolean("mythos_workspace_migrated", false)) {
        val old = prefs.getString("workdir", "")?.trim().orEmpty()
        if (old.isEmpty() || old == "kita") prefs.edit().putString("workdir", "MYTHOS").apply()
        prefs.edit().putBoolean("mythos_workspace_migrated", true).apply()
    }
    startLegacyWorkspaceMigration()
    startWorkspaceHousekeeping()
    bg { try { sweepOrphanCcProjects() } catch (e: Exception) { logQuiet("MainLayout.kt", e) } } // dunia agen API: murni penyimpanan aplikasi
    // Versi lama pernah mengaktifkan mode otonom secara default; minta opt-in eksplisit setelah upgrade.
    if (prefs.getBoolean("autonomous", false) && !prefs.getBoolean("autonomous_ack", false)) {
        prefs.edit().putBoolean("autonomous", false).apply()
    }
    prov = initialProvider(prefs.getString("prov", null), !prefs.getString("key", "").isNullOrEmpty())
    migrateKeys()
    val rawSum = prefs.getString("sum_text", "").orEmpty()
    summaryText = if (rawSum.isBlank()) {
        ""
    } else {
        when (val dec = Secret.decrypt(rawSum)) {
            is SecretDecryptResult.Success -> {
                if (!isEncryptedText(rawSum)) {
                    Secret.encrypt(dec.value).onSuccess { enc -> prefs.edit().putString("sum_text", enc).apply() }
                }
                dec.value
            }
            else -> ""
        }
    }
    sumUpto = prefs.getInt("sum_upto", 0)
    bg {
        try { CommandLog.migratePlaintextIfNeeded(this) } catch (e: Exception) { logQuiet("MainLayout.kt", e) }
        try { migratePlaintextChats() } catch (e: Exception) { logQuiet("MainLayout.kt", e) }
        try { cleanupStaleDownloadAttachments() } catch (e: Exception) { logQuiet("MainLayout.kt", e) }
    }
}

/** Bilah atas: hanya menampilkan judul "Chat samaran" di tengah saat mode samaran aktif (tombol hantu pindah ke kotak input). */
internal fun MainActivity.buildTopBar(): LinearLayout {
    topTitle = TextView(this).apply {
        text = "Chat samaran"
        textSize = Typo.BODY_L
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER
        maxLines = 1
        visibility = View.GONE
    }
    val topBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        elevation = 0f
        setPadding(dp(16), dp(8), dp(16), dp(8))
        addView(topTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
    return topBar
}

/** Area tengah: daftar chat, sapaan kosong, dan tombol gulir ke bawah. Mengisi chatBox, scroll, emptyState, dst. */
internal fun MainActivity.buildCenter(): FrameLayout {
    // Area tengah: daftar chat + sapaan kosong
    chatBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(24))
    }
    scroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(18))
        addView(chatBox)
    }
    // Ruang kosong di bawah isi chat (hampir setinggi layar) agar pesan pengguna bisa dinaikkan ke atas dan layar tetap diam
    // selama jawaban bertambah, seperti rekaman acuan. Ruangnya mengikuti tinggi layar chat.
    scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
        val want = (bottom - top - dp(96)).coerceAtLeast(dp(24))
        if (chatBox.paddingBottom != want) {
            chatBox.setPadding(chatBox.paddingLeft, chatBox.paddingTop, chatBox.paddingRight, want)
        }
    }
    emptyNormal = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        // Kunci logo Mythos: tulisan "Mythos" (gambar dari logo asli, diwarnai mengikuti tema) di atas ikon mata.
        addView(android.widget.ImageView(context).apply {
            setImageResource(R.drawable.logo_wordmark)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setColorFilter(Palette.INK, android.graphics.PorterDuff.Mode.SRC_IN)
            contentDescription = "Mythos"
        }, LinearLayout.LayoutParams(dp(88), WRAP).apply { bottomMargin = dp(4) })
        addView(BurstView(context), LinearLayout.LayoutParams(dp(76), dp(46)))
        addView(TextView(context).apply {
            val nama = (prefs.getString("nama_tampil", NAMA_TAMPIL_BAWAAN) ?: NAMA_TAMPIL_BAWAAN).trim().ifBlank { NAMA_TAMPIL_BAWAAN }
            text = sapaanKosong(nama)
            textSize = Typo.HERO
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
            letterSpacing = -0.03f
            setTextColor(Palette.INK)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(20) })
        // Aksi cepat: mengisi kolom input dengan kalimat awal (tidak langsung terkirim) supaya bisa dilengkapi dulu.
        val quick = listOf(
            "Buat aplikasi baru" to "Buatkan aplikasi Android: ",
            "Audit proyek" to "Audit proyek saya dan cari bug serta celah keamanan: ",
            "Lanjutkan proyek terakhir" to "Lanjutkan proyek terakhir yang saya kerjakan."
        )
        for ((label, prompt) in quick) {
            addView(TextView(context).apply {
                text = label
                textSize = Typo.BODY
                gravity = Gravity.CENTER
                setTextColor(Palette.INK)
                maxLines = 1
                background = pressable(rounded(Palette.CHIP, 20, Palette.FIELD_BORDER, 1))
                setPadding(dp(18), dp(10), dp(18), dp(10))
                minimumHeight = dp(44)
                contentDescription = label
                setOnClickListener {
                    hapticTap()
                    input.setText(prompt)
                    input.setSelection(input.text.length)
                    input.requestFocus()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })
        }
    }
    emptyIncognito = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        addView(GlyphView(context, GlyphView.GHOST_SOLID, Palette.INK), LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(TextView(context).apply {
            val lead = "Chat samaran tidak masuk ke riwayat dan tidak disimpan di perangkat ini. "
            val link = "Pelajari selengkapnya"
            val sp = android.text.SpannableString(lead + link)
            sp.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) { showIncognitoInfo() }
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = Palette.INK
                    ds.isUnderlineText = true
                }
            }, lead.length, lead.length + link.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sp
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            textSize = Typo.BODY_L
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setTextColor(Palette.INK)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
    }
    val emptyContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(emptyNormal, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(emptyIncognito, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    emptyState = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(emptyContent, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
    }
    // Panah digambar IconView, bukan glyph "↓": sebagian font perangkat tidak punya glyph itu (tampil sebagai tanda petik).
    scrollDownBtn = FrameLayout(this).apply {
        contentDescription = getString(R.string.cd_scroll_down)
        background = pressable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((Palette.CARD and 0x00FFFFFF) or (0xD8 shl 24))
            setStroke(1, Palette.BORDER)
        })
        elevation = dp(1).toFloat()
        visibility = View.GONE
        addView(IconView(context, IconView.CHEVRON_DOWN, Palette.INK_SOFT), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        setOnClickListener { hapticTap(); forceScrollDown() }
    }
    scroll.setOnScrollChangeListener { _, _, y, _, _ ->
        val content = scroll.getChildAt(0)
        val atBottom = content == null || contentEnd() - (y + scroll.height) <= dp(36)
        // Gerakan pengikut otomatis tertinggal sedikit di belakang isi; itu bukan pengguna yang menggulir ke atas.
        if (!autoScrolling) {
            userScrolledUp = !atBottom
            scrollDownBtn.visibility = if (atBottom) View.GONE else View.VISIBLE
        }
    }
    val center = FrameLayout(this).apply {
        addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(emptyState, FrameLayout.LayoutParams(MATCH, MATCH))
        // UI-03: sejajarkan kanan-bawah sesuai margin di applyResponsive() dan perluas hitbox menjadi 48x48dp.
        addView(scrollDownBtn, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(18), dp(14))
        })
    }
    center.touchPads().add(scrollDownBtn, 5, 5, 5, 5)
    return center
}

/** Tombol baris ikon: kotak bulat putih bergaris tipis, ikon garis tipis di tengah. */
private fun MainActivity.iconButton(desc: String, glyph: Int, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
    contentDescription = desc
    background = pressable(rounded(Palette.CARD, 12, Palette.FIELD_BORDER, 1))
    addView(GlyphView(context, glyph, Palette.ICON).apply { strokeDp = 1.7f }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    setOnClickListener { hapticTap(); onClick() }
}

/**
 * Kolom input: teks di atas, satu baris ikon di bawah.
 * Kiri: tambah lampiran | Chat samaran | menu (model, riwayat, proyek, pengaturan, dst). Kanan: mikrofon | Kirim.
 * Mengisi input, modelLabel, deepBtn, actionBtn, dst.
 */
internal fun MainActivity.buildInputPill(): LinearLayout {
    val plusBtn = iconButton(getString(R.string.cd_attach), GlyphView.PLUS_CIRCLE) { showAddSheet() }

    // Logo penyedia AI aktif (menggantikan ikon mata Chat samaran; Chat samaran tetap lewat tombol hantu di bilah atas).
    // Ketuk untuk berganti AI: dialog penyedia berisi daftar penyedia dan kunci.
    providerBadge = BadgeView(this, "?", Palette.ICON)
    val ghostBtn = FrameLayout(this).apply {
        contentDescription = "Ganti penyedia AI"
        background = pressable(rounded(Palette.CARD, 12, Palette.FIELD_BORDER, 1))
        addView(providerBadge, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        setOnClickListener { hapticTap(); showApiKeyDialog() }
    }
    refreshProviderBadge()

    input = EditText(this).apply {
        hint = "Start typing a prompt"
        setHintTextColor(Palette.INK_SOFT)
        setTextColor(Palette.INK)
        textSize = Typo.BODY_L
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        background = null
        maxLines = 5
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(6), dp(8), dp(6), dp(8))
    }

    // Label model tidak lagi tampil di baris ikon; teksnya dibaca lembar menu (MenuSheet.kt) dan tetap diperbarui updateModelLabel().
    modelLabel = TextView(this).apply {
        textSize = Typo.BODY_S
        setTextColor(Palette.INK)
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    updateModelLabel()

    // Titik oranye di sudut tombol menu: tanda Penalaran mendalam sedang aktif (diatur setDeep).
    deepBtn = View(this).apply {
        contentDescription = getString(R.string.cd_deep_off)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.BRAND); setStroke(dp(2), Palette.CARD) }
        visibility = if (deepOn()) View.VISIBLE else View.GONE
    }
    val appsBtn = iconButton("Menu: model, riwayat, proyek, pengaturan", GlyphView.APPS) { showMenuSheet() }
    appsBtn.addView(deepBtn, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })

    // Tombol Chat samaran (ikon hantu) dipindah dari bilah atas ke barisan kotak ikon di form input agar rapi.
    ghostIcon = IconView(this, IconView.GHOST, Palette.INK)
    ghostCircleBg = rounded(Palette.CARD, 12, Palette.FIELD_BORDER, 1)
    val incognitoBtn = FrameLayout(this).apply {
        contentDescription = "Chat samaran"
        background = pressable(ghostCircleBg)
        addView(ghostIcon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        setOnClickListener { hapticTap(); setIncognito(!incognito) }
    }

    actionIcon = GlyphView(this, GlyphView.SEND_LINE, Palette.INK_SOFT).apply { strokeDp = 1.7f }
    actionLabel = TextView(this).apply {
        text = "Kirim"
        textSize = Typo.BODY
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(Palette.INK_SOFT)
        maxLines = 1
        isSingleLine = true
    }
    actionBg = rounded(Palette.CARD, 12, Palette.FIELD_BORDER, 1)
    actionBtn = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        contentDescription = getString(R.string.cd_send)
        background = pressable(actionBg)
        setPadding(dp(10), 0, dp(12), 0)
        addView(actionIcon, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(actionLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(5) })
        setOnClickListener { onAction() }
    }
    var convertingLongText = false
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (convertingLongText) return
            val raw = s?.toString().orEmpty()
            if (!busy && LongTextAttachment.shouldConvert(raw, leakOsintMode())) {
                if (attachLongTextAsFile(raw)) {
                    convertingLongText = true
                    s?.clear()
                    convertingLongText = false
                    updateAction()
                    return
                }
            }
            updateLeakCommandHighlight(s)
            updateAction()
        }
    })
    // Baris ikon disederhanakan: kiri tambah (+) dan menu; kanan satu tombol dinamis (mikrofon saat kosong, Kirim saat ada isi,
    // Henti saat agen bekerja). Penyedia AI dan Chat samaran dipindah ke lembar Menu. Dengan hanya 3 tombol, lebar hitbox
    // dapat 48dp (42dp + 3dp kiri/kanan) dan tinggi 48dp (40dp + 4dp atas/bawah) tanpa membuat baris sempit di layar 360dp.
    val btnW = dp(42)
    val btnH = dp(40)
    val gap = dp(6)
    val bottomRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(plusBtn, LinearLayout.LayoutParams(btnW, btnH))
        addView(appsBtn, LinearLayout.LayoutParams(btnW, btnH).apply { marginStart = gap })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))   // ruang kosong: tombol aksi menempel ke kanan
        addView(actionBtn, LinearLayout.LayoutParams(WRAP, btnH).apply { marginStart = dp(6) })
    }
    val pill = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.NAV, 18, Palette.FIELD_BORDER, 1)
        elevation = dp(4).toFloat()
        if (Build.VERSION.SDK_INT >= 28) {
            outlineAmbientShadowColor = 0x22000000
            outlineSpotShadowColor = 0x22000000
        }
        setPadding(dp(6), dp(8), dp(6), dp(7))
        val leakChip = TextView(context).apply {
            text = "● LeakOSINT Aktif"
            textSize = Typo.CAPTION
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setTextColor(Palette.DIFF_ADD_FG)
            background = rounded(Palette.DIFF_ADD_BG, 999)
            setPadding(dp(9), dp(2), dp(9), dp(2))
            visibility = View.GONE
        }
        leakInputIndicator = leakChip
        addView(leakChip, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            marginStart = dp(4)
            bottomMargin = dp(4)
        })
        addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(bottomRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
    }
    // Jarak antar tombol 6dp -> perluasan 3dp kiri/kanan + 4dp atas/bawah (tanpa tumpang tindih; hitbox 48dp).
    pill.touchPads().apply {
        for (b in listOf<View>(plusBtn, appsBtn, actionBtn)) add(b, 3, 4, 3, 4)
    }
    return pill
}
