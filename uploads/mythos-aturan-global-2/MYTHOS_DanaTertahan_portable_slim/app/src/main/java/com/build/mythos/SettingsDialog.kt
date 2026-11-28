package com.build.mythos

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.net.URL

// Dialog Pengaturan (folder kerja, GitHub, mode baca-saja, mode otonom, konektor). Penyedia, kunci API, dan model ada di ApiKeyDialog.kt.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.showSettings() {
    val wdir = EditText(this).apply {
        hint = "Contoh: MYTHOS"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setText(prefs.getString("workdir", "MYTHOS"))
    }
    val ghToken = EditText(this).apply {
        hint = "Token GitHub"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val saved = when (val r = Secret.decrypt(prefs.getString("github_token", "") ?: "")) {
            is SecretDecryptResult.Success -> r.value
            else -> ""
        }
        setText(saved)
    }
    val ghRepo = EditText(this).apply {
        hint = "URL repository"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        // Pengaturan lama (pemilik + nama repo terpisah) otomatis ditampilkan sebagai link.
        val legacy = parseRepoLink(prefs.getString("github_owner", "").orEmpty() + "/" + prefs.getString("github_repo", "").orEmpty())?.url.orEmpty()
        setText(prefs.getString("github_repo_url", "").orEmpty().ifBlank { legacy })
    }
    val deapiKey = EditText(this).apply {
        hint = "Kunci deAPI (sk-dp-...)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val saved = when (val r = Secret.decrypt(prefs.getString("deapi_key", "") ?: "")) {
            is SecretDecryptResult.Success -> r.value
            else -> ""
        }
        setText(saved)
    }
    val extra = EditText(this).apply {
        hint = "Opsional"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 2
        gravity = Gravity.TOP
        setText(prefs.getString("extra", ""))
    }
    for (f in listOf(wdir, ghRepo, ghToken, deapiKey, extra)) styleField(f)

    /** Satu baris pengaturan: judul (+ keterangan opsional) + saklar. */
    fun toggleRow(title: String, hint: String = "", on: Boolean): Pair<View, Switch> {
        val sw = Switch(this).apply { isChecked = on }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = Typo.BODY; setTextColor(Palette.INK) })
            if (hint.isNotBlank()) {
                addView(TextView(context).apply { text = hint; textSize = Typo.CAPTION; setTextColor(Palette.INK_SOFT) })
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), 0, dp(10))
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
            setOnClickListener { sw.toggle() }
        }
        return row to sw
    }
    val (_, auto) = toggleRow("Setujui otomatis perintah baca", "", prefs.getBoolean("autoread", true))
    val (roRow, ro) = toggleRow("Mode hanya baca", "", prefs.getBoolean("readonly", false))
    val (autonomousRow, autonomous) = toggleRow("Mode otonom", "", prefs.getBoolean("autonomous", false))
    val (crossFailoverRow, crossFailover) = toggleRow(
        "Failover lintas penyedia AI",
        "Bawaan mati. Bila aktif, konfirmasi tetap diminta sebelum mengirim konteks ke vendor AI lain.",
        prefs.getBoolean("cross_provider_failover", false)
    )
    val (sandboxRow, sandbox) = toggleRow("Mode sandbox", "", prefs.getBoolean("sandbox", false))

    // Tempo tampilan jawaban: Santai, Normal, atau Cepat. Berlaku langsung pada jawaban berikutnya.
    var tempoKey = StreamTempo.byKey(prefs.getString(StreamTempo.PREF_KEY, null)).key
    val tempoChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
    fun refreshTempo() {
        for (i in 0 until tempoChips.childCount) {
            val chip = tempoChips.getChildAt(i) as TextView
            val on = chip.tag == tempoKey
            chip.setTextColor(if (on) android.graphics.Color.WHITE else Palette.INK)
            chip.background = rounded(if (on) Palette.BRAND else Palette.FIELD, 18)
        }
    }
    for (t in StreamTempo.ALL) {
        tempoChips.addView(TextView(this).apply {
            tag = t.key
            text = t.label
            textSize = Typo.BODY_S
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { tempoKey = t.key; refreshTempo() }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
    }
    val tempoRow = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(10), 0, dp(8))
        addView(TextView(context).apply { text = "Kecepatan jawaban"; textSize = Typo.BODY; setTextColor(Palette.INK) })
        addView(tempoChips)
    }
    refreshTempo()

    // Pengaturan Ukuran Font (5 langkah) dan Ketebalan Font (halus 0..100) dengan pratinjau langsung.
    val initialFontStep = prefs.getInt(PREF_FONT_SCALE_STEP, DEFAULT_FONT_SCALE_STEP)
    val initialWeightProg = prefs.getInt(PREF_FONT_WEIGHT_PROGRESS, DEFAULT_FONT_WEIGHT_PROGRESS)
    var selectedFontStep = initialFontStep
    var selectedWeightProg = initialWeightProg

    val fontSizeValTv = TextView(this).apply {
        text = fontScaleLabelForStep(selectedFontStep)
        textSize = Typo.BODY_S
        setTextColor(Palette.INK_SOFT)
    }
    val fontWeightValTv = TextView(this).apply {
        text = fontWeightLabelForProgress(selectedWeightProg)
        textSize = Typo.BODY_S
        setTextColor(Palette.INK_SOFT)
    }
    val fontPreviewTv = TextView(this).apply {
        text = "Contoh tampilan teks percakapan MYTHOS di layar HP."
        setTextColor(Palette.INK)
        setLineSpacing(0f, 1.35f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Palette.FIELD, 12)
    }
    fun updateFontPreview() {
        val ratio = fontScaleForStep(selectedFontStep) / activeFontScale.coerceAtLeast(0.5f)
        fontPreviewTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, ASSISTANT_TEXT_SP * ratio)
        fontPreviewTv.typeface = applyAppFontWeight(
            android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL),
            fontWeightForProgress(selectedWeightProg)
        )
    }
    updateFontPreview()

    val fontSizeHeader = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = "Ukuran Font"
            textSize = Typo.BODY_L
            setTextColor(Palette.INK)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(fontSizeValTv)
    }
    val fontSizeSlider = FontSliderView(
        this,
        maxValue = FONT_SCALE_STEPS.lastIndex,
        showTicks = true,
        initialValue = selectedFontStep
    ) { step ->
        selectedFontStep = step
        fontSizeValTv.text = fontScaleLabelForStep(step)
        updateFontPreview()
    }
    val fontWeightHeader = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = "Ketebalan Font"
            textSize = Typo.BODY_L
            setTextColor(Palette.INK)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(fontWeightValTv)
    }
    val fontWeightSlider = FontSliderView(
        this,
        maxValue = 100,
        showTicks = false,
        initialValue = selectedWeightProg
    ) { prog ->
        selectedWeightProg = prog
        fontWeightValTv.text = fontWeightLabelForProgress(prog)
        updateFontPreview()
    }
    val fontSection = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(14), dp(4), dp(8))
        addView(fontSizeHeader, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(fontSizeSlider, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        addView(fontWeightHeader, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        addView(fontWeightSlider, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        addView(fontPreviewTv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
    }

    /** Baris tautan: teks + panah, seluruh baris bisa diketuk. */
    fun linkRow(label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(null)
        setPadding(dp(4), dp(12), dp(4), dp(12))
        addView(TextView(context).apply { text = label; textSize = Typo.BODY; setTextColor(Palette.INK) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply { text = "›"; textSize = Typo.DISPLAY; setTextColor(Palette.INK_SOFT) })
        setOnClickListener { onClick() }
    }
    fun divider(): View = View(this).apply { setBackgroundColor(Palette.DIVIDER) }
    var dialog: Dialog? = null
    // Penyedia, kunci API, dan model kini ada di dialog "Hubungkan AI Anda" yang lebih ringkas; di sini cukup satu baris.
    val providerRow: View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(rounded(Palette.FIELD, 14))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "Penyedia AI"; textSize = Typo.BODY; setTextColor(Palette.INK) })
            addView(TextView(context).apply {
                val m = if (preset(prov).fmt == "cli") "" else pvOf(prov, "model").ifEmpty { preset(prov).model }
                text = ProviderCatalog.label(prov) + if (m.isNotBlank()) " · $m" else ""
                textSize = Typo.CAPTION; setTextColor(Palette.INK_SOFT); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply { text = "›"; textSize = Typo.DISPLAY; setTextColor(Palette.INK_SOFT) })
        setOnClickListener { dialog?.dismiss(); showApiKeyDialog() }
    }
    var advOpen = false
    val reportBtn = linkRow("Laporan masalah") { copyReport() }
    val setupBtn = linkRow("Siapkan Termux") { showSetup() }
    val syncTermuxBtn = linkRow("Sinkronisasi Termux") { showTermuxSettingsSync() }
    val connBtn = linkRow("Konektor") { showConnectorHub() }
    val assistBtn = linkRow("Asisten layar (panel di atas aplikasi lain)") { showAssistSetup() }
    val logBtn = linkRow("Catatan perintah") { startActivity(Intent(this, CommandLogActivity::class.java)) }
    // Opsi yang jarang dipakai disembunyikan di bawah satu tombol supaya layar Lanjutan tidak membingungkan.
    var moreOpen = false
    val more = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(sandboxRow)
        addView(linkRow("Hapus memori (${memoryCount()})") {
            AlertDialog.Builder(this@showSettings)
                .setTitle("Hapus memori obrolan?")
                .setMessage("Catatan yang dipakai model lokal sebagai bahan akan dihapus. Riwayat chat tidak ikut terhapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ -> clearMemory(); toast("Memori obrolan dihapus") }
                .show()
        })
    }
    val moreBtn = TextView(this).apply {
        text = "Opsi lain ▾"
        textSize = Typo.BODY_S
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(Palette.BRAND)
        setPadding(dp(4), dp(12), 0, dp(8))
        setOnClickListener {
            moreOpen = !moreOpen
            more.visibility = if (moreOpen) View.VISIBLE else View.GONE
            text = if (moreOpen) "Opsi lain ▴" else "Opsi lain ▾"
        }
    }
    val adv = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(fieldLabel("Folder kerja"))
        addView(wdir)
        addView(fieldLabel("GitHub"))
        addView(ghRepo)
        addView(ghToken, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        addView(fieldLabel("Gambar AI (deAPI / FLUX)"))
        addView(deapiKey)
        // "Setujui otomatis perintah baca" (autoRow) tidak ditampilkan: sudah tercakup "Konfirmasi seperlunya".
        // Nilainya tetap dibaca dan disimpan (bawaan aktif), jadi perilaku tidak berubah.
        addView(tempoRow)
        addView(roRow)
        addView(autonomousRow)
        addView(crossFailoverRow)
        addView(moreBtn)
        addView(more)
        addView(fieldLabel("Instruksi tambahan"))
        addView(extra)
    }
    val advBtn = TextView(this).apply {
        text = "Lanjutan ▾"
        textSize = Typo.BODY_S
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(Palette.BRAND)
        setPadding(dp(4), dp(14), 0, dp(8))
    }
    fun refresh() {
        adv.visibility = if (advOpen) View.VISIBLE else View.GONE
        advBtn.text = if (advOpen) "Lanjutan ▴" else "Lanjutan ▾"
    }
    advBtn.setOnClickListener { advOpen = !advOpen; refresh() }
    var checkingWorkdir = false
    fun save(workdirConfirmed: Boolean = false) {
        val selectedWorkdir = WorkspacePaths.normalizeSetting(wdir.text.toString())
        val workdirError = WorkspacePaths.validationError(wdir.text.toString(), termuxHome)
        if (selectedWorkdir == null || workdirError != null) {
            wdir.error = workdirError ?: "Folder kerja tidak valid"
            wdir.requestFocus()
            return
        }
        val currentWorkdir = WorkspacePaths.normalizeSetting(prefs.getString("workdir", "MYTHOS") ?: "MYTHOS")
        if (!workdirConfirmed && selectedWorkdir != currentWorkdir) {
            if (checkingWorkdir) return
            checkingWorkdir = true
            wdir.isEnabled = false
            toast("Menyiapkan folder kerja dan memeriksa repository Git…")
            val candidateRoot = WorkspacePaths.resolve(selectedWorkdir, termuxHome)
            val termuxUsr = termuxHome.substringBeforeLast("/home", termuxHome).trimEnd('/') + "/usr"
            val prepareCommand = WorkspacePaths.prepareAndProbeCommand(
                workspaceRoot = candidateRoot,
                termuxHome = termuxHome,
                referenceRoot = referenceRoot(),
                termuxUsr = termuxUsr,
                requireHomeContainment = !selectedWorkdir.startsWith("/"),
                quote = { sh(it) }
            )
            bg {
                val probe = try {
                    termux(prepareCommand, limit = 1200, timeoutSec = 20, useWorkdir = false, killable = false)
                } catch (e: Exception) { "workdir prepare failed: ${e.message}" }
                runOnUiThread {
                    checkingWorkdir = false
                    wdir.isEnabled = true
                    if (dialog?.isShowing != true) return@runOnUiThread
                    val readyPath = probe.lineSequence()
                        .firstOrNull { it.startsWith("MYTHOS_WORKDIR_READY=") }
                        ?.substringAfter('=')?.trim().orEmpty()
                    val gitRoot = probe.lineSequence()
                        .firstOrNull { it.startsWith("MYTHOS_WORKDIR_IN_GIT_REPO=") }
                        ?.substringAfter('=')?.trim().orEmpty()
                    val outsideGit = probe.lineSequence().any { it.startsWith("MYTHOS_WORKDIR_NOT_IN_GIT_REPO=") }
                    when {
                        !probe.startsWith("exit=0") || readyPath.isBlank() -> {
                            val body = probe.substringAfter('\n', probe).trim().take(1200)
                            val reason = when {
                                probe.contains("MYTHOS_WORKDIR_OUTSIDE_HOME") -> "Path relatif mengarah ke luar HOME Termux, kemungkinan melalui symlink. Pilih folder di dalam HOME atau gunakan path absolut secara sengaja."
                                probe.contains("MYTHOS_WORKDIR_REFERENCE_FORBIDDEN") -> "Folder kerja mengarah ke MYTHOS/reference yang dilindungi hanya-baca."
                                probe.contains("MYTHOS_WORKDIR_USR_FORBIDDEN") -> "Folder kerja tidak boleh berada di direktori sistem Termux (/usr)."
                                probe.contains("MYTHOS_WORKDIR_ROOT_FORBIDDEN") -> "Root filesystem '/' tidak boleh dijadikan folder kerja."
                                probe.contains("MYTHOS_WORKDIR_CREATE_FAILED") -> "Termux gagal membuat folder. Periksa izin dan pastikan path bukan file."
                                probe.contains("MYTHOS_WORKDIR_REALPATH_UNAVAILABLE") -> "Perintah realpath tidak tersedia di Termux. Jalankan pembaruan paket Termux lalu coba lagi."
                                else -> "Termux tidak dapat menyiapkan atau memverifikasi folder kerja."
                            }
                            AlertDialog.Builder(this)
                                .setTitle("Folder kerja tidak dapat disiapkan")
                                .setMessage(reason + if (body.isNotBlank()) "\n\nDetail: $body" else "")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        gitRoot.isNotBlank() -> {
                            AlertDialog.Builder(this)
                                .setTitle("Folder kerja berada di repository Git")
                                .setMessage(
                                    "Folder kerja berhasil dibuat/ditemukan:\n$readyPath\n\n" +
                                        "Repository Git yang menaunginya:\n$gitRoot\n\n" +
                                        "Peringatan ini berasal dari repository Git lokal dan tidak bergantung pada URL GitHub. " +
                                        "Agen dapat membuat atau mengubah file di repository tersebut sesuai izin aplikasi. " +
                                        "Gunakan folder lain bila kamu ingin memisahkan pekerjaan dari repository ini.\n\nTetap gunakan folder ini?"
                                )
                                .setNegativeButton("Batal", null)
                                .setPositiveButton("Tetap gunakan") { _, _ -> save(workdirConfirmed = true) }
                                .show()
                        }
                        outsideGit -> save(workdirConfirmed = true)
                        else -> {
                            AlertDialog.Builder(this)
                                .setTitle("Status repository belum diketahui")
                                .setMessage(
                                    "Folder kerja sudah disiapkan di:\n$readyPath\n\nNamun Termux tidak mengembalikan hasil pemeriksaan Git yang valid. Pengaturan belum disimpan. Periksa koneksi Termux lalu coba lagi."
                                )
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
            return
        }
        dialog?.dismiss()
        val wantsAutonomous = autonomous.isChecked
        val repoText = ghRepo.text.toString().trim()
        val repoRef = parseRepoLink(repoText)
        if (repoText.isNotEmpty() && repoRef == null) toast("Link repository GitHub tidak valid, tidak disimpan")
        val ed = prefs.edit()
            .putString("extra", extra.text.toString().trim())
            .putString("workdir", selectedWorkdir)
            .putString("github_repo_url", repoRef?.url.orEmpty())
            .remove("github_owner")
            .remove("github_repo")
            .remove("github_private")
            .putBoolean("autoread", auto.isChecked)
            .putBoolean("readonly", ro.isChecked)
            .putBoolean("cross_provider_failover", crossFailover.isChecked)
            .putBoolean("sandbox", sandbox.isChecked)
            .putString(StreamTempo.PREF_KEY, tempoKey)
            .putInt(PREF_FONT_SCALE_STEP, selectedFontStep)
            .putInt(PREF_FONT_WEIGHT_PROGRESS, selectedWeightProg)
        StreamTempo.current = StreamTempo.byKey(tempoKey)
        val rawGithubToken = ghToken.text.toString().trim()
        if (rawGithubToken.isBlank()) {
            ed.remove("github_token")
        } else {
            val encryptedGithubToken = Secret.encrypt(rawGithubToken)
            if (encryptedGithubToken.isSuccess) {
                ed.putString("github_token", encryptedGithubToken.getOrThrow())
            } else {
                toast("Token GitHub tidak dapat diamankan. Token tidak disimpan.")
            }
        }
        val rawDeapi = deapiKey.text.toString().trim()
        if (rawDeapi.isBlank()) {
            ed.remove("deapi_key")
        } else {
            val encryptedDeapi = Secret.encrypt(rawDeapi)
            if (encryptedDeapi.isSuccess) {
                ed.putString("deapi_key", encryptedDeapi.getOrThrow())
            } else {
                toast("Kunci deAPI tidak dapat diamankan. Kunci tidak disimpan.")
            }
        }
        ed.apply()
        bg { try { ensureWorkspaceLayout() } catch (ignored: Exception) { logQuiet("SettingsDialog.kt", ignored) } }
        if (prefs.getBoolean("termux_sync_enabled", false)) {
            bg {
                val syncResult = try { pushTermuxSettings() } catch (e: Exception) { "Gagal: ${e.message}" }
                if (!syncResult.startsWith("Berhasil:")) {
                    runOnUiThread { toast("Sinkronisasi Termux gagal. Buka Pengaturan > Sinkronisasi dua arah Termux untuk memeriksa.") }
                }
            }
        }
        val fontChanged = selectedFontStep != initialFontStep || selectedWeightProg != initialWeightProg
        fun applyFontChangeNow(): Boolean {
            if (!fontChanged) return false
            activeFontScale = fontScaleForStep(selectedFontStep)
            activeFontWeight = fontWeightForProgress(selectedWeightProg)
            resources.enforceLockedDisplayMetrics()
            if (canRecreateForTheme()) {
                recreate()
                return true
            }
            toast("Pengaturan font disimpan; akan diterapkan penuh setelah tugas selesai.")
            return false
        }
        if (!wantsAutonomous || prefs.getBoolean("autonomous_ack", false)) {
            prefs.edit().putBoolean("autonomous", wantsAutonomous).apply()
            if (applyFontChangeNow()) return
        } else {
            AlertDialog.Builder(this)
                .setTitle("Aktifkan mode otonom?")
                .setMessage(
                    "Dalam mode otonom, AI dapat menjalankan perintah shell, menulis file, " +
                        "dan memulai pemasangan APK tanpa dialog persetujuan. Aktifkan hanya " +
                        "jika Anda memahami dan menerima risikonya."
                )
                .setNegativeButton("Batal") { _, _ ->
                    prefs.edit().putBoolean("autonomous", false).apply()
                }
                .setPositiveButton("Saya paham, aktifkan") { _, _ ->
                    prefs.edit()
                        .putBoolean("autonomous", true)
                        .putBoolean("autonomous_ack", true)
                        .apply()
                }
                .setOnDismissListener { applyFontChangeNow() }
                .show()
        }
        updateModelLabel()
        autoSetupModels()
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(providerRow, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(fontSection, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(10) })
        addView(connBtn)
        addView(assistBtn)
        addView(syncTermuxBtn)
        addView(logBtn)
        addView(reportBtn)
        addView(setupBtn)
        addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)))
        addView(advBtn)
        addView(adv)
    }
    val footer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(softButton("Batal") { dialog?.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(filledButton("Simpan") { save() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
    }
    dialog = showSheet("Pengaturan", null, box, footer)
}
