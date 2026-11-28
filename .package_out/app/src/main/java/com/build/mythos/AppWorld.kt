package com.build.mythos

// Dua dunia kerja yang sengaja dipisah (logika murni, tanpa android.*, diuji di AppWorldTest):
//  - agen API (Gemini/OpenAI-compatible/Anthropic): bekerja PENUH di dalam aplikasi (filesDir/cc_projects/<chat>), tanpa Termux.
//  - agen CLI (Codex, Gemini CLI, Claude Code): bekerja di Termux (~/MYTHOS/chats/<chat>).
// Keduanya tidak berbagi folder maupun alat, jadi tidak saling mengganggu.

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** True hanya untuk penyedia CLI: satu-satunya dunia yang boleh menyentuh Termux. */
internal fun usesTermuxWorld(fmt: String): Boolean = fmt == "cli"

/** Path relatif terhadap folder proyek agen API (yang dipahami alat Read/Write), atau path asli bila di luar folder itu. */
internal fun relativeToProject(path: String, projectRoot: String): String {
    val root = projectRoot.trimEnd('/')
    return if (path.startsWith("$root/")) path.removePrefix("$root/") else path
}

/** Id chat jadi nama folder proyek agen API. Satu-satunya tempat aturan ini, dipakai ccProjectDirFor dan penyapu folder yatim. */
internal fun sanitizeChatId(chatId: String): String = chatId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "chat" }

/** Nama folder proyek agen API yang chat-nya sudah tidak ada. Daftar chat kosong = jangan menyapu apa pun (indeks mungkin gagal dibaca). */
internal fun orphanProjectDirs(existing: List<String>, keepChatIds: Collection<String>): List<String> {
    if (keepChatIds.isEmpty()) return emptyList()
    val keep = keepChatIds.map { sanitizeChatId(it) }.toSet()
    return existing.filter { it !in keep }
}

private val BAD_NAME_CHARS = Regex("""[\\/:*?"<>|\x00-\x1F]""")

private val attachmentReserveLock = Any()

/** Nama file lampiran yang aman dan belum terpakai di [dir]: "a.txt", lalu "a (1).txt", "a (2).txt", dst. */
internal fun uniqueAttachmentName(dir: File, name: String): String {
    val clean = name.replace(BAD_NAME_CHARS, "_").trim().trim('.').take(120).ifBlank { "file" }
    if (!File(dir, clean).exists()) return clean
    val dot = clean.lastIndexOf('.')
    val stem = if (dot > 0) clean.substring(0, dot) else clean
    val ext = if (dot > 0) clean.substring(dot) else ""
    var i = 1
    while (File(dir, "$stem ($i)$ext").exists()) i++
    return "$stem ($i)$ext"
}

/**
 * F-06: reservasi nama berkas lampiran secara atomik memakai lock + createNewFile()
 * agar dua impor paralel tidak pernah memilih target yang sama atau saling menghapus.
 */
internal fun reserveUniqueAttachmentFile(dir: File, name: String): File = synchronized(attachmentReserveLock) {
    dir.mkdirs()
    val clean = name.replace(BAD_NAME_CHARS, "_").trim().trim('.').take(120).ifBlank { "file" }
    val dot = clean.lastIndexOf('.')
    val stem = if (dot > 0) clean.substring(0, dot) else clean
    val ext = if (dot > 0) clean.substring(dot) else ""
    var candidate = File(dir, clean)
    var i = 1
    while (!candidate.createNewFile()) {
        candidate = File(dir, "$stem ($i)$ext")
        i++
    }
    candidate
}

internal class ZipExtractResult(val files: Int, val bytes: Long, val error: String?)

/**
 * Ekstrak zip ke [destDir] tanpa Termux. Menolak zip-slip (path absolut, "..", atau keluar dari tujuan), melewati folder sampah
 * (.git, node_modules, dst), dan berhenti bila melewati batas jumlah atau ukuran. Pemanggil menghapus [destDir] bila error != null.
 */
internal fun extractZipSafely(
    input: InputStream,
    destDir: File,
    maxEntries: Int = ZIP_MAX_ENTRIES,
    maxBytes: Long = ZIP_MAX_TOTAL_BYTES,
    beforeWrite: (File) -> Unit = {}
): ZipExtractResult {
    val dest = destDir.canonicalFile
    dest.mkdirs()
    var count = 0
    var total = 0L
    try {
        ZipInputStream(input).use { zin ->
            val buf = ByteArray(16384)
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                if (isUnsafeEntry(name)) return ZipExtractResult(count, total, "path berbahaya: $name")
                if (isNoiseEntry(name)) continue
                if (count >= maxEntries) return ZipExtractResult(count, total, "terlalu banyak file")
                val out = File(dest, name).canonicalFile
                if (!out.path.startsWith(dest.path + File.separator)) return ZipExtractResult(count, total, "path di luar tujuan: $name")
                beforeWrite(out) // mis. mencadangkan file lama ke jurnal pemulihan; exception menghentikan ekstraksi
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return ZipExtractResult(count, total, "ukuran melebihi batas")
                        o.write(buf, 0, n)
                    }
                }
                count++
            }
        }
    } catch (e: Exception) {
        return ZipExtractResult(count, total, e.message ?: "gagal mengekstrak")
    }
    return ZipExtractResult(count, total, null)
}
