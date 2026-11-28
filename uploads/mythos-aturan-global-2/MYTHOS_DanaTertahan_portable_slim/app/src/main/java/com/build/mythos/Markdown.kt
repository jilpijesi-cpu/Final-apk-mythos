package com.build.mythos

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

// Render markdown dan blok kode di gelembung chat.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.fillMarkdown(box: LinearLayout, text: String, withCopy: Boolean = true, streaming: Boolean = false) {
    box.removeAllViews()
    (box.tag as? MsgMeta)?.text = text
    var last = 0
    for (m in fenceRe.findAll(text)) {
        addTextBlock(box, text.substring(last, m.range.first), streaming)
        box.addView(
            codeBlock(m.groupValues[1], m.groupValues[2].trimEnd('\n')),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10); bottomMargin = dp(6) }
        )
        last = m.range.last + 1
    }
    if (last < text.length) addTextBlock(box, text.substring(last), streaming)
    if (withCopy && text.isNotBlank()) addCopyRow(box, text)
}

internal fun MainActivity.copyText(label: String, content: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, content))
}

/** Warna lingkaran inisial sumber, stabil per nama situs. */
private val SOURCE_COLORS = intArrayOf(0xFF2E7D32.toInt(), 0xFF8D3B2E.toInt(), 0xFF1E88E5.toInt(), 0xFF6A4C93.toInt(), 0xFFB26A00.toInt(), 0xFF00796B.toInt())

/** Chip "N sumber" (lingkaran inisial situs + jumlah) di atas baris ikon; ketuk untuk daftar tautan. Hanya muncul bila agen membuka web. */
internal fun MainActivity.addSourcesChip(box: LinearLayout) {
    val srcs = SourceLog.snapshot()
    if (srcs.isEmpty()) return
    SourceLog.clear()   // dipakai satu kali, oleh balasan yang sedang ditutup
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val avatars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for ((i, s) in srcs.take(3).withIndex()) {
        val letter = (s.host.firstOrNull() ?: '?').uppercaseChar().toString()
        val tv = TextView(this).apply {
            text = letter
            gravity = Gravity.CENTER
            textSize = Typo.CAPTION
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(SOURCE_COLORS[(s.host.hashCode() and 0x7fffffff) % SOURCE_COLORS.size])
                setStroke(dp(2), Palette.BG)
            }
        }
        avatars.addView(tv, LinearLayout.LayoutParams(dp(26), dp(26)).apply { if (i > 0) marginStart = -dp(8) })
    }
    row.addView(avatars)
    row.addView(TextView(this).apply {
        text = "${srcs.size} sumber"
        textSize = Typo.BODY_S
        setTextColor(Palette.INK_SOFT)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
    row.setPadding(0, dp(8), dp(16), dp(8))
    row.contentDescription = "${srcs.size} sumber web"
    row.setOnClickListener { row.hapticConfirm(); showSourcesDialog(srcs) }
    box.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
}

internal fun MainActivity.showSourcesDialog(srcs: List<WebSource>) {
    val labels = srcs.map { it.title + "\n" + it.host }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("${srcs.size} sumber")
        .setItems(labels) { _, i ->
            val uri = android.net.Uri.parse(srcs[i].url.trim())
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                toast("Tautan tidak didukung")
                return@setItems
            }
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
            catch (e: Exception) { toast("Tidak bisa membuka tautan") }
        }
        .setNegativeButton("Tutup", null)
        .show()
}

/** Teks bersih untuk dibacakan: tanpa blok kode, tanda markdown, dan emoji. */
internal fun speakableText(md: String): String =
    md.replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("[*_`#>|]+"), "")
        .replace(Regex("[\\u2705\\u274C\\u2753\\u26A0\\u23F3\\u231B\\uFE0F\\u200D\\x{1F300}-\\x{1FAFF}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

/** Baris ikon di bawah setiap balasan (tanpa tulisan): salin, bagikan, bacakan, suka, tidak suka, ulangi. */
internal fun MainActivity.addCopyRow(box: LinearLayout, content: String) {
    addSourcesChip(box)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val glyphs = ArrayList<GlyphView>()
    fun icon(kind: Int, desc: String, onTap: (GlyphView) -> Unit): GlyphView {
        val g = GlyphView(this, kind, Palette.INK_SOFT).apply { strokeDp = 1.35f }
        glyphs.add(g)
        val cell = android.widget.FrameLayout(this).apply {
            contentDescription = desc
            background = pressable(rounded(Color.TRANSPARENT, 22))
            setOnClickListener { hapticConfirm(); onTap(g) }
            addView(g, android.widget.FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
        }
        row.addView(cell, LinearLayout.LayoutParams(dp(44), dp(44)))
        return g
    }
    fun flash(g: GlyphView) {
        g.color = Palette.ORANGE_DOT
        g.postDelayed({ g.color = Palette.INK_SOFT }, 1200)
    }
    var down: GlyphView? = null
    icon(GlyphView.COPY, getString(R.string.cd_copy_reply)) { g ->
        copyText("balasan", content); toast("Tersalin"); flash(g)
    }
    icon(GlyphView.SHARE, getString(R.string.cd_share_reply)) { shareText(content) }
    icon(GlyphView.PLAY, getString(R.string.cd_play_reply)) { g ->
        val t = tts
        if (t == null) { toast("Suara belum siap") }
        else if (t.isSpeaking) { t.stop(); g.color = Palette.INK_SOFT }
        else {
            val s = speakableText(content)
            if (s.isNotEmpty()) { t.speak(s, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "reply"); g.color = Palette.ORANGE_DOT }
        }
    }
    val up = icon(GlyphView.THUMB_UP, getString(R.string.cd_like_reply)) { g ->
        val on = g.color != Palette.ORANGE_DOT
        g.color = if (on) Palette.ORANGE_DOT else Palette.INK_SOFT
        if (on) down?.color = Palette.INK_SOFT
    }
    down = icon(GlyphView.THUMB_DOWN, getString(R.string.cd_dislike_reply)) { g ->
        val on = g.color != Palette.ORANGE_DOT
        g.color = if (on) Palette.ORANGE_DOT else Palette.INK_SOFT
        if (on) up.color = Palette.INK_SOFT
    }
    icon(GlyphView.RETRY, getString(R.string.cd_retry_reply)) {
        if (!busy && preset(prov).fmt != "cli" && isLastAi(box)) regenerate()
        else toast("Hanya jawaban terakhir yang bisa diulang")
    }
    box.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2); marginStart = -dp(10) })
}

/**
 * Gaya teks jawaban AI, ala aplikasi Claude: huruf serif, ukuran 16,5 sp, jarak baris lega, warna tinta hangat.
 * Dipakai oleh teks yang sedang mengalir DAN hasil akhirnya supaya tidak ada lompatan huruf/ukuran saat selesai.
 */
internal const val ASSISTANT_TEXT_SP = 15.5f
internal const val ASSISTANT_LINE_SPACING = 1.40f

internal fun styleAssistantText(tv: TextView) {
    tv.textSize = ASSISTANT_TEXT_SP
    tv.typeface = applyAppFontWeight(Typeface.create("serif", Typeface.NORMAL))
    tv.setLineSpacing(0f, ASSISTANT_LINE_SPACING)
    tv.letterSpacing = 0.004f
    tv.setTextColor(Palette.INK)
}

internal fun MainActivity.addTextBlock(box: LinearLayout, seg: String, streaming: Boolean = false) {
    val t = seg.trim('\n')
    if (t.isBlank()) return
    for (blk in splitMdBlocks(t)) {
        when (blk) {
            is MdBlock.Table -> box.addView(
                tableView(blk.table) { showMsgMenu(box) },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(8) }
            )
            is MdBlock.Text -> {
                val part = blk.text.trim('\n')
                if (part.isBlank()) continue
                val tv = (if (streaming) ShimmerText(this) else TextView(this)).apply {
                    text = mdToSpannable(part)
                    setOnLongClickListener { showMsgMenu(box); true }
                    styleAssistantText(this)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    if (this is ShimmerText) setShimmer(false)
                }
                box.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
    }
}

/** Tabel Markdown: kolom melebar dan teks membungkus supaya muat di layar ponsel; baris judul tebal. */
internal fun MainActivity.tableView(t: MdTable, onLongPress: () -> Unit): View {
    val cols = t.header.size
    val tl = android.widget.TableLayout(this).apply {
        isStretchAllColumns = true
        isShrinkAllColumns = true
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Palette.DIVIDER)
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(1), dp(1), dp(1), dp(1))
    }
    val ctx = this
    fun addRow(cells: List<String>, header: Boolean) {
        val row = android.widget.TableRow(ctx)
        if (header) row.setBackgroundColor(Palette.CHIP)
        for (i in 0 until cols) {
            val sp = inline(cells.getOrElse(i) { "" })
            if (header && sp.isNotEmpty()) sp.setSpan(StyleSpan(Typeface.BOLD), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val cell = TextView(ctx).apply {
                text = sp
                textSize = Typo.BODY_S
                setTextColor(Palette.INK)
                setLineSpacing(0f, 1.2f)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                if (sp.getSpans(0, sp.length, android.text.style.URLSpan::class.java).isNotEmpty()) {
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
                setOnLongClickListener { onLongPress(); true }
            }
            row.addView(cell, android.widget.TableRow.LayoutParams(0, WRAP, 1f))
        }
        tl.addView(row, android.widget.TableLayout.LayoutParams(MATCH, WRAP))
    }
    addRow(t.header, true)
    for (r in t.rows) {
        tl.addView(View(ctx).apply { setBackgroundColor(Palette.DIVIDER) }, android.widget.TableLayout.LayoutParams(MATCH, dp(1)))
        addRow(r, false)
    }
    return tl
}

/** Markdown ringan: judul (#), daftar (-, *), **tebal**, *miring*, `kode inline`, dan [tautan](tujuan). */
private val listOrQuoteRe = Regex("""^\s*(\d+[.)]\s|>|[-*+]\s)""")

internal fun MainActivity.mdToSpannable(srcRaw: String): SpannableStringBuilder {
    val src = stripIcons(srcRaw)
    val out = SpannableStringBuilder()
    val lines = src.split("\n")
    var prevBlank = false
    // Rentang baris pembuka paragraf yang perlu menjorok. Span dipasang SETELAH semua teks selesai ditambahkan (lihat bawah).
    val indentRanges = ArrayList<IntRange>()
    for ((i, raw) in lines.withIndex()) {
        val start = out.length
        val startsParagraph = prevBlank && i > 0
        prevBlank = raw.isBlank()
        val h = headRe.find(raw)
        val b = bulletRe.find(raw)
        if (h != null) {
            out.append(inline(h.groupValues[2]))
            val size = when (h.groupValues[1].length) { 1 -> 1.22f; 2 -> 1.14f; else -> 1.06f }
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (b != null) {
            out.append("    ".repeat(b.groupValues[1].length / 2))
            val bs = out.length
            out.append("•  ")
            out.setSpan(ForegroundColorSpan(Palette.INK_SOFT), bs, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(inline(b.groupValues[2]))
        } else {
            out.append(inline(raw))
            // Paragraf baru (setelah baris kosong): baris pertamanya menjorok sedikit ke dalam. Daftar bernomor dan kutipan tidak.
            if (startsParagraph && raw.isNotBlank() && out.length > start && !listOrQuoteRe.containsMatchIn(raw)) {
                indentRanges.add(start until out.length)
            }
        }
        if (i < lines.size - 1) out.append("\n")
    }
    // Span paragraf TIDAK boleh dipasang di tengah proses menambah teks: SPAN_PARAGRAPH yang berakhir di ujung teks ikut
    // memanjang menelan teks yang ditambahkan sesudahnya. Akibatnya tiap paragraf baru menumpuk satu jorokan lagi dan
    // tulisan tampak seperti tangga (makin ke bawah makin menjorok). Dipasang di sini, setelah teks final, jorokan
    // hanya berlaku pada paragrafnya sendiri.
    for (r in indentRanges) {
        val end = if (r.last + 1 < out.length) r.last + 2 else out.length // sertakan "\n" agar berakhir di batas paragraf
        out.setSpan(LeadingMarginSpan.Standard(dp(20), 0), r.first, end, Spanned.SPAN_PARAGRAPH)
    }
    return out
}

internal fun MainActivity.inline(src: String): SpannableStringBuilder {
    val out = SpannableStringBuilder()
    var last = 0
    for (m in inlineRe.findAll(src)) {
        out.append(src, last, m.range.first)
        val st = out.length
        if (m.groups[1] != null) {
            out.append(m.groupValues[1])
            if (m.groupValues[1].length <= InlineCodeSpan.MAX_CHARS) {
                // Kotak bulat, teks biru (InlineCodeSpan). Hanya untuk kode pendek: span ini tidak bisa terpotong antarbaris.
                out.setSpan(InlineCodeSpan(resources.displayMetrics.density), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                // Cadangan untuk kode panjang agar tetap bisa turun ke baris berikutnya.
                out.setSpan(TypefaceSpan("monospace"), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(Palette.FIELD), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(Palette.LINK_BLUE), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.9f), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else if (m.groups[2] != null) {
            out.append(inline(m.groupValues[2]))
            out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (m.groups[4] != null) {
            // Tautan [teks](tujuan): tampilkan hanya teksnya. Hanya http(s) yang bisa diketuk;
            // path berkas lokal (mis. /data/data/com.termux/.../storage.py:23) cukup jadi teks bergaris bawah.
            out.append(inline(m.groupValues[4]))
            val url = m.groupValues[5]
            if (url.startsWith("http://") || url.startsWith("https://")) {
                out.setSpan(android.text.style.URLSpan(url), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.setSpan(android.text.style.UnderlineSpan(), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            out.append(m.groupValues[3])
            out.setSpan(StyleSpan(Typeface.ITALIC), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        last = m.range.last + 1
    }
    out.append(src, last, src.length)
    return out
}

/** Pewarnaan sintaks sederhana (komentar, string, angka, kata kunci, fungsi, tipe). Tanpa pustaka. */
internal fun MainActivity.highlight(lang: String, code: String): CharSequence {
    if (code.length > 8000) return code
    val comment = if (lang.trim().lowercase() in hashLangs) "#[^\\n]*"
    else "//[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|\\z)|<!--[\\s\\S]*?(?:-->|\\z)"
    val re = Regex("""($comment)|("(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?)|(\b\d+(?:\.\d+)?\b)|([@A-Za-z_][A-Za-z0-9_]*)""")
    val out = SpannableStringBuilder(code)
    fun paint(color: String, a: Int, b: Int, italic: Boolean = false) {
        out.setSpan(ForegroundColorSpan(Color.parseColor(color)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (italic) out.setSpan(StyleSpan(Typeface.ITALIC), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    for (m in re.findAll(code)) {
        val a = m.range.first
        val b = m.range.last + 1
        when {
            m.groups[1] != null -> paint("#7C8AA5", a, b, true)   // komentar
            m.groups[2] != null -> paint("#86EFAC", a, b)         // string
            m.groups[3] != null -> paint("#FDBA74", a, b)         // angka
            else -> {
                val w = m.value
                when {
                    w in kwSet -> paint("#C4B5FD", a, b)                                  // kata kunci
                    b < code.length && code[b] == '(' -> paint("#7DD3FC", a, b)           // fungsi
                    w[0].isUpperCase() || w[0] == '@' -> paint("#FCD34D", a, b)           // tipe / anotasi
                }
            }
        }
    }
    return out
}

/** Kotak kode bertema gelap: label bahasa berwarna, tombol Salin, teks monospace yang bisa digeser ke samping. */
internal fun MainActivity.codeBlock(lang: String, code: String): View {
    val label = TextView(this).apply {
        text = if (lang.isBlank()) "kode" else lang.trim().lowercase()
        textSize = Typo.CAPTION
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Live.INDIGO_SOFT)
    }
    val copy = TextView(this).apply {
        text = "Salin"
        contentDescription = getString(R.string.cd_copy_code)
        textSize = Typo.CAPTION
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Live.FG)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = GradientDrawable().apply {
            setColor(Live.LINE)
            cornerRadius = dp(10).toFloat()
        }
        extendTouchVertical(6)   // area ketuk ~40dp, gambar chip tetap
        setOnClickListener {
            hapticConfirm()
            val cm = this@codeBlock.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("kode", code))
            text = "Tersalin ✓"
            contentDescription = getString(R.string.cd_code_copied)
            postDelayed({ text = "Salin"; contentDescription = getString(R.string.cd_copy_code) }, 1500)
        }
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(8), dp(2))
        addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(copy)
    }
    val codeTv = TextView(this).apply {
        text = highlight(lang, code)
        typeface = Typeface.MONOSPACE
        textSize = Typo.SMALL
        setTextColor(Live.FG)
        setTextIsSelectable(true)
        setPadding(dp(14), dp(6), dp(14), dp(14))
    }
    val hs = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(codeTv)
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Palette.CODE_BG)
            cornerRadius = dp(14).toFloat()
        }
        addView(header)
        addView(hs)
    }
}
