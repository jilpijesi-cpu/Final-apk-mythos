package com.build.mythos

import java.io.File
import org.json.JSONObject

/** Checkpoint per chat (id chat = juga id folder kerja), supaya "lanjutkan" tidak mengambil tugas chat lain. */
internal fun MainActivity.checkpointFile() = File(filesDir, "agent_checkpoint_${currentChatId()}.json")

/** Berkas global lama (satu untuk semua chat). Tidak lagi dibaca; dihapus bila ada. */
private fun MainActivity.legacyCheckpointFile() = File(filesDir, "agent_checkpoint.json")

/** Checkpoint Chat samaran: hanya di memori, tidak pernah ditulis ke disk (isinya memuat pesan pengguna). */
private object IncognitoCheckpoint {
    @Volatile var value: AgentCheckpoint? = null
}

internal fun AgentCheckpoint.toJson() = JSONObject()
    .put("taskId", taskId)
    .put("userMessage", userMessage)
    .put("turn", turn)
    .put("lastTool", lastTool)
    .put("phase", phase)
    .put("lastError", lastError)
    .put("updatedAt", updatedAt)

internal fun checkpointFromJson(o: JSONObject) = AgentCheckpoint(
    taskId = o.optString("taskId"),
    userMessage = o.optString("userMessage"),
    turn = o.optInt("turn"),
    lastTool = o.optString("lastTool"),
    phase = o.optString("phase"),
    lastError = o.optString("lastError"),
    updatedAt = o.optLong("updatedAt")
)

internal fun MainActivity.saveAgentCheckpoint(checkpoint: AgentCheckpoint) {
    if (incognito) {
        IncognitoCheckpoint.value = checkpoint
        return
    }
    try {
        atomicWriteText(checkpointFile(), checkpoint.toJson().toString())
    } catch (e: Exception) {
        logQuiet("AgentCheckpointStore.kt", e)
    }
}

internal fun MainActivity.loadAgentCheckpoint(): AgentCheckpoint? = if (incognito) {
    IncognitoCheckpoint.value?.takeIf { it.userMessage.isNotBlank() }
} else try {
    val f = checkpointFile()
    if (!f.isFile) null else checkpointFromJson(JSONObject(f.readText())).takeIf { it.userMessage.isNotBlank() }
} catch (ignored: Exception) {
    null
}

internal fun MainActivity.clearAgentCheckpoint() {
    IncognitoCheckpoint.value = null
    if (incognito) return
    try {
        checkpointFile().delete()
        legacyCheckpointFile().delete()
    } catch (ignored: Exception) { logQuiet("AgentCheckpointStore.kt", ignored) }
}
