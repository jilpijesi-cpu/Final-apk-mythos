package com.mythos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.app.ProviderChoice
import com.mythos.app.agent.CodexCliStatus
import com.mythos.app.agent.TermuxLauncher
import com.mythos.app.agent.ExtraKeys
import com.mythos.app.agent.PermissionMode
import com.mythos.app.agent.ModelState
import com.mythos.app.agent.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.mythos.app.UserSettings
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.mythos.app.PkceOAuthSession
import com.mythos.app.agent.GoogleAccount
import com.mythos.app.agent.GoogleWorkspaceConfig
import com.mythos.app.agent.HttpGoogleWorkspaceService
import com.mythos.app.agent.IntegrationCheckResult
import com.mythos.app.agent.IntegrationTester
import com.mythos.app.agent.SensitiveDataGuard
import com.mythos.app.agent.WorkspaceServiceKind
import kotlinx.coroutines.launch

/** Halaman Pengaturan terpisah: tiap ikon/pintasan membuka satu halaman sesuai fungsinya. */
enum class SettingsSection(val title: String) {
    MODEL("Model"),
    GITHUB("GitHub"),
    SEARCH("Pencarian"),
    PERMISSION("Izin"),
    WORKSPACE("Google"),
    APPEARANCE("Tampilan"),
}

@Composable
fun SettingsScreen(
    section: SettingsSection = SettingsSection.MODEL,
    initial: UserSettings,
    draft: UserSettings?,
    onDraftChange: (UserSettings) -> Unit,
    loadFor: (ProviderChoice) -> UserSettings,
    scan: suspend (UserSettings, Boolean) -> List<ModelStatus>,
    onSave: (UserSettings) -> Unit,
    onBack: () -> Unit,
    onRecheck: () -> Unit = {},
    extraKeys: Map<ProviderChoice, List<String>> = emptyMap(),
    onAddExtra: (ProviderChoice, String) -> String = { _, _ -> "" },
    onRemoveExtra: (ProviderChoice, String) -> Unit = { _, _ -> },
    onRemoveMain: (ProviderChoice, String) -> Unit = { _, _ -> },
    onSetMain: (ProviderChoice, String) -> Unit = { _, _ -> },
    onCheckCodexCli: suspend () -> CodexCliStatus = { CodexCliStatus.UNKNOWN },
    onLoginCodex: () -> Unit = {},
    codexLoginStarting: Boolean = false,
    appActive: Boolean = true,
    onPersistImmediate: (UserSettings) -> Unit = {},
    onStartPkceSession: (PkceOAuthSession?) -> Unit = {},
    onLoadPkceSession: () -> PkceOAuthSession? = { null },
    onAccentChange: (String) -> Unit = {},
    onFontChange: (String) -> Unit = {},
) {
    // Isian yang belum disimpan dipertahankan di ViewModel (rotasi layar tidak boleh menghapus kunci yang baru ditempel).
    var s by remember { mutableStateOf(draft ?: initial) }
    var keyInput by remember(s.provider) { mutableStateOf("") }
    var showGh by remember { mutableStateOf(false) }
    var scanUi by remember { mutableStateOf<ScanUi>(ScanUi.Idle) }
    var recheck by remember { mutableIntStateOf(0) }
    var forceNext by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    LaunchedEffect(s) { onDraftChange(s) }

    val scope = rememberCoroutineScope()
    val testResults = remember { mutableStateMapOf<String, IntegrationCheckResult>() }
    var testingKey by remember { mutableStateOf<String?>(null) }
    var codexCliStatus by remember { mutableStateOf(CodexCliStatus.UNKNOWN) }
    var codexRecheck by remember { mutableIntStateOf(0) }
    // Pemeriksaan login dilakukan saat layar dibuka/kembali aktif dan saat pengguna meminta cek ulang.
    LaunchedEffect(s.provider, section, appActive, codexRecheck) {
        if (!appActive) return@LaunchedEffect
        if (section == SettingsSection.MODEL && s.provider == ProviderChoice.CODEX) {
            codexCliStatus = onCheckCodexCli()
        } else {
            codexCliStatus = CodexCliStatus.UNKNOWN
        }
    }

    // Login Google selesai di peramban lalu kembali lewat tautan; salin kolom Google terbaru dari pengaturan tersimpan ke layar ini.
    LaunchedEffect(initial.googleAccounts, initial.activeGoogleAccountId, initial.gmailEnabled, initial.calendarEnabled, initial.driveEnabled) {
        s = s.copy(
            googleAccounts = initial.googleAccounts,
            activeGoogleAccountId = initial.activeGoogleAccountId,
            gmailEnabled = initial.gmailEnabled,
            calendarEnabled = initial.calendarEnabled,
            driveEnabled = initial.driveEnabled,
        )
    }

    // Pemeriksaan model otomatis begitu kunci/penyedia berubah (ditunda agar tidak jalan tiap ketukan). Hasil lama dipakai
    // ulang dari cache (hemat kuota penyedia gratis); permintaan nyata baru dikirim bila diminta lewat "Periksa ulang".
    LaunchedEffect(section, s.provider, s.apiKey, s.ollamaHost, recheck) {
        if (section != SettingsSection.MODEL || s.provider.lockedModel != null || (s.provider.needsKey && s.apiKey.isBlank())) {
            scanUi = ScanUi.Idle
            return@LaunchedEffect
        }
        scanUi = ScanUi.Scanning
        delay(800)
        val force = forceNext
        forceNext = false
        scanUi = try {
            ScanUi.Done(scan(s, force))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanUi.Error(e.message ?: "gagal memeriksa")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MythosColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clickable(onClickLabel = "Kembali", role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", tint = MythosColors.Ink)
            }
            Spacer(Modifier.width(4.dp))
            Text(section.title, style = TextStyle(fontFamily = MythosSerif, fontSize = 24.sp, color = MythosColors.Ink))
        }
        Spacer(Modifier.height(20.dp))

        if (section == SettingsSection.MODEL) {
            Label("Penyedia")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderChoice.values().forEach { p ->
                    val selected = p == s.provider
                    val shape = RoundedCornerShape(20.dp)
                    Box(
                        Modifier
                            .clip(shape)
                            .background(if (selected) MythosColors.Ink else MythosColors.Card)
                            .border(1.dp, MythosColors.Border, shape)
                            .clickable { s = loadFor(p).copy(githubToken = s.githubToken, githubRepo = s.githubRepo, ollamaHost = s.ollamaHost, braveKey = s.braveKey, tavilyKey = s.tavilyKey, permissionMode = s.permissionMode) }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (providerLogo(p) != null) {
                                // Lencana putih: logo gelap (GitHub #181717) tidak boleh hilang di atas chip terpilih yang gelap.
                                Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                                    ProviderLogo(p, 16.dp)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(p.label, style = TextStyle(fontSize = 15.sp, color = if (selected) Color.White else MythosColors.Ink))
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            if (s.provider.lockedModel != null) {
                Label("Model")
                Text(
                    s.provider.lockedModel.orEmpty(),
                    style = TextStyle(fontSize = 15.sp, color = MythosColors.Ink),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MythosColors.Card)
                        .border(1.dp, MythosColors.Border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 12.dp)
                )
            } else {
                Label("Model")
                if (!showModelPicker) {
                    Text(
                        if (s.model.isBlank()) "Otomatis" else s.model,
                        style = TextStyle(fontSize = 14.sp, color = MythosColors.Ink)
                    )
                } else {
                    ModelPicker(scanUi, s.model) { s = s.copy(model = it) }
                    if (scanUi !is ScanUi.Scanning && scanUi !is ScanUi.Idle) {
                        TextButton(onClick = { forceNext = true; recheck++ }) { Text("Periksa ulang") }
                    }
                    OutlinedTextField(
                        value = s.model,
                        onValueChange = { s = s.copy(model = it) },
                        singleLine = true,
                        label = { Text("Nama model") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                TextButton(onClick = { showModelPicker = !showModelPicker }) {
                    Text(if (showModelPicker) "Otomatis" else "Pilih model")
                }
            }

            if (s.provider.needsKey) {
                Spacer(Modifier.height(16.dp))
                KeysSection(
                    provider = s.provider,
                    main = s.apiKey,
                    input = keyInput,
                    onInputChange = { keyInput = it },
                    onMainChange = {
                        s = s.copy(apiKey = it)
                        onSetMain(s.provider, it)
                    },
                    extras = extraKeys[s.provider].orEmpty(),
                    onAdd = { onAddExtra(s.provider, it) },
                    onRemove = { onRemoveExtra(s.provider, it) },
                    onRemoveMain = { onRemoveMain(s.provider, it) },
                    hint = keyHint(s.provider),
                )
            }

            if (s.provider == ProviderChoice.CODEX) {
                Spacer(Modifier.height(16.dp))
                Label("Codex CLI")
                val ctx = LocalContext.current
                val termuxOk = remember { TermuxLauncher.installed(ctx) }
                val permOk = TermuxLauncher.permitted(ctx)
                val card: CodexCardState = when {
                    codexLoginStarting -> CodexCardState(CodexTone.WORK, "Membuka sesi login…", "Termux sedang menyiapkan Codex CLI dan login ChatGPT.")
                    !termuxOk -> CodexCardState(CodexTone.WARN, "Termux belum terpasang", "Pasang Termux dari sumber tepercaya, lalu kembali ke sini.")
                    !permOk -> CodexCardState(CodexTone.WARN, "Perlu izin Termux", "Izinkan Mythos menjalankan perintah Codex app-server lewat Termux.", retry = true, retryLabel = "Beri izin & lanjutkan")
                    codexCliStatus == CodexCliStatus.NOT_INSTALLED -> CodexCardState(CodexTone.WARN, "Codex CLI belum terpasang", "Tombol di bawah memasang Node.js dan Codex dari repositori Termux aktif, lalu membuka login ChatGPT.", retry = true, retryLabel = "Pasang & masuk dengan ChatGPT")
                    codexCliStatus == CodexCliStatus.NOT_READY -> CodexCardState(CodexTone.WARN, "Codex app-server belum siap", "Pemeriksaan perintah `codex app-server` gagal. Coba perbaiki instalasi lewat sesi Termux.", retry = true, retryLabel = "Perbaiki & masuk")
                    codexCliStatus == CodexCliStatus.LOGIN_REQUIRED -> CodexCardState(CodexTone.WARN, "Belum masuk ke ChatGPT", "Login dilakukan di Termux/browser. Kredensial akun tetap dikelola Codex CLI, bukan disimpan Mythos.", retry = true, retryLabel = "Masuk dengan ChatGPT")
                    codexCliStatus == CodexCliStatus.TERMUX_SETUP_REQUIRED -> CodexCardState(CodexTone.WARN, "Termux menolak koneksi", "Aktifkan allow-external-apps=true di ~/.termux/termux.properties, tutup dan buka Termux, lalu coba lagi.", retry = true, retryLabel = "Coba lagi")
                    codexCliStatus == CodexCliStatus.PERMISSION_REQUIRED -> CodexCardState(CodexTone.WARN, "Perlu izin Termux", "Berikan izin RUN_COMMAND agar Mythos dapat memeriksa login dan menjalankan sesi app-server langsung.", retry = true, retryLabel = "Beri izin & lanjutkan")
                    codexCliStatus == CodexCliStatus.UNKNOWN -> CodexCardState(CodexTone.WORK, "Memeriksa Codex CLI…", "Meminta Termux memeriksa instalasi dan status login.", retry = true, retryLabel = "Periksa lagi")
                    else -> CodexCardState(CodexTone.OK, "Siap", "Codex CLI siap. Sesi Codex dijalankan di Termux satu per pesan dan berhenti setelah selesai.", retry = true, retryLabel = "Periksa lagi")
                }
                CodexStatusCard(card) {
                    when {
                        !permOk || codexCliStatus == CodexCliStatus.NOT_INSTALLED || codexCliStatus == CodexCliStatus.NOT_READY || codexCliStatus == CodexCliStatus.LOGIN_REQUIRED -> onLoginCodex()
                        else -> codexRecheck++
                    }
                }
            }

            if (s.provider == ProviderChoice.OLLAMA) {
                Spacer(Modifier.height(16.dp))
                Label("Alamat Ollama")
                OutlinedTextField(
                    value = s.ollamaHost,
                    onValueChange = { s = s.copy(ollamaHost = it) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (section == SettingsSection.GITHUB) {
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = s.githubRepo,
                onValueChange = { s = s.copy(githubRepo = it) },
                singleLine = true,
                label = { Text("Repo (pemilik/nama)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = s.githubToken,
                onValueChange = { s = s.copy(githubToken = it) },
                singleLine = true,
                label = { Text("Token GitHub") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showGh) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showGh = !showGh }) { Text(if (showGh) "Sembunyi" else "Lihat") } },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (section == SettingsSection.SEARCH) {
            val braveLogo = connectorLogo("brave")
            val tavilyLogo = connectorLogo("tavily")
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = s.braveKey,
                onValueChange = { s = s.copy(braveKey = it) },
                singleLine = true,
                label = { Text("Brave") },
                leadingIcon = braveLogo?.let { r -> brandIcon(r) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = s.tavilyKey,
                onValueChange = { s = s.copy(tavilyKey = it) },
                singleLine = true,
                label = { Text("Tavily") },
                leadingIcon = tavilyLogo?.let { r -> brandIcon(r) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (section == SettingsSection.PERMISSION) {
            Spacer(Modifier.height(24.dp))
            val modes = listOf(
                PermissionMode.ACCEPT_EDITS to ("Otomatis" to ""),
                PermissionMode.DEFAULT to ("Tanya dulu" to ""),
                PermissionMode.PLAN to ("Rencana saja" to ""),
            )
            modes.forEach { (m, text) ->
                val on = s.permissionMode == m
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) Color.White else Color.Transparent)
                        .clickable(role = Role.RadioButton) { s = s.copy(permissionMode = m) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(selected = on, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(text.first, style = TextStyle(fontSize = 16.sp, color = MythosColors.Ink))
                        if (text.second.isNotBlank()) Text(text.second, style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint))
                    }
                }
            }
        }

        if (section == SettingsSection.APPEARANCE) {
            Spacer(Modifier.height(24.dp))
            AccentColorRow(AccentTheme.key, onAccentChange)
            FontStyleRow(FontTheme.key, onFontChange)
        }

        if (section == SettingsSection.WORKSPACE) {
            GoogleWorkspaceMultiAccountSection(
                s = s,
                onSettingsChange = { updated ->
                    s = updated
                    onPersistImmediate(updated)
                },
                testResults = testResults,
                testingKey = testingKey,
                onTestAllWorkspace = {
                    if (testingKey == null) {
                        testingKey = "WORKSPACE_ALL"
                        scope.launch {
                            try {
                                IntegrationTester.testAllGoogleServices(s).forEach { testResults[it.service] = it }
                            } finally {
                                testingKey = null
                            }
                        }
                    }
                },
                onStartPkceSession = onStartPkceSession,
                onLoadPkceSession = onLoadPkceSession,
            )
        }

        val pendingKeys = if (section == SettingsSection.MODEL && s.provider.needsKey && keyInput.isNotBlank()) {
            ExtraKeys.parse(keyInput, extraKeys[s.provider].orEmpty() + s.apiKey).added
        } else emptyList()

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                var toSave = s
                if (pendingKeys.isNotEmpty()) {
                    if (toSave.apiKey.isBlank()) {
                        toSave = toSave.copy(apiKey = pendingKeys.first())
                        val rest = pendingKeys.drop(1)
                        if (rest.isNotEmpty()) onAddExtra(toSave.provider, rest.joinToString("\n"))
                    } else {
                        onAddExtra(toSave.provider, pendingKeys.joinToString("\n"))
                    }
                    keyInput = ""
                }
                onSave(toSave)
            },
            enabled = s.isConfigured || pendingKeys.isNotEmpty() || section != SettingsSection.MODEL,
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (section == SettingsSection.WORKSPACE || section == SettingsSection.APPEARANCE) "Selesai" else "Simpan", fontSize = 16.sp) }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = TextStyle(fontSize = 13.sp, color = MythosColors.Hint),
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun KeysSection(
    provider: ProviderChoice,
    main: String,
    input: String,
    onInputChange: (String) -> Unit,
    onMainChange: (String) -> Unit,
    extras: List<String>,
    onAdd: (String) -> String,
    onRemove: (String) -> Unit,
    onRemoveMain: (String) -> Unit,
    hint: String?,
) {
    var message by remember(provider) { mutableStateOf<String?>(null) }
    var removing by remember(provider) { mutableStateOf<String?>(null) }
    val all = (listOf(main).filter { it.isNotBlank() } + extras).distinct()
    val small = TextStyle(fontSize = 12.sp, color = MythosColors.Hint)
    Label("Kunci API ${provider.label}" + if (all.isNotEmpty()) " (${all.size})" else "")
    if (!hint.isNullOrBlank()) {
        Text(hint, style = small, modifier = Modifier.padding(bottom = 6.dp))
    }
    OutlinedTextField(
        value = input,
        onValueChange = { onInputChange(it); message = null },
        minLines = 2,
        maxLines = 6,
        label = { Text("Tempel kunci") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(6.dp))
    Button(
        onClick = {
            val text = input.trim()
            if (main.isBlank()) {
                val r = ExtraKeys.parse(text, extras)
                if (r.added.isEmpty()) {
                    message = onAdd(text)
                } else {
                    onMainChange(r.added.first())
                    val rest = r.added.drop(1)
                    if (rest.isNotEmpty()) onAdd(rest.joinToString("\n"))
                    message = "Kunci utama tersimpan" + (if (rest.isNotEmpty()) ", ${rest.size} kunci cadangan ditambahkan" else "") + "."
                    onInputChange("")
                }
            } else {
                message = onAdd(text)
                if (message?.startsWith("Ditambahkan") == true) onInputChange("")
            }
        },
        enabled = input.isNotBlank(),
        colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White)
    ) { Text("Tambah kunci") }
    message?.let {
        Text(it, style = TextStyle(fontSize = 13.sp, color = MythosColors.Ink), modifier = Modifier.padding(top = 8.dp))
    }
    all.forEachIndexed { i, k ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${provider.label} #${i + 1}" + if (i == 0) " (utama)" else "",
                    style = TextStyle(fontSize = 14.sp, color = MythosColors.Ink)
                )
                Text(ExtraKeys.mask(k), style = small)
            }
            TextButton(onClick = { removing = k }) { Text("Hapus") }
        }
    }
    removing?.let { k ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Hapus kunci ini?") },
            text = { Text(ExtraKeys.mask(k) + " akan dihapus dari aplikasi sekarang juga. Anda bisa menempelkannya lagi nanti.") },
            confirmButton = {
                TextButton(onClick = {
                    if (k == main) {
                        val newMain = extras.firstOrNull { it != k }.orEmpty()
                        onMainChange(newMain)
                        onRemoveMain(newMain)   // tersimpan langsung, tidak menunggu Simpan
                        if (k in extras) onRemove(k)   // jaga-jaga bila kunci yang sama juga tercatat sebagai cadangan
                    } else onRemove(k)
                    removing = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Batal") } },
        )
    }
}

private sealed interface ScanUi {
    data object Idle : ScanUi
    data object Scanning : ScanUi
    data class Done(val models: List<ModelStatus>) : ScanUi
    data class Error(val message: String) : ScanUi
}

@Composable
private fun ModelPicker(ui: ScanUi, selected: String, onPick: (String) -> Unit) {
    val hint = TextStyle(fontSize = 12.sp, color = MythosColors.Hint)
    ModelRow(
        dot = Color(0xFF2E7D32), title = "Otomatis",
        subtitle = "",
        selected = selected.isBlank(), enabled = true
    ) { onPick("") }
    when (ui) {
        ScanUi.Idle -> Unit
        ScanUi.Scanning -> Text("Memeriksa…", style = hint, modifier = Modifier.padding(top = 6.dp))
        is ScanUi.Error -> Text(
            "Daftar model gagal diambil.",
            style = hint, modifier = Modifier.padding(top = 6.dp)
        )
        is ScanUi.Done -> ui.models.forEach { m ->
            val (color, label) = when (m.state) {
                ModelState.READY -> Color(0xFF2E7D32) to "siap"
                ModelState.UNTESTED -> Color(0xFF757575) to "cadangan"
                ModelState.BUSY -> Color(0xFFB26A00) to "sibuk"
                ModelState.FAILED -> Color(0xFFB3261E) to "gagal"
            }
            ModelRow(color, m.model, label, selected == m.model, m.state != ModelState.FAILED) { onPick(m.model) }
        }
    }
}

@Composable
private fun ModelRow(dot: Color, title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(shape)
            .background(if (selected) MythosColors.Card else Color.Transparent)
            .border(1.dp, if (selected) MythosColors.Ink else MythosColors.Border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(dot))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 14.sp, color = if (enabled) MythosColors.Ink else MythosColors.Hint))
            if (subtitle.isNotBlank()) Text(subtitle, style = TextStyle(fontSize = 12.sp, color = MythosColors.Hint))
        }
    }
}

private fun keyHint(p: ProviderChoice): String? = when (p) {
    ProviderChoice.HUGGINGFACE -> "Token akses Hugging Face (hf_…) dengan izin \"Make calls to Inference Providers\"."
    ProviderChoice.GITHUB_MODELS -> "Personal access token GitHub dengan izin \"models:read\"."
    ProviderChoice.OPENROUTER -> "Kunci API OpenRouter (sk-or-…)."
    ProviderChoice.CODEX -> "Login akun dilakukan dan dikelola oleh Codex CLI di Termux; Mythos tidak menyimpan token API Codex."
    else -> null
}

@Composable
private fun GoogleWorkspaceMultiAccountSection(
    s: UserSettings,
    onSettingsChange: (UserSettings) -> Unit,
    testResults: Map<String, IntegrationCheckResult>,
    testingKey: String?,
    onTestAllWorkspace: () -> Unit,
    onStartPkceSession: (PkceOAuthSession?) -> Unit,
    onLoadPkceSession: () -> PkceOAuthSession?,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var authCodeInput by remember { mutableStateOf("") }
    var exchanging by remember { mutableStateOf(false) }
    var accountToRemove by remember { mutableStateOf<GoogleAccount?>(null) }
    var advanced by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(14.dp)
    val accounts = s.allGoogleAccounts
    val active = s.activeGoogleAccount
    val canConnect = s.googleClientId.isNotBlank() && (s.gmailEnabled || s.calendarEnabled || s.driveEnabled)

    Label("Akun")
    Column(
        Modifier.fillMaxWidth().clip(cardShape).background(MythosColors.Card).border(1.dp, MythosColors.Border, cardShape)
    ) {
        if (accounts.isEmpty()) {
            Text(
                "Belum ada akun",
                style = TextStyle(fontSize = 15.sp, color = MythosColors.Hint),
                modifier = Modifier.padding(16.dp)
            )
        }
        accounts.forEachIndexed { i, acct ->
            val isActive = acct.id == active?.id
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MythosColors.Border))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { if (!isActive) onSettingsChange(s.withActiveGoogleAccount(acct.id)) }
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(acct.email, style = TextStyle(fontSize = 15.sp, color = MythosColors.Ink))
                    if (isActive) Text("Aktif", style = TextStyle(fontSize = 12.sp, color = Color(0xFF1B5E20)))
                }
                TextButton(onClick = { accountToRemove = acct }) {
                    Text("Hapus", style = TextStyle(fontSize = 13.sp, color = Color(0xFFB3261E)))
                }
            }
        }
    }

    // Client ID cukup diisi sekali; setelah terisi disembunyikan di "Lanjutan".
    if (s.googleClientId.isBlank() || advanced) {
        Spacer(Modifier.height(20.dp))
        Label("Client ID Google")
        OutlinedTextField(
            value = s.googleClientId,
            onValueChange = { onSettingsChange(s.copy(googleClientId = it.trim())) },
            singleLine = true,
            placeholder = { Text("xxxx.apps.googleusercontent.com", style = TextStyle(fontSize = 14.sp, color = MythosColors.Hint)) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            message = null
            val result = runCatching {
                val (session, url) = GoogleWorkspaceConfig.newLogin(
                    clientId = s.googleClientId,
                    gmail = s.gmailEnabled,
                    calendar = s.calendarEnabled,
                    drive = s.driveEnabled,
                )
                onStartPkceSession(session)
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            if (result.isFailure) message = "✕ Gagal membuka Google. Coba lagi."
        },
        enabled = canConnect,
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) { Text(if (accounts.isEmpty()) "Hubungkan" else "Tambah akun", fontSize = 15.sp) }

    Spacer(Modifier.height(24.dp))
    Label("Layanan")
    Column(
        Modifier.fillMaxWidth().clip(cardShape).background(MythosColors.Card).border(1.dp, MythosColors.Border, cardShape)
    ) {
        val rows = listOf(
            Triple(WorkspaceServiceKind.GMAIL, "Gmail", s.gmailEnabled),
            Triple(WorkspaceServiceKind.CALENDAR, "Kalender", s.calendarEnabled),
            Triple(WorkspaceServiceKind.DRIVE, "Drive", s.driveEnabled),
        )
        rows.forEachIndexed { i, (kind, label, on) ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MythosColors.Border))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val logo = connectorLogo(when (kind) {
                    WorkspaceServiceKind.GMAIL -> "gmail"
                    WorkspaceServiceKind.CALENDAR -> "calendar"
                    WorkspaceServiceKind.DRIVE -> "drive"
                })
                if (logo != null) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(logo), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Text(label, style = TextStyle(fontSize = 15.sp, color = MythosColors.Ink), modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { want ->
                    // PATCH7: sakelar hanya mengaktifkan/mematikan pemakaian. Izin Google diminta saat login,
                    // jadi layanan yang belum pernah diberikan scope OAuth-nya (isServiceGranted = false) butuh login ulang.
                    if (want && active != null && !active.isServiceGranted(kind)) {
                        message = "Nyalakan $label lalu tekan \"Tambah akun\" (pilih akun yang sama) agar Google meminta izinnya."
                    }
                    onSettingsChange(s.withServiceConnection(kind, want))
                })
            }
        }
    }

    if (active != null && advanced) {
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onTestAllWorkspace,
            enabled = testingKey == null,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) { Text(if (testingKey != null) "Menguji…" else "Uji", color = MythosColors.Ink, fontSize = 15.sp) }
        testResults.values.forEach { r ->
            Spacer(Modifier.height(8.dp))
            CheckResultBanner(r)
        }
    }

    message?.let { msg ->
        Spacer(Modifier.height(10.dp))
        Text(msg, style = TextStyle(fontSize = 13.sp, color = if (msg.startsWith("✓")) Color(0xFF1B5E20) else Color(0xFFB3261E)))
    }

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { advanced = !advanced; if (!advanced) showManual = false }) {
        Text(if (advanced) "Tutup" else "Lanjutan", style = TextStyle(fontSize = 13.sp, color = MythosColors.Hint))
    }
    if (advanced) {
        TextButton(onClick = { showManual = !showManual }) {
            Text(if (showManual) "Tutup" else "Masukkan kode manual", style = TextStyle(fontSize = 13.sp, color = MythosColors.Hint))
        }
    }
    if (showManual && advanced) {
        OutlinedTextField(
            value = authCodeInput,
            onValueChange = { authCodeInput = it },
            placeholder = { Text("Tempel tautan atau kode", style = TextStyle(fontSize = 14.sp, color = MythosColors.Hint)) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val pkceSession = onLoadPkceSession()
                if (pkceSession == null) {
                    message = "✕ Tekan \"Hubungkan\" dulu (sesi login berlaku 10 menit)."
                    return@Button
                }
                // Kode mentah boleh (pengguna sendiri yang menempel); bila yang ditempel URL berisi state, harus cocok.
                val pastedState = GoogleWorkspaceConfig.extractState(authCodeInput)
                if (pastedState != null && !GoogleWorkspaceConfig.stateMatches(pkceSession.state, pastedState)) {
                    message = "✕ Tautan itu bukan dari login yang sedang berjalan. Tekan \"Hubungkan\" lagi."
                    return@Button
                }
                exchanging = true
                message = null
                scope.launch {
                    val result = HttpGoogleWorkspaceService.exchangeCodeWithPkce(
                        clientId = pkceSession.clientId.ifBlank { s.googleClientId },
                        rawCodeOrUrl = authCodeInput,
                        codeVerifier = pkceSession.codeVerifier,
                        redirectUri = pkceSession.redirectUri,
                        gmailConnected = pkceSession.gmailRequested,
                        calendarConnected = pkceSession.calendarRequested,
                        driveConnected = pkceSession.driveRequested,
                    )
                    exchanging = false
                    result.fold(
                        onSuccess = { newAcct ->
                            val finalAcct = if (newAcct.email == "Akun Google" && pkceSession.loginHint.isNotBlank()) {
                                newAcct.copy(id = pkceSession.loginHint.lowercase(), email = pkceSession.loginHint)
                            } else newAcct
                            onStartPkceSession(null)
                            onSettingsChange(s.withAddedOrUpdatedGoogleAccount(finalAcct))
                            authCodeInput = ""
                            showManual = false
                            message = "✓ ${finalAcct.email} terhubung."
                        },
                        onFailure = { err ->
                            message = "✕ ${SensitiveDataGuard.redactSensitiveCredentials(err.message ?: "Gagal menukar kode")}"
                        }
                    )
                }
            },
            enabled = authCodeInput.isNotBlank() && !exchanging,
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MythosColors.Ink, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) { Text(if (exchanging) "Memproses…" else "Hubungkan", fontSize = 15.sp) }
    }

    accountToRemove?.let { acct ->
        AlertDialog(
            onDismissRequest = { accountToRemove = null },
            title = { Text("Hapus akun?") },
            text = { Text(acct.email) },
            confirmButton = {
                TextButton(onClick = {
                    onSettingsChange(s.withRemovedGoogleAccount(acct.id))
                    accountToRemove = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { accountToRemove = null }) { Text("Batal") } }
        )
    }
}

@Composable
private fun CheckResultBanner(res: IntegrationCheckResult) {
    val bg = if (res.ok) Color(0xFFE8F5E9) else Color(0xFFFCE8E6)
    val fg = if (res.ok) Color(0xFF1B5E20) else Color(0xFFB3261E)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (res.ok) "✓" else "✕",
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = fg)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            val latencyText = if (res.latencyMs > 0) " (${res.latencyMs} ms)" else ""
            Text(
                "${res.service}$latencyText",
                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = fg)
            )
            Text(
                res.safeDetail,
                style = TextStyle(fontSize = 12.sp, color = fg)
            )
        }
    }
}

/** Ikon merek kecil di dalam kolom isian (warna asli, tanpa tint). */
private fun brandIcon(@androidx.annotation.DrawableRes res: Int): @Composable () -> Unit = {
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(res), contentDescription = null, modifier = Modifier.size(20.dp))
}


private enum class CodexTone { OK, WORK, WARN }

private data class CodexCardState(
    val tone: CodexTone,
    val title: String,
    val sub: String,
    val retry: Boolean = false,
    val retryLabel: String = "Coba lagi",
)

/** Kartu status Codex CLI: indikator kesiapan app-server, penjelasan, dan tombol aksi bila perlu. */
@Composable
private fun CodexStatusCard(c: CodexCardState, onRetry: () -> Unit) {
    val dot = when (c.tone) {
        CodexTone.OK -> Color(0xFF2E7D32)
        CodexTone.WORK -> MythosColors.Hint
        CodexTone.WARN -> MythosColors.Accent
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MythosColors.Card)
            .border(1.dp, MythosColors.Border, RoundedCornerShape(12.dp)).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (c.tone == CodexTone.WORK) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = dot)
            } else {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            }
            Spacer(Modifier.width(10.dp))
            Text(c.title, style = TextStyle(fontSize = 15.sp, color = MythosColors.Ink))
        }
        Text(c.sub, style = TextStyle(fontSize = 13.sp, color = MythosColors.Hint), modifier = Modifier.padding(top = 4.dp))
        if (c.retry) {
            TextButton(onClick = onRetry) { Text(c.retryLabel) }
        }
    }
}
