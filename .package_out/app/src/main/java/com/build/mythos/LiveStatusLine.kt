package com.build.mythos

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// Baris status AI "live": satu baris berisi ikon yang berganti sesuai pekerjaan AI, teks berkilau
// yang memudar saat berganti, dan panah. Diketuk -> terbuka terminal gelap (LiveTerminal.kt) yang menampilkan perintah + keluarannya
// secara live (diwarnai, bisa dipilih per langkah, bisa disalin). Data berasal dari AgentStepStore (renderSteps).
// Logika murni pewarnaan ada di TerminalLex.kt (teruji); berkas ini hanya tampilan baris status.

private const val NARRATION_HOLD_MS = 2400L   // lama narasi tampil sebelum kembali ke status kerja (rekaman acuan: ~2 dtk)
private const val MIN_DWELL_MS = 1200L      // status kerja tampil minimal ini sebelum diganti (rekaman acuan: ~1,2 dtk)
private const val NARRATION_GAP_MS = 350L    // jeda status kerja di antara dua narasi
private const val GRID_BURST_MS = 7000L       // ikon 4 titik di awal tiap rangkaian alat (video: 5,5-8 dtk), lalu kembali 3 titik
private const val DEEP_INTRO = "Deep Reasoning..."   // baris atas selama otak terbentuk
// Logo layanan (Gmail/Telegram/WhatsApp/PDF/GitHub) pada baris status: titik oranye berganti sebentar jadi logo, lalu kembali.
// Diukur frame demi frame dari rekaman acuan (25 fps, 19 siklus): logo tampil ~0,36 dtk, titik ~0,8 dtk, periode ~1,15 dtk;
// logo pertama muncul ~0,9 dtk setelah langkah mulai; logo langsung berukuran penuh (tanpa membesar/mengecil), hanya
// memudar masuk (~0,2 dtk) dan keluar cepat (~0,1 dtk); titik memudar cepat (~0,08 dtk) agar tidak menumpuk di balik logo.
private const val LOGO_FIRST_MS = 900L
private const val LOGO_HOLD_MS = 360L
private const val LOGO_DOTS_MS = 800L
private const val LOGO_IN_MS = 200L
private const val LOGO_OUT_MS = 100L
private const val LOGO_DOTS_FADE_MS = 80L
private const val STILL_AFTER_MS = 6000L      // tanpa aktivitas baru selama ini -> muncul "Masih diproses..."

internal class LiveStatusLine(private val act: MainActivity) : LinearLayout(act) {
    val terminal = LiveTerminal(act)

    private val ui = Handler(Looper.getMainLooper())
    // Tahan dasar mengikuti profil tempo (Normal = 700 ms seperti dulu, Santai = 1300 ms).
    private fun newGate() = TextGate(700L) { holdFor(it, (StreamTempo.current.gateHoldMs - 200L).coerceAtLeast(500L)) }
    private var gate = newGate()
    private val kindByText = HashMap<String, Int>()
    private var iconKind = Int.MIN_VALUE
    private var iconTint = 0
    private var finished = false
    private var expanded = false
    private var breathing = false
    // Narasi hanya dipicu event App Server nyata. Ikon asli tidak dibuat ulang; alpha saja yang disembunyikan.
    private var narrationGeneration = 0
    private var narrationRunnable: Runnable? = null
    private var narrationGapRunnable: Runnable? = null
    private var typingNarration = false
    private var currentNarrationText = ""
    private var narrationIdle = "Sedang dikerjakan…"
    // Ikon status kerja saat TIDAK ada narasi: KIND_DOTS, atau logo layanan (GitHub, Gmail, ...) selama alat berlogo masih berjalan.
    // Dulu status kerja ("Sedang menjalankan perintah") selalu KIND_DOTS sehingga logo hilang di tengah alat yang masih berjalan.
    private var idleKind = KIND_DOTS
    private val pendingNarrations = java.util.ArrayDeque<String>()
    private var pendingStatusLine: Pair<String, Int>? = null
    private var lastChangeMs = 0L
    private var deferredIdle: String? = null
    private val applyDeferred = Runnable {
        val t = deferredIdle ?: return@Runnable
        deferredIdle = null
        if (!finished && !typingNarration && label.text.toString() != t) {
            pendingStatusLine = null
            swapText(t, idleKind, Live.ORANGE, working = true)
        }
    }
    private val startDeferredNarration = Runnable {
        if (finished || typingNarration) return@Runnable
        val n = pendingNarrations.pollFirst() ?: return@Runnable
        startNarration(n, clearPendingStatus = true)
    }
    private var breatheAnim: ObjectAnimator? = null
    private var heightAnim: ValueAnimator? = null

    private val iconSlot = FrameLayout(act)
    private fun newLabel() = ShimmerText(act).apply {
        textSize = Typo.TITLE_S
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        shimmerBase = Live.SHIMMER_LOW
        shimmerHi = Live.TEXT_HI
        shimmerGlow = Live.ORANGE   // tepi depan gelombang berpendar oranye hangat
        setTextColor(Live.TEXT_HI)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        alpha = 0f
        setShimmer(false)
    }
    private val labelA = newLabel()
    private val labelB = newLabel()
    private var label = labelA
    // Dua teks bertumpuk di wadah yang lebarnya beranimasi: teks lama terpotong dari kanan sambil memudar,
    // teks baru memudar masuk di tempat yang sama, dan panah ikut bergeser (seperti di Claude).
    private val textHost = FrameLayout(act).apply {
        clipChildren = true
        addView(labelA, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
        addView(labelB, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
    }
    private var hostAnim: ValueAnimator? = null
    private val chevron = IconView(act, IconView.CHEVRON, Live.CHEVRON)

    private val head = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = act.dp(40)
        setPadding(0, act.dp(4), act.dp(10), act.dp(4))
        background = pressable(act.rounded(Color.TRANSPARENT, 14))
        addView(iconSlot, LinearLayout.LayoutParams(act.dp(40), act.dp(32)))
        addView(textHost, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(chevron, LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
        setOnClickListener { toggle() }
        a11yExpandable { expanded }
    }

    // Ikon: dasar 3 titik mengorbit; 4 titik (grid) hanya dalam "ledakan" singkat di awal tiap rangkaian alat, sesuai rekaman acuan.
    private var burstGrid = false
    private val endBurst = Runnable {
        burstGrid = false
        (iconSlot.getChildAt(0) as? ClaudeDotsView)?.setMode(if (iconKind == KIND_DOTS || isLogoKind(iconKind)) ClaudeDotsView.ORBIT else ClaudeDotsView.GRID)
    }

    /** Dipanggil saat rangkaian alat baru dimulai: ikon berganti ke 4 titik sebentar. Bentuk gerak ikon tidak diubah. */
    fun gridBurst() {
        if (finished) return
        ui.removeCallbacks(endBurst)
        burstGrid = true
        (iconSlot.getChildAt(0) as? ClaudeDotsView)?.setMode(ClaudeDotsView.GRID)
        ui.postDelayed(endBurst, GRID_BURST_MS)
    }

    private fun stopBurst() {
        ui.removeCallbacks(endBurst)
        burstGrid = false
    }

    // Sub-baris kecil di bawah baris status saat pekerjaan berjalan lama tanpa kabar baru (seperti di rekaman acuan).
    private val stillTv = TextView(act).apply {
        text = "Masih diproses..."
        textSize = Typo.SMALL   // lebih kecil dari baris status (17f) supaya terbaca sebagai keterangan tambahan
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(Live.TEXT_BASE)
        setPadding(act.dp(40), act.dp(4), 0, act.dp(2))
        visibility = View.GONE
    }
    private val showStill = Runnable { if (!finished && !deepMode) stillTv.visibility = View.VISIBLE }

    // ---- Penalaran mendalam: baris bawah (tahap penalaran), status otak, dan teks baris atas yang ditahan selama otak terbentuk.
    private var deepMode = false
    private var deepIntro = false
    private var deepFormed = false
    private var introPass = false
    private var deferredDeepLine: Triple<String, Int, Int>? = null
    private val stageTv = ShimmerText(act).apply {
        textSize = Typo.SMALL
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(Live.TEXT_HI)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        setPadding(act.dp(40), act.dp(2), 0, act.dp(2))
        visibility = View.GONE
        setShimmer(false)
    }

    /** Sapuan cahaya: normal = abu berkilau lama; deep = oranye, emas, putih, lebih cepat. Putih dihindari di tema terang agar tetap terbaca. */
    private fun styleDeep(on: Boolean) {
        val base: Int; val hi: Int; val glow: Int; val period: Long
        if (on) {
            base = if (Palette.dark) 0xFFE3A06F.toInt() else 0xFFB4532F.toInt()
            hi = if (Palette.dark) 0xFFFFD27A.toInt() else 0xFFE08A00.toInt()
            glow = if (Palette.dark) 0xFFFFFFFF.toInt() else 0xFFFFC04D.toInt()
            period = 1500L
        } else {
            base = Live.SHIMMER_LOW; hi = Live.TEXT_HI; glow = Live.ORANGE; period = 2600L
        }
        for (v in arrayOf(labelA, labelB, stageTv)) {
            v.shimmerBase = base; v.shimmerHi = hi; v.shimmerGlow = glow; v.periodMs = period
        }
    }

    private fun resetDeep() {
        deepMode = false; deepIntro = false; deepFormed = false; deferredDeepLine = null
        stageTv.animate().cancel()
        stageTv.setShimmer(false)
        stageTv.visibility = View.GONE
        styleDeep(false)
    }

    private fun attachBrain(dv: ClaudeDotsView, animate: Boolean) {
        dv.onBrainSettled = { formed -> onBrainSettled(formed) }
        dv.setBrain(deepMode, animate && !deepFormed)
    }

    private fun onBrainSettled(formed: Boolean) {
        if (!formed) { deepFormed = false; return }
        if (!deepMode || finished) return
        deepFormed = true
        deepIntro = false
        // Formasi selesai: tampilkan aktivitas NYATA terakhir (yang ditahan selama formasi, atau status terakhir agen).
        val d = deferredDeepLine
        deferredDeepLine = null
        if (d != null) swapText(d.first, d.second, d.third, working = true)
        else tidyLine(act.curTitle, 90).takeIf { it.isNotBlank() }?.let { swapText(it, KIND_DOTS, Live.ORANGE, working = true) }
    }

    /** Aktif/nonaktifkan penalaran mendalam pada baris live: bola oranye <-> otak, teks dua baris, sapuan cahaya intens. */
    fun setDeepMode(on: Boolean, animate: Boolean) {
        if (finished || on == deepMode) return
        deepMode = on
        styleDeep(on)
        if (on) {
            deepIntro = true; deepFormed = false; deferredDeepLine = null
            val st = deepStageFor(act.agentState, act.deepAnalyzing) ?: 0
            stageTv.animate().cancel()
            stageTv.text = DEEP_STAGE_TEXT[st]
            stageTv.visibility = View.VISIBLE
            stageTv.alpha = 0f
            stageTv.setShimmer(true)
            stageTv.animate().alpha(1f).setDuration(260).start()
            introPass = true
            swapText(DEEP_INTRO, KIND_DOTS, Live.ORANGE, working = true)
            introPass = false
            (iconSlot.getChildAt(0) as? ClaudeDotsView)?.let { attachBrain(it, animate) }
        } else {
            deepIntro = false; deferredDeepLine = null
            stageTv.animate().cancel()
            stageTv.animate().alpha(0f).setDuration(200).withEndAction {
                if (!deepMode) { stageTv.setShimmer(false); stageTv.visibility = View.GONE }
            }.start()
            (iconSlot.getChildAt(0) as? ClaudeDotsView)?.setBrain(false)
            tidyLine(act.curTitle, 90).takeIf { it.isNotBlank() }?.let { swapText(it, KIND_DOTS, Live.ORANGE, working = true) }
        }
    }

    /** Tahap penalaran (0 Analyzing, 1 Planning, 2 Executing, 3 Verifying) dari keadaan agen nyata; satu tahap tampil sekali waktu. */
    fun deepStage(index: Int) {
        if (!deepMode || finished) return
        val t = DEEP_STAGE_TEXT[index.coerceIn(0, DEEP_STAGE_TEXT.size - 1)]
        if (stageTv.text.toString() == t) return
        stageTv.animate().cancel()
        if (stageTv.alpha < 0.05f) {
            stageTv.text = t
            stageTv.animate().alpha(1f).setDuration(220).start()
            return
        }
        stageTv.animate().alpha(0f).setDuration(120).withEndAction {
            stageTv.text = t
            stageTv.animate().alpha(1f).setDuration(180).start()
        }.start()
    }

    private fun armStill() {
        ui.removeCallbacks(showStill)
        stillTv.visibility = View.GONE
        ui.postDelayed(showStill, STILL_AFTER_MS)
    }

    private fun disarmStill() {
        ui.removeCallbacks(showStill)
        stillTv.visibility = View.GONE
    }

    private val release = Runnable {
        gate.release(SystemClock.uptimeMillis())?.let { applyLine(it) }
        scheduleRelease()
    }

    init {
        orientation = VERTICAL
        addView(head, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(stageTv, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(stillTv, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(terminal, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(6); bottomMargin = act.dp(4) })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Teks panjang dipotong dengan "…" sebelum panah agar tetap ringkas.
        post {
            val mw = (w - paddingLeft - paddingRight - act.dp(40 + 20 + 12)).coerceAtLeast(act.dp(80))
            labelA.maxWidth = mw
            labelB.maxWidth = mw
        }
    }

    // ---------------------------------------------------------- Teks dan ikon

    /** Satu baris penjelasan aktivitas. Teks sama diabaikan; perubahan beruntun ditahan agar tidak berkedip. */
    fun setLine(raw: String, kind: Int = KIND_DOTS) {
        if (finished) return
        val t = tidyLine(raw, 90)
        if (t.isEmpty()) return
        if (typingNarration) {
            // Jangan memotong kalimat di tengah ketikan; tampilkan status terbaru setelah narasi selesai.
            pendingStatusLine = t to kind
            return
        }
        kindByText[t] = kind
        gate.submit(t, SystemClock.uptimeMillis())?.let { applyLine(it) }
        scheduleRelease()
    }

    /**
     * Perbarui teks status kerja agar sesuai keadaan nyata (mis. alat selesai -> "Proses berpikir").
     * Bila narasi sedang tampil, teks ini dipakai begitu narasi selesai; bila tidak, langsung diganti.
     */
    fun setIdle(idle: String, logo: Int? = null) {
        if (finished) return
        val t = tidyLine(idle, 60)
        if (t.isBlank()) return
        narrationIdle = t
        idleKind = logo ?: KIND_DOTS
        if (typingNarration) return
        if (label.text.toString() == t) {
            // Teks sama tetapi logo berubah (alat berlogo mulai/selesai): ganti ikonnya saja, tanpa memudarkan teks.
            if (iconKind != idleKind && (isLogoKind(iconKind) || isLogoKind(idleKind)) && !deepIntro) setIcon(idleKind, Live.ORANGE, true)
            return
        }
        // Beri waktu baca: status sebelumnya tampil minimal MIN_DWELL_MS sebelum diganti.
        val wait = MIN_DWELL_MS - (SystemClock.uptimeMillis() - lastChangeMs)
        if (wait > 60L) {
            deferredIdle = t
            ui.removeCallbacks(applyDeferred)
            ui.postDelayed(applyDeferred, wait)
        } else {
            pendingStatusLine = null
            swapText(t, idleKind, Live.ORANGE, working = true)
        }
    }

    fun showNarration(raw: String, idle: String = "Sedang dikerjakan…", logo: Int? = null) {
        if (finished) return
        narrationIdle = tidyLine(idle, 60).ifBlank { "Sedang dikerjakan…" }
        idleKind = logo ?: KIND_DOTS
        val text = tidyLine(raw, 90)
        if (text.isBlank()) return
        if (typingNarration) {
            if (text != currentNarrationText && pendingNarrations.peekLast() != text) pendingNarrations.addLast(text)
            while (pendingNarrations.size > 2) pendingNarrations.removeFirst()
            return
        }
        if (text == currentNarrationText && label.text.toString() == text) return
        // Narasi juga menunggu status kerja yang sedang tampil selesai dibaca; yang terlalu lama mengantri dibuang
        // (simpan 2 terbaru) supaya teks tidak tertinggal dari pekerjaan sebenarnya.
        val wait = MIN_DWELL_MS - (SystemClock.uptimeMillis() - lastChangeMs)
        if (wait > 60L) {
            if (pendingNarrations.peekLast() != text) pendingNarrations.addLast(text)
            while (pendingNarrations.size > 2) pendingNarrations.removeFirst()
            ui.removeCallbacks(startDeferredNarration)
            ui.postDelayed(startDeferredNarration, wait)
            return
        }
        startNarration(text, clearPendingStatus = true)
    }

    private fun startNarration(text: String, clearPendingStatus: Boolean) {
        if (finished) return
        ui.removeCallbacks(release)
        ui.removeCallbacks(applyDeferred)
        deferredIdle = null
        narrationRunnable?.let { ui.removeCallbacks(it) }
        val generation = ++narrationGeneration
        // Narasi muncul utuh lewat pergantian teks seperti status biasa (teks lama memudar, teks baru memudar masuk,
        // lebar wadah dan panah ikut bergeser, teks berkilau). Tidak diketik per karakter, sesuai rekaman acuan.
        typingNarration = true
        currentNarrationText = text
        if (clearPendingStatus) pendingStatusLine = null
        val kind = when {
            idleKind != KIND_DOTS -> idleKind                 // alat berlogo sedang berjalan: logonya tetap tampil
            isLogoKind(iconKind) -> KIND_DOTS                 // logo lama tidak boleh menempel pada langkah tanpa logo
            iconKind == Int.MIN_VALUE -> KIND_DOTS
            else -> iconKind
        }
        swapText(text, kind, Live.ORANGE, working = true)
        armStill()
        val run = Runnable {
            if (generation != narrationGeneration || !typingNarration) return@Runnable
            narrationRunnable = null
            typingNarration = false
            // Setelah narasi tertahan cukup lama, kembali ke status kerja; ikon asli tetap hidup tanpa dibuat ulang.
            val pending = pendingStatusLine
            pendingStatusLine = null
            val next = if (pendingNarrations.isNotEmpty()) pendingNarrations.removeFirst() else null
            // Seperti rekaman acuan: bila tidak ada narasi berikutnya, kembali ke status kerja ("Sedang menjalankan perintah").
            if (pending != null && pending.first != text) {
                swapText(pending.first, pending.second, Live.ORANGE, working = true)
            } else if (next == null) {
                swapText(narrationIdle, idleKind, Live.ORANGE, working = true)
            }
            if (next != null) {
                narrationGapRunnable?.let { ui.removeCallbacks(it) }
                val gapRun = Runnable {
                    narrationGapRunnable = null
                    if (generation == narrationGeneration && !finished && !typingNarration) {
                        startNarration(next, clearPendingStatus = false)
                    }
                }
                narrationGapRunnable = gapRun
                ui.postDelayed(gapRun, NARRATION_GAP_MS)
            }
        }
        narrationRunnable = run
        ui.postDelayed(run, NARRATION_HOLD_MS)
    }

    private fun cancelNarration() {
        stopBurst()
        disarmStill()
        narrationGeneration++
        narrationRunnable?.let { ui.removeCallbacks(it) }
        narrationRunnable = null
        narrationGapRunnable?.let { ui.removeCallbacks(it) }
        narrationGapRunnable = null
        ui.removeCallbacks(applyDeferred)
        ui.removeCallbacks(startDeferredNarration)
        deferredIdle = null
        typingNarration = false
        currentNarrationText = ""
        pendingNarrations.clear()
        pendingStatusLine = null
        iconSlot.animate().cancel()
        iconSlot.alpha = 1f
    }

    private fun scheduleRelease() {
        ui.removeCallbacks(release)
        gate.nextDelay(SystemClock.uptimeMillis())?.let { ui.postDelayed(release, it) }
    }

    private fun applyLine(t: String) {
        val kind = kindByText[t] ?: KIND_DOTS
        swapText(t, kind, Live.ORANGE, working = true)
        armStill()
    }

    /** Ganti teks + ikon: teks lama terpotong dan memudar, teks baru memudar masuk bersamaan (tanpa geser), lebar wadah beranimasi. */
    private fun swapText(t: String, kind: Int, tint: Int, working: Boolean) {
        // Selama otak terbentuk, baris atas tetap "Deep Reasoning..."; aktivitas nyata terbaru disimpan dan ditampilkan setelahnya.
        if (deepIntro && working && !introPass) { deferredDeepLine = Triple(t, kind, tint); return }
        lastChangeMs = SystemClock.uptimeMillis()
        val old = label
        val nw = if (old === labelA) labelB else labelA
        old.animate().cancel()
        nw.animate().cancel()
        hostAnim?.cancel()
        nw.text = t
        nw.setShimmer(working)
        if (!working) nw.setTextColor(Live.TEXT_HI)
        nw.translationY = 0f
        setIcon(kind, tint, working)
        label = nw
        nw.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val toW = nw.measuredWidth
        val first = old.alpha < 0.05f || old.text.isEmpty() || textHost.width <= 0
        val lp = textHost.layoutParams
        if (first) {
            old.alpha = 0f
            lp.width = toW
            textHost.layoutParams = lp
            nw.alpha = 0f
            nw.animate().alpha(1f).setDuration(200).start()
            return
        }
        val fromW = textHost.width
        old.animate().alpha(0f).setDuration(200).withEndAction { if (old !== label) old.setShimmer(false) }.start()
        nw.alpha = 0f
        nw.animate().alpha(1f).setDuration(200).start()
        val a = ValueAnimator.ofInt(fromW, toW)
        hostAnim = a
        a.duration = 250
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            lp.width = it.animatedValue as Int
            textHost.layoutParams = lp
        }
        a.start()
    }

    private fun setIcon(kind: Int, tint: Int, breathe: Boolean) {
        if (kind == iconKind && tint == iconTint) return
        iconKind = kind
        iconTint = tint
        stopBreathe()
        if (breathe && tint == Live.ORANGE) {
            // Keadaan bekerja: satu ikon titik yang berganti bentuk (orbit 3 titik / kotak 4 titik) dengan susut-tahan-kembang.
            val mode = if ((kind == KIND_DOTS || isLogoKind(kind)) && !burstGrid) ClaudeDotsView.ORBIT else ClaudeDotsView.GRID
            val cur = iconSlot.getChildAt(0) as? ClaudeDotsView
            if (cur != null) { cur.setMode(mode); syncLogo(kind); return }
            stopLogo()
            iconSlot.removeAllViews()
            val dv = ClaudeDotsView(act)
            iconSlot.addView(dv, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
            dv.setMode(mode)
            if (deepMode) attachBrain(dv, true)
            breathing = false
            syncLogo(kind)
            return
        }
        stopLogo()
        iconSlot.removeAllViews()
        val v: View = if (kind == KIND_DOTS) {
            OrbitDotsView(act, tint)
        } else {
            IconView(act, kind, tint).apply { working = kind == IconView.GEAR && breathe }
        }
        val size = when {
            kind == KIND_DOTS -> act.dp(30)
            finished -> act.dp(18)
            else -> act.dp(24)
        }
        iconSlot.addView(v, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        v.scaleX = 0.4f
        v.scaleY = 0.4f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280)
            .setInterpolator(OvershootInterpolator(2f)).start()
        breathing = breathe && kind != KIND_DOTS && kind != IconView.GEAR
        if (breathing && isAttachedToWindow) startBreathe()
    }

    // ---------------------------------------------------------- Logo layanan (Gmail, Telegram, WhatsApp, PDF, GitHub)

    private var logoView: ImageView? = null
    private var logoShown = false

    private fun logoRes(kind: Int): Int = when (kind) {
        KIND_LOGO_GMAIL -> R.drawable.logo_gmail
        KIND_LOGO_TELEGRAM -> R.drawable.logo_telegram
        KIND_LOGO_PDF -> R.drawable.logo_pdf
        KIND_LOGO_WHATSAPP -> R.drawable.logo_whatsapp
        KIND_LOGO_GITHUB -> R.drawable.logo_github // drawable-night-nodpi punya versi terbalik untuk mode gelap
        else -> 0
    }

    private val logoCycle = object : Runnable {
        override fun run() {
            val lv = logoView ?: return
            if (finished || deepMode) { stopLogo(); return }
            logoShown = !logoShown
            val dots = iconSlot.getChildAt(0)
            if (logoShown) {
                // Seperti rekaman acuan: ukuran logo tetap, hanya alpha yang berubah.
                dots?.animate()?.alpha(0f)?.setDuration(LOGO_DOTS_FADE_MS)?.start()
                lv.scaleX = 1f; lv.scaleY = 1f
                lv.animate().alpha(1f).setDuration(LOGO_IN_MS).setInterpolator(DecelerateInterpolator()).start()
            } else {
                lv.animate().alpha(0f).setDuration(LOGO_OUT_MS).setInterpolator(DecelerateInterpolator()).start()
                dots?.animate()?.alpha(1f)?.setDuration(LOGO_DOTS_FADE_MS)?.start()
            }
            ui.postDelayed(this, if (logoShown) LOGO_HOLD_MS else LOGO_DOTS_MS)
        }
    }

    /** Pasang (atau lepas) logo layanan di atas titik sesuai [kind]. Logo yang sama tidak dibuat ulang. */
    private fun syncLogo(kind: Int) {
        val res = logoRes(kind)
        if (res == 0 || deepMode || finished) { stopLogo(); return }
        if (logoView?.tag == res) return
        stopLogo()
        val lv = ImageView(act).apply {
            setImageResource(res)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0f
            tag = res
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // 21 dp: di rekaman acuan logo Gmail 35 x 27 px, sedangkan orbit titik (lebar penuh 19 dp) 32 px -> skala ~1,7 px/dp -> ~21 x 16 dp.
        iconSlot.addView(lv, FrameLayout.LayoutParams(act.dp(21), act.dp(21), Gravity.CENTER))
        logoView = lv
        logoShown = false
        ui.postDelayed(logoCycle, LOGO_FIRST_MS)
    }

    private fun stopLogo() {
        ui.removeCallbacks(logoCycle)
        logoView?.let { it.animate().cancel(); iconSlot.removeView(it) }
        logoView = null
        logoShown = false
        iconSlot.getChildAt(0)?.let { it.animate().cancel(); it.alpha = 1f }
    }

    private fun startBreathe() {
        breatheAnim?.cancel()
        breatheAnim = ObjectAnimator.ofPropertyValuesHolder(
            iconSlot,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.08f)
        ).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopBreathe() {
        breatheAnim?.cancel()
        breatheAnim = null
        iconSlot.scaleX = 1f
        iconSlot.scaleY = 1f
    }

    // ---------------------------------------------------------- Siklus

    private fun setIconSlotCompact(compact: Boolean) {
        val lp = (iconSlot.layoutParams as? LinearLayout.LayoutParams) ?: return
        if (compact) {
            lp.width = act.dp(20)
            lp.height = act.dp(20)
            lp.marginStart = act.dp(4)
            lp.marginEnd = act.dp(6)
        } else {
            lp.width = act.dp(40)
            lp.height = act.dp(32)
            lp.marginStart = 0
            lp.marginEnd = 0
        }
        iconSlot.layoutParams = lp
    }

    /** Putaran baru: kosongkan semuanya dan mulai dari "berpikir". */
    fun begin(firstText: String) {
        ui.removeCallbacks(release)
        cancelNarration()
        gate = newGate()
        kindByText.clear()
        finished = false
        idleKind = KIND_DOTS
        setIconSlotCompact(false)
        iconKind = Int.MIN_VALUE
        clearLabels()
        stopLogo()
        iconSlot.removeAllViews()
        setExpanded(open = false, animate = false)
        terminal.reset()
        resetDeep()
        setLine(firstText, KIND_DOTS)
    }

    /** Hasil akhir: ikon berhenti, teks diam, panah tetap bisa dibuka untuk meninjau langkah. */
    fun finish(text: String, kind: Int, tint: Int) {
        ui.removeCallbacks(release)
        cancelNarration()
        finished = true
        setIconSlotCompact(true)
        resetDeep()
        swapText(tidyLine(text, 90), kind, tint, working = false)
    }

    fun reset() {
        ui.removeCallbacks(release)
        cancelNarration()
        gate = newGate()
        kindByText.clear()
        finished = false
        idleKind = KIND_DOTS
        setIconSlotCompact(false)
        iconKind = Int.MIN_VALUE
        clearLabels()
        stopBreathe()
        stopLogo()
        iconSlot.removeAllViews()
        setExpanded(open = false, animate = false)
        terminal.reset()
        resetDeep()
    }

    private fun clearLabels() {
        hostAnim?.cancel()
        for (l in arrayOf(labelA, labelB)) {
            l.animate().cancel()
            l.setShimmer(false)
            l.alpha = 0f
            l.text = ""
        }
        label = labelA
        textHost.layoutParams = textHost.layoutParams.also { it.width = WRAP }
    }

    fun render(groups: List<AgentStepGroup>) = terminal.render(groups)

    // ---------------------------------------------------------- Buka / tutup terminal

    private fun toggle() {
        head.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        setExpanded(open = !expanded, animate = true)
    }

    private fun setExpanded(open: Boolean, animate: Boolean) {
        if (open == expanded && !(open && terminal.visibility != View.VISIBLE)) {
            if (!animate && !open) terminal.visibility = View.GONE
            return
        }
        expanded = open
        chevron.animate().cancel()
        if (animate) chevron.animate().rotation(if (open) 90f else 0f).setDuration(220).start()
        else chevron.rotation = if (open) 90f else 0f
        heightAnim?.cancel()
        heightAnim = null
        terminal.animate().cancel()
        if (open) terminal.onOpened() else terminal.onClosed()
        if (!animate) {
            terminal.alpha = 1f
            terminal.layoutParams.height = WRAP
            terminal.visibility = if (open) View.VISIBLE else View.GONE
            terminal.requestLayout()
            return
        }
        val availW = (width - paddingLeft - paddingRight).coerceAtLeast(act.dp(200))
        terminal.visibility = View.VISIBLE
        terminal.measure(
            View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val full = terminal.measuredHeight
        val from = if (open) 0 else terminal.height
        val to = if (open) full else 0
        terminal.alpha = if (open) 0f else 1f
        terminal.layoutParams.height = from
        terminal.requestLayout()
        terminal.animate().alpha(if (open) 1f else 0f).setDuration(240).start()
        val a = ValueAnimator.ofInt(from, to)
        heightAnim = a
        a.duration = 280
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            terminal.layoutParams.height = it.animatedValue as Int
            terminal.requestLayout()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (heightAnim !== a) return
                heightAnim = null
                terminal.layoutParams.height = WRAP
                if (open) {
                    terminal.alpha = 1f
                    terminal.requestLayout()
                    act.scrollDown()
                } else {
                    terminal.visibility = View.GONE
                    terminal.requestLayout()
                }
            }
        })
        a.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (breathing) startBreathe()
        // Baris dilepas lalu dipasang lagi (mis. zona aktivitas dipindah di dalam chat): saat dilepas semua jadwal ikut mati,
        // sehingga siklus titik <-> logo berhenti padahal alatnya masih berjalan. Hidupkan lagi siklusnya.
        if (logoView != null && !finished && !deepMode) {
            ui.removeCallbacks(logoCycle)
            logoShown = false
            logoView?.alpha = 0f
            iconSlot.getChildAt(0)?.alpha = 1f
            ui.postDelayed(logoCycle, LOGO_DOTS_MS)
        }
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null)
        cancelNarration()
        breatheAnim?.cancel()
        breatheAnim = null
        hostAnim?.cancel()
        hostAnim = null
        heightAnim?.cancel()
        heightAnim = null
        super.onDetachedFromWindow()
    }
}
