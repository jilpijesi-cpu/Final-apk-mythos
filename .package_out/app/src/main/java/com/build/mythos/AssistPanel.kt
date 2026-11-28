package com.build.mythos

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.build.mythos.cc.ImagePart
import com.build.mythos.cc.Msg
import com.build.mythos.cc.Role
import com.build.mythos.cc.FailoverClient
import com.build.mythos.cc.KeyPool
import com.build.mythos.cc.KeySpec
import com.build.mythos.cc.createClient
import com.build.mythos.cc.createRawClient
import com.build.mythos.cc.supportsVision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Aksi yang harus dikerjakan oleh sesi asisten (jendela di luar aplikasi). */
internal interface AssistHost {
    fun close()
    fun openMythos()
    /** Salin teks lalu tutup panel supaya pengguna bisa menempelnya di kolom aplikasi di bawahnya. */
    fun insert(text: String)
    fun toast(msg: String)
}

/**
 * Panel asisten bergaya lembar-bawah (acuan: panel Gemini di Gmail), memakai warna, ikon, dan tipografi MYTHOS.
 * Dibangun penuh dari kode (tanpa XML) seperti bagian lain aplikasi. Satu instance = satu pemanggilan asisten.
 */
internal class AssistPanel(private val ctx: Context, private val host: AssistHost) {

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    // Dekripsi API key (Android Keystore) bisa lambat; dimuat di latar begitu panel dibuat, bukan di thread utama saat kirim.
    private var loadedKeys: List<String> = emptyList()
    private val cfgLoad: Deferred<AssistCfg> = scope.async(Dispatchers.Default) {
        // Profil tempo yang sama dengan chat utama (Pengaturan > tempo tampilan): baca di latar, tetapkan di Main thread.
        val tempo = StreamTempo.byKey(ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString(StreamTempo.PREF_KEY, null))
        withContext(Dispatchers.Main.immediate) { StreamTempo.current = tempo }
        AssistConfig.load(ctx)
    }

    // Jawaban hidup (streaming): teks mengalir dari thread I/O, diketik per frame di thread UI oleh mesin yang sama dengan chat.
    private val ui = Handler(Looper.getMainLooper())
    private val tw = AssistTypewriter()
    private var streamCol: LinearLayout? = null
    private var streamTv: TextView? = null
    private var netDone = false
    private var finalAnswer = ""
    private var typing: Runnable? = null
    private var skipTyping = false
    private val latest = AtomicReference<String?>(null)
    private val flushStream = Runnable { latest.getAndSet(null)?.let { onStreamText(it) } }

    private var capture: ScreenCapture? = null
    private val history = ArrayList<Msg>()
    private var screenSent = false
    private var busy = false
    private var lastAnswer = ""
    private var status: StatusRow? = null
    private var errorBox: View? = null
    private var idleBox: View? = null
    private var chipText: TextView? = null
    private var bottomInset = 0

    // ------------------------------------------------------------------ kerangka

    private val scrim = View(ctx).apply {
        setBackgroundColor(if (Palette.dark) 0x80000000.toInt() else 0x55000000)
        setOnClickListener { close() }
    }

    private val content = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), dp(8))
    }

    private val scroll = MaxHeightScroll(ctx).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        maxPx = (ctx.resources.displayMetrics.heightPixels * 0.5f).toInt()
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val input = EditText(ctx).apply {
        hint = "Masukkan perintah di sini"
        setHintTextColor(Palette.HINT)
        setTextColor(Palette.INK)
        textSize = Typo.BODY_L
        background = null
        setPadding(0, dp(12), dp(8), dp(12))
        setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        maxLines = 4
        imeOptions = EditorInfo.IME_ACTION_SEND
        setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateSend() }
        })
    }

    private val sendGlyph = GlyphView(ctx, GlyphView.SEND_LINE, Palette.INK_SOFT)
    private val sendBtn = FrameLayout(ctx).apply {
        addView(sendGlyph, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener { if (busy) stop() else submit() }
        contentDescription = "Kirim"
    }

    private val grabber = View(ctx).apply {
        background = rounded(Palette.BORDER, 3)
    }

    private val header: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        addView(iconBtn(GlyphView.NEW_CHAT_LINE, "Percakapan baru") { resetConversation() })
        addView(SparkleView(ctx), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(2) })
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        addView(iconBtn(GlyphView.CLOSE, "Tutup") { close() })
    }

    private val inputRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Palette.FIELD, 28)
        setPadding(dp(20), 0, dp(8), 0)
        addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(sendBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
    }

    private val footer = TextView(ctx).apply {
        text = "MYTHOS bisa membuat kesalahan. Isi layar dikirim ke penyedia AI yang Anda pilih."
        textSize = Typo.CAPTION
        gravity = Gravity.CENTER
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(24), dp(10), dp(24), dp(4))
    }

    private val sheet = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Palette.CARD)
            cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f)
        }
        elevation = dpf(12f)
        isClickable = true // jangan teruskan ketukan ke scrim
        addView(grabber, LinearLayout.LayoutParams(dp(36), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(8), dp(16), 0)
        })
        addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    val root = FrameLayout(ctx).apply {
        addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setOnApplyWindowInsetsListener { _, insets ->
            bottomInset = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            footer.setPadding(dp(24), dp(10), dp(24), dp(8) + bottomInset)
            insets
        }
        // Tinggi area gulir mengikuti sisa tinggi jendela (mis. saat keyboard terbuka).
        addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h <= 0) return@addOnLayoutChangeListener
            val fixed = grabber.height + dp(15) + header.height + inputRow.height + dp(8) + footer.height
            val want = (h * 0.94f).toInt() - fixed
            val next = want.coerceAtLeast(dp(120))
            if (next != scroll.maxPx) { scroll.maxPx = next; scroll.post { scroll.requestLayout() } }
        }
    }

    init {
        updateSend()
        showIdle()
    }

    // ------------------------------------------------------------------ siklus hidup

    fun animateIn() {
        sheet.visibility = View.INVISIBLE
        scrim.alpha = 0f
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.visibility = View.VISIBLE
            sheet.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
            scrim.animate().alpha(1f).setDuration(260).start()
        }
    }

    private fun close() {
        hideKeyboard()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(190).withEndAction { host.close() }.start()
        scrim.animate().alpha(0f).setDuration(190).start()
    }

    /** Dipanggil sesi saat dibuang atau disembunyikan. */
    fun destroy() {
        job?.cancel()
        scope.cancel()
        ui.removeCallbacksAndMessages(null)
        sheet.animate().cancel()
        scrim.animate().cancel()
        typing = null
        status?.stop()
    }

    fun setCapture(c: ScreenCapture?) {
        // Pertahankan tangkapan layar yang sudah tiba lebih dulu dari teks (urutan panggilan Android tidak dijamin).
        val shot = c?.screenshotB64 ?: capture?.screenshotB64
        capture = if (c == null) capture else ScreenCapture(c.appLabel, c.packageName, c.texts, shot)
        updateChip()
    }

    fun setScreenshot(b64: String?) {
        val c = capture
        capture = if (c != null) ScreenCapture(c.appLabel, c.packageName, c.texts, b64)
        else ScreenCapture("aplikasi ini", "", emptyList(), b64)
        updateChip()
    }

    // ------------------------------------------------------------------ keadaan awal

    private fun updateChip() {
        val c = capture ?: return
        val n = c.texts.size
        val shot = if (c.screenshotB64 != null) " + gambar" else ""
        chipText?.text = if (c.hasText || c.screenshotB64 != null) "Membaca layar: ${c.appLabel} · $n teks$shot" else "Layar ${c.appLabel}: tidak ada teks terbaca"
    }

    private fun showIdle() {
        content.removeAllViews()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(SparkleView(ctx), LinearLayout.LayoutParams(dp(30), dp(30)))
        box.addView(TextView(ctx).apply {
            text = "Tanya tentang layar ini"
            textSize = Typo.DISPLAY
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.INK)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        chipText = TextView(ctx).apply {
            textSize = Typo.CAPTION
            setTextColor(Palette.BRAND_DEEP.let { if (Palette.dark) Palette.BRAND_2 else it })
            background = rounded(tint(Palette.BRAND, 0.85f), 14)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            text = "Membaca layar…"
        }
        box.addView(chipText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        for (s in AssistLogic.SUGGESTIONS) {
            box.addView(TextView(ctx).apply {
                text = s
                textSize = Typo.BODY
                setTextColor(Palette.INK)
                background = pressable(rounded(Palette.FIELD, 16))
                setPadding(dp(16), dp(13), dp(16), dp(13))
                setOnClickListener { sendText(s) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        idleBox = box
        content.addView(box)
        updateChip()
    }

    private fun resetConversation() {
        job?.cancel()
        setBusy(false)
        history.clear()
        screenSent = false
        lastAnswer = ""
        netDone = false
        clearStream()
        errorBox = null
        showIdle()
    }

    // ------------------------------------------------------------------ kirim

    private fun submit() {
        val q = input.text.toString().trim()
        if (q.isEmpty() || busy) return
        input.setText("")
        sendText(q)
    }

    private fun sendText(q: String) {
        if (busy) return
        hideKeyboard()
        if (idleBox != null) { content.removeAllViews(); idleBox = null }
        errorBox?.let { content.removeView(it); errorBox = null }
        addUserBubble(q)
        runModel(q, retry = false)
    }

    private fun stop() {
        // Jawaban sudah tiba dan tinggal diketik: tombol berhenti mempercepat sampai tuntas (seperti mengetuk teks di chat).
        if (netDone) { skipTyping = true; return }
        job?.cancel()
        clearStream()
        if (history.lastOrNull()?.role == Role.USER) {
            history.removeAt(history.size - 1)
            if (history.isEmpty()) screenSent = false
        }
        setBusy(false)
    }

    private fun runModel(question: String, retry: Boolean) {
        setBusy(true)
        netDone = false
        clearStream()
        showStatus()
        val c = capture
        val first = !screenSent
        job = scope.launch {
            try {
                val ready = when (val r = cfgLoad.await()) {
                    is AssistCfg.Missing -> {
                        clearStream()
                        setBusy(false)
                        showError(r.message, canRetry = false, canOpenApp = true)
                        return@launch
                    }
                    is AssistCfg.Ready -> r
                }
                loadedKeys = ready.keys.ifEmpty { listOfNotNull(ready.cfg.apiKey.takeIf { it.isNotBlank() }) }
                val needAdd = !retry || history.lastOrNull()?.role != Role.USER
                if (needAdd) {
                    // Penyusunan pesan (termasuk penyamaran rahasia di teks layar) di luar thread utama.
                    val (text, imgs) = withContext(Dispatchers.Default) {
                        val t = AssistLogic.userMessage(c, question, includeScreen = first)
                        val shot = c?.screenshotB64
                        val im = if (first && shot != null && supportsVision(ready.cfg.kind, ready.cfg.model))
                            listOf(ImagePart("image/jpeg", shot)) else emptyList()
                        t to im
                    }
                    history.add(Msg(Role.USER, text, imgs))
                    screenSent = true
                }
                val snapshot = history.toList()
                // Jawaban mengalir: teks lengkap sejauh ini diteruskan ke thread UI (hanya yang terbaru yang ditunggu).
                val answer = withContext(Dispatchers.IO) {
                    val effKeys = ready.keys.ifEmpty { listOf(ready.cfg.apiKey) }
                    val effModels = ready.activeModels.ifEmpty { listOf(ready.cfg.model) }
                    val client = if (effKeys.size > 1 || effModels.size > 1) {
                        val specs = ArrayList<KeySpec>(effModels.size * effKeys.size)
                        effKeys.forEachIndexed { idx, k ->
                            for (m in effModels) {
                                specs.add(KeySpec("#${idx + 1}", ready.cfg.kind, ready.cfg.baseUrl, k, m, ready.cfg.tokenParam, ready.cfg.temperature))
                            }
                        }
                        FailoverClient(
                            KeyPool(specs, rotateEvery = 0, sticky = true),
                            factory = ::createRawClient,
                            noun = if (effModels.size > 1) "kunci/model" else "kunci",
                            onDeadKey = { deadKey, _, _ ->
                                removeDeadApiKeysFromPrefs(ctx, ready.providerName, setOf(deadKey))
                                CcKeyPoolCache.clear()
                            },
                        )
                    } else {
                        createClient(ready.cfg)
                    }
                    client.streamText(AssistLogic.SYSTEM, snapshot) { t -> if (latest.getAndSet(t) == null) ui.post(flushStream) }
                }
                history.add(Msg(Role.ASSISTANT, answer))
                // Gambar hanya perlu dikirim sekali; jangan diunggah ulang di tiap pertanyaan lanjutan.
                val slim = AssistLogic.dropImages(history)
                history.clear(); history.addAll(slim)
                lastAnswer = answer
                finalAnswer = answer
                netDone = true
                latest.set(null)
                // Teks akhir menjadi sasaran pasti; sisa ketikan dituntaskan lalu diganti tampilan akhir (lihat finalizeAnswer).
                ensureStreamView()
                tw.setTarget(answer)
                startTyping()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearStream()
                setBusy(false)
                val msg = Cx.redact(e.message ?: e.javaClass.simpleName, loadedKeys)
                showError("Tidak bisa menjawab: ${msg.take(300)}", canRetry = true, canOpenApp = false, retryQuestion = question)
            }
        }
    }

    // ------------------------------------------------------------------ jawaban hidup (alur sama dengan chat)

    /** Baris status hidup: ikon titik mengorbit + teks berkilau, sama dengan baris status di chat. */
    private fun showStatus() {
        removeStatus()
        val s = StatusRow(ctx)
        status = s
        content.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        toBottom()
    }

    private fun removeStatus() {
        status?.let { it.stop(); content.removeView(it) }
        status = null
    }

    /** Teks lengkap terbaru dari model (thread UI). Hanya mengubah sasaran; yang menampilkannya adalah [startTyping]. */
    private fun onStreamText(t: String) {
        if (!busy || netDone || t.isEmpty() || t == tw.target) return
        ensureStreamView()
        status?.setText("Menyusun balasan…")
        tw.setTarget(t)
        startTyping()
    }

    private fun ensureStreamView(): TextView {
        streamTv?.let { return it }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(SparkleView(ctx), LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(8) })
        val tv = TextView(ctx).apply {
            textSize = Typo.BODY_L
            setTextColor(Palette.INK)
            setLineSpacing(dpf(3f), 1f)
            // Ketuk teks yang sedang mengalir untuk langsung melihat sisanya (sama dengan chat).
            setOnClickListener { skipTyping = true }
        }
        col.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Teks mengalir di atas baris status, seperti transkrip di atas baris status live di chat.
        val st = status
        val at = if (st != null) content.indexOfChild(st) else content.childCount
        content.addView(col, at.coerceAtLeast(0), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        streamCol = col
        streamTv = tv
        return tv
    }

    /** Satu loop frame yang mengejar sasaran dengan penghalus yang sama dengan chat. Aman dipanggil berulang. */
    private fun startTyping() {
        if (typing != null) return
        var last = SystemClock.uptimeMillis()
        skipTyping = false
        val run = object : Runnable {
            override fun run() {
                val tv = streamTv
                if (tv == null) { typing = null; return }
                val now = SystemClock.uptimeMillis()
                val dt = (now - last).coerceAtLeast(1L)
                last = now
                val changed = tw.tick(now, dt, skipTyping)
                if (changed || (!skipTyping && tw.fading(now))) {
                    tv.text = streamSpannable(tw.shown, now)
                    if (changed) scrollToEnd()
                }
                if (!tw.settled(now, skipTyping)) { ui.postDelayed(this, StreamSmoother.FRAME_MS); return }
                typing = null
                skipTyping = false
                if (netDone) finalizeAnswer()
            }
        }
        typing = run
        ui.post(run)
    }

    /** Ketikan tuntas dan jawaban lengkap sudah ada: ganti dengan tampilan akhir (markdown penuh + tombol aksi). */
    private fun finalizeAnswer() {
        val a = finalAnswer
        clearStream()
        setBusy(false)
        addAnswer(a, withSources = history.count { it.role == Role.ASSISTANT } == 1, streamed = true)
    }

    /** Hentikan ketikan dan buang tampilan hidup (baris status + teks yang sedang mengalir). */
    private fun clearStream() {
        typing?.let { ui.removeCallbacks(it) }
        typing = null
        skipTyping = false
        latest.set(null)
        removeStatus()
        streamCol?.let { content.removeView(it) }
        streamCol = null
        streamTv = null
        tw.reset()
    }

    private fun scrollToEnd() = scroll.post { scroll.scrollTo(0, (content.height - scroll.height).coerceAtLeast(0)) }

    private fun setBusy(b: Boolean) { busy = b; updateSend() }

    private fun updateSend() {
        val has = input.text?.isNotBlank() == true
        when {
            busy -> {
                sendGlyph.kind = GlyphView.STOP_LINE
                sendGlyph.color = if (Palette.dark) Palette.BRAND_2 else Palette.BRAND_DEEP
                sendBtn.background = rounded(tint(Palette.BRAND, 0.8f), 21)
                sendBtn.contentDescription = "Berhenti"
            }
            has -> {
                sendGlyph.kind = GlyphView.SEND_LINE
                sendGlyph.color = 0xFFFFFFFF.toInt()
                sendBtn.background = rounded(Palette.BRAND, 21)
                sendBtn.contentDescription = "Kirim"
            }
            else -> {
                sendGlyph.kind = GlyphView.SEND_LINE
                sendGlyph.color = Palette.INK_SOFT
                sendBtn.background = null
                sendBtn.contentDescription = "Kirim"
            }
        }
    }

    // ------------------------------------------------------------------ isi percakapan

    private fun addUserBubble(q: String) {
        val tv = TextView(ctx).apply {
            text = q
            textSize = Typo.BODY_L
            setTextColor(Palette.INK)
            background = rounded(Palette.BUBBLE, 20)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        content.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END; topMargin = dp(10); bottomMargin = dp(14)
        })
        toBottom()
    }

    private fun showError(msg: String, canRetry: Boolean, canOpenApp: Boolean, retryQuestion: String = "") {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(ctx).apply {
            text = msg
            textSize = Typo.BODY
            setTextColor(Palette.RED)
        })
        if (canRetry) box.addView(pill("Coba lagi") {
            content.removeView(box); errorBox = null
            runModel(retryQuestion, retry = true)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        if (canOpenApp) box.addView(pill("Buka MYTHOS") { host.openMythos() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        errorBox = box
        content.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        toBottom()
    }

    private fun addAnswer(md: String, withSources: Boolean, streamed: Boolean = false) {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(SparkleView(ctx), LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(8) })
        renderMarkdown(col, md)

        val c = capture
        if (withSources && c != null && c.texts.isNotEmpty()) col.addView(sourcesRow(c), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        // Baris aksi: suka / tidak suka ... salin + Sisipkan
        var up: GlyphView? = null
        var down: GlyphView? = null
        fun mark(isUp: Boolean) {
            up?.color = if (isUp) Palette.BRAND else Palette.ICON
            down?.color = if (!isUp) Palette.BRAND else Palette.ICON
        }
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val upBtn = iconBtn(GlyphView.THUMB_UP, "Jawaban membantu") { mark(true) }
        val downBtn = iconBtn(GlyphView.THUMB_DOWN, "Jawaban kurang tepat") { mark(false) }
        up = upBtn.getChildAt(0) as GlyphView
        down = downBtn.getChildAt(0) as GlyphView
        actions.addView(upBtn)
        actions.addView(downBtn)
        actions.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(iconBtn(GlyphView.COPY, "Salin") {
            copy(AssistLogic.plain(md)); host.toast("Disalin")
        })
        actions.addView(TextView(ctx).apply {
            text = "Sisipkan"
            textSize = Typo.BODY_S
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (Palette.dark) Palette.BRAND_2 else Palette.BRAND_DEEP)
            background = pressable(rounded(tint(Palette.BRAND, 0.82f), 20))
            setPadding(dp(18), dp(9), dp(18), dp(9))
            setOnClickListener { host.insert(AssistLogic.plain(md)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        col.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        content.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        // Jawaban yang baru selesai mengalir: tetap di dasar (pembaca sedang mengikuti ketikan). Selain itu mulai dari awal jawaban.
        if (streamed) toBottom() else toTopOf(col)
    }

    /** "Sumber (N)": jumlah teks yang dibaca dari layar; ketuk untuk melihat apa yang dikirim ke model. */
    private fun sourcesRow(c: ScreenCapture): View {
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val detail = TextView(ctx).apply {
            visibility = View.GONE
            textSize = Typo.CAPTION
            setTextColor(Palette.INK_SOFT)
            background = rounded(Palette.FIELD, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            text = "Dari aplikasi ${c.appLabel}:\n\n" + c.texts.take(40).joinToString("\n") { "• " + redactSecrets(it).take(120) } +
                if (c.texts.size > 40) "\n… dan ${c.texts.size - 40} teks lain" else ""
        }
        val arrow = GlyphView(ctx, GlyphView.CHEVRON_R, Palette.INK_SOFT)
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(ctx).apply {
                text = "Sumber (${c.texts.size})"
                textSize = Typo.BODY_S
                setTextColor(Palette.INK_SOFT)
            })
            addView(arrow, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) })
            setOnClickListener {
                val open = detail.visibility != View.VISIBLE
                detail.visibility = if (open) View.VISIBLE else View.GONE
                arrow.rotation = if (open) 90f else 0f
            }
        }
        wrap.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    // ------------------------------------------------------------------ markdown

    private fun spannable(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        for (seg in AssistLogic.inlineSegments(text)) {
            val st = sb.length
            sb.append(seg.text)
            if (seg.bold) sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (seg.code) {
                sb.setSpan(TypefaceSpan("monospace"), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(Palette.CHIP), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun isList(b: AssistMdBlock) = b is AssistMdBlock.Bullet || b is AssistMdBlock.Numbered

    /**
     * Teks yang sedang diketik: markdown ringan yang sama dengan tampilan akhir, tanpa penanda mentah (tanda yang belum
     * ditutup disembunyikan oleh [hideOpenMarkup]), dan huruf terbaru memudar masuk menurut umurnya sendiri (seperti chat).
     */
    private fun streamSpannable(raw: String, now: Long): CharSequence {
        val sb = SpannableStringBuilder()
        var prev: AssistMdBlock? = null
        for (b in AssistLogic.parseMarkdown(hideOpenMarkup(raw))) {
            if (prev != null) sb.append(if (isList(prev) && isList(b)) "\n" else "\n\n")
            when (b) {
                is AssistMdBlock.Heading -> {
                    val st = sb.length
                    sb.append(spannable(b.text))
                    sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is AssistMdBlock.Para -> sb.append(spannable(b.text))
                is AssistMdBlock.Bullet -> sb.append("  ".repeat(b.level.coerceIn(0, 3))).append("• ").append(spannable(b.text))
                is AssistMdBlock.Numbered -> sb.append("  ".repeat(b.level.coerceIn(0, 3))).append("${b.number}. ").append(spannable(b.text))
            }
            prev = b
        }
        // Huruf terakhir memudar masuk; umur dihitung dari ekor teks mentah (sama dengan updateStreamingText di chat).
        val n = sb.length
        var d = 0
        while (d < n && d < StreamTempo.current.fadeReach) {
            val age = tw.ageAt(tw.shown.length - 1 - d, now)
            if (age >= StreamSmoother.FADE_MS) break
            val pos = n - 1 - d
            if (!sb[pos].isWhitespace()) {
                sb.setSpan(
                    ForegroundColorSpan((Palette.INK and 0x00FFFFFF) or (StreamSmoother.fadeAlpha(age) shl 24)),
                    pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            d++
        }
        return sb
    }

    private fun bodyText(text: String, bold: Boolean = false) = TextView(ctx).apply {
        this.text = spannable(text)
        textSize = Typo.BODY_L
        setTextColor(Palette.INK)
        setLineSpacing(dpf(3f), 1f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun renderMarkdown(box: LinearLayout, md: String) {
        val lp = { top: Int -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) } }
        for (b in AssistLogic.parseMarkdown(md)) {
            when (b) {
                is AssistMdBlock.Heading -> box.addView(bodyText(b.text, bold = true), lp(14))
                is AssistMdBlock.Para -> box.addView(bodyText(b.text), lp(8))
                is AssistMdBlock.Bullet -> box.addView(listRow("•", b.text, b.level), lp(6))
                is AssistMdBlock.Numbered -> box.addView(listRow("${b.number}.", b.text, b.level), lp(6))
            }
        }
    }

    private fun listRow(marker: String, text: String, level: Int): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4 + level.coerceIn(0, 3) * 18), 0, 0, 0)
        addView(TextView(ctx).apply {
            this.text = marker
            textSize = Typo.BODY_L
            setTextColor(Palette.INK_SOFT)
            minWidth = dp(20)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bodyText(text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    // ------------------------------------------------------------------ util tampilan

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp.toFloat())
    }

    private fun iconBtn(kind: Int, desc: String, onTap: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        contentDescription = desc
        addView(GlyphView(ctx, kind, Palette.ICON), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onTap() }
    }

    private fun pill(label: String, onTap: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = Typo.BODY_S
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.INK)
        background = pressable(rounded(Palette.FIELD, 20))
        setPadding(dp(18), dp(9), dp(18), dp(9))
        setOnClickListener { onTap() }
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("MYTHOS", text))
    }

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun toBottom() = scroll.post { scroll.smoothScrollTo(0, content.height) }

    /** Setelah jawaban muncul, gulir ke awal jawaban (bukan ke dasar) supaya pembacaan dimulai dari atas. */
    private fun toTopOf(v: View) = scroll.post { scroll.smoothScrollTo(0, (v.top - dp(8)).coerceAtLeast(0)) }

    // ------------------------------------------------------------------ view khusus

    /** ScrollView yang tingginya dibatasi [maxPx] supaya lembar tidak menutupi seluruh layar. */
    private class MaxHeightScroll(c: Context) : ScrollView(c) {
        var maxPx = Int.MAX_VALUE
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }
    }

    /** Bintang empat sudut bergradasi warna merek (pengganti bintang biru Gemini). */
    private class SparkleView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            if (s <= 0f) return
            val m = s / 2f
            path.reset()
            path.moveTo(m, 0f); path.quadTo(m, m, s, m)
            path.quadTo(m, m, m, s); path.quadTo(m, m, 0f, m)
            path.quadTo(m, m, m, 0f); path.close()
            p.shader = LinearGradient(0f, 0f, s, s, Palette.BRAND_2, Palette.BRAND, Shader.TileMode.CLAMP)
            canvas.drawPath(path, p)
        }
    }

    /**
     * Baris status hidup selama jawaban disusun: ikon titik mengorbit (ClaudeDotsView) dan teks berkilau (ShimmerText),
     * komponen dan warna yang sama dengan LiveStatusLine di chat. Panel tidak menjalankan alat, jadi tanpa narasi alat/terminal.
     */
    private class StatusRow(c: Context) : LinearLayout(c) {
        private val label = ShimmerText(c).apply {
            textSize = Typo.TITLE_S
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            shimmerBase = Live.SHIMMER_LOW
            shimmerHi = Live.TEXT_HI
            shimmerGlow = Live.ORANGE   // tepi depan gelombang berpendar oranye hangat
            setTextColor(Live.TEXT_HI)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }

        init {
            val d = c.resources.displayMetrics.density
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val dots = ClaudeDotsView(c)
            val slot = FrameLayout(c).apply {
                addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            }
            addView(slot, LayoutParams((40 * d).toInt(), (32 * d).toInt()))
            addView(label, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            dots.setMode(ClaudeDotsView.ORBIT)
            setText("Berpikir…")
        }

        fun setText(t: String) {
            if (label.text.toString() == t) return
            label.text = t
            label.setShimmer(true)
            label.alpha = 0f
            label.animate().alpha(1f).setDuration(200).start()
        }

        fun stop() { label.animate().cancel(); label.setShimmer(false) }
    }
}
