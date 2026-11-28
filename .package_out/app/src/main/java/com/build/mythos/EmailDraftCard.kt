package com.build.mythos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Isi satu kartu draf email. Semua teks sudah jadi (tidak ada logika di kartu). */
internal data class EmailDraft(
    var sender: String,          // nama di baris atas kartu, mis. "Tujuan Example" (lihat [emailDisplayName]); ikut berubah bila penerima diedit
    var toText: String,          // baris kedua, mis. "kepada tujuan@example.com"; ikut berubah bila penerima diedit
    var subject: String,         // dapat diubah lewat ikon pensil
    var body: String,            // isi email apa adanya; dapat diubah lewat ikon pensil
    val attachmentName: String? = null,
    var recipients: String = "",  // alamat penerima (ke), dipisah koma; dapat diubah lewat ikon pensil
    val dateText: String = SimpleDateFormat("d MMM", Locale("id", "ID")).format(Date())  // dipertahankan agar kompatibel; tidak ditampilkan
)

/** "tujuan@example.com" -> "Tujuan Example"; "budi.santoso@gmail.com" -> "Budi Santoso". */
internal fun emailDisplayName(address: String): String {
    val a = address.trim()
    val local = a.substringBefore('@').replace(Regex("[._\\-+]+"), " ").trim()
    val domain = a.substringAfter('@', "").substringBefore('.').trim()
    val free = setOf("gmail", "yahoo", "outlook", "hotmail", "icloud", "proton", "protonmail", "live", "msn", "aol")
    val words = ArrayList<String>()
    if (local.isNotEmpty()) words.addAll(local.split(' ').filter { it.isNotEmpty() })
    if (domain.isNotEmpty() && domain.lowercase(Locale.ROOT) !in free) words.add(domain)
    if (words.isEmpty()) return a
    return words.joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() } }
}

/**
 * Kartu draf email bergaya Gmail (acuan: rekaman layar "Tujuan Example / Draf / kepada ...").
 *
 * Tampilannya TETAP, tidak mengikuti pengaturan perangkat:
 *  - warna ditulis langsung (tidak lewat [Palette], jadi mode gelap tidak berpengaruh) dan force-dark dimatikan;
 *  - semua ukuran dalam "satuan acuan" (acuan = lebar kartu 529 px pada rekaman 576x1280) lalu diskalakan terhadap
 *    LEBAR KARTU, bukan dp/sp, jadi ukuran font sistem, display size, dan densitas layar tidak mengubah proporsinya;
 *  - font memakai font bawaan perangkat (sama dengan rekaman), nama pengirim memakai bobot medium.
 *
 * Tombol Kirim tidak ada di kartu (sama seperti acuan); dialog persetujuan yang memasang tombol "Kirim"
 * dan memanggil [commitEdits] lebih dulu supaya hasil edit ikut terkirim.
 */
internal class EmailDraftCardView(ctx: Context, private val draft: EmailDraft) : FrameLayout(ctx) {

    private companion object {
        const val REF_W = 529f            // lebar kartu pada rekaman acuan
        const val CARD = 0xFFFCFCFC.toInt()
        const val CARD_LINE = 0xFFE6E6E6.toInt()
        const val INK = 0xFF000000.toInt()
        const val INK_SOFT = 0xFF444444.toInt()
        const val FIELD_LINE = 0xFFC4C7C5.toInt()
        const val HILITE = 0xFFE8EAED.toInt()

        // --- ukuran (satuan acuan), diukur dari rekaman
        const val CARD_RADIUS = 20f
        const val HEAD_H = 85f            // tinggi area kepala
        const val TEXT_LEFT = 101.5f      // x nama / "kepada"
        const val NAME_RIGHT = 350f       // batas kanan nama + "Draf"
        const val TO_MAX_W = 205f         // lebar maksimum teks "kepada ..." sebelum dipotong "..."
        const val SIDE = 20.5f            // margin kiri/kanan isi email
        const val LIST_TEXT_X = 70f       // awal teks butir daftar
        const val FIRST_BASE = 25f        // baseline baris pertama isi, dihitung dari bawah area kepala (=110 dari atas kartu)
        const val BODY_BOTTOM = 28f       // jarak baseline terakhir ke tepi bawah / lampiran
        const val CARD_BOTTOM = 14f
        const val TEXT_PX = 14.5f         // font isi
    }

    private class Piece(val text: String, val list: Boolean, val newBlock: Boolean, val number: String?)
    private class Row(val text: String, val x: Float, val baseline: Float, val bullet: Boolean, val number: String?)

    /** Dipertahankan agar kompatibel dengan kode lama; tombol Kirim sekarang dipasang oleh dialog. */
    var onSend: (() -> Unit)? = null

    private var unit = 0f
    private var editing = false
    private var recipientEdit: EditText? = null
    private var subjectEdit: EditText? = null
    private var bodyEdit: EditText? = null
    private val regular: Typeface = Typeface.DEFAULT
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    init { noDark() }

    private fun View.noDark() { if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        var w = MeasureSpec.getSize(widthSpec)
        if (w <= 0) w = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        val u = w / REF_W
        if (kotlin.math.abs(u - unit) > 0.0005f) { unit = u; rebuild() }
        super.onMeasure(widthSpec, heightSpec)
    }

    private fun px(v: Float) = (v * unit)
    private fun pxi(v: Float) = (v * unit).toInt()
    private fun hair() = maxOf(1, Math.round(px(1f)))

    private fun icon(id: Int): Drawable = context.getDrawable(id)!!.mutate()

    /** Gambar ikon berpusat di (cx, cy) satuan acuan dengan ukuran w x h satuan acuan. */
    private fun drawIcon(c: Canvas, d: Drawable, cx: Float, cy: Float, w: Float, h: Float) {
        d.setBounds(
            Math.round(px(cx - w / 2f)), Math.round(px(cy - h / 2f)),
            Math.round(px(cx + w / 2f)), Math.round(px(cy + h / 2f))
        )
        d.draw(c)
    }

    private fun rebuild() {
        saveEdits()   // aman: tidak berbuat apa-apa bila kolom edit belum ada
        removeAllViews()
        background = GradientDrawable().apply {
            setColor(CARD); cornerRadius = px(CARD_RADIUS); setStroke(hair(), CARD_LINE)
        }
        val full = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        col.addView(HeaderView(context), LinearLayout.LayoutParams(full, pxi(HEAD_H)))

        if (editing) {
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pxi(SIDE), 0, pxi(SIDE), pxi(18f))
            }
            recipientEdit = field(draft.recipients, 15f, regular, single = true).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                hint = "Penerima (pisahkan dengan koma)"
            }
            subjectEdit = field(draft.subject, 15.5f, medium, single = true)
            bodyEdit = field(draft.body, TEXT_PX, regular, single = false)
            box.addView(recipientEdit, LinearLayout.LayoutParams(full, wrap))
            box.addView(subjectEdit, LinearLayout.LayoutParams(full, wrap).apply { topMargin = pxi(12f) })
            box.addView(bodyEdit, LinearLayout.LayoutParams(full, wrap).apply { topMargin = pxi(12f) })
            col.addView(box, LinearLayout.LayoutParams(full, wrap))
        } else {
            recipientEdit = null; subjectEdit = null; bodyEdit = null
            col.addView(BodyView(context, draft.body), LinearLayout.LayoutParams(full, wrap))
        }

        // lampiran yang memang ikut terkirim (tidak ada di rekaman acuan, hanya muncul bila ada lampiran)
        draft.attachmentName?.takeIf { it.isNotBlank() }?.let { name ->
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pxi(14f), 0, pxi(14f), 0)
                background = GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt()); cornerRadius = px(12f); setStroke(hair(), CARD_LINE)
                }
            }
            chip.addView(ImageView(context).apply { setImageDrawable(icon(R.drawable.edc_att_file)); noDark() },
                LinearLayout.LayoutParams(pxi(22f), pxi(22f)))
            chip.addView(TextView(context).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14f))
                setTextColor(INK); typeface = regular
                includeFontPadding = false
                maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                noDark()
            }, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = pxi(10f) })
            col.addView(chip, LinearLayout.LayoutParams(full, pxi(40f)).apply {
                leftMargin = pxi(SIDE); rightMargin = pxi(SIDE); bottomMargin = pxi(14f)
            })
        }

        // tanpa lampiran: beri jarak bawah kartu (baki "Lampirkan…" sudah dihapus)
        if (draft.attachmentName.isNullOrBlank()) col.addView(View(context), LinearLayout.LayoutParams(full, pxi(CARD_BOTTOM)))

        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        contentDescription = "Draf email, ${draft.toText}"
        if (editing) bodyEdit?.post { bodyEdit?.requestFocus(); bodyEdit?.setSelection(bodyEdit?.text?.length ?: 0) }
    }

    private fun field(value: String, size: Float, face: Typeface, single: Boolean): EditText = EditText(context).apply {
        setText(value)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size))
        setTextColor(INK); typeface = face
        includeFontPadding = false
        setPadding(pxi(14f), pxi(12f), pxi(14f), pxi(12f))
        background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = px(14f); setStroke(hair(), FIELD_LINE) }
        inputType = if (single) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (!single) { minLines = 6; gravity = Gravity.TOP or Gravity.START }
        noDark()
    }

    /** Ambil isi kolom edit ke [draft]; kolom kosong diabaikan supaya email tidak terkirim tanpa subjek/isi. */
    private fun saveEdits() {
        recipientEdit?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { r ->
            draft.recipients = r
            val list = r.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (list.isNotEmpty()) {
                draft.sender = emailDisplayName(list.first())
                draft.toText = "kepada " + list.first() + if (list.size > 1) " +${list.size - 1}" else ""
            }
        }
        subjectEdit?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { draft.subject = it }
        bodyEdit?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { draft.body = it }
    }

    /** Dipanggil dialog tepat sebelum email dikirim, supaya yang terkirim adalah versi terakhir di kartu. */
    fun commitEdits() { saveEdits() }

    private fun toggleEdit() {
        if (editing) { saveEdits(); editing = false } else editing = true
        rebuild()
        requestLayout()
    }

    // ------------------------------------------------------------------ kepala

    private inner class HeaderView(ctx: Context) : View(ctx) {
        private val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; typeface = medium; textSize = px(18f) }
        private val drafP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK_SOFT; typeface = regular; textSize = px(14f) }
        private val toP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; typeface = regular; textSize = px(17f) }
        private val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HILITE }
        private val gmail = icon(R.drawable.edc_gmail)
        private val edit = icon(R.drawable.edc_edit)
        private val reply = icon(R.drawable.edc_reply)
        private val more = icon(R.drawable.edc_more_vert)
        private val chev = icon(R.drawable.edc_chevron)

        init {
            noDark()
            contentDescription = "Draf email, ${draft.toText}"
        }

        override fun onDraw(c: Canvas) {
            // logo Gmail: 52 x 39 di (20, 20)
            gmail.setBounds(pxi(20f), pxi(20f), pxi(72f), pxi(59f)); gmail.draw(c)

            // nama + "Draf" pada baseline yang sama
            val draf = "Draf"
            val gap = px(12f)
            val nameMax = px(NAME_RIGHT) - px(TEXT_LEFT) - gap - drafP.measureText(draf)
            val name = TextUtils.ellipsize(draft.sender, nameP, maxOf(nameMax, 1f), TextUtils.TruncateAt.END).toString()
            c.drawText(name, px(TEXT_LEFT), px(34f), nameP)
            c.drawText(draf, px(TEXT_LEFT) + nameP.measureText(name) + gap, px(34f), drafP)

            // "kepada ..." + panah kecil di sebelahnya
            val to = TextUtils.ellipsize(draft.toText, toP, px(TO_MAX_W), TextUtils.TruncateAt.END).toString()
            c.drawText(to, px(TEXT_LEFT), px(55f), toP)
            val chevCx = (px(TEXT_LEFT) + toP.measureText(to) + px(24f)) / unit
            drawIcon(c, chev, chevCx, 49.5f, 20f, 20f)

            // tiga ikon kanan: pensil (edit), balas, titik tiga
            if (editing) c.drawCircle(px(371f), px(38.5f), px(22f), hl)
            drawIcon(c, edit, 371f, 38.5f, 29f, 29f)
            drawIcon(c, reply, 434.5f, 39.5f, 31f, 31f)
            drawIcon(c, more, 498f, 40f, 34f, 34f)
        }

        private fun onPencil(e: MotionEvent): Boolean =
            Math.hypot((e.x - px(371f)).toDouble(), (e.y - px(38.5f)).toDouble()) <= px(26f)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> return onPencil(e)
                MotionEvent.ACTION_UP -> {
                    if (onPencil(e)) { performClick(); post { toggleEdit() } }
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }

    // ------------------------------------------------------------------ isi email

    private fun parseBody(body: String): List<Piece> {
        val out = ArrayList<Piece>()
        val mark = Regex("^\\s*(?:([-*\u2022\u2013])|(\\d{1,2}[.)]))\\s+(.*\\S)\\s*$")
        val blocks = body.replace("\r\n", "\n").replace('\r', '\n').trim().split(Regex("\n[ \t]*\n+"))
        for (blk in blocks) {
            var first = true
            for (raw in blk.split('\n')) {
                val line = raw.trimEnd()
                if (line.isBlank()) continue
                val m = mark.find(line)
                if (m != null) out.add(Piece(m.groupValues[3], true, first, m.groupValues[2].ifEmpty { null }))
                else out.add(Piece(line.trim(), false, first, null))
                first = false
            }
        }
        return out
    }

    /** Selisih baseline antar baris, dalam satuan acuan (diukur dari rekaman). */
    private fun advance(prevList: Boolean, p: Piece, firstLineOfPiece: Boolean): Float {
        if (!firstLineOfPiece) return if (p.list) 19f else 24f
        if (!p.newBlock && prevList == p.list) return if (p.list) 19f else 24f
        return when {
            !prevList && !p.list -> 35.5f     // paragraf -> paragraf
            !prevList && p.list -> 29f        // paragraf -> daftar
            prevList && !p.list -> 34f        // daftar -> paragraf
            else -> 29f                       // daftar -> daftar (blok baru)
        }
    }

    private inner class BodyView(ctx: Context, body: String) : View(ctx) {
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; typeface = regular; textSize = px(TEXT_PX) }
        private val numPaint = TextPaint(paint).apply { textAlign = Paint.Align.RIGHT }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
        private val pieces = parseBody(body)
        private var rows: List<Row> = emptyList()
        private var total = 0f

        init { noDark() }

        private fun layoutRows() {
            val out = ArrayList<Row>()
            var baseline = 0f
            var prevList = false
            for (p in pieces) {
                val tx = if (p.list) LIST_TEXT_X else SIDE
                val avail = maxOf(1, pxi(REF_W - tx - SIDE))
                val sl = StaticLayout.Builder.obtain(p.text, 0, p.text.length, paint, avail)
                    .setIncludePad(false).build()
                for (i in 0 until sl.lineCount) {
                    baseline = if (out.isEmpty()) px(FIRST_BASE) else baseline + px(advance(prevList, p, i == 0))
                    val s = sl.getLineStart(i)
                    var e = sl.getLineEnd(i)
                    while (e > s && p.text[e - 1].isWhitespace()) e--
                    out.add(Row(p.text.substring(s, e), px(tx), baseline, p.list && i == 0 && p.number == null, if (i == 0) p.number else null))
                    prevList = p.list
                }
            }
            rows = out
            total = (if (out.isEmpty()) 0f else baseline) + px(BODY_BOTTOM)
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            layoutRows()
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.ceil(total.toDouble()).toInt())
        }

        override fun onDraw(c: Canvas) {
            for (r in rows) {
                c.drawText(r.text, r.x, r.baseline, paint)
                if (r.bullet) c.drawCircle(px(52.5f), r.baseline - px(4.6f), px(2.3f), dot)
                r.number?.let { c.drawText(it, px(62f), r.baseline, numPaint) }
            }
        }
    }
}
