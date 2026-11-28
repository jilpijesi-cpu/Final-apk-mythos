package com.build.mythos

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Integrasi Android/UI untuk modul MYTHOS LeakOSINT Search Engine.
 *
 * Fitur:
 * - Penyimpanan token aman lewat Android Keystore ([Secret.decrypt] / [Secret.encrypt]), tanpa hardcode.
 * - Mode pencarian langsung di chat MYTHOS (ketik nama/kata kunci atau batch `NAMA 02` per baris)
 *   serta perintah cepat (`/leak`, `/osint`, `/leakosint`) dan alat agen `cari_leakosint`.
 * - Tampilan chat biasa yang mengalir rapi sesuai urutan nama input pengguna:
 *   - Setiap nama tampil berurutan dengan stiker masing-masing kolom.
 *   - Tombol `📋 Salin` di setiap data agar bisa disalin satu per satu, plus `Salin Semua` & `Mentah`/`Filter`.
 *   - Animasi muncul bertahap (efek mengetik halus + fade-in lembut per blok, ketuk untuk langsung selesai).
 */

internal const val PREF_LEAKOSINT_TOKEN = "leakosint_token"
internal const val PREF_LEAKOSINT_LIMIT = "leakosint_limit"
internal const val PREF_LEAKOSINT_LANG = "leakosint_lang"
internal const val PREF_LEAKOSINT_BOT_NAME = "leakosint_bot_name"
internal const val PREF_LEAKOSINT_MODE = "leakosint_mode"

internal fun MainActivity.leakOsintMode(): Boolean = prefs.getBoolean(PREF_LEAKOSINT_MODE, false)

internal fun MainActivity.setLeakOsintMode(enabled: Boolean) {
    prefs.edit().putBoolean(PREF_LEAKOSINT_MODE, enabled).apply()
    refreshLeakOsintLook()
    toast(
        if (enabled) "Mode MYTHOS LeakOSINT aktif — ketik kueri atau 'NAMA 02' per baris"
        else "Mode MYTHOS LeakOSINT dimatikan — chat AI normal"
    )
}

internal fun MainActivity.refreshLeakOsintLook() {
    val active = leakOsintMode()
    inputOrNull()?.let { inp ->
        inp.hint = if (active) {
            "Ketik kueri atau 'NAMA 02' per baris (LeakOSINT)…"
        } else {
            "Start typing a prompt"
        }
    }
    if (!incognito) {
        topTitleOrNull()?.let { title ->
            if (active) {
                title.text = "MYTHOS LeakOSINT"
                title.visibility = View.VISIBLE
            } else {
                title.text = "Chat samaran"
                title.visibility = View.GONE
            }
        }
    }
    if (inputOrNull() != null) updateAction()
}

private class LeakCmdColorSpan(color: Int) : ForegroundColorSpan(color)
private class LeakCmdBoldSpan : StyleSpan(Typeface.BOLD)

/** Warnai token `/leak`, `/osint`, atau `/leakosint` di kolom ketik saat aktif; bersihkan bila tidak aktif. */
internal fun MainActivity.updateLeakCommandHighlight(s: Editable?) {
    if (s == null) return
    s.getSpans(0, s.length, LeakCmdColorSpan::class.java).forEach { s.removeSpan(it) }
    s.getSpans(0, s.length, LeakCmdBoldSpan::class.java).forEach { s.removeSpan(it) }
    if (attachments.isNotEmpty()) return
    val range = LeakOsintEngine.commandPrefixRange(s.toString()) ?: return
    val start = range.first.coerceIn(0, s.length)
    val end = (range.last + 1).coerceIn(start, s.length)
    if (end > start) {
        s.setSpan(LeakCmdColorSpan(Palette.DIFF_ADD_FG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(LeakCmdBoldSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

internal fun MainActivity.leakOsintToken(): String =
    when (val r = Secret.decrypt(prefs.getString(PREF_LEAKOSINT_TOKEN, "") ?: "")) {
        is SecretDecryptResult.Success -> r.value.trim()
        else -> ""
    }

internal fun MainActivity.leakOsintConfig(): LeakOsintConfig {
    val token = leakOsintToken()
    val limit = LeakOsintEngine.clampLimit(prefs.getInt(PREF_LEAKOSINT_LIMIT, LeakOsintEngine.DEFAULT_LIMIT))
    val lang = prefs.getString(PREF_LEAKOSINT_LANG, LeakOsintEngine.DEFAULT_LANG)?.trim().orEmpty()
        .ifBlank { LeakOsintEngine.DEFAULT_LANG }
    val botName = prefs.getString(PREF_LEAKOSINT_BOT_NAME, "")?.trim().orEmpty()
    return LeakOsintConfig(
        token = token,
        limit = limit,
        lang = lang,
        type = LeakOsintEngine.DEFAULT_TYPE,
        botName = botName
    )
}

/**
 * Jalankan pencarian LeakOSINT langsung dari input chat MYTHOS.
 * Mendukung kueri tunggal, banyak baris, dan filter otomatis Indonesia + akhiran HP (`JEPRI 02`).
 */
internal fun MainActivity.runLeakOsintChatSearch(rawQuery: String, addUserBubble: Boolean = true) {
    if (busy) return
    val query = rawQuery.trim()
    if (query.isEmpty()) {
        toast("Masukkan nama atau kata kunci pencarian")
        return
    }
    val config = leakOsintConfig()
    if (config.token.isEmpty()) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("LeakOSINT belum terhubung")
            .setMessage(
                "Hubungkan dulu di Pengaturan > Konektor > LeakOSINT. " +
                    "Token dienkripsi memakai Android Keystore."
            )
            .setNegativeButton("Nanti", null)
            .setPositiveButton("Buka Konektor") { _, _ -> showConnectorHub() }
            .show()
        return
    }
    if (!NetState.online) {
        toast("Perangkat sedang offline. Sambungkan internet untuk mencari di LeakOSINT.")
        return
    }

    busy = true
    cancelled = false
    input.setText("")
    hideKeyboard()
    updateAction()

    if (addUserBubble) {
        addMsg(USER, query)
        synchronized(hist) { hist.add(Hist(USER, query)) }
        if (!incognito) saveHist()
    }

    zone.beginRun()
    setStatus(KIND_DOTS, "Mencari data…")

    worker = bgCancellable(
        onError = { err ->
            if (cancelled) {
                runOnUiThread { if (!isFinishing && !isDestroyed) addNote("Pencarian LeakOSINT dibatalkan.") }
            } else {
                val safeMsg = LeakOsintEngine.maskToken(err.message ?: "Terjadi kesalahan jaringan.", config.token)
                runOnUiThread { if (!isFinishing && !isDestroyed) addNote("Gagal mencari di LeakOSINT: $safeMsg") }
            }
        },
        onFinally = {
            activeConn = null
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                zone.reset()
                setStatus(KIND_HIDE, "")
                updateAction()
            }
        }
    ) {
        val outcome = LeakOsintEngine.search(
            query = query,
            config = config,
            onConnectionOpen = { conn -> activeConn = conn }
        )
        if (cancelled) return@bgCancellable
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            when (outcome) {
                is LeakOsintOutcome.Error -> {
                    val errText = "**MYTHOS LeakOSINT Search Engine**\n\n${outcome.message}"
                    addMsg(AI, errText, pin = false)
                    synchronized(hist) { hist.add(Hist(AI, errText)) }
                    if (!incognito) saveHist()
                }
                is LeakOsintOutcome.Success -> {
                    val report = outcome.report
                    addLeakOsintReportMsg(report)
                    val storedMd = LeakOsintEngine.formatAllPagesMarkdown(report)
                    synchronized(hist) { hist.add(Hist(AI, storedMd)) }
                    if (!incognito) saveHist()
                }
            }
        }
    }
}

/**
 * Render hasil pencarian LeakOSINT sebagai chat biasa yang mengalir rapi sesuai urutan nama input,
 * lengkap dengan stiker per kolom, tombol salin satuan di tiap data, dan animasi mengetik elegan.
 */
internal fun MainActivity.addLeakOsintReportMsg(initialReport: LeakOsintReport, animate: Boolean = true) = runOnUiThread {
    emptyState.visibility = View.GONE
    val box = newAiBox()

    val filteredReport = initialReport
    val rawReport = initialReport.unfilteredReport
    var showingRaw = false
    var activeReport = filteredReport
    var rawPageIdx = 0

    val revealHandler = Handler(Looper.getMainLooper())
    val revealInterpolator = DecelerateInterpolator(1.6f)
    var revealCancelled = false
    var revealFinishNow: (() -> Unit)? = null

    val headerText = TextView(this).apply {
        textSize = Typo.CAPTION
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(2), 0, dp(2), dp(8))
    }

    val contentBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    val controlsDivider = View(this).apply {
        setBackgroundColor(Palette.DIVIDER)
    }

    val controlsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(2))
    }

    fun stopReveal() {
        revealCancelled = true
        revealHandler.removeCallbacksAndMessages(null)
        revealFinishNow = null
    }

    box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            stopReveal()
        }
    })

    fun navButton(label: String, desc: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        contentDescription = desc
        textSize = Typo.SMALL
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER
        background = pressable(rounded(Palette.CARD, 10, Palette.FIELD_BORDER, 1))
        setPadding(dp(11), dp(6), dp(11), dp(6))
        setOnClickListener { this.hapticTap(); onClick() }
    }

    fun copyToClipboard(label: String, content: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, content))
        toast("$label disalin")
    }

    fun buildStickerRecordSpannable(rec: LeakOsintStructuredRecord): SpannableStringBuilder {
        val rows = listOf(
            "📂 " to ("Sumber" to rec.sourceName),
            "👤 " to ("Nama" to rec.nama),
            "📞 " to ("Nomor" to rec.nomor),
            "📧 " to ("Email" to rec.email),
            "📍 " to ("Alamat" to rec.alamat),
            "🪪 " to ("No NIK/Paspor" to rec.nikPaspor),
            "⚧ " to ("Jenis kelamin" to rec.jenisKelamin)
        )
        val sb = SpannableStringBuilder()
        rows.forEachIndexed { idx, (icon, pair) ->
            if (idx > 0) sb.append("\n")
            sb.append(icon)
            val labelStart = sb.length
            sb.append(pair.first)
            sb.setSpan(StyleSpan(Typeface.BOLD), labelStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(": ").append(pair.second)
        }
        return sb
    }

    fun animateViewFadeSlide(view: View, durationMs: Long = 220L) {
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = dp(6).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(durationMs)
            .setInterpolator(revealInterpolator)
            .start()
    }

    data class RevealStep(
        val container: View,
        val typingTarget: TextView? = null,
        val fullText: CharSequence? = null
    )

    fun renderFadedSlice(
        full: CharSequence,
        shownLen: Int,
        appearAt: LongArray,
        nowMs: Long
    ): CharSequence {
        val spanned = SpannableStringBuilder(full.subSequence(0, shownLen))
        val fadeMs = StreamSmoother.FADE_MS
        val reach = StreamTempo.current.fadeReach
        val inkRgb = Palette.INK and 0x00FFFFFF
        var d = 0
        while (d < shownLen && d < reach) {
            val pos = shownLen - 1 - d
            val t0 = appearAt[pos]
            val age = if (t0 > 0L) (nowMs - t0).coerceAtLeast(0L) else fadeMs
            if (age >= fadeMs) break
            var spanStart = pos
            val spanEnd = pos + 1
            if (Character.isLowSurrogate(spanned[pos]) && pos > 0 && Character.isHighSurrogate(spanned[pos - 1])) {
                spanStart = pos - 1
                d++
            }
            if (!spanned[spanStart].isWhitespace()) {
                val alpha = StreamSmoother.fadeAlpha(age)
                spanned.setSpan(
                    ForegroundColorSpan(inkRgb or (alpha shl 24)),
                    spanStart,
                    spanEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            d++
        }
        return spanned
    }

    fun runElegantReveal(steps: List<RevealStep>, animate: Boolean, onDone: () -> Unit) {
        stopReveal()
        if (!animate || steps.isEmpty()) {
            for (s in steps) {
                s.container.visibility = View.VISIBLE
                s.container.alpha = 1f
                s.container.translationY = 0f
                if (s.typingTarget != null && s.fullText != null) {
                    s.typingTarget.text = s.fullText
                    s.typingTarget.setTextIsSelectable(true)
                }
            }
            onDone()
            return
        }

        revealCancelled = false
        for (s in steps) {
            s.container.visibility = View.GONE
            if (s.typingTarget != null) {
                s.typingTarget.text = ""
            }
        }

        val pacer = StreamSmoother.Pacer()
        var lastTickMs = SystemClock.uptimeMillis()

        revealFinishNow = {
            if (!revealCancelled) {
                revealCancelled = true
                revealHandler.removeCallbacksAndMessages(null)
                revealFinishNow = null
                for (s in steps) {
                    s.container.animate().cancel()
                    s.container.visibility = View.VISIBLE
                    s.container.alpha = 1f
                    s.container.translationY = 0f
                    if (s.typingTarget != null && s.fullText != null) {
                        s.typingTarget.text = s.fullText
                        s.typingTarget.setTextIsSelectable(true)
                    }
                }
                onDone()
            }
        }

        var stepIdx = 0
        var charPos = 0
        var currentStr = ""
        var appearAt = LongArray(0)

        fun prepareStep(idx: Int) {
            charPos = 0
            pacer.reset()
            lastTickMs = SystemClock.uptimeMillis()
            val full = steps.getOrNull(idx)?.fullText
            currentStr = full?.toString().orEmpty()
            appearAt = LongArray(currentStr.length)
        }

        prepareStep(0)

        fun tick() {
            if (revealCancelled) return
            if (stepIdx >= steps.size) {
                revealFinishNow = null
                onDone()
                return
            }
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastTickMs).coerceIn(1L, 100L)
            lastTickMs = now

            val current = steps[stepIdx]
            if (current.container.visibility != View.VISIBLE) {
                animateViewFadeSlide(current.container, durationMs = 210L)
                scrollDown()
            }
            val tv = current.typingTarget
            val full = current.fullText
            if (tv == null || full == null || full.isEmpty()) {
                stepIdx++
                prepareStep(stepIdx)
                revealHandler.postDelayed({ tick() }, 60L)
                return
            }

            if (charPos < currentStr.length) {
                val nextEnd = StreamSmoother.nextShown(pacer, currentStr, charPos, dt)
                if (nextEnd > charPos) {
                    for (i in charPos until nextEnd) {
                        appearAt[i] = now
                    }
                    charPos = nextEnd
                }
            }

            val lastCharAge = if (charPos > 0 && appearAt[charPos - 1] > 0L) now - appearAt[charPos - 1] else StreamSmoother.FADE_MS
            val fadingDone = charPos >= currentStr.length && lastCharAge >= StreamSmoother.FADE_MS

            if (fadingDone) {
                tv.text = full
                tv.setTextIsSelectable(true)
                stepIdx++
                prepareStep(stepIdx)
                revealHandler.postDelayed({ tick() }, 45L)
            } else {
                tv.text = renderFadedSlice(full, charPos, appearAt, now)
                revealHandler.postDelayed({ tick() }, StreamSmoother.FRAME_MS)
            }
        }

        revealHandler.post { tick() }
    }

    fun makeRecordRowView(
        rec: LeakOsintStructuredRecord,
        targetLabel: String?
    ): Pair<LinearLayout, TextView> {
        val entryBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(8))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        val tagText = "📌 Data #${if (rec.indexInTarget > 0) rec.indexInTarget else 1}"
        val tagView = TextView(this).apply {
            text = tagText
            textSize = Typo.CAPTION
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Palette.BRAND)
        }

        val copyOneBtn = TextView(this).apply {
            text = "📋 Salin"
            contentDescription = "Salin data ini"
            textSize = Typo.CAPTION
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.INK)
            gravity = Gravity.CENTER
            background = pressable(rounded(Palette.FIELD, 8, Palette.FIELD_BORDER, 1))
            setPadding(dp(9), dp(3), dp(9), dp(3))
            setOnClickListener {
                this.hapticTap()
                val label = if (!targetLabel.isNullOrBlank()) "Data #${rec.indexInTarget} ($targetLabel)" else "Data #${rec.indexInTarget}"
                copyToClipboard(label, LeakOsintEngine.formatStructuredRecordCopyText(rec, targetLabel))
            }
        }

        topRow.addView(tagView, LinearLayout.LayoutParams(0, WRAP, 1f))
        topRow.addView(copyOneBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        entryBox.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val bodyView = TextView(this).apply {
            textSize = Typo.SMALL
            setTextColor(Palette.INK)
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(false)
        }
        entryBox.addView(bodyView, LinearLayout.LayoutParams(MATCH, WRAP))
        return entryBox to bodyView
    }

    fun makeDivider(strong: Boolean = false): View = View(this).apply {
        setBackgroundColor(if (strong) Palette.FIELD_BORDER else Palette.DIVIDER)
    }

    lateinit var rebuildControls: () -> Unit
    lateinit var renderCurrentView: (Boolean) -> Unit

    fun showRawPagePicker() {
        val labels = activeReport.pages.map { p ->
            val part = if (p.totalSubPages > 1) " (Bagian ${p.subPage}/${p.totalSubPages})" else ""
            val recInfo = if (p.totalRecordsInSource > 0) " · ${p.recordStart}–${p.recordEnd}/${p.totalRecordsInSource}" else ""
            "${p.pageIndex + 1}. ${p.sourceName}$part$recInfo"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih Sumber / Halaman (${activeReport.pages.size})")
            .setSingleChoiceItems(labels, rawPageIdx) { dlg, which ->
                rawPageIdx = which
                renderCurrentView(false)
                dlg.dismiss()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    rebuildControls = {
        controlsRow.removeAllViews()
        val isOrderedStream = !showingRaw && activeReport.targetGroups.isNotEmpty()

        if (!isOrderedStream && activeReport.pages.size > 1) {
            val prevBtn = navButton("<<", "Halaman sebelumnya") {
                rawPageIdx = LeakOsintEngine.wrapPageIndex(rawPageIdx - 1, activeReport.pages.size)
                renderCurrentView(false)
            }
            val pageIndicator = TextView(this).apply {
                text = "${rawPageIdx + 1}/${activeReport.pages.size}"
                textSize = Typo.SMALL
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Palette.INK)
                gravity = Gravity.CENTER
                background = pressable(rounded(Palette.FIELD, 10, Palette.FIELD_BORDER, 1))
                setPadding(dp(9), dp(6), dp(9), dp(6))
                setOnClickListener { v ->
                    v.hapticTap()
                    showRawPagePicker()
                }
            }
            val nextBtn = navButton(">>", "Halaman berikutnya") {
                rawPageIdx = LeakOsintEngine.wrapPageIndex(rawPageIdx + 1, activeReport.pages.size)
                renderCurrentView(false)
            }
            controlsRow.addView(prevBtn, LinearLayout.LayoutParams(WRAP, WRAP))
            controlsRow.addView(pageIndicator, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) })
            controlsRow.addView(nextBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) })
        }

        if (rawReport != null) {
            val toggleRawBtn = navButton(
                if (showingRaw) "Filter" else "Mentah",
                if (showingRaw) "Kembali ke hasil tersaring urut per nama" else "Lihat seluruh data mentah tanpa memanggil API ulang"
            ) {
                showingRaw = !showingRaw
                activeReport = if (showingRaw) rawReport else filteredReport
                rawPageIdx = 0
                renderCurrentView(false)
            }
            controlsRow.addView(
                toggleRawBtn,
                LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    if (!isOrderedStream && activeReport.pages.size > 1) marginStart = dp(6)
                    marginEnd = dp(6)
                }
            )
        }

        val copyAllBtn = navButton("Salin Semua", "Salin seluruh hasil") {
            copyToClipboard("Semua hasil LeakOSINT", LeakOsintEngine.formatAllPagesMarkdown(activeReport))
        }
        controlsRow.addView(copyAllBtn, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    renderCurrentView = { animate: Boolean ->
        stopReveal()
        contentBox.removeAllViews()
        val steps = ArrayList<RevealStep>()

        val isOrderedStream = !showingRaw && activeReport.targetGroups.isNotEmpty()
        if (isOrderedStream) {
            headerText.text = LeakOsintEngine.formatSummaryLine(activeReport)
            val groups = activeReport.targetGroups
            val anyHit = groups.any { it.records.isNotEmpty() }

            groups.forEachIndexed { gIdx, group ->
                if (gIdx > 0) {
                    val sep = makeDivider(strong = true)
                    contentBox.addView(
                        sep,
                        LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                            topMargin = dp(6)
                            bottomMargin = dp(8)
                        }
                    )
                    steps.add(RevealStep(sep))
                }

                if (group.records.isEmpty()) {
                    val zeroRow = TextView(this).apply {
                        text = "❌ ${group.orderIndex}. ${group.label} — Tidak ditemukan (0 cocok)"
                        textSize = Typo.SMALL
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(Palette.INK_SOFT)
                        setPadding(dp(2), dp(4), dp(2), dp(4))
                    }
                    contentBox.addView(zeroRow, LinearLayout.LayoutParams(MATCH, WRAP))
                    steps.add(RevealStep(zeroRow))
                } else {
                    val headingRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(2), dp(2), dp(2), dp(6))
                    }
                    val titleTv = TextView(this).apply {
                        text = "🔎 ${group.orderIndex}. ${group.label}"
                        textSize = Typo.BODY
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(Palette.INK)
                    }
                    val badgeTv = TextView(this).apply {
                        text = "${group.records.size} cocok"
                        textSize = Typo.CAPTION
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(Palette.DIFF_ADD_FG)
                        background = rounded(Palette.DIFF_ADD_BG, 999)
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                    }
                    headingRow.addView(titleTv, LinearLayout.LayoutParams(WRAP, WRAP))
                    headingRow.addView(badgeTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                    contentBox.addView(headingRow, LinearLayout.LayoutParams(MATCH, WRAP))
                    steps.add(RevealStep(headingRow))

                    group.records.forEachIndexed { rIdx, rec ->
                        if (rIdx > 0) {
                            val sep = makeDivider(strong = false)
                            contentBox.addView(
                                sep,
                                LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                                    topMargin = dp(2)
                                    bottomMargin = dp(4)
                                }
                            )
                            steps.add(RevealStep(sep))
                        }
                        val (entryView, bodyView) = makeRecordRowView(rec, group.label)
                        contentBox.addView(entryView, LinearLayout.LayoutParams(MATCH, WRAP))
                        steps.add(RevealStep(entryView, bodyView, buildStickerRecordSpannable(rec)))
                    }
                }
            }

            if (!anyHit && activeReport.pages.firstOrNull()?.infoLeak?.isNotBlank() == true) {
                val hintView = TextView(this).apply {
                    text = activeReport.pages.first().infoLeak
                    textSize = Typo.CAPTION
                    setTypeface(Typeface.SANS_SERIF, Typeface.ITALIC)
                    setTextColor(Palette.INK_SOFT)
                    setPadding(dp(2), dp(8), dp(2), dp(2))
                }
                contentBox.addView(hintView, LinearLayout.LayoutParams(MATCH, WRAP))
                steps.add(RevealStep(hintView))
            }
            (box.tag as? MsgMeta)?.text = LeakOsintEngine.formatAllPagesMarkdown(activeReport)
        } else {
            rawPageIdx = LeakOsintEngine.wrapPageIndex(rawPageIdx, activeReport.pages.size)
            val page = activeReport.pages[rawPageIdx]
            val modePrefix = if (showingRaw) "[Data Mentah] " else ""
            headerText.text = modePrefix + LeakOsintEngine.formatSummaryLine(activeReport, page)

            if (page.structuredRecords.isNotEmpty()) {
                val srcHeading = TextView(this).apply {
                    text = "🔎 ${page.sourceName} (${page.totalRecordsInSource} data)"
                    textSize = Typo.BODY
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(Palette.INK)
                    setPadding(dp(2), dp(2), dp(2), dp(6))
                }
                contentBox.addView(srcHeading, LinearLayout.LayoutParams(MATCH, WRAP))
                steps.add(RevealStep(srcHeading))

                page.structuredRecords.forEachIndexed { rIdx, rec ->
                    if (rIdx > 0) {
                        val sep = makeDivider(strong = false)
                        contentBox.addView(
                            sep,
                            LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                                topMargin = dp(2)
                                bottomMargin = dp(4)
                            }
                        )
                        steps.add(RevealStep(sep))
                    }
                    val (entryView, bodyView) = makeRecordRowView(rec, page.sourceName)
                    contentBox.addView(entryView, LinearLayout.LayoutParams(MATCH, WRAP))
                    steps.add(RevealStep(entryView, bodyView, buildStickerRecordSpannable(rec)))
                }
            } else {
                fillMarkdown(contentBox, page.markdownText, withCopy = false)
            }
            (box.tag as? MsgMeta)?.text = page.markdownText
        }

        rebuildControls()
        val typingCount = steps.count { it.typingTarget != null }
        val shouldAnimate = animate && typingCount <= 15
        controlsDivider.visibility = if (shouldAnimate && steps.isNotEmpty()) View.GONE else View.VISIBLE
        controlsRow.visibility = if (shouldAnimate && steps.isNotEmpty()) View.GONE else View.VISIBLE

        runElegantReveal(steps, shouldAnimate) {
            if (controlsRow.visibility != View.VISIBLE) {
                animateViewFadeSlide(controlsDivider, durationMs = 180L)
                animateViewFadeSlide(controlsRow, durationMs = 220L)
                scrollDown()
            }
            decorateAgentBubble(box, active = false)
        }
    }

    // Ketuk area pesan saat animasi berjalan untuk langsung menuntaskan seluruh teks
    contentBox.setOnClickListener {
        revealFinishNow?.invoke()
    }

    box.addView(headerText, LinearLayout.LayoutParams(MATCH, WRAP))
    box.addView(contentBox, LinearLayout.LayoutParams(MATCH, WRAP))
    box.addView(controlsDivider, LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(6) })
    box.addView(controlsRow, LinearLayout.LayoutParams(MATCH, WRAP))

    decorateAgentBubble(box, active = animate)
    renderCurrentView(animate)
    if (animate) scrollDown()
}

// ------------------------------------------------------------------ Deklarasi & eksekusi alat agen MYTHOS

