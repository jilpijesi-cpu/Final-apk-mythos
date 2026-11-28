package com.build.mythos

import android.view.ViewGroup
import org.json.JSONObject

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
internal const val USER = 0
internal const val AI = 1
internal const val KIND_HIDE = -3 // penanda: sembunyikan baris status
internal const val KIND_DOTS = -1 // penanda: tampilkan titik animasi sebagai ikon status
// Penanda logo layanan di baris status live: titik oranye berganti sebentar menjadi logo aslinya (Gmail, Telegram, WhatsApp, PDF, GitHub), lalu kembali ke titik.
internal const val KIND_LOGO_GMAIL = -10
internal const val KIND_LOGO_TELEGRAM = -11
internal const val KIND_LOGO_PDF = -12
internal const val KIND_LOGO_WHATSAPP = -13
internal const val KIND_LOGO_GITHUB = -14

/** Penanda pada tampilan pesan: siapa pengirimnya dan teks mentahnya (untuk menu tekan-lama). */
internal class MsgMeta(val role: Int, var text: String)

/** Satu baris pemeriksaan di layar "Siapkan Termux". */
internal class SetupStep(
    val label: String,
    val ok: Boolean,
    val help: String = "",
    val acts: List<Pair<String, () -> Unit>> = emptyList()
)

/** Dilempar saat pengguna menekan Stop. */
internal class Cancelled : RuntimeException("Dihentikan")

class Call(val id: String, val name: String, val args: JSONObject)

/** Riwayat netral: role 0 = pengguna, 1 = model, 2 = hasil tool. Dikonversi ke format tiap penyedia. */
class HistImage(val path: String, val previewPath: String? = null) {
    @Volatile internal var cachedPayload: EncodedImage? = null
    /** true setelah tugas selesai dan semua salinan sementara dihapus; gambar tidak dikirim/disimpan lagi. */
    @Volatile internal var released: Boolean = false
}

/** File yang diserahkan agen ke chat (alat Present): [rel] = "<idChat>/<nama>" di chat_files, supaya kartu unduh pulih saat chat dibuka ulang. */
class HistFile(val rel: String, val name: String, val size: Long)

class Hist(
    val role: Int,
    val text: String = "",
    val calls: List<Call> = emptyList(),
    val results: List<Pair<Call, String>> = emptyList(),
    val raw: JSONObject? = null,
    val images: List<HistImage> = emptyList(),
    val files: List<HistFile> = emptyList(),
    /** Jejak tampilan satu tugas agen (narasi + rangkaian langkah), hanya untuk dipulihkan di layar saat chat dibuka ulang; tidak dikirim ke model. */
    val trail: org.json.JSONArray? = null
)

internal class Preset(val name: String, val fmt: String, val base: String, val model: String)

/** Penyedia API bawaan (tanpa CLI). Dipakai bersama oleh MainActivity dan panel asisten (AssistConfig). */
internal val BASE_PRESETS = listOf(
    Preset("Gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.8-flash"),
    Preset("OpenAI", "openai", "https://api.openai.com/v1", ""),
    Preset("Anthropic", "anthropic", "https://api.anthropic.com/v1", ""),
    Preset("OpenRouter", "openai", "https://openrouter.ai/api/v1", OPENROUTER_FREE_MODEL),
    Preset("Groq", "openai", "https://api.groq.com/openai/v1", ""),
    Preset("DeepSeek", "openai", "https://api.deepseek.com", ""),
    Preset("Ollama lokal", "openai", "http://127.0.0.1:11434/v1", ""),
    Preset("Kustom (OpenAI)", "openai", "", "")
)
