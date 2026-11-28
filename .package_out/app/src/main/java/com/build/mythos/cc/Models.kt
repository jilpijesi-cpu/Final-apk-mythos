package com.build.mythos.cc

enum class Role { USER, ASSISTANT }

/** Gambar terlampir pada pesan (base64 tanpa awalan data:). */
class ImagePart(val mime: String, val base64: String)

data class Msg(val role: Role, val text: String, val images: List<ImagePart> = emptyList())

enum class Risk { SAFE, WRITE, DANGEROUS }
enum class Effect { READ, WRITE, EXEC, META }
enum class StepStatus { TODO, DOING, DONE, BLOCKED }

data class PlanStep(val title: String, val status: StepStatus)

/**
 * Hasil satu pemanggilan alat.
 * changed   : file yang diubah (masuk daftar "belum diverifikasi")
 * readPaths : file yang baru dibaca ulang (menghapus status "belum diverifikasi")
 * verifies  : true bila perintah build/test sukses (menghapus semua status belum diverifikasi)
 */
data class ToolResult(
    val ok: Boolean,
    val text: String,
    val changed: List<String> = emptyList(),
    val readPaths: List<String> = emptyList(),
    val verifies: Boolean = false,
)

sealed interface AgentEvent {
    data class Thought(val step: Int, val text: String) : AgentEvent
    data class PlanChanged(val steps: List<PlanStep>) : AgentEvent
    data class ToolStart(val step: Int, val tool: String, val summary: String) : AgentEvent
    data class ToolEnd(val step: Int, val tool: String, val ok: Boolean, val preview: String, val millis: Long) : AgentEvent
    data class Notice(val text: String) : AgentEvent
    data class Final(val summary: String, val verified: Boolean, val steps: Int) : AgentEvent
    data class Failed(val reason: String) : AgentEvent
    /** Balasan model sedang mengalir (streaming): label singkat untuk baris hidup, mis. "Menulis App.kt…". Tidak disimpan di riwayat. */
    data class Streaming(val step: Int, val label: String) : AgentEvent
}

data class AgentResult(val success: Boolean, val summary: String, val verified: Boolean, val steps: Int)

class AgentAbort(message: String) : Exception(message)
class ArgError(message: String) : Exception(message)
