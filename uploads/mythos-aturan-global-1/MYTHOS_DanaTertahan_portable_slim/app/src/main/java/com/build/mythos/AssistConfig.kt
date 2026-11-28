package com.build.mythos

import android.content.Context
import com.build.mythos.cc.ProviderConfig
import com.build.mythos.cc.ProviderKind
import com.build.mythos.cc.defaultMaxOutputTokens

/** Hasil pembacaan model dari Pengaturan MYTHOS untuk panel asisten (yang berjalan di luar MainActivity). */
internal sealed interface AssistCfg {
    class Ready(
        val cfg: ProviderConfig,
        val providerName: String,
        val keys: List<String> = listOf(cfg.apiKey).filter { it.isNotBlank() },
        val activeModels: List<String> = listOf(cfg.model).filter { it.isNotBlank() },
    ) : AssistCfg
    class Missing(val message: String) : AssistCfg
}

/**
 * Membaca penyedia, model, alamat dasar, dan API key yang sama dengan yang dipakai chat utama
 * (SharedPreferences "cfg", kunci terenkripsi Keystore). Penyedia CLI tidak didukung panel ini.
 */
internal object AssistConfig {
    private fun decrypt(ctx: Context, name: String): String {
        val raw = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString(name, "") ?: ""
        if (raw.isEmpty()) return ""
        return (Secret.decrypt(raw) as? SecretDecryptResult.Success)?.value.orEmpty()
    }

    fun load(ctx: Context): AssistCfg {
        val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val legacyGemini = !prefs.getString("key", "").isNullOrEmpty()
        val prov = initialProvider(prefs.getString("prov", null), legacyGemini)
        val preset = BASE_PRESETS.firstOrNull { it.name == prov }
            ?: return AssistCfg.Missing("Penyedia \"$prov\" memakai CLI dan belum bisa dipakai di panel ini. Pilih penyedia API (mis. Gemini, OpenAI, Anthropic, OpenRouter) di Pengaturan MYTHOS.")
        val kind = when (preset.fmt) {
            "gemini" -> ProviderKind.GEMINI
            "anthropic" -> ProviderKind.ANTHROPIC
            else -> ProviderKind.OPENAI_COMPATIBLE
        }
        fun pv(k: String) = prefs.getString("p_${prov}_$k", "").orEmpty()
        val base = AssistLogic.normalizeBase(preset.fmt, pv("base").ifEmpty { preset.base })
        val model = (lockedModelFor(prov) ?: pv("model").ifEmpty { preset.model }).trim()
        var rawKey = decrypt(ctx, "p_${prov}_key")
        if (rawKey.isEmpty() && prov == "Gemini") rawKey = decrypt(ctx, "key")
        val keys = parseApiKeys(rawKey)
        val key = keys.firstOrNull().orEmpty()
        if (base.isEmpty()) return AssistCfg.Missing("Alamat dasar untuk \"$prov\" belum diisi. Buka Pengaturan MYTHOS.")
        if (model.isEmpty()) return AssistCfg.Missing("Model untuk \"$prov\" belum dipilih. Buka MYTHOS lalu pilih model.")
        if (key.isBlank() && !base.startsWith("http://")) return AssistCfg.Missing("API key untuk \"$prov\" belum diisi. Buka MYTHOS lalu hubungkan AI Anda.")
        val probedGood = parseActiveProbedModels(pv("probe"), key)
        val activeModels = activeModelsForFailover(model, probedGood, prov)
        return AssistCfg.Ready(
            ProviderConfig(kind, base, key, model, maxOutputTokens = defaultMaxOutputTokens(kind, base, model)),
            prov,
            keys,
            activeModels,
        )
    }
}
