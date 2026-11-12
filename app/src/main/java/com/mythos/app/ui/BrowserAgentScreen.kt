package com.mythos.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import com.mythos.app.agent.AutoAction
import com.mythos.app.agent.BrowserActionGuard
import com.mythos.app.agent.BrowserAutopilot
import com.mythos.app.agent.BrowserCommand
import com.mythos.app.agent.BrowserDomainPolicy
import com.mythos.app.agent.BrowserInstruction
import com.mythos.app.agent.TaskRoute
import com.mythos.app.agent.FormPlanHistory
import com.mythos.app.agent.FormPlanResult
import com.mythos.app.agent.FormPlanner
import com.mythos.app.agent.FormStep
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import android.webkit.ValueCallback

/** Memaksa lebar layout 980 px seperti Chrome \"Situs desktop\" (mengabaikan width=device-width milik situs). */
private const val DESKTOP_VIEWPORT_JS =
    "(function(){var m=document.querySelector('meta[name=viewport]');" +
    "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
    "m.setAttribute('content','width=980');})();"

/** Browser Agent dengan sesi login lokal, pembacaan halaman, pengisian field, dan approval untuk aksi berdampak. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserAgentScreen(
    onBack: () -> Unit,
    onAnalyze: (String) -> Unit = {},
    onPlan: suspend (system: String, user: String) -> String = { _, _ -> "" },
    /** Dipanggil saat pengguna menjalankan rencana: host halaman + isi yang akan diisi, untuk riwayat versi. */
    onPlanRun: (host: String, entries: List<FormPlanHistory.Entry>) -> Unit = { _, _ -> },
) {
    BackHandler(onBack = onBack)
    var startUrl by remember { mutableStateOf("https://kontak157.ojk.go.id/appkpublicportal/Home") }
    var address by remember { mutableStateOf(startUrl) }
    var task by remember { mutableStateOf("") }
    var pageText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Login sendiri di situs, lalu beri instruksi kepada agen.") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var approvalTitle by remember { mutableStateOf<String?>(null) }
    var approvalAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var plan by remember { mutableStateOf<FormPlanResult?>(null) }
    var planBusy by remember { mutableStateOf(false) }
    var autoRunning by remember { mutableStateOf(false) }
    val autoHistory = remember { mutableStateListOf<String>() }
    /** Langkah tempat mode otonom dijeda (login/pertanyaan agen); 0 = tidak ada jeda. Riwayat tetap disimpan untuk Lanjutkan. */
    var autoPausedAt by remember { mutableStateOf(0) }
    /** Host tempat mode otonom bekerja; berpindah ke host lain (tautan/redirect) butuh konfirmasi pengguna. */
    var autoHost by remember { mutableStateOf("") }
    val autoAcceptedHosts = remember { mutableStateListOf<String>() }
    var showLog by remember { mutableStateOf(false) }
    /** Tujuan agen otonom yang sedang berjalan/dijeda (tetap ada walau kolom perintah diubah untuk menjawab agen). */
    var autoGoal by remember { mutableStateOf("") }
    /** Pemantau live: langkah saat ini, fase kerja (membaca/menunggu model/mengisi/menunggu konfirmasi) dan aksi yang sedang dikerjakan. */
    var autoStepNow by remember { mutableStateOf(0) }
    var autoPhase by remember { mutableStateOf("") }
    var autoNow by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    /** Tanya-jawab ala chat tentang halaman yang sedang terbuka (tanpa mengubah formulir). */
    var answerText by remember { mutableStateOf("") }
    var answerBusy by remember { mutableStateOf(false) }
    /** Host di luar daftar tepercaya yang diizinkan pengguna selama sesi ini (cocok persis, tidak disimpan). */
    val allowedExtra = remember { mutableStateListOf<String>() }
    /** Bila dinyalakan manual oleh pengguna lewat chip "Semua situs", semua situs https boleh dibaca/diisi agen (bawaan: mati, mengikuti daftar tepercaya). */
    var allowAll by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableStateOf(100) }
    var showText by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Layar tidak boleh padam saat agen bekerja (proses agen ikut mati bila aplikasi dijeda).
    LaunchedEffect(autoRunning, webView) { webView?.keepScreenOn = autoRunning }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
        message = if (uris.isEmpty()) "Pemilihan lampiran dibatalkan." else "${uris.size} lampiran dipilih. Periksa form sebelum mengirim."
    }

    val context = LocalContext.current
    val formScript = remember { runCatching { context.assets.open("mythos_form.js").bufferedReader().use { it.readText() } }.getOrDefault("") }

    val untrustedMsg = "Halaman ini di luar daftar situs tepercaya, jadi agen tidak membaca atau mengisinya. Buka lewat kolom alamat dan izinkan bila Anda yakin."

    fun pageTrusted(): Boolean = BrowserDomainPolicy.isAllowed(webView?.url.orEmpty(), allowedExtra.toList(), allowAll)

    /** Situs tepercaya (ojk.go.id, bi.go.id) langsung dibuka; situs lain hanya setelah pengguna mengizinkan lewat dialog. */
    fun navigate(url: String) {
        val host = BrowserDomainPolicy.hostOf(url)
        if (host == null) { message = "Alamat ditolak: hanya https yang diizinkan."; return }
        if (BrowserDomainPolicy.isAllowed(url, allowedExtra.toList(), allowAll)) { startUrl = url; webView?.loadUrl(url); return }
        approvalTitle = "Situs di luar daftar tepercaya:\n$host\n\nAgen baru boleh membaca atau mengisi halaman ini bila Anda mengizinkan. Jangan izinkan bila Anda tidak mengenali situs ini. Buka dan izinkan?"
        approvalAction = { allowedExtra.add(host); startUrl = url; webView?.loadUrl(url) }
    }

    fun readPage(announce: Boolean = true) {
        if (!pageTrusted()) { if (announce) message = untrustedMsg; return }
        webView?.evaluateJavascript("(document.body && document.body.innerText) || ''") { raw ->
            pageText = (runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw.removeSurrounding("\""))
                .take(30_000)
            if (announce) message = "Halaman sudah dibaca."
        }
    }

    /** Menjalankan satu operasi form di halaman dan mengembalikan hasil JSON {ok,msg,...}. */
    fun callForm(args: JSONObject, onResult: (JSONObject) -> Unit) {
        val wv = webView
        if (wv == null || formScript.isBlank()) { message = "Halaman atau skrip form belum siap."; return }
        if (!pageTrusted()) { message = untrustedMsg; onResult(JSONObject().put("ok", false).put("msg", untrustedMsg)); return }
        wv.evaluateJavascript("$formScript\n;MythosForm.run(${args});") { raw ->
            val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            val obj = runCatching { JSONObject(text ?: "") }.getOrElse { JSONObject().put("ok", false).put("msg", "respons halaman tidak terbaca") }
            onResult(obj)
        }
    }

    fun report(r: JSONObject) {
        message = (if (r.optBoolean("ok")) "Berhasil: " else "Gagal: ") + r.optString("msg")
        readPage(announce = false)
    }

    /** Aksi berdampak selalu lewat dialog konfirmasi; aksi aman langsung jalan. */
    fun guarded(label: String, reason: String?, action: () -> Unit) {
        if (reason == null) { action(); return }
        approvalTitle = "$label\n\nAksi ini termasuk: $reason. Periksa halaman terlebih dahulu. Jalankan?"
        approvalAction = action
    }

    /** Menjalankan satu langkah rencana. Hanya isi/pilih/centang/radio/tanggal; klik tidak pernah ada di rencana. */
    fun execCommand(cmd: BrowserCommand, onDone: (JSONObject) -> Unit) {
        when (cmd) {
            is BrowserCommand.Fill -> callForm(JSONObject().put("op", "fill").put("label", cmd.label).put("value", cmd.value), onDone)
            is BrowserCommand.DateField -> callForm(JSONObject().put("op", "date").put("label", cmd.label).put("value", cmd.isoDate), onDone)
            is BrowserCommand.Check -> callForm(JSONObject().put("op", "check").put("label", cmd.label).put("checked", cmd.checked), onDone)
            is BrowserCommand.Radio -> callForm(JSONObject().put("op", "radio").put("label", cmd.label).put("value", cmd.value), onDone)
            is BrowserCommand.Choose -> callForm(JSONObject().put("op", "choose").put("label", cmd.label).put("value", cmd.value)) { r ->
                if (r.optBoolean("pending")) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        callForm(JSONObject().put("op", "pick").put("value", cmd.value), onDone)
                    }, 450)
                } else onDone(r)
            }
            else -> onDone(JSONObject().put("ok", false).put("msg", "aksi tidak didukung dalam rencana"))
        }
    }

    /** Jalankan rencana berurutan. Langkah bertanda guard berhenti di dialog konfirmasi; Batal menghentikan sisa rencana. */
    fun runPlan(steps: List<FormStep>, index: Int, okCount: Int, failed: List<String>) {
        if (index >= steps.size) {
            message = "Rencana selesai: $okCount dari ${steps.size} langkah berhasil" +
                (if (failed.isEmpty()) "." else ". Gagal: " + failed.joinToString("; ") + ".") +
                " Periksa halaman, lalu kirim sendiri."
            readPage(announce = false)
            return
        }
        val step = steps[index]
        message = "Langkah ${index + 1}/${steps.size}: ${step.describe()}"
        val go = {
            execCommand(step.command) { r ->
                val ok = r.optBoolean("ok")
                val nextFailed = if (ok) failed else failed + "${step.describe()} (${r.optString("msg")})"
                // jeda singkat agar dropdown/komponen yang bergantung satu sama lain sempat diperbarui
                Handler(Looper.getMainLooper()).postDelayed({ runPlan(steps, index + 1, okCount + if (ok) 1 else 0, nextFailed) }, 250)
            }
        }
        guarded(step.describe(), step.guardReason, go)
    }

    /** Peta field -> model menyusun rencana dari catatan pengguna + teks halaman -> ditampilkan untuk ditinjau. */
    fun makePlan() {
        val notes = task.trim()
        if (notes.isBlank()) {
            message = "Tulis fakta/catatan kasus di kolom instruksi (nama, tanggal, kronologi, usulan penyelesaian), lalu tekan Rencanakan."
            return
        }
        if (!pageTrusted()) { message = untrustedMsg; return }
        planBusy = true
        message = "Memetakan kolom halaman…"
        callForm(JSONObject().put("op", "inspect")) { r ->
            val fields = r.optJSONArray("fields")?.toString().orEmpty()
            if (fields.isBlank() || fields == "[]") {
                planBusy = false
                message = "Tidak ada kolom yang terdeteksi di halaman ini."
                return@callForm
            }
            message = "Mythos menyusun rencana pengisian…"
            scope.launch {
                try {
                    val raw = onPlan(FormPlanner.systemPrompt(), FormPlanner.userPrompt(fields, notes, pageText))
                    val result = FormPlanner.parse(raw)
                    if (result.steps.isEmpty()) {
                        message = "Rencana kosong. " + (result.rejected.firstOrNull() ?: "Model tidak menemukan data yang cocok dengan kolom.")
                    } else {
                        plan = result
                        message = "Rencana siap ditinjau (${result.steps.size} langkah)."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Gagal menyusun rencana: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    planBusy = false
                }
            }
        }
    }

    fun stopAuto(msg: String) { autoRunning = false; autoPausedAt = 0; autoHost = ""; autoAcceptedHosts.clear(); autoPhase = ""; autoNow = ""; message = msg; readPage(announce = false) }

    /** Tunggu halaman benar-benar selesai dimuat (klik bisa berpindah halaman) sebelum agen membaca ulang; maksimal [maxWait] ms. */
    fun whenSettled(minDelay: Long = 800, maxWait: Long = 9000, then: () -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val start = SystemClock.uptimeMillis()
        fun poll() {
            if (!autoRunning) return
            val waited = SystemClock.uptimeMillis() - start
            if (waited >= maxWait || (progress >= 100 && waited >= minDelay)) then()
            else h.postDelayed({ poll() }, 250)
        }
        autoPhase = "Menunggu halaman selesai dimuat"
        h.postDelayed({ poll() }, minDelay)
    }

    /** Jeda (bukan berhenti): riwayat dan nomor langkah disimpan, pengguna menekan Lanjutkan setelah login/menjawab. */
    fun pauseAuto(msg: String, step: Int) { autoRunning = false; autoPausedAt = step; autoPhase = "Dijeda"; message = msg; readPage(announce = false) }

    /** Mode otonom: baca halaman -> model memilih SATU aksi -> jalankan -> ulangi. Klik final tetap minta konfirmasi. */
    fun autoStep(n: Int, fails: Int) {
        if (!autoRunning) return
        if (webView == null || formScript.isBlank()) { stopAuto("Halaman atau skrip form belum siap."); return }
        if (n > BrowserAutopilot.MAX_STEPS) { stopAuto("Berhenti: batas ${BrowserAutopilot.MAX_STEPS} langkah tercapai. Periksa halaman, lalu jalankan lagi bila perlu."); return }
        if (!pageTrusted()) { stopAuto(untrustedMsg); return }
        val hostNow = BrowserDomainPolicy.hostOf(webView?.url.orEmpty()).orEmpty()
        if (autoHost.isEmpty()) autoHost = hostNow
        if (hostNow.isNotEmpty() && hostNow != autoHost && hostNow !in autoAcceptedHosts) {
            // Pindah situs di tengah tugas: bisa tautan jebakan. Jangan baca/isi sebelum pengguna setuju.
            approvalTitle = "Agen berpindah dari $autoHost ke $hostNow.\n\nLanjutkan bekerja di situs ini? Batal akan menghentikan mode otonom."
            approvalAction = { autoAcceptedHosts.add(hostNow); autoStep(n, fails) }
            return
        }
        autoStepNow = n
        autoPhase = "Membaca halaman"
        autoNow = ""
        message = "Otonom $n/${BrowserAutopilot.MAX_STEPS}: membaca halaman…"
        callForm(JSONObject().put("op", "inspect")) { r ->
            if (!autoRunning) return@callForm
            val fields = r.optJSONArray("fields")?.toString().orEmpty()
            if (BrowserAutopilot.needsLogin(fields)) {
                autoHistory.add("Halaman login => menunggu pengguna")
                pauseAuto("Dijeda: halaman meminta login. Login sendiri, lalu tekan Lanjutkan.", n)
                return@callForm
            }
            webView?.evaluateJavascript("(document.body && document.body.innerText) || ''") { raw ->
                if (!autoRunning) return@evaluateJavascript
                val text = (runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw.removeSurrounding("\"")).take(30_000)
                pageText = text
                autoPhase = "Model memilih langkah"
                message = "Otonom $n: model memilih langkah…"
                scope.launch {
                    val answer = try {
                        onPlan(BrowserAutopilot.systemPrompt(), BrowserAutopilot.userPrompt(autoGoal, fields, text, autoHistory.toList(), n))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        stopAuto("Gagal menghubungi model: ${e.message ?: e.javaClass.simpleName}")
                        return@launch
                    }
                    if (!autoRunning) return@launch
                    val actions = BrowserAutopilot.parseBatch(answer)
                    fun runAt(i: Int, f: Int) {
                        val action = actions[i]
                        val desc = BrowserAutopilot.describe(action)
                        autoNow = if (action is AutoAction.Act || action is AutoAction.Click) desc else ""
                        val next: (JSONObject) -> Unit = { res ->
                            val ok = res.optBoolean("ok")
                            autoHistory.add("$desc => " + (if (ok) "ok" else "gagal: " + res.optString("msg")).take(160))
                            val nf = if (ok) 0 else f + 1
                            if (nf >= BrowserAutopilot.MAX_CONSECUTIVE_FAILS) {
                                stopAuto("Berhenti: $nf langkah gagal berturut-turut. Terakhir: ${res.optString("msg")}")
                            } else if (ok && i + 1 < actions.size) {
                                // sisa isian dalam satu jawaban model: lanjut tanpa membaca ulang halaman
                                Handler(Looper.getMainLooper()).postDelayed({ if (autoRunning) runAt(i + 1, 0) }, 300)
                            } else {
                                whenSettled { autoStep(n + 1, nf) }
                            }
                            Unit
                        }
                        if ((action is AutoAction.Act || action is AutoAction.Click) && BrowserAutopilot.isLooping(autoHistory, desc)) {
                            stopAuto("Berhenti: agen mengulang \"$desc\" tanpa kemajuan. Periksa halaman atau beri tujuan yang lebih rinci.")
                            return
                        }
                        when (action) {
                            is AutoAction.Done -> stopAuto("Selesai: ${action.summary} Periksa halaman; pengiriman final tetap Anda yang memutuskan.")
                            is AutoAction.Ask -> {
                                autoHistory.add("Bertanya: ${action.question.take(120)} => menunggu pengguna")
                                pauseAuto("Agen bertanya: ${action.question} Tambahkan jawaban di kolom perintah, lalu tekan Lanjutkan.", n)
                            }
                            is AutoAction.Invalid -> next(JSONObject().put("ok", false).put("msg", action.reason))
                            is AutoAction.Act -> {
                                message = "Otonom $n: ${action.step.describe()}"
                                autoPhase = if (action.step.guardReason != null) "Menunggu konfirmasi Anda" else "Mengisi formulir"
                                guarded(action.step.describe(), action.step.guardReason) {
                                    autoPhase = "Mengisi formulir"
                                    execCommand(action.step.command, next)
                                }
                            }
                            is AutoAction.Click -> {
                                message = "Otonom $n: klik \"${action.label}\""
                                autoPhase = "Memeriksa tombol"
                                // Dry-run: periksa teks tombol SEBENARNYA dan apakah ia mengirim formulir
                                callForm(JSONObject().put("op", "click").put("label", action.label).put("dry", true)) { probe ->
                                    if (!probe.optBoolean("ok")) { next(probe); return@callForm }
                                    val actual = probe.optString("text")
                                    val clickReason = BrowserActionGuard.reasonForButton(null, actual, probe.optBoolean("submit"))
                                    if (clickReason != null) autoPhase = "Menunggu konfirmasi Anda"
                                    guarded("Klik \"$actual\"", clickReason) {
                                        autoPhase = "Mengklik tombol"
                                        callForm(JSONObject().put("op", "click").put("label", actual).put("target", true), next)
                                    }
                                }
                            }
                        }
                    }
                    runAt(0, fails)
                }
            }
        }
    }

    fun startAuto(reuseGoal: Boolean = false) {
        val goal = if (reuseGoal && autoGoal.isNotBlank()) autoGoal else task.trim()
        if (goal.isBlank()) { message = "Tulis tujuan di kolom perintah, lalu tekan kirim."; return }
        if (!pageTrusted()) { message = untrustedMsg; return }
        autoGoal = goal
        autoHistory.clear()
        autoPausedAt = 0
        autoHost = ""
        autoAcceptedHosts.clear()
        autoRunning = true
        autoStep(1, 0)
    }

    fun resumeAuto() {
        if (!pageTrusted()) { message = untrustedMsg; return }
        // Teks yang diketik saat dijeda = jawaban/tambahan untuk agen; tujuan semula tidak hilang.
        autoGoal = BrowserAutopilot.mergeGoal(autoGoal, task)
        if (autoGoal.isBlank()) { message = "Tujuan kosong. Tulis tujuan di kolom perintah, lalu tekan kirim."; return }
        val step = autoPausedAt.coerceAtLeast(1)
        autoPausedAt = 0
        autoRunning = true
        autoStep(step, 0)
    }

    fun askAboutPage(question: String) {
        if (webView == null || formScript.isBlank()) { message = "Halaman atau skrip form belum siap."; return }
        if (!pageTrusted()) { message = untrustedMsg; return }
        if (answerBusy) return
        answerBusy = true
        answerText = ""
        message = "Membaca halaman untuk menjawab…"
        callForm(JSONObject().put("op", "inspect")) { r ->
            val fields = r.optJSONArray("fields")?.toString().orEmpty()
            webView?.evaluateJavascript("(document.body && document.body.innerText) || ''") { raw ->
                val text = (runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw.removeSurrounding("\"")).take(30_000)
                pageText = text
                message = "Mythos menyusun jawaban…"
                scope.launch {
                    try {
                        val a = onPlan(BrowserAutopilot.askSystemPrompt(), BrowserAutopilot.askUserPrompt(question, fields, text)).trim()
                        answerText = a.ifBlank { "Model tidak memberi jawaban. Coba ulangi pertanyaannya." }
                        message = "Jawaban siap."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = "Gagal menjawab: ${e.message ?: e.javaClass.simpleName}"
                    } finally {
                        answerBusy = false
                    }
                }
            } ?: run { answerBusy = false }
        }
    }

    fun runTask() {
        val instruction = task.trim()
        if (instruction.isBlank()) {
            message = "Tulis instruksi, misalnya: isi kolom Usulan Penyelesaian dengan ..."
            return
        }
        // Tombol kirim cerdas: perintah tunggal jelas -> langsung; kalimat bebas/tujuan majemuk -> agen otonom; saat dijeda -> jawaban.
        when (BrowserAutopilot.route(instruction, autoRunning, autoPausedAt > 0)) {
            TaskRoute.EMPTY -> return
            TaskRoute.BUSY -> { message = "Agen sedang bekerja. Tekan Hentikan dulu bila ingin memberi perintah lain."; return }
            TaskRoute.QUESTION -> { askAboutPage(instruction); return }
            TaskRoute.RESUME -> { resumeAuto(); return }
            TaskRoute.AUTONOMOUS -> { startAuto(); return }
            TaskRoute.DIRECT -> Unit
        }
        when (val cmd = BrowserInstruction.parse(instruction)) {
            null -> message = "Instruksi belum dikenali. Contoh: baca halaman; periksa kolom; isi kolom [label] dengan [teks]; " +
                "pilih [opsi] pada [dropdown]; centang [label]; pilih radio [opsi] pada [label]; isi tanggal [label] dengan 8 November 2026; klik [tombol]."
            BrowserCommand.Read -> readPage()
            BrowserCommand.Inspect -> callForm(JSONObject().put("op", "inspect")) { r ->
                pageText = r.optJSONArray("fields")?.let { arr -> (0 until arr.length()).joinToString("\n") { arr.getJSONObject(it).toString() } }
                    ?: r.optString("msg")
                message = "Peta field dibuat. Tekan \"Analisis\" atau periksa daftar di bawah."
            }
            is BrowserCommand.Fill -> callForm(JSONObject().put("op", "fill").put("label", cmd.label).put("value", cmd.value)) { report(it) }
            is BrowserCommand.DateField -> callForm(JSONObject().put("op", "date").put("label", cmd.label).put("value", cmd.isoDate)) { report(it) }
            is BrowserCommand.Choose -> callForm(JSONObject().put("op", "choose").put("label", cmd.label).put("value", cmd.value)) { r ->
                if (r.optBoolean("pending")) {
                    // dropdown kustom: beri waktu daftar opsi muncul, lalu klik opsinya
                    Handler(Looper.getMainLooper()).postDelayed({
                        callForm(JSONObject().put("op", "pick").put("value", cmd.value)) { report(it) }
                    }, 450)
                } else report(r)
            }
            is BrowserCommand.Check -> guarded(
                (if (cmd.checked) "Centang " else "Hapus centang ") + cmd.label,
                if (cmd.checked) BrowserActionGuard.reasonFor(instruction, cmd.label) else null,
            ) { callForm(JSONObject().put("op", "check").put("label", cmd.label).put("checked", cmd.checked)) { report(it) } }
            is BrowserCommand.Radio -> guarded(
                "Pilih ${cmd.value}", BrowserActionGuard.reasonFor(instruction, cmd.value),
            ) { callForm(JSONObject().put("op", "radio").put("label", cmd.label).put("value", cmd.value)) { report(it) } }
            is BrowserCommand.Click -> {
                // Dry-run dulu: periksa teks tombol SEBENARNYA, bukan hanya kata di instruksi.
                callForm(JSONObject().put("op", "click").put("label", cmd.label).put("dry", true)) { probe ->
                    if (!probe.optBoolean("ok")) { report(probe); return@callForm }
                    val actual = probe.optString("text")
                    guarded("Klik \"$actual\"", BrowserActionGuard.reasonForButton(instruction, actual, probe.optBoolean("submit"))) {
                        // target=true: klik elemen yang SAMA dengan yang diperiksa saat dry-run (bukan tombol lain berteks serupa)
                        callForm(JSONObject().put("op", "click").put("label", actual).put("target", true)) { report(it) }
                    }
                }
            }
        }
    }

    fun openAddress() {
        val target = address.trim()
        if (target.startsWith("https://")) navigate(target) else message = "Demi keamanan, alamat harus dimulai dengan https://"
    }

    val trusted = BrowserDomainPolicy.isAllowed(currentUrl, allowedExtra.toList(), allowAll)
    val okGreen = Color(0xFF3C8A55)
    val statusColor = when {
        message.startsWith("Gagal") || message == untrustedMsg -> MythosColors.Error
        planBusy -> MythosColors.Accent
        else -> okGreen
    }

    Column(
        Modifier.fillMaxSize().background(MythosColors.Background)
            .statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        // Bilah atas minimal: kembali, alamat, muat ulang dalam satu baris rapat; sisanya untuk halaman web
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", tint = MythosColors.Icon, modifier = Modifier.size(20.dp))
            }
            PillField(
                value = address,
                onValueChange = { address = it },
                placeholder = "Alamat situs",
                modifier = Modifier.weight(1f),
                compact = true,
                imeAction = ImeAction.Go,
                onAction = { openAddress() },
                leading = {
                    Icon(
                        if (trusted) Icons.Outlined.Lock else Icons.Outlined.Warning,
                        contentDescription = if (trusted) "Situs tepercaya" else "Situs belum tepercaya",
                        tint = if (trusted) okGreen else MythosColors.Error,
                        modifier = Modifier.size(16.dp),
                    )
                },
                trailing = {
                    IconButton(onClick = { openAddress() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Buka", tint = MythosColors.Icon, modifier = Modifier.size(18.dp))
                    }
                },
            )
            IconButton(onClick = { webView?.reload() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, "Muat ulang", tint = MythosColors.Icon, modifier = Modifier.size(20.dp))
            }
        }

        // Garis kemajuan tipis (tinggi tetap 2dp, tidak menggeser tata letak)
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MythosColors.Send,
                    trackColor = Color.Transparent,
                )
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Tampilan 1:1 dengan Chrome "Situs desktop": layout selebar 980 piksel CSS diperkecil muat layar, semua terlihat
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.textZoom = 100
                    val chromeVer = Regex("Chrome/([\\d.]+)").find(settings.userAgentString)?.groupValues?.get(1) ?: "124.0.0.0"
                    settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVer Safari/537.36"
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            val url = request.url.toString()
                            if (BrowserDomainPolicy.isAllowed(url, allowedExtra.toList(), allowAll)) return false
                            if (BrowserDomainPolicy.hostOf(url) == null) {
                                message = "Tautan non-https atau tidak valid diblokir."
                                return true
                            }
                            navigate(url)
                            return true
                        }

                        override fun onPageStarted(view: WebView, pageUrl: String, favicon: android.graphics.Bitmap?) {
                            currentUrl = pageUrl
                            loadError = null
                            if (progress >= 100) progress = 5   // supaya agen menunggu pemuatan, bukan mengira halaman lama sudah siap
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) loadError = "Gagal memuat halaman: ${error.description}. Periksa koneksi lalu tekan muat ulang."
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                            if (request.isForMainFrame && errorResponse.statusCode >= 400) loadError = "Gagal memuat halaman: situs membalas HTTP ${errorResponse.statusCode}."
                        }

                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            handler.cancel()
                            loadError = "Gagal memuat halaman: sertifikat SSL situs tidak tepercaya, pemuatan dibatalkan."
                        }

                        override fun onPageCommitVisible(view: WebView, pageUrl: String) {
                            view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                            address = pageUrl
                            currentUrl = pageUrl
                            message = loadError
                                ?: if (BrowserDomainPolicy.isAllowed(pageUrl, allowedExtra.toList(), allowAll)) "Halaman siap. Login atau beri instruksi agen." else untrustedMsg
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }

                        override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                            fileCallback?.onReceiveValue(null)
                            fileCallback = callback
                            val accepts = params?.acceptTypes?.filter { it.isNotBlank() }?.toTypedArray()
                            pickFiles.launch(if (accepts.isNullOrEmpty()) arrayOf("*/*") else accepts)
                            return true
                        }
                    }
                    layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
                    loadUrl(startUrl)
                    webView = this
                }
            },
            update = { webView = it },
        )

        // Dok agen minimal di dasar layar: status satu baris, perintah, lalu chip aksi yang bisa digeser
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            color = MythosColors.Composer,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, MythosColors.ComposerBorder),
        ) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(6.dp))
                    Text(message, fontFamily = MythosSans, fontSize = 11.sp, color = MythosColors.Hint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (answerBusy || answerText.isNotBlank()) {
                    AnswerCard(busy = answerBusy, text = answerText, onClose = { answerText = "" })
                }
                if (autoRunning || autoPausedAt > 0 || (showLog && autoHistory.isNotEmpty())) {
                    LiveMonitor(
                        running = autoRunning,
                        paused = autoPausedAt > 0,
                        step = autoStepNow,
                        maxSteps = BrowserAutopilot.MAX_STEPS,
                        phase = autoPhase,
                        now = autoNow,
                        goal = autoGoal,
                        history = autoHistory.toList(),
                        onStop = { stopAuto("Dihentikan.") },
                    )
                }
                PillField(
                    value = task,
                    onValueChange = { task = it },
                    placeholder = "Perintah atau catatan kasus…",
                    singleLine = false,
                    maxLines = 3,
                    compact = true,
                    trailing = {
                        val ready = task.isNotBlank()
                        Box(
                            Modifier.size(28.dp).clip(CircleShape)
                                .background(if (ready) MythosColors.Send else MythosColors.Chip)
                                .clickable(enabled = ready) { runTask() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, "Jalankan", tint = if (ready) Color.White else MythosColors.Hint, modifier = Modifier.size(16.dp))
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ActionChip(if (autoRunning) "Hentikan" else if (autoPausedAt > 0) "Lanjutkan" else "Otonom") {
                        when {
                            autoRunning -> stopAuto("Dihentikan.")
                            autoPausedAt > 0 -> resumeAuto()
                            else -> startAuto()
                        }
                    }
                    if (!autoRunning && autoGoal.isNotBlank()) ActionChip("Mulai ulang") { startAuto(reuseGoal = true) }
                    if (autoHistory.isNotEmpty()) ActionChip(if (showLog) "Sembunyikan riwayat" else "Riwayat agen") { showLog = !showLog }
                    ActionChip(if (allowAll) "Semua situs: Aktif" else "Semua situs: Mati") { allowAll = !allowAll }
                    ActionChip("Baca halaman") { readPage() }
                    ActionChip(if (planBusy) "Menyusun…" else "Rencanakan form", enabled = !planBusy) { makePlan() }
                    if (pageText.isNotBlank()) {
                        ActionChip("Analisis halaman") { onAnalyze(pageText) }
                        ActionChip(if (showText) "Sembunyikan teks" else "Lihat teks") { showText = !showText }
                    }
                }
                if (showText && pageText.isNotBlank()) {
                    Text(
                        pageText,
                        Modifier.fillMaxWidth().height(100.dp).padding(top = 4.dp).verticalScroll(rememberScrollState()),
                        fontFamily = MythosSans, fontSize = 12.sp, color = MythosColors.Hint,
                    )
                }
            }
        }
    }

    plan?.let { p ->
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text("Rencana pengisian (${p.steps.size} langkah)") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Periksa dulu. Tidak ada yang dikirim: tombol kirim tetap Anda yang menekan.", style = MaterialTheme.typography.bodySmall)
                    p.steps.forEachIndexed { i, st ->
                        Text(
                            "${i + 1}. ${st.describe()}" + (st.guardReason?.let { "\n   ⚠ akan minta konfirmasi: $it" } ?: "") +
                                (if (st.why.isNotBlank()) "\n   ${st.why}" else ""),
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (p.rejected.isNotEmpty()) {
                        Text("Ditolak otomatis:", Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        p.rejected.forEach { Text("• $it", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = p.steps.isNotEmpty(), onClick = {
                    val steps = p.steps
                    plan = null
                    BrowserDomainPolicy.hostOf(webView?.url.orEmpty())?.let { host ->
                        onPlanRun(host, steps.mapNotNull { st ->
                            when (val c = st.command) {
                                is BrowserCommand.Fill -> FormPlanHistory.Entry("isi", c.label, c.value)
                                is BrowserCommand.Choose -> FormPlanHistory.Entry("pilih", c.label, c.value)
                                is BrowserCommand.DateField -> FormPlanHistory.Entry("tanggal", c.label, c.isoDate)
                                is BrowserCommand.Radio -> FormPlanHistory.Entry("radio", c.label, c.value)
                                is BrowserCommand.Check -> FormPlanHistory.Entry(if (c.checked) "centang" else "hapus centang", c.label, "")
                                else -> null
                            }
                        })
                    }
                    runPlan(steps, 0, 0, emptyList())
                }) { Text("Jalankan rencana") }
            },
            dismissButton = { TextButton(onClick = { plan = null }) { Text("Batal") } },
        )
    }

    approvalTitle?.let { title ->
        // Batal saat agen bekerja: jeda (riwayat tersimpan, Lanjutkan tersedia) dan catat agar model tidak mengusulkan aksi yang sama lagi.
        val cancelApproval = {
            approvalTitle = null
            approvalAction = null
            if (autoRunning) {
                if (autoNow.isNotBlank()) autoHistory.add("$autoNow => ditolak pengguna")
                pauseAuto("Dibatalkan. Ketik arahan baru lalu tekan Lanjutkan, atau tekan Mulai ulang.", autoStepNow)
            }
            Unit
        }
        AlertDialog(
            onDismissRequest = { cancelApproval() },
            title = { Text(if (title.startsWith("Situs di luar")) "Situs belum tepercaya" else if (title.startsWith("Agen berpindah")) "Agen pindah situs" else "Konfirmasi tindakan final") },
            text = { Text(title) },
            confirmButton = {
                TextButton(onClick = { val action = approvalAction; approvalTitle = null; approvalAction = null; action?.invoke() }) { Text(if (title.startsWith("Situs di luar")) "Izinkan dan buka" else if (title.startsWith("Agen berpindah")) "Lanjutkan" else "Jalankan") }
            },
            dismissButton = { TextButton(onClick = { cancelApproval() }) { Text("Batal") } },
        )
    }
}

/** Jawaban tanya-jawab tentang halaman (seperti chat), bisa digulir dan ditutup. */
@Composable
private fun AnswerCard(busy: Boolean, text: String, onClose: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MythosColors.Background,
        border = BorderStroke(1.dp, MythosColors.ComposerBorder),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (busy) "Mythos sedang menjawab…" else "Jawaban Mythos", Modifier.weight(1f),
                    fontFamily = MythosSans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MythosColors.Ink)
                if (!busy) ActionChip("Tutup", onClick = onClose)
            }
            if (text.isNotBlank()) {
                Text(text, Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 6.dp).verticalScroll(rememberScrollState()),
                    fontFamily = MythosSans, fontSize = 13.sp, color = MythosColors.Ink)
            }
        }
    }
}

/**
 * Pemantau live agen otonom: langkah n/maks, fase kerja, aksi yang sedang dikerjakan (elemennya juga disorot di halaman),
 * dan riwayat bergulir (✓ berhasil / ✗ gagal) yang selalu terlihat selama agen bekerja.
 */
@Composable
private fun LiveMonitor(
    running: Boolean,
    paused: Boolean,
    step: Int,
    maxSteps: Int,
    phase: String,
    now: String,
    goal: String,
    history: List<String>,
    onStop: () -> Unit,
) {
    val dot = when { running -> MythosColors.Send; paused -> Color(0xFFD9822B); else -> MythosColors.Hint }
    val scroll = rememberScrollState()
    LaunchedEffect(history.size) { scroll.animateScrollTo(scroll.maxValue) }
    Surface(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MythosColors.Background,
        border = BorderStroke(1.dp, MythosColors.ComposerBorder),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(
                    (if (running) "Agen bekerja" else if (paused) "Agen dijeda" else "Riwayat agen") +
                        (if (step > 0) " · langkah $step/$maxSteps" else ""),
                    Modifier.weight(1f), fontFamily = MythosSans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MythosColors.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (running) ActionChip("Hentikan", onClick = onStop)
            }
            if (running && step > 0) {
                LinearProgressIndicator(
                    progress = { (step.toFloat() / maxSteps).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                    color = MythosColors.Send,
                    trackColor = MythosColors.Chip,
                )
            }
            if (goal.isNotBlank()) {
                Text("Tujuan: " + goal.replace(Regex("\\s+"), " ").take(110), Modifier.padding(top = 6.dp),
                    fontFamily = MythosSans, fontSize = 11.sp, color = MythosColors.Hint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (phase.isNotBlank() || now.isNotBlank()) {
                Text(
                    listOf(phase, now).filter { it.isNotBlank() }.joinToString(": "),
                    Modifier.padding(top = 4.dp), fontFamily = MythosSans, fontSize = 12.sp, color = MythosColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (history.isNotEmpty()) {
                Text(
                    history.takeLast(30).joinToString("\n") { BrowserAutopilot.formatLogLine(it) },
                    Modifier.fillMaxWidth().heightIn(max = 84.dp).padding(top = 6.dp).verticalScroll(scroll),
                    fontFamily = MythosSans, fontSize = 11.sp, color = MythosColors.Hint,
                )
            }
        }
    }
}

/** Kolom teks berbentuk kapsul: ikon awal opsional, teks, aksi akhir opsional. */
@Composable
private fun PillField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    imeAction: ImeAction = ImeAction.Default,
    onAction: () -> Unit = {},
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
) {
    val fontPx = if (compact) 14.sp else 15.sp
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 18.dp else 26.dp),
        color = MythosColors.Background,
        border = BorderStroke(1.dp, MythosColors.ComposerBorder),
    ) {
        Row(
            if (compact) Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp).heightIn(min = 32.dp)
            else Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = singleLine,
                maxLines = maxLines,
                textStyle = TextStyle(fontFamily = MythosSans, fontSize = fontPx, color = MythosColors.Ink),
                cursorBrush = SolidColor(MythosColors.Ink),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = { onAction() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, fontFamily = MythosSans, fontSize = fontPx, color = MythosColors.Placeholder)
                        inner()
                    }
                },
            )
            if (trailing != null) {
                Spacer(Modifier.width(6.dp))
                trailing()
            }
        }
    }
}

/** Chip aksi ringkas (tinggi sekitar 26dp) tanpa garis tepi. */
@Composable
private fun ActionChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).background(MythosColors.Chip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = MythosSans, fontSize = 12.sp, color = if (enabled) MythosColors.Ink else MythosColors.Hint, maxLines = 1)
    }
}
