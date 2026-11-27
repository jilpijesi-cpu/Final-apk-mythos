package com.build.mythos

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.content.res.Configuration
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    internal var tts: TextToSpeech? = null

    internal val presets = BASE_PRESETS + CLI_LOGINS.map { Preset(it.name, "cli", it.run, "") } + Preset("CLI Termux", "cli", "gemini -p {prompt}", "")

    @Volatile internal var prov = DEFAULT_PROVIDER
    internal val activeProviderOverride = ThreadLocal<Pair<String, String?>?>()
    internal var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    internal var offlineBanner: android.view.View? = null
    internal var bootHealthRunnable: Runnable? = null

    @Volatile internal var agentState: AgentState = AgentState.Idle
        set(v) { field = v; notifyDeepStage() }
    /** True selama tahap berpikir mendalam berjalan (tahap "Analyzing"). */
    @Volatile internal var deepAnalyzing = false
    @Volatile internal var agentTaskId = ""

    @Volatile internal var keyWarning: String? = null

    /** Model cadangan sementara selama satu tugas (diisi callModelRetry saat model utama sibuk/kuota habis). */
    @Volatile internal var modelOverride: String? = null

    /** Penyedia cadangan sementara selama satu tugas (memakai API key lain yang sudah terdaftar). Null = pakai penyedia utama. */
    @Volatile internal var fallbackProv: String? = null

    /** Model yang dipakai pada [fallbackProv] (model penyedia lain, bukan pilihan di Pengaturan). */
    @Volatile internal var fallbackModel: String? = null

    /** True selama peralihan otomatis: percobaan ulang dipersingkat agar pindah ke AI lain lebih cepat. */
    @Volatile internal var fastFail = false

    /** Kartu peralihan yang sedang tampil (null bila tidak ada episode berjalan). */
    internal var failoverView: FailoverView? = null

    /** Cache daftar model per penyedia, dan model yang gagal selama tugas ini (kuota habis / ditolak). Dikosongkan tiap tugas baru. */
    internal val poolCache: MutableMap<String, ProviderPool?> = java.util.Collections.synchronizedMap(HashMap())
    internal val hardFailed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    internal fun resetFailover() {
        modelOverride = null
        fallbackProv = null
        fallbackModel = null
        fastFail = false
        poolCache.clear()
        hardFailed.clear()
        if (::modelLabel.isInitialized) updateModelLabel()
    }

    internal val desk =
        "Kalimat singkat (maks 8 kata) berbahasa Indonesia, diawali kata kerja, yang menyebut SASARAN KONKRET langkah ini " +
        "(nama file, fungsi, atau tujuan) sesuai perintah yang kamu jalankan sekarang, contoh: 'Membaca logika failover di ModelCall.kt', " +
        "'Mencari teks penolakan gambar', 'Memperbaiki error build'. Jangan kalimat umum seperti 'Menjalankan perintah'"

    internal lateinit var prefs: SharedPreferences

    internal val hist = ArrayList<Hist>()

    internal lateinit var chatBox: LinearLayout

    internal lateinit var scroll: ScrollView
    internal lateinit var scrollDownBtn: View
    @Volatile internal var userScrolledUp = false
    /** Pengikut gulir otomatis sedang berjalan / sedang menggeser layar (bukan gulir pengguna). */
    internal var followRunning = false
    internal var autoScrolling = false

    internal lateinit var emptyState: View

    // Chat samaran dan bar atas
    internal var incognito = false
    @Volatile internal var incognitoWriteWarned = false   // peringatan "file ditulis ke folder kerja" sudah tampil di sesi samaran ini
    internal lateinit var topTitle: TextView
    internal fun topTitleOrNull(): TextView? = if (::topTitle.isInitialized) topTitle else null
    internal lateinit var ghostCircleBg: GradientDrawable
    internal lateinit var ghostIcon: IconView
    internal lateinit var emptyNormal: View
    internal lateinit var emptyIncognito: View
    internal var cameraUri: Uri? = null

    internal lateinit var modelLabel: TextView

    internal lateinit var input: EditText
    internal fun inputOrNull(): EditText? = if (::input.isInitialized) input else null
    internal var leakInputIndicator: TextView? = null

    internal lateinit var actionIcon: GlyphView

    internal lateinit var actionLabel: TextView

    /** Logo penyedia AI aktif di baris ikon kolom input; diketuk untuk berganti AI (diperbarui refreshProviderBadge). */
    internal lateinit var providerBadge: BadgeView

    internal fun providerBadgeOrNull(): BadgeView? = if (::providerBadge.isInitialized) providerBadge else null

    internal lateinit var actionBtn: View

    internal lateinit var actionBg: GradientDrawable

    // Baris status tunggal (ikon + teks berkilau + tanda >)
    internal lateinit var statusRoot: LinearLayout

    internal lateinit var statusLead: FrameLayout

    internal lateinit var statusTitle: ShimmerText
    // Satu baris status dinamis: hanya merotasi penjelasan yang sesuai fase aktif.
    internal val statusTickerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal var statusTickerTask: Runnable? = null
    internal var statusBaseTitle = ""

    internal lateinit var statusChevron: IconView

    internal lateinit var statusDetail: TextView

    internal var leadKind = -2

    internal var detailOpen = false

    @Volatile internal var curKind = KIND_DOTS

    @Volatile internal var curTitle = ""

    /** Judul langkah alat terakhir; dipakai teks status antar-langkah ("Membaca hasil: ..."). */
    @Volatile internal var lastStepTitle = ""

    /** Nomor langkah alat yang sedang berjalan; membatalkan pembaruan teks tertunda dari langkah sebelumnya. */
    @Volatile internal var stepSeq = 0

    @Volatile internal var curDetail = ""

    @Volatile internal var curQuiet = false

    /** Panel aktivitas AI tetap di bawah header: wadah kartu (maks 2) + area teks tengah / laporan akhir. */
    internal lateinit var zone: ActivityZone

    /** Fakta nyata putaran agen yang sedang/baru berjalan; sumber laporan akhir. */
    internal val runLog = RunLog()

    /** Langkah alat yang sedang berjalan; dipakai untuk menandai "menunggu izin". */
    @Volatile internal var activeStep: StepCard? = null

    /** Sumber StateFlow untuk daftar langkah live yang dapat dibuka dari baris aktivitas compact. */
    internal val agentSteps = AgentStepStore()

    @Volatile internal var busy = false

    internal val reqCode = java.util.concurrent.atomic.AtomicInteger(0)

    internal lateinit var pillView: View

    /** Lebar kolom isi maksimum (dp): di tablet / lanskap isi chat tetap nyaman dibaca, di tengah layar. */
    private val maxColumnDp = 760

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.withLockedDisplay())
    }

    internal fun applyResponsive() {
        if (!::chatBox.isInitialized) return
        resources.enforceLockedDisplayMetrics()
        val wDp = resources.configuration.screenWidthDp
        val compactMobile = wDp <= 420
        // Optimasi mobile: margin lebih ramping di layar HP agar ruang baca chat & baris tombol lebih lega.
        val side = if (compactMobile) 14 else maxOf(16, (wDp - maxColumnDp) / 2 - 4)
        // UI-02: pertahankan paddingBottom dinamis dari scroll layout listener agar posisi gulir chat tidak rusak.
        chatBox.setPadding(dp(side), dp(8), dp(side), maxOf(chatBox.paddingBottom, dp(16)))
        (pillView.layoutParams as? LinearLayout.LayoutParams)?.let {
            val pillSide = if (compactMobile) 10 else side
            val m = dp(pillSide)
            it.setMargins(m, dp(4), m, dp(if (compactMobile) 10 else 16))
            pillView.layoutParams = it
            // Tombol menu dan hantu di bilah atas ikut selebar kartu input: sisi luarnya sejajar.
            (topTitle.parent as? View)?.setPadding(m, dp(6), m, dp(6))
        }
        attachScroll.setPadding(dp(side), dp(4), dp(side), dp(4))
        // Tombol gulir ke bawah ikut kolom isi (bukan menempel di tepi layar) saat tablet/lanskap.
        (scrollDownBtn.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.setMargins(0, 0, dp(maxOf(16, side + 4)), dp(12))
            scrollDownBtn.layoutParams = it
        }
        if (::zone.isInitialized) zone.setPadding(dp(side), dp(6), dp(side), 0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val locked = lockDisplayConfiguration(newConfig)
        super.onConfigurationChanged(locked)
        resources.enforceLockedDisplayMetrics()
        // Tema sistem berganti (terang <-> gelap): bangun ulang tampilan supaya semua warna ikut. Riwayat chat sudah tersimpan
        // di ChatStore; saat agen sedang bekerja ditunda sampai selesai (onResume memeriksa lagi) agar tugas tidak terputus.
        if (themeStale(locked.uiMode) && canRecreateForTheme()) { recreate(); return }
        applyResponsive()
    }

    @Volatile internal var cancelled = false

    @Volatile internal var pendingInterrupt: String? = null
    @Volatile internal var voiceTurnPending = false

    @Volatile internal var allowAll = false
    /** True bila persetujuan terakhir gagal karena tidak ada jawaban (bukan penolakan eksplisit). Lihat deniedResult(). */
    @Volatile internal var lastDenyUnanswered = false
    @Volatile internal var lastApprovalMode = ApprovalMode.MANUAL // diisi confirm(), dibaca runTool() untuk catatan

    @Volatile internal var activeConn: HttpURLConnection? = null

    @Volatile internal var lastPartial = ""

    internal var worker: kotlinx.coroutines.Job? = null

    /** Pekerjaan impor lampiran yang sedang berjalan; dibatalkan saat Activity benar-benar selesai (lihat onDestroy). */
    internal val importJobs = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.Job>()

    /** Folder proyek Android yang benar-benar berhasil dibuat pada tugas aktif; tidak boleh ditebak ulang model. */
    @Volatile internal var activeStudioProjectFolder = ""

    internal var confirmDialog: AlertDialog? = null

    internal var streamText = ""
    internal val typewriterHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal var typewriterTarget = ""
    internal var typewriterShown = ""
    internal var typewriterRunnable: Runnable? = null
    internal var streamEnded = false
    internal var streamCopyAdded = false
    /** Codex memasok delta langsung; jangan menambahkan animasi ketik buatan di atas stream. */
    internal var streamDirectRendering = false
    @Volatile internal var codexStreamOutputStarted = false
    internal val codexActivityUi: CodexActivityUi by lazy { CodexActivityUi(this) }

    internal fun col(s: String) = Color.parseColor(s)

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    internal fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Deklarasi alat aplikasi yang dijembatani ke mesin cc: konektor aktif + dana tertahan + berangkas (dibaca ulang tiap permintaan). */
    internal val tools: JSONArray
        get() {
            val customs = connectorToolDecls()
            if (customs.isEmpty()) return baseTools
            val decls = JSONArray()
            val b = baseTools.getJSONObject(0).getJSONArray("functionDeclarations")
            for (i in 0 until b.length()) decls.put(b.getJSONObject(i))
            for (d in customs) decls.put(d)
            return JSONArray().put(JSONObject().put("functionDeclarations", decls))
        }

    internal val baseTools: JSONArray by lazy { buildBaseTools(desk) }

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncAppFontPrefs(this)
        resources.enforceLockedDisplayMetrics()
        applyTheme(this)   // Palette.dark harus siap sebelum tampilan apa pun dibangun
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("id", "ID")
        }
        initPrefsAndMigrations()

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.NAV
        window.applyBarAppearance(navLight = true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        val topBar = buildTopBar()
        val center = buildCenter()

        buildStatusRow()

        val pill = buildInputPill()

        zone = ActivityZone(this)
        root.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(buildOfflineBanner(), LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(16), 0, dp(16), dp(4)) })
        root.addView(center, LinearLayout.LayoutParams(MATCH, 0, 1f))
        attachRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        attachScroll = HorizontalScrollView(this).apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16), dp(4), dp(16), dp(4))
            addView(attachRow)
        }
        root.addView(attachScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        pillView = pill
        root.addView(pill, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(16), dp(8), dp(16), dp(16)) })

        val contentRoot = FrameLayout(this).apply {
            setBackgroundColor(Palette.BG)
            addView(ChatBackground(context), FrameLayout.LayoutParams(MATCH, MATCH))
            addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        applyFontWeightTree(contentRoot)
        setContentView(contentRoot)
        applyIncognitoLook()
        refreshLeakOsintLook()
        applyResponsive()
        restoreHistory()
        startNetworkMonitor()
        sweepOrphanImages()   // buang pratinjau gambar sisa Chat samaran yang tidak sempat dibersihkan
        startBootHealth()

        val perms = ArrayList<String>()
        perms.add("com.termux.permission.RUN_COMMAND")
        if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.POST_NOTIFICATIONS")
        requestPermissions(perms.toTypedArray(), 1)
        if (!prefs.getBoolean("setup_seen", false)) {
            prefs.edit().putBoolean("setup_seen", true).apply()
            showSetup()
        } else if (needsKey() && cfgKey().isEmpty()) {
            showApiKeyDialog()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GOOGLE_ACCOUNT_PICKER_REQUEST_CODE) {
            handleGoogleAccountPickerResult(resultCode, data)
            return
        }
        if (requestCode == GOOGLE_AUTH_REQUEST_CODE) {
            handleGoogleAuthorizationResult(resultCode, data)
            return
        }
        handleActivityResult(requestCode, resultCode, data)
    }

    /** Dipanggil (thread UI) setelah login Google selesai, agar layar yang terbuka bisa memperbarui statusnya. */
    internal var googleLoginDone: ((String) -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    @Volatile internal var pendingImport: Uri? = null
    internal val attachments = ArrayList<Attachment>()
    internal lateinit var attachScroll: HorizontalScrollView
    internal lateinit var attachRow: LinearLayout

    // ------------------------------------------------ Balasan AI: streaming + markdown

    internal var streamBox: LinearLayout? = null

    // ------------------------------------------------ Siapkan Termux

    internal var setupRecheck: (() -> Unit)? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        handlePermissionsResult(requestCode, grantResults)
    }

    // ------------------------------------------------ Kartu langkah (baca / tulis / build)

    /** Buat satu baris langkah di kartu Aktivitas Agen. Panggil di thread UI. */
    /** Kartu langkah tanpa baris tampilan: alat jembatan (agen cc) memakai pelaksana jalur lama tanpa menggambar kartu kedua. */
    internal fun silentStepCard(tool: String = ""): StepCard = StepCard(null, tool)

    internal fun newStepCard(
        title: String, body: String, icon: Int = IconView.BLOB_TERM, tool: String = "", target: String = ""
    ): StepCard {
        val row = feedForNewStep().addStep(title, body, icon)
        val type = when (tool) {
            "run_command" -> if (body.startsWith("$ ")) AgentStepType.COMMAND else AgentStepType.READ_FILE
            "build_apk" -> AgentStepType.COMMAND
            "write_file", "validate_project", "self_audit", "self_upgrade", "berangkas_baca", "berangkas_salin", "cari_web", "baca_web", "buat_gambar", "edit_gambar", "cari_leakosint", "create_android_project", "create_tool", "delete_tool" -> AgentStepType.TOOL
            in CONNECTOR_TOOL_NAMES -> AgentStepType.TOOL
            in DANA_TOOL_NAMES -> AgentStepType.TOOL
            else -> AgentStepType.THINKING
        }
        val step = AgentStep(
            type = type,
            title = title,
            fullCommand = body,
            status = AgentStepStatus.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        agentSteps.add(step)
        zone.renderSteps(agentSteps.groups.value)
        return StepCard(row, tool, title, target, step.id)
    }

    /** Pegangan satu langkah di feed: hasil tool memperbarui barisnya (status, waktu, detail). */
    internal inner class StepCard(
        private val row: FeedRow?,
        private val tool: String = "",
        private val title: String = "",
        private val target: String = "",
        private val stepId: String = ""
    ) {
        private var recorded = false

        fun finish(ok: Boolean, note: String = "", body: CharSequence? = null, state: StepState? = null) {
            val st = state ?: if (ok) StepState.OK else StepState.FAIL
            // Catat fakta langkah untuk laporan akhir (sekali saja per langkah; RunLog aman lintas-thread).
            if (!recorded && row != null) {
                recorded = true
                runLog.record(StepRecord(tool, title, target, row.kind, st, System.currentTimeMillis() - row.startedAt))
            }
            if (stepId.isNotBlank()) agentSteps.finish(stepId, st == StepState.OK, body?.toString())
            runOnUiThread {
                row?.finish(st, note, body)
                zone.renderSteps(agentSteps.groups.value)
            }
        }

        fun setWaiting(w: Boolean) {
            if (stepId.isNotBlank()) agentSteps.update(stepId) { it.copy(status = if (w) AgentStepStatus.WAITING else AgentStepStatus.RUNNING) }
            runOnUiThread {
                row?.setWaiting(w)
                zone.renderSteps(agentSteps.groups.value)
            }
        }

        /** Keluaran sebagian dari perintah yang sedang berjalan (tampil real-time di kartu). */
        fun liveOutput(text: String) {
            if (stepId.isNotBlank()) agentSteps.update(stepId) { it.copy(output = AgentStepStore.truncateOutput(text)) }
            runOnUiThread {
                row?.setLive(text)
                zone.renderSteps(agentSteps.groups.value)
            }
        }
    }

    // ------------------------------------------------ Chip saran setelah balasan

    internal var chipsRow: View? = null
    internal var standbyRow: View? = null

    @Volatile internal var sawBuild = false

    @Volatile internal var sawFail = false
    @Volatile internal var taskBrief = ""

    /** Hasil tahap Penalaran mendalam untuk tugas yang sedang berjalan. */
    @Volatile internal var deepNotes = ""

    /** Tahap berpikir Penalaran mendalam sudah dijalankan untuk tugas ini (agar tidak diulang tiap putaran). */
    @Volatile internal var deepStageDone = false

    internal lateinit var deepBtn: View

    internal fun themeStale(uiMode: Int = resources.configuration.uiMode) = isNightMode(uiMode) != Palette.dark

    /** Bangun ulang hanya saat aman: tidak ada tugas agen berjalan dan bukan Chat samaran (yang hilang bila Activity dibuat ulang). */
    internal fun canRecreateForTheme() = !busy && !incognito

    override fun onResume() {
        super.onResume()
        syncAppFontPrefs(this)
        resources.enforceLockedDisplayMetrics()
        if (themeStale() && canRecreateForTheme()) { recreate(); return }
        onLoginRefresh?.invoke()
        updateOfflineBanner()
        drainOfflineQueue()
        autoSetupModels(silent = true) // segarkan daftar model bila cache uji sudah lewat 24 jam
        retryUnavailableModels() // uji ulang model tersembunyi/sibuk (diam-diam, paling cepat sejam sekali)
        restoreMemoryFromMirrorOnce() // pulihkan memori obrolan dari cadangan Termux (sekali per proses)
        importOldChatsOnce() // isi memori obrolan dari chat lama (sekali)
        mirrorMemoryAsync() // cadangkan bila ada perubahan
    }

    override fun onDestroy() {
        stopNetworkMonitor()
        typewriterHandler.removeCallbacksAndMessages(null)
        typewriterRunnable = null
        statusTickerHandler.removeCallbacksAndMessages(null)
        bootHealthRunnable?.let { window.decorView.removeCallbacks(it) }
        bootHealthRunnable = null
        if (::zone.isInitialized) zone.feed.clear()
        confirmDialog?.dismiss()
        confirmDialog = null
        // F-07: saat perubahan konfigurasi (rotasi), biarkan impor berjalan selesai; batalkan & bersihkan hanya saat Activity benar-benar ditutup.
        if (isFinishing || !isChangingConfigurations) {
            importJobs.forEach { it.cancel() }
            importJobs.clear()
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
        releaseWaits()
        if (isFinishing) {
            cancelled = true
            try { activeConn?.disconnect() } catch (e: Exception) { logQuiet("MainActivity.kt", e) }
            worker?.cancel()
            try { stopService(Intent(this, AgentService::class.java)) } catch (e: Exception) { logQuiet("MainActivity.kt", e) }
        }
        super.onDestroy()
    }

    // ------------------------------------------------ Persetujuan otomatis untuk perintah baca saja

    @Volatile internal var ctxOverride: List<Hist>? = null

    @Volatile internal var sysOverride: String? = null

    /** true selama panggilan terisolasi yang tidak boleh memanggil alat (tahap berpikir Penalaran mendalam). */
    @Volatile internal var noToolsCall = false

    /** True selama giliran ini lewat jalur sapaan/obrolan ringan (LightChat.kt): tahap memahami dan penalaran mendalam dilewati. */
    @Volatile internal var lightChat = false

    @Volatile internal var summaryText = ""

    @Volatile internal var sumUpto = 0

    internal var compactSkipUntil = 0

    /** Batas konteks hasil pelajaran dari error 413, per "penyedia/model". Kosong = pakai [CTX_BUDGET_MAX]. */
    internal val ctxCaps = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Ukuran (karakter) riwayat pada permintaan terakhir, dipakai untuk menghitung pemangkasan setelah 413. */
    @Volatile internal var lastCtxChars = 0

    internal val ctxBudget: Int get() = effectiveProv().let { p -> ctxCaps["$p/${cfgModel()}"] ?: defaultCtxBudget(p) }

    internal val retryable = setOf(408, 429, 500, 502, 503, 504, 529)

    /** True setelah koneksi streaming berhasil dibuka; dipakai membedakan putus di tengah stream dari gagal konek. */
    @Volatile internal var streamOpen = false

    // ---- CLI di Termux (gemini, claude, codex, dll). Tanpa memori percakapan antar pesan.
    /** Template CLI yang sudah disetujui "YA, SELALU"; berlaku sampai percakapan dihapus atau template diubah. */
    @Volatile internal var cliAllowedTpl: String? = null

    /** Diisi dialog Pengaturan selagi terbuka: dipanggil saat pengguna kembali ke aplikasi (mis. selesai login di browser). */
    @Volatile internal var onLoginRefresh: (() -> Unit)? = null

    internal val termuxHome = WorkspacePaths.TERMUX_HOME

    internal val termuxTmp = WorkspacePaths.TERMUX_TMP
}
