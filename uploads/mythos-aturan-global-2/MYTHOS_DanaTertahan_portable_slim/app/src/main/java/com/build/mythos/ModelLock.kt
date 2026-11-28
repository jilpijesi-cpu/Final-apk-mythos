package com.build.mythos

// Model terkunci per penyedia. OpenRouter dengan API key hanya memakai openrouter/free (router model gratis OpenRouter):
// daftar model, ketik manual, hasil uji, dan peralihan failover dalam penyedia ini tidak bisa mengubahnya. Murni Kotlin (ModelLockTest).

internal const val OPENROUTER_FREE_MODEL = "openrouter/free"

/** Model yang dikunci untuk [provider], atau null bila penyedia itu bebas memilih model. */
internal fun lockedModelFor(provider: String): String? = null

internal fun lockedModelMessage(provider: String): String =
    "Model $provider dikunci ke ${lockedModelFor(provider) ?: ""}."

/** Cadangan satu penyedia: model terkunci saja bila ada, kalau tidak hasil [others]. */
internal fun poolModelsFor(provider: String, others: () -> List<String>): List<String> =
    lockedModelFor(provider)?.let { listOf(it) } ?: others()

/** Penyedia utama bawaan untuk pemasangan baru. */
internal const val DEFAULT_PROVIDER = "OpenRouter"

/**
 * Penyedia utama saat aplikasi dibuka: pilihan tersimpan bila ada. Tanpa pilihan tersimpan, pengguna lama yang masih punya
 * API key Gemini versi lama (kunci "key") tetap di Gemini supaya tidak tiba-tiba pindah ke penyedia tanpa kunci; selain itu [DEFAULT_PROVIDER].
 */
internal fun initialProvider(saved: String?, hasLegacyGeminiKey: Boolean): String =
    saved?.takeIf { it.isNotBlank() } ?: if (hasLegacyGeminiKey) "Gemini" else DEFAULT_PROVIDER
