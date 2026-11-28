package com.build.mythos

import java.io.File

// Catatan file yang ditulis agen (write_file dan APK hasil build) untuk halaman Artefak.
// Satu entri JSON per baris di filesDir/artifact_log.jsonl; logika murninya ada di WorkspaceList.

private fun MainActivity.artifactLogFile() = File(filesDir, "artifact_log.jsonl")

@Synchronized
internal fun MainActivity.artifactLogLoad(): List<ArtifactRec> = try {
    val f = artifactLogFile()
    if (f.exists()) WorkspaceList.decode(f.readLines()) else emptyList()
} catch (e: Exception) { emptyList() }

@Synchronized
private fun MainActivity.artifactLogSave(list: List<ArtifactRec>) {
    try {
        atomicWriteText(artifactLogFile(), list.joinToString("\n") { WorkspaceList.encode(it) })
    } catch (e: Exception) { logQuiet("ArtifactLog.kt", e) }
}


@Synchronized
internal fun MainActivity.forgetArtifacts(paths: Collection<String>) {
    if (paths.isEmpty()) return
    val cur = artifactLogLoad()
    val keep = cur.filter { it.path !in paths }
    if (keep.size != cur.size) artifactLogSave(keep)
}
