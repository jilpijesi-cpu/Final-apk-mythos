package com.build.mythos

import java.util.Base64

/** Shared non-secret settings mirrored between MYTHOS and Termux. */
internal data class SharedTermuxSettings(
    val workdir: String?,
    val repoUrl: String?,
    val provider: String?,
    val model: String?,
    val updatedAt: Long?
)

/** Versioned properties codec. Values are Base64 to preserve Unicode and arbitrary shell characters. */
internal object TermuxSettingsCodec {
    fun encode(workdir: String, repoUrl: String, provider: String, model: String, updatedAt: Long): String = buildString {
        appendLine("schema=1")
        appendLine("workdir_b64=${encodeValue(workdir)}")
        appendLine("repo_url_b64=${encodeValue(repoUrl)}")
        appendLine("provider_b64=${encodeValue(provider)}")
        appendLine("model_b64=${encodeValue(model)}")
        appendLine("updated_at=$updatedAt")
    }

    fun decode(raw: String): SharedTermuxSettings? {
        val entries = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val p = line.indexOf('=')
            if (p > 0) entries[line.substring(0, p)] = line.substring(p + 1)
        }
        if (entries["schema"] != "1") return null
        fun value(key: String): String? {
            val encoded = entries[key] ?: return null
            return try {
                String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val workdir = value("workdir_b64")
        val repoUrl = value("repo_url_b64")
        val provider = value("provider_b64")
        val model = value("model_b64")
        if (entries.containsKey("workdir_b64") && workdir == null) return null
        if (entries.containsKey("repo_url_b64") && repoUrl == null) return null
        if (entries.containsKey("provider_b64") && provider == null) return null
        if (entries.containsKey("model_b64") && model == null) return null
        return SharedTermuxSettings(workdir, repoUrl, provider, model, entries["updated_at"]?.toLongOrNull())
    }

    private fun encodeValue(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
