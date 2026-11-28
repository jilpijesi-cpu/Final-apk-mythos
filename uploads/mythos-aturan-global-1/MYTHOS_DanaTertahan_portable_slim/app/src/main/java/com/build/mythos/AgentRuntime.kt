package com.build.mythos

/** State tunggal agen; flag lama tetap dipertahankan sementara untuk kompatibilitas UI. */
internal sealed interface AgentState {
    data object Idle : AgentState
    data object Planning : AgentState
    data class CallingModel(val attempt: Int) : AgentState
    data class AwaitingApproval(val tool: String) : AgentState
    data class RunningTool(val tool: String) : AgentState
    data object Verifying : AgentState
    data class Completed(val report: Report?) : AgentState
    data class Failed(val message: String) : AgentState
    data object Cancelled : AgentState
}

internal enum class ErrorKind {
    NONE, NETWORK_TRANSIENT, NETWORK_AUTH, MODEL_RATE_LIMIT, INVALID_TOOL_ARGUMENT,
    USER_DENIED, TERMUX_UNAVAILABLE, COMMAND_TIMEOUT, COMMAND_FAILED,
    VERIFICATION_FAILED, CANCELLED, UNKNOWN
}

internal data class ToolResult(
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    /** Tindakan tidak dijalankan: ditolak pengguna, tidak dijawab, atau diblokir sandbox/mode hanya baca. */
    val denied: Boolean = false
) {
    val ok: Boolean get() = !timedOut && !cancelled && !denied && exitCode == 0
    val errorKind: ErrorKind get() = when {
        cancelled -> ErrorKind.CANCELLED
        denied -> ErrorKind.USER_DENIED
        timedOut -> ErrorKind.COMMAND_TIMEOUT
        ok -> ErrorKind.NONE
        exitCode == 401 || exitCode == 403 -> ErrorKind.NETWORK_AUTH
        exitCode == 408 || exitCode == 429 || exitCode == 500 || exitCode == 502 || exitCode == 503 || exitCode == 504 || exitCode == 529 -> ErrorKind.NETWORK_TRANSIENT
        exitCode == 124 -> ErrorKind.COMMAND_TIMEOUT
        // Termux benar-benar tidak terjangkau: termux() mengembalikan "Gagal memanggil Termux: ..." tanpa baris exit=.
        // Exit 126/127 hanya berarti perintahnya sendiri tidak ditemukan/tidak bisa dijalankan, bukan Termux mati.
        exitCode == null && stderr.startsWith("Gagal memanggil Termux") -> ErrorKind.TERMUX_UNAVAILABLE
        else -> ErrorKind.COMMAND_FAILED
    }

}

/** Awalan hasil alat yang berarti tindakan TIDAK dijalankan (lihat deniedResult dan runTool). */
private val DENIED_PREFIXES = listOf("Ditolak", "Diblokir")

internal fun parseToolResult(raw: String): ToolResult {
    val first = raw.lineSequence().firstOrNull().orEmpty()
    val exit = Regex("^exit=(-?\\d+)").find(first)?.groupValues?.get(1)?.toIntOrNull()
    val body = raw.substringAfter('\n', "")
    val denied = exit == null && DENIED_PREFIXES.any { first.startsWith(it) }
    val timedOut = !denied && (exit == 124 || first.contains("Timeout", ignoreCase = true))
    // Tanpa baris exit=, baris pertama adalah pesan galat dari termux(); simpan agar bisa dikenali.
    return ToolResult(exitCode = exit, stdout = body, stderr = if (exit == null) first else "", timedOut = timedOut, denied = denied)
}

internal data class AgentCheckpoint(
    val taskId: String,
    val userMessage: String,
    val turn: Int,
    val lastTool: String,
    val phase: String,
    val lastError: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
