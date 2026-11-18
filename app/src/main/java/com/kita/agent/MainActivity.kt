package com.kita.agent

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

    internal val presets = listOf(
        Preset("Gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.8-flash"),
        Preset("OpenAI", "openai", "https://api.openai.com/v1", ""),
        Preset("Anthropic", "anthropic", "https://api.anthropic.com/v1", ""),
        Preset("OpenRouter", "openai", "https://openrouter.ai/api/v1", ""),
        Preset("Groq", "openai", "https://api.groq.com/openai/v1", ""),
        Preset("DeepSeek", "openai", "https://api.deepseek.com", ""),
        Preset("Ollama lokal", "openai", "http://127.0.0.1:11434/v1", ""),
        Preset("Kustom (OpenAI)", "openai", "", "")
    ) + CLI_LOGINS.map { Preset(it.name, "cli", it.run, "") } + Preset("CLI Termux", "cli", "gemini -p {prompt}", "")

    internal var prov = "Gemini"

    @Volatile internal var agentState: AgentState = AgentState.Idle
    @Volatile internal var agentTaskId = ""

    internal fun preset(n: String) = presets.firstOrNull { it.name == n } ?: presets[0]

    @Volatile internal var keyWarning: String? = null

    private fun readStoredKey(storageName: String): String {
        val raw = prefs.getString(storageName, "") ?: ""
        return when (val result = Secret.decrypt(raw)) {
            is SecretDecryptResult.Success -> result.value
            SecretDecryptResult.Missing -> {
                prefs.edit().remove(storageName).apply()
                keyWarning = "API key lama tidak dapat dibuka karena kunci Keystore hilang. Masukkan ulang API key."
                ""
            }
            SecretDecryptResult.Corrupt -> {
                prefs.edit().remove(storageName).apply()
                keyWarning = "API key tersimpan rusak dan sudah dihapus. Masukkan ulang API key."
                ""
            }
            SecretDecryptResult.KeystoreUnavailable -> {
                keyWarning = "Android Keystore tidak tersedia. API key tidak dibaca; coba lagi atau masukkan ulang nanti."
                ""
            }
        }
    }

    internal fun pvOf(n: String, k: String): String {
        val storageName = "p_${n}_$k"
        return if (k == "key") readStoredKey(storageName) else prefs.getString(storageName, "") ?: ""
    }

    /** Enkripsi kunci API lama yang masih tersimpan sebagai teks biasa. */
    internal fun migrateKeys() {
        val ed = prefs.edit()
        var changed = false
        for (pr in presets) {
            val k = "p_${pr.name}_key"
            val raw = prefs.getString(k, "") ?: ""
            if (raw.isNotEmpty() && !Secret.isEncrypted(raw)) {
                val enc = Secret.encrypt(raw).getOrNull()
                if (enc != null && Secret.isEncrypted(enc)) { ed.putString(k, enc); changed = true }
                else { ed.remove(k); changed = true; keyWarning = "API key lama tidak dapat diamankan dan sudah dihapus. Masukkan ulang API key." }
            }
        }
        val old = prefs.getString("key", "") ?: ""
        if (old.isNotEmpty() && !Secret.isEncrypted(old)) {
            val enc = Secret.encrypt(old).getOrNull()
            if (enc != null && Secret.isEncrypted(enc)) { ed.putString("key", enc); changed = true }
            else { ed.remove("key"); changed = true; keyWarning = "API key lama tidak dapat diamankan dan sudah dihapus. Masukkan ulang API key." }
        }
        if (changed) ed.apply()
    }

    internal fun cfgKey(): String {
        val k = pvOf(prov, "key")
        return if (k.isEmpty() && prov == "Gemini") readStoredKey("key") else k
    }

    /** Model cadangan sementara selama satu tugas (diisi callModelRetry saat model utama sibuk/kuota habis). */
    @Volatile internal var modelOverride: String? = null

    /** Penyedia cadangan sementara selama satu tugas (memakai API key lain yang sudah terdaftar). Null = pakai penyedia utama. */
    @Volatile internal var fallbackProv: String? = null

    /** Model yang dipakai pada [fallbackProv] (model penyedia lain, bukan pilihan di Pengaturan). */
    @Volatile internal var fallbackModel: String? = null

    /** True selama peralihan otomatis: percobaan ulang dipersingkat agar pindah ke AI lain lebih cepat. */
    @Volatile internal var fastFail = false

    /** Cache daftar model per penyedia, dan model yang gagal selama tugas ini (kuota habis / ditolak). Dikosongkan tiap tugas baru. */
    internal val poolCache = HashMap<String, ProviderPool?>()
    internal val hardFailed = HashSet<String>()

    internal fun resetFailover() {
        modelOverride = null
        fallbackProv = null
        fallbackModel = null
        fastFail = false
        poolCache.clear()
        hardFailed.clear()
    }

    internal fun cfgModel(): String = modelOverride ?: pvOf(prov, "model").ifEmpty { preset(prov).model }

    internal fun cfgBase(): String = pvOf(prov, "base").ifEmpty { preset(prov).base }
