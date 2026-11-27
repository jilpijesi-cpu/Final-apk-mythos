package com.build.mythos

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

// Palet warna, ikon orbit, dan terminal live yang terbuka saat baris status (LiveStatusLine.kt) diketuk.
// Dipisah dari LiveStatusLine.kt agar setiap berkas tetap ringkas (< 800 baris).

internal object Live {
    // Baris status
    val ORANGE = Palette.ORANGE_DOT      // aksen hangat (titik "berpikir" dan ikon kerja)
    val TEXT_BASE: Int get() = if (Palette.dark) 0xFF7E7C77.toInt() else 0xFFA7A59F.toInt()   // teks abu saat berkilau
    val TEXT_HI: Int get() = if (Palette.dark) 0xFFECEAE5.toInt() else 0xFF141413.toInt()     // sorot kilau / teks akhir
    val CHEVRON = 0xFF8D8C86.toInt()
    /** Warna dasar teks status saat gelombang menyapu: lebih redup dari TEXT_BASE agar kontras dengan sorot lebih dalam. */
    val SHIMMER_LOW: Int get() = if (Palette.dark) 0xFF6A6863.toInt() else 0xFFB4B2AC.toInt()

    // Terminal
    val BG = Palette.CODE_BG
    val BAR = 0xFF162033.toInt()
    val LINE = 0xFF1E293B.toInt()
    val FG = 0xFFE2E8F0.toInt()
    val DIM = 0xFF64748B.toInt()
    val DIM_HI = 0xFF94A3B8.toInt()
    val GREEN = 0xFF4ADE80.toInt()
    val RED = 0xFFF87171.toInt()
    val AMBER = 0xFFFBBF24.toInt()
    val CYAN = 0xFF67E8F9.toInt()
    val VIOLET = 0xFFC4B5FD.toInt()
    val INDIGO_SOFT = 0xFFA5B4FC.toInt()   // label bahasa pada blok kode
    val ORANGE_SOFT = 0xFFFDBA74.toInt()
    val ROSE = 0xFFFDA4AF.toInt()
    val ADD_BG = 0x2216A34A
    val DEL_BG = 0x22EF4444

    fun colorOf(k: TermKind): Int = when (k) {
        TermKind.PLAIN -> FG
        TermKind.DIM -> DIM
        TermKind.CMD -> CYAN
        TermKind.FLAG -> ORANGE_SOFT
        TermKind.STR -> GREEN
        TermKind.OP -> VIOLET
        TermKind.VAR -> AMBER
        TermKind.KEYWORD -> VIOLET
        TermKind.NUM -> ORANGE_SOFT
        TermKind.PATH -> VIOLET
        TermKind.TAG -> CYAN
        TermKind.OK -> GREEN
        TermKind.ERR -> RED
        TermKind.WARN -> AMBER
        TermKind.TASK -> DIM_HI
        TermKind.ADD -> GREEN
        TermKind.DEL -> ROSE
        TermKind.PROMPT -> GREEN
    }
}

private const val TERM_SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

// ------------------------------------------------------------------ Ikon "berpikir": tiga titik mengorbit

/** Tiga titik hangat yang berputar sambil "bernapas" (mengembang-mengerut). Berhenti sendiri saat lepas dari layar. */
internal class OrbitDotsView(ctx: Context, private val dotColor: Int = Live.ORANGE) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var anim: ValueAnimator? = null

    private fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        p.color = dotColor
        val cx = width / 2f
        val cy = height / 2f
        val s = minOf(width, height).toFloat()
        val twoPi = (2.0 * Math.PI).toFloat()
        val breathe = 0.5f - 0.5f * cos(phase * twoPi * 2f) // 0..1, dua kali per putaran
        val orbit = s * (0.09f + 0.21f * breathe)
        for (i in 0..2) {
            val a = phase * twoPi + i * twoPi / 3f
            val r = s * (0.065f + 0.05f * (0.5f + 0.5f * sin(phase * twoPi * 3f + i * 2.1f)))
            c.drawCircle(cx + orbit * cos(a), cy + orbit * sin(a), r, p)
        }
    }
}

/** Kotak kursor yang berkedip. */
private class BlinkBlock(ctx: Context, color: Int) : View(ctx) {
    private var anim: ObjectAnimator? = null

    init {
        setBackgroundColor(color)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim?.cancel()
        anim = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.08f).apply {
            duration = 520
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        alpha = 1f
        super.onDetachedFromWindow()
    }
}

/** ScrollView dengan tinggi maksimum; di dalam ScrollView lain ia merebut gestur geser selama masih bisa digulir. */
private class CappedScroll(ctx: Context) : ScrollView(ctx) {
    var maxHeightPx: Int = 0
        set(v) {
            field = v
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && (canScrollVertically(1) || canScrollVertically(-1))) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onInterceptTouchEvent(ev)
    }
}

// ------------------------------------------------------------------ Terminal live

internal class LiveTerminal(private val act: MainActivity) : LinearLayout(act) {
    var onApprove: (() -> Unit)? = null
    var onReject: (() -> Unit)? = null
    var onOpenFull: ((AgentStep) -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())
    private var steps: List<AgentStep> = emptyList()
    private var pinnedId: String? = null
    private var shownId = ""
    private var shownLines: List<String> = emptyList()
    private var chipSig = ""
    private var big = false
    private var renderedLines = 0
    private var lastH = 0
    private val queue = ArrayList<TermLine>()

    private fun dotView(color: Int): View = View(act).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }
    private val dotRed = dotView(Live.RED)
    private val dotAmber = dotView(Live.AMBER)
    private val dotGreen = dotView(Live.GREEN)
    private val dots = arrayOf(dotRed, dotAmber, dotGreen)
    private var dotAnim: ValueAnimator? = null

    private fun startDotBlink() {
        if (dotAnim != null) return
        dotAnim = ValueAnimator.ofFloat(0f, 3f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val phase = anim.animatedValue as Float
                for (i in dots.indices) {
                    var dist = kotlin.math.abs(phase - i)
                    if (dist > 1.5f) dist = 3f - dist
                    val glow = (1f - dist).coerceIn(0f, 1f)
                    val d = dots[i]
                    d.alpha = 0.25f + 0.75f * glow
                    val s = 0.90f + 0.22f * glow
                    d.scaleX = s
                    d.scaleY = s
                }
            }
            start()
        }
    }

    private fun stopDotBlink() {
        dotAnim?.cancel()
        dotAnim = null
        for (d in dots) {
            d.alpha = 1f
            d.scaleX = 1f
            d.scaleY = 1f
        }
    }

    private fun pill(label: String, fg: Int, bg: Int, onClick: () -> Unit): TextView = TextView(act).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(fg)
        gravity = Gravity.CENTER
        background = pressable(act.rounded(bg, 10))
        setPadding(act.dp(9), act.dp(3), act.dp(9), act.dp(3))
        extendTouchVertical(7)   // area ketuk ~40dp, gambar pil tetap
        setOnClickListener { onClick() }
    }

    private val titleTv = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
        setTextColor(Live.DIM_HI)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val copyTv: TextView = pill("Salin", Live.FG, Live.LINE) { copyCurrent() }
    private val sizeTv: TextView = pill("Besar", Live.FG, Live.LINE) { toggleBig() }

    private val topBar = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(10), act.dp(7), act.dp(8), act.dp(7))
        background = GradientDrawable().apply {
            setColor(Live.BAR)
            val r = act.dp(14).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        addView(dotRed, LinearLayout.LayoutParams(act.dp(7), act.dp(7)).apply { marginEnd = act.dp(5) })
        addView(dotAmber, LinearLayout.LayoutParams(act.dp(7), act.dp(7)).apply { marginEnd = act.dp(5) })
        addView(dotGreen, LinearLayout.LayoutParams(act.dp(7), act.dp(7)).apply { marginEnd = act.dp(8) })
        addView(titleTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(sizeTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(8) })
        addView(copyTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(6) })
    }

    private val chipsRow = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(10), act.dp(5), act.dp(10), act.dp(3))
    }
    private val chipsScroll = HorizontalScrollView(act).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        visibility = View.GONE
        addView(chipsRow, FrameLayout.LayoutParams(WRAP, WRAP))
    }

    private val codeTv = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
        setTextColor(Live.FG)
        setLineSpacing(0f, 1.25f)
    }
    private val cursorRow = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, act.dp(2), 0, 0)
        addView(TextView(act).apply {
            text = "❯"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
            setTextColor(Live.GREEN)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(BlinkBlock(act, Live.GREEN), LinearLayout.LayoutParams(act.dp(6), act.dp(12)).apply { marginStart = act.dp(5) })
    }
    private val bodyBox = LinearLayout(act).apply {
        orientation = VERTICAL
        setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
        addView(codeTv, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(cursorRow, LinearLayout.LayoutParams(WRAP, WRAP))
    }
    private val scroll = CappedScroll(act).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        maxHeightPx = act.dp(250)
        addView(bodyBox, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private val stateTv = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
        setTextColor(Live.DIM_HI)
        setSingleLine(true)
    }
    private val rejectTv: TextView = pill("Tolak", Live.FG, Live.LINE) { hapticReject(); onReject?.invoke() }
    private val approveTv: TextView = pill("Setuju", Live.BG, Live.GREEN) { hapticConfirm(); onApprove?.invoke() }
    private val footer = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(12), act.dp(6), act.dp(8), act.dp(7))
        addView(stateTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(rejectTv, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(approveTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(6) })
    }

    private val drip = object : Runnable {
        override fun run() {
            if (queue.isEmpty()) return
            val follow = nearBottom()
            val n = minOf(queue.size, maxOf(1, (queue.size + 5) / 6))
            for (i in 0 until n) appendLine(queue.removeAt(0))
            trimOld()
            if (follow) scrollToBottom()
            notifyGrowth()
            if (queue.isNotEmpty()) ui.postDelayed(this, 45)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val st = current()
            if (st != null) updateState(st)
            ui.postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        background = act.rounded(Live.BG, 14, Live.LINE, 1)
        addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(chipsScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(View(act).apply { setBackgroundColor(Live.LINE) }, LinearLayout.LayoutParams(MATCH, act.dp(1)))
        addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
        titleTv.setOnLongClickListener {
            current()?.let { s -> onOpenFull?.invoke(s) }
            true
        }
        showIdle()
    }

    // ---------------------------------------------------------- API

    /** Terima daftar langkah terbaru. Murah bila terminal sedang tertutup. */
    fun render(groups: List<AgentStepGroup>) {
        steps = groups.flatMap { it.steps }.sortedBy { it.startedAt ?: Long.MAX_VALUE }
        if (visibility == View.VISIBLE) refresh(animate = true)
    }

    fun onOpened() {
        shownId = ""
        refresh(animate = false)
        ui.removeCallbacks(ticker)
        ui.postDelayed(ticker, 1000)
        startDotBlink()
    }

    fun onClosed() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(drip)
        stopDotBlink()
        queue.clear()
        shownId = ""
    }

    fun reset() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(drip)
        queue.clear()
        steps = emptyList()
        pinnedId = null
        shownId = ""
        shownLines = emptyList()
        chipSig = ""
        big = false
        sizeTv.text = "Besar"
        scroll.maxHeightPx = act.dp(250)
        chipsRow.removeAllViews()
        chipsScroll.visibility = View.GONE
        showIdle()
    }

    // ---------------------------------------------------------- Isi

    private fun current(): AgentStep? {
        val id = pinnedId
        if (id != null) {
            val s = steps.firstOrNull { it.id == id }
            if (s != null) return s
        }
        return steps.lastOrNull()
    }

    private fun refresh(animate: Boolean) {
        val st = current()
        updateChips(st)
        if (st == null) {
            showIdle()
            return
        }
        titleTv.text = st.title
        updateState(st)
        val lines = termLinesFor(st)
        val plain = lines.map { it.text }
        val add = if (animate && st.id == shownId) newLinesAfter(shownLines, plain) else null
        if (add != null) {
            if (add.isNotEmpty()) {
                queue.addAll(lines.subList(lines.size - add.size, lines.size))
                ui.removeCallbacks(drip)
                ui.post(drip)
            }
        } else {
            replaceAll(lines)
        }
        shownId = st.id
        shownLines = plain
    }

    private fun showIdle() {
        titleTv.text = "terminal"
        stateTv.text = ""
        rejectTv.visibility = View.GONE
        approveTv.visibility = View.GONE
        cursorRow.visibility = View.VISIBLE
        shownId = ""
        shownLines = emptyList()
        replaceAll(listOf(TermLine("Menunggu perintah dari AI…", emptyList(), TermKind.DIM)))
    }

    private fun updateState(st: AgentStep) {
        val now = System.currentTimeMillis()
        val running = st.status == AgentStepStatus.RUNNING
        val waiting = st.status == AgentStepStatus.WAITING
        cursorRow.visibility = if (running || waiting) View.VISIBLE else View.GONE
        val needApproval = st.isApprovalWaiting
        rejectTv.visibility = if (needApproval) View.VISIBLE else View.GONE
        approveTv.visibility = if (needApproval) View.VISIBLE else View.GONE
        when (st.status) {
            AgentStepStatus.RUNNING -> {
                stateTv.text = "● berjalan  " + formatElapsed(now - (st.startedAt ?: now))
                stateTv.setTextColor(Live.ORANGE)
            }
            AgentStepStatus.WAITING -> {
                stateTv.text = "○ menunggu izin"
                stateTv.setTextColor(Live.AMBER)
            }
            AgentStepStatus.SUCCESS -> {
                stateTv.text = "✓ selesai  " + formatElapsed(st.durationMs)
                stateTv.setTextColor(Live.GREEN)
            }
            AgentStepStatus.FAILED -> {
                stateTv.text = "✕ gagal  " + formatElapsed(st.durationMs)
                stateTv.setTextColor(Live.RED)
            }
        }
    }

    private fun updateChips(sel: AgentStep?) {
        val last = steps.takeLast(24)
        chipsScroll.visibility = if (steps.size >= 2) View.VISIBLE else View.GONE
        val sig = last.joinToString("|") { it.id + it.status.ordinal } + "#" + (sel?.id ?: "") + "#" + (pinnedId ?: "")
        if (sig == chipSig) return
        chipSig = sig
        val keepX = chipsScroll.scrollX
        chipsRow.removeAllViews()
        val base = steps.size - last.size
        for ((i, s) in last.withIndex()) {
            val on = s.id == sel?.id
            val glyph: String
            val gc: Int
            when (s.status) {
                AgentStepStatus.WAITING -> { glyph = "○"; gc = Live.AMBER }
                AgentStepStatus.RUNNING -> { glyph = "●"; gc = Live.ORANGE }
                AgentStepStatus.SUCCESS -> { glyph = "✓"; gc = Live.GREEN }
                AgentStepStatus.FAILED -> { glyph = "✕"; gc = Live.RED }
            }
            val label = SpannableStringBuilder(glyph + " " + chipLabel(base + i, s.title, 18))
            label.setSpan(ForegroundColorSpan(gc), 0, glyph.length, TERM_SPAN_FLAGS)
            val chip = TextView(act).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
                setSingleLine(true)
                setTextColor(if (on) Live.FG else Live.DIM_HI)
                background = pressable(act.rounded(if (on) Live.LINE else Color.TRANSPARENT, 10))
                setPadding(act.dp(8), act.dp(3), act.dp(8), act.dp(3))
                extendTouchVertical(7)   // area ketuk ~40dp, gambar chip tetap
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    pinnedId = if (s.id == steps.lastOrNull()?.id) null else s.id
                    refresh(animate = false)
                }
            }
            chipsRow.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = act.dp(4) })
        }
        if (pinnedId == null) chipsScroll.post { chipsScroll.fullScroll(View.FOCUS_RIGHT) }
        else chipsScroll.post { chipsScroll.scrollTo(keepX, 0) }
    }

    // ---------------------------------------------------------- Menggambar baris

    private fun styled(line: TermLine): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        sb.append(line.text).append('\n')
        val end = line.text.length
        if (end > 0) {
            sb.setSpan(ForegroundColorSpan(Live.colorOf(line.whole)), 0, end, TERM_SPAN_FLAGS)
            for (s in line.spans) {
                if (s.start >= 0 && s.end <= end && s.start < s.end) {
                    sb.setSpan(ForegroundColorSpan(Live.colorOf(s.kind)), s.start, s.end, TERM_SPAN_FLAGS)
                }
            }
        }
        when (line.whole) {
            TermKind.ADD -> sb.setSpan(DiffBg(Live.ADD_BG), 0, sb.length, TERM_SPAN_FLAGS)
            TermKind.DEL -> sb.setSpan(DiffBg(Live.DEL_BG), 0, sb.length, TERM_SPAN_FLAGS)
            else -> Unit
        }
        return sb
    }

    private fun appendLine(line: TermLine) {
        codeTv.append(styled(line))
        renderedLines++
    }

    private fun replaceAll(lines: List<TermLine>) {
        ui.removeCallbacks(drip)
        queue.clear()
        val all = SpannableStringBuilder()
        for (l in lines) all.append(styled(l))
        codeTv.setText(all, TextView.BufferType.EDITABLE)
        renderedLines = lines.size
        scrollToBottom()
        notifyGrowth()
    }

    /** Buang baris tertua agar tampilan tetap ringan saat keluaran sangat panjang. */
    private fun trimOld() {
        if (renderedLines <= 420) return
        val e = codeTv.editableText ?: return
        var pos = 0
        var removed = 0
        while (removed < 120) {
            val nl = e.indexOf('\n', pos)
            if (nl < 0) break
            pos = nl + 1
            removed++
        }
        if (pos > 0) {
            e.delete(0, pos)
            renderedLines -= removed
        }
    }

    private fun nearBottom(): Boolean {
        val c = scroll.getChildAt(0) ?: return true
        return scroll.scrollY + scroll.height >= c.height - act.dp(28)
    }

    private fun scrollToBottom() {
        scroll.post {
            val c = scroll.getChildAt(0)
            if (c != null) scroll.smoothScrollTo(0, (c.height - scroll.height).coerceAtLeast(0))
        }
    }

    /** Beri tahu chat agar ikut menggulir saat terminal bertambah tinggi. */
    private fun notifyGrowth() {
        scroll.post {
            if (scroll.height != lastH) {
                lastH = scroll.height
                act.scrollDown()
            }
        }
    }

    // ---------------------------------------------------------- Aksi

    private fun copyCurrent() {
        val text = shownLines.joinToString("\n")
        if (text.isBlank()) return
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        act.copyText("terminal", text)
        copyTv.text = "Tersalin ✓"
        ui.postDelayed({ copyTv.text = "Salin" }, 1400)
    }

    private fun toggleBig() {
        big = !big
        sizeTv.text = if (big) "Kecil" else "Besar"
        scroll.maxHeightPx = act.dp(if (big) 460 else 250)
        scrollToBottom()
        notifyGrowth()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(drip)
        stopDotBlink()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE) {
            ui.removeCallbacks(ticker)
            ui.postDelayed(ticker, 1000)
            startDotBlink()
        }
    }
}
