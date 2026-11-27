package com.build.mythos

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Base64

internal fun MainActivity.showTermuxSettingsSync() {
    val info = TextView(this).apply {
        textSize = Typo.BODY_S
        setTextColor(Palette.INK)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        text = "Memeriksa koneksi Termux…"
    }
    val note = TextView(this).apply {
        text = "Yang disinkronkan: folder kerja, URL repository, penyedia AI aktif, dan nama model. API key, token GitHub, token Gemini/Claude/Codex, instruksi pribadi, serta kredensial CLI tidak disalin. Perubahan dari Termux diambil saat kamu menekan ‘Ambil dari Termux’."
        textSize = Typo.CAPTION
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(18), 0, dp(18), dp(14))
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(info)
        addView(note)
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle("Sinkronisasi dua arah Termux")
        .setView(content)
        .setNegativeButton("Ambil dari Termux", null)
        .setNeutralButton("Periksa", null)
        .setPositiveButton("Kirim ke Termux", null)
        .create()

    fun <T> runTask(
        label: String,
        task: () -> T,
        onError: (String) -> Unit = { info.text = it },
        onSuccess: (T) -> Unit
    ) {
        info.text = "$label…"
        content.isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        bg {
            val outcome = try { Result.success(task()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                content.isEnabled = true
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                outcome.onSuccess(onSuccess).onFailure { e ->
                    onError("Gagal: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun inspect() {
        runTask("Memeriksa Termux dan alat yang tersedia", { inspectTermuxSettings() }) { info.text = it }
    }

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { inspect() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runTask("Mengirim pengaturan aplikasi ke Termux", { pushTermuxSettings() }) { result ->
                info.text = result
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            runTask("Membaca pengaturan Termux", { pullTermuxSettings() }) { pulled ->
                if (pulled !is TermuxSyncPullResult.Ok) {
                    info.text = (pulled as TermuxSyncPullResult.Fail).message
                    return@runTask
                }
                val raw = pulled.raw
                val remote = TermuxSettingsCodec.decode(raw)
                if (remote == null) {
                    info.text = "Format pengaturan Termux tidak dikenal atau rusak. Tidak ada pengaturan aplikasi yang diubah."
                    return@runTask
                }
                val nextWorkdir = remote.workdir?.let { WorkspacePaths.normalizeSetting(it) }
                val workdirError = remote.workdir?.let { WorkspacePaths.validationError(it, termuxHome) }
                val repo = remote.repoUrl.orEmpty()
                val repoValid = repo.isBlank() || parseRepoLink(repo) != null
                val nextProvider = remote.provider ?: prov
                val providerValid = remote.provider == null || presets.any { it.name == remote.provider }
                val nextModel = remote.model
                val modelValid = nextModel == null || when {
                    lockedModelFor(nextProvider) != null -> nextModel == lockedModelFor(nextProvider)
                    nextProvider == "Gemini CLI (Google)" -> isValidGeminiCliModel(normalizeGeminiCliModel(nextModel))
                    else -> nextModel.length <= 200 && nextModel.none { Character.isISOControl(it) }
                }
                if (remote.workdir != null && (nextWorkdir == null || workdirError != null)) {
                    info.text = "Folder kerja dari Termux ditolak: ${workdirError ?: "path tidak valid"}. Pengaturan aplikasi tetap tidak berubah."
                    return@runTask
                }
                if (!repoValid) {
                    info.text = "URL repository dari Termux tidak valid. Pengaturan aplikasi tetap tidak berubah."
                    return@runTask
                }
                if (!providerValid || !modelValid) {
                    info.text = "Penyedia atau nama model dari Termux tidak valid/tidak didukung. Pengaturan aplikasi tetap tidak berubah."
                    return@runTask
                }
                val oldWorkdir = workdir()
                val oldRepo = prefs.getString("github_repo_url", "").orEmpty()
                val newWorkdir = nextWorkdir ?: oldWorkdir
                val newRepo = if (remote.repoUrl != null) parseRepoLink(repo)?.url.orEmpty() else oldRepo
                val oldModel = cfgModel()
                val providerChanged = remote.provider != null && nextProvider != prov
                val newModel = when {
                    nextModel != null && nextModel.isNotBlank() -> nextModel
                    providerChanged || nextModel != null -> lockedModelFor(nextProvider) ?: preset(nextProvider).model
                    else -> oldModel
                }
                val probe = pulled.probe
                val workdirWarning = when {
                    remote.workdir == null || newWorkdir == oldWorkdir -> ""
                    probe == "inside_git_repo" -> "\n\nPERINGATAN: folder kerja berada di dalam repository Git. Agen dapat mengubah source di lokasi itu. Gunakan folder terpisah seperti ~/MYTHOS bila memungkinkan."
                    probe != "outside_git_repo" -> "\n\nPERINGATAN: lokasi tidak dapat diverifikasi terhadap repository Git. Periksa path sebelum melanjutkan."
                    else -> ""
                }
                AlertDialog.Builder(this)
                    .setTitle("Terapkan pengaturan dari Termux?")
                    .setMessage(
                        "Folder kerja: $oldWorkdir → $newWorkdir\n" +
                            "Repository: ${oldRepo.ifBlank { "(kosong)" }} → ${newRepo.ifBlank { "(kosong)" }}\n" +
                            "Penyedia/model: $prov / $oldModel → $nextProvider / $newModel\n\n" +
                            "Hanya metadata non-rahasia ini yang akan diperbarui. Workspace dan file proyek tidak dipindah atau dihapus." + workdirWarning
                    )
                    .setNegativeButton("Batal", null)
                    .setPositiveButton(if (workdirWarning.isEmpty()) "Terapkan" else "Tetap gunakan") { _, _ ->
                        val edit = prefs.edit()
                        if (remote.workdir != null) edit.putString("workdir", newWorkdir)
                        if (remote.repoUrl != null) edit.putString("github_repo_url", newRepo)
                        if (remote.provider != null) edit.putString("prov", nextProvider)
                        if (remote.model != null || providerChanged) {
                            // Nilai kosong atau provider baru tanpa model berarti gunakan preset default.
                            if (newModel.isBlank()) edit.remove("p_${nextProvider}_model")
                            else edit.putString("p_${nextProvider}_model", newModel)
                        }
                        edit.putBoolean("termux_sync_enabled", true)
                        edit.apply()
                        prov = nextProvider
                        bg { try { ensureWorkspaceLayout() } catch (ignored: Exception) { logQuiet("TermuxSettingsSync.kt", ignored) } }
                        updateModelLabel()
                        info.text = "Sinkronisasi selesai: pengaturan Termux diterapkan ke aplikasi.\n\nFolder kerja: $newWorkdir\nRepository: ${newRepo.ifBlank { "(kosong)" }}\nPenyedia/model: $nextProvider / $newModel"
                    }
                    .show()
            }
        }
    }
    dialog.show()
    inspect()
}

/** Tulis empat preferensi allowlist non-rahasia ke ruang konfigurasi khusus MYTHOS di HOME Termux. */
internal fun MainActivity.pushTermuxSettings(): String {
    val currentWorkdir = WorkspacePaths.normalizeSetting(workdir())
        ?: return "Folder kerja aplikasi tidak valid; tidak ada data yang dikirim."
    val repo = parseRepoLink(prefs.getString("github_repo_url", "").orEmpty())?.url.orEmpty()
    val timestamp = System.currentTimeMillis() / 1000L
    val currentProvider = prov
    val currentModel = cfgModel()
    val config = TermuxSettingsCodec.encode(currentWorkdir, repo, currentProvider, currentModel, timestamp)
    val helper = TERMUX_SETTINGS_HELPER
    val configPath = "$termuxHome/.config/mythos/android-app-sync-v1.properties"
    val helperPath = "${WorkspacePaths.TERMUX_BIN}/mythos-settings"
    val configB64 = Base64.getEncoder().encodeToString(config.toByteArray(Charsets.UTF_8))
    val helperB64 = Base64.getEncoder().encodeToString(helper.toByteArray(Charsets.UTF_8))
    val cmd = """
        set -eu
        umask 077
        mkdir -p ${sh("$termuxHome/.config/mythos")} ${sh(WorkspacePaths.TERMUX_BIN)}
        if [ -e ${sh(helperPath)} ] && ! grep -q '^# MYTHOS_SETTINGS_BRIDGE_V1${'$'}' ${sh(helperPath)}; then
            echo 'Perintah mythos-settings sudah ada dan bukan milik MYTHOS; file tidak ditimpa.' >&2
            exit 4
        fi
        config_tmp=${'$'}(mktemp ${sh("$configPath.tmp.XXXXXX")})
        trap 'rm -f "${'$'}config_tmp" "${'$'}{helper_tmp:-}"' EXIT
        helper_tmp=${'$'}(mktemp ${sh("$helperPath.tmp.XXXXXX")})
        printf %s ${sh(configB64)} | base64 -d > "${'$'}config_tmp"
        chmod 600 "${'$'}config_tmp"
        mv -f "${'$'}config_tmp" ${sh(configPath)}
        printf %s ${sh(helperB64)} | base64 -d > "${'$'}helper_tmp"
        chmod 700 "${'$'}helper_tmp"
        mv -f "${'$'}helper_tmp" ${sh(helperPath)}
        echo MYTHOS_SYNC_PUSH_OK
    """.trimIndent()
    val result = termux(cmd, limit = 1200, timeoutSec = 20, useWorkdir = false, killable = false)
    return if (result.startsWith("exit=0") && result.contains("MYTHOS_SYNC_PUSH_OK")) {
        prefs.edit().putBoolean("termux_sync_enabled", true).apply()
        "Berhasil: pengaturan aplikasi dikirim ke Termux.\n\nFolder kerja: $currentWorkdir\nRepository: ${repo.ifBlank { "(kosong)" }}\n\n" +
            "Perintah di Termux:\nmythos-settings show\nmythos-settings set workdir MYTHOS\nmythos-settings set repo https://github.com/pemilik/nama-repo\nmythos-settings set provider \"Gemini CLI (Google)\"\nmythos-settings set model gemini-2.5-flash\n\n" +
            "Perintah set hanya mengubah metadata sinkronisasi MYTHOS, bukan memindahkan folder atau mengganti remote Git proyek."
    } else "Pengiriman gagal.\n$result"
}

/** Baca-saja: status paket/CLI/auth asli di Termux. Tidak pernah mencetak token atau isi berkas kredensial. */
private fun MainActivity.inspectTermuxSettings(): String {
    val cmd = """
        printf 'bridge='; [ -x ${sh("${WorkspacePaths.TERMUX_BIN}/mythos-settings")} ] && echo installed || echo not_installed
        printf 'config='; [ -f ${sh("$termuxHome/.config/mythos/android-app-sync-v1.properties")} ] && echo present || echo absent
        printf 'gh='; command -v gh >/dev/null 2>&1 && echo available || echo missing
        if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then echo gh_auth=connected; else echo gh_auth=not_connected; fi
        for name in git node npm python3 java proot-distro gemini codex claude; do
            if command -v "${'$'}name" >/dev/null 2>&1; then
                case "${'$'}name" in
                    git) version=${'$'}(git --version 2>/dev/null | head -n 1) ;;
                    node) version=${'$'}(node --version 2>/dev/null | head -n 1) ;;
                    npm) version=${'$'}(npm --version 2>/dev/null | head -n 1) ;;
                    python3) version=${'$'}(python3 --version 2>&1 | head -n 1) ;;
                    java) version=${'$'}(java -version 2>&1 | head -n 1) ;;
                    proot-distro) version=available ;;
                    *) version=${'$'}("${'$'}name" --version 2>&1 | head -n 1) ;;
                esac
                printf '%s=%s\n' "${'$'}name" "${'$'}{version:-available}"
            else
                printf '%s=missing\n' "${'$'}name"
            fi
        done
        echo MYTHOS_SYNC_INSPECT_OK
    """.trimIndent()
    val result = termux(cmd, limit = 3500, timeoutSec = 30, useWorkdir = false, killable = false)
    if (!result.startsWith("exit=0") || !result.contains("MYTHOS_SYNC_INSPECT_OK")) return "Termux belum dapat diperiksa.\n$result"
    val details = result.substringAfter('\n').substringBefore("MYTHOS_SYNC_INSPECT_OK").trim()
    return buildString {
        appendLine("Koneksi Termux aktif.")
        appendLine("Status autentikasi GitHub: ${if (details.lineSequence().any { it.trim() == "gh_auth=connected" }) "terhubung melalui gh CLI" else "belum terhubung / gh belum siap"}")
        appendLine()
        appendLine("Alat di Termux:")
        details.lineSequence().filter { it.contains('=') && !it.startsWith("bridge=") && !it.startsWith("config=") && !it.startsWith("gh=") && !it.startsWith("gh_auth=") }
            .forEach { appendLine("• $it") }
        appendLine()
        appendLine("Jembatan MYTHOS: ${if (details.lineSequence().any { it.trim() == "bridge=installed" }) "terpasang" else "belum terpasang (tekan Kirim ke Termux sekali)"}")
    }.trim()
}

private sealed class TermuxSyncPullResult {
    data class Ok(val raw: String, val probe: String) : TermuxSyncPullResult()
    data class Fail(val message: String) : TermuxSyncPullResult()
}

private fun MainActivity.pullTermuxSettings(): TermuxSyncPullResult {
    val path = "$termuxHome/.config/mythos/android-app-sync-v1.properties"
    val result = termux("test -f ${sh(path)} && cat ${sh(path)}", limit = 3000, timeoutSec = 15, useWorkdir = false, killable = false)
    if (!result.startsWith("exit=0\n")) {
        return TermuxSyncPullResult.Fail("MYTHOS_SYNC_READ_FAIL\nJembatan belum ditemukan. Tekan ‘Kirim ke Termux’ sekali untuk memasangnya, lalu coba lagi.\n$result")
    }
    val raw = result.removePrefix("exit=0\n")
    val remote = TermuxSettingsCodec.decode(raw)
    val workdir = remote?.workdir
    val probe = if (workdir == null) {
        "not_checked"
    } else {
        val normalized = WorkspacePaths.normalizeSetting(workdir)
        val error = WorkspacePaths.validationError(workdir, termuxHome)
        if (normalized == null || error != null) "not_checked"
        else {
            val target = WorkspacePaths.resolve(normalized, termuxHome)
            val check = try {
                termux(WorkspacePaths.gitRootProbeCommand(target) { sh(it) }, limit = 400, timeoutSec = 12, useWorkdir = false, killable = false)
            } catch (_: Exception) { "" }
            when {
                check.startsWith("exit=0") && check.contains("MYTHOS_WORKDIR_IN_GIT_REPO") -> "inside_git_repo"
                check.startsWith("exit=0") && check.contains("MYTHOS_WORKDIR_NOT_IN_GIT_REPO") -> "outside_git_repo"
                else -> "not_checked"
            }
        }
    }
    return TermuxSyncPullResult.Ok(raw.trimEnd(), probe)
}

private const val TERMUX_SETTINGS_HELPER = """#!${WorkspacePaths.TERMUX_BIN}/bash
# MYTHOS_SETTINGS_BRIDGE_V1
set -eu
CFG="${'$'}HOME/.config/mythos/android-app-sync-v1.properties"
mkdir -p "${'$'}(dirname "${'$'}CFG")"
get_encoded() {
  [ -f "${'$'}CFG" ] || return 0
  awk -F= -v k="${'$'}1" '${'$'}1 == k { sub(/^[^=]*=/, ""); print; exit }' "${'$'}CFG"
}
decode_value() {
  [ -n "${'$'}{1:-}" ] || return 0
  printf '%s' "${'$'}1" | base64 -d 2>/dev/null || true
}
write_value() {
  key="${'$'}1"; value="${'$'}2"
  case "${'$'}value" in *${'$'}'\n'*|*${'$'}'\r'*) echo "Nilai tidak boleh berisi baris baru." >&2; exit 2;; esac
  case "${'$'}key" in
    workdir) storage_key=workdir_b64 ;;
    repo) storage_key=repo_url_b64 ;;
    provider) storage_key=provider_b64 ;;
    model) storage_key=model_b64 ;;
    *) echo "Kunci yang didukung: workdir, repo, provider, model" >&2; exit 2 ;;
  esac
  encoded="${'$'}(printf '%s' "${'$'}value" | base64 | tr -d '\r\n')"
  tmp="${'$'}(mktemp "${'$'}{CFG}.tmp.XXXXXX")"
  trap 'rm -f "${'$'}{tmp:-}"' EXIT
  if [ -f "${'$'}CFG" ]; then
    if [ "${'$'}key" = provider ]; then
      awk -F= -v k="${'$'}storage_key" '${'$'}1 != k && ${'$'}1 != "model_b64" && ${'$'}1 != "updated_at" { print }' "${'$'}CFG" > "${'$'}tmp"
    else
      awk -F= -v k="${'$'}storage_key" '${'$'}1 != k && ${'$'}1 != "updated_at" { print }' "${'$'}CFG" > "${'$'}tmp"
    fi
  else : > "${'$'}tmp"; fi
  [ -s "${'$'}tmp" ] || printf 'schema=1\n' > "${'$'}tmp"
  printf '%s=%s\n' "${'$'}storage_key" "${'$'}encoded" >> "${'$'}tmp"
  printf 'updated_at=%s\n' "${'$'}(date +%s)" >> "${'$'}tmp"
  chmod 600 "${'$'}tmp"
  mv "${'$'}tmp" "${'$'}CFG"
  echo "Tersimpan: ${'$'}key. Buka MYTHOS > Pengaturan > Sinkronisasi dua arah Termux > Ambil dari Termux."
}
case "${'$'}{1:-show}" in
  show)
    printf 'Folder kerja: %s\n' "${'$'}(decode_value "${'$'}(get_encoded workdir_b64)")"
    printf 'Repository: %s\n' "${'$'}(decode_value "${'$'}(get_encoded repo_url_b64)")"
    printf 'Penyedia: %s\n' "${'$'}(decode_value "${'$'}(get_encoded provider_b64)")"
    printf 'Model: %s\n' "${'$'}(decode_value "${'$'}(get_encoded model_b64)")"
    ;;
  set)
    [ "${'$'}#" -eq 3 ] || { echo 'Pakai: mythos-settings set workdir|repo|provider|model NILAI' >&2; exit 2; }
    write_value "${'$'}2" "${'$'}3"
    ;;
  export)
    [ -f "${'$'}CFG" ] && cat "${'$'}CFG" || exit 1
    ;;
  *) echo 'Perintah: mythos-settings show | set workdir NILAI | set repo URL | set provider NAMA | set model NAMA | export' ;;
esac
"""
