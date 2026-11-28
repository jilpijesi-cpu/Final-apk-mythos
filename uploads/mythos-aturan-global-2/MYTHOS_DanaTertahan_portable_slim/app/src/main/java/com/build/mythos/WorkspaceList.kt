package com.build.mythos

import org.json.JSONObject

// Logika murni (tanpa android.*) untuk halaman Proyek dan Artefak: membaca keluaran daftar folder/file dari Termux,
// mencatat file buatan agen, dan format tampilan. Diuji di WorkspaceListTest.

/** Satu folder proyek di projects/aktif atau projects/arsip pada workspace aktif. */
internal class ProjectEntry(val name: String, val archived: Boolean, val modified: Long)

/** Satu file hasil kerja agen (path absolut di Termux). */
internal class FileEntry(val path: String, val size: Long, val modified: Long) {
    val name: String get() = path.substringAfterLast('/')
    val dir: String get() = path.substringBeforeLast('/', "")
}

/** Catatan file yang ditulis agen. [kind]: "file" (write_file) atau "apk" (hasil build). */
internal class ArtifactRec(val path: String, val kind: String, val time: Long)

internal object WorkspaceList {
    const val MAX_LOG = 300

    /** Nama folder/file tunggal yang aman dipakai dalam perintah shell dan path (tanpa pemisah, tanpa kontrol). */
    fun isSafeName(n: String): Boolean =
        n.isNotEmpty() && n.length <= 120 && n != "." && n != ".." && n.none { it == '/' || it == '\n' || it == '\r' || it == '\u0000' }

    fun projectPath(root: String, name: String, archived: Boolean): String? {
        if (!isSafeName(name)) return null
        return root.trimEnd('/') + "/projects/" + (if (archived) "arsip" else "aktif") + "/" + name
    }

    /** Baris "mtime<TAB>path" (keluaran find -printf '%T@\t%p\n'); baris lain (exit=, catatan) diabaikan. */
    fun parseProjects(out: String, root: String): List<ProjectEntry> {
        val base = root.trimEnd('/') + "/projects/"
        val list = ArrayList<ProjectEntry>()
        for (line in out.lineSequence()) {
            val parts = line.split('\t', limit = 2)
            if (parts.size < 2) continue
            val t = parts[0].trim().toDoubleOrNull()?.toLong() ?: continue
            val path = parts[1].trimEnd()
            if (!path.startsWith(base)) continue
            val rest = path.removePrefix(base).split('/')
            if (rest.size != 2) continue
            val archived = when (rest[0]) { "aktif" -> false; "arsip" -> true; else -> continue }
            val name = rest[1]
            if (!isSafeName(name) || name.startsWith(".")) continue
            list.add(ProjectEntry(name, archived, t))
        }
        return list.sortedByDescending { it.modified }
    }

    /** Baris "size<TAB>mtime<TAB>path". Path duplikat digabung. */
    fun parseFiles(out: String): List<FileEntry> {
        val map = LinkedHashMap<String, FileEntry>()
        for (line in out.lineSequence()) {
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) continue
            val size = parts[0].trim().toLongOrNull() ?: continue
            val t = parts[1].trim().toDoubleOrNull()?.toLong() ?: continue
            val path = parts[2].trimEnd()
            if (!path.startsWith("/")) continue
            map[path] = FileEntry(path, size, t)
        }
        return map.values.sortedByDescending { it.modified }
    }

    /** Hasil kerja = APK dan file di luar folder proyek; file sumber proyek hanya muncul di tampilan "Semua". */
    fun isDeliverable(rec: ArtifactRec): Boolean = rec.kind == "apk" || !rec.path.contains("/projects/")

    fun isDeliverable(path: String): Boolean = !path.contains("/projects/")

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    private val TEXT_EXT = setOf(
        "txt", "md", "kt", "kts", "java", "json", "xml", "html", "htm", "css", "js", "ts", "py", "sh", "yml", "yaml",
        "gradle", "csv", "log", "properties", "toml", "ini", "cfg", "conf", "sql", "gitignore", "pro"
    )

    fun ext(name: String): String = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""

    fun isTextName(name: String): Boolean = ext(name) in TEXT_EXT || name.lowercase() in setOf("readme", "license", "makefile", "dockerfile")

    fun mimeFor(name: String): String = when (ext(name)) {
        "apk" -> "application/vnd.android.package-archive"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "zip" -> "application/zip"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "doc" -> "application/msword"
        "xls" -> "application/vnd.ms-excel"
        "ppt" -> "application/vnd.ms-powerpoint"
        "svg" -> "image/svg+xml"
        "xml" -> "application/xml"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        "gz", "tgz" -> "application/gzip"
        "tar" -> "application/x-tar"
        "7z" -> "application/x-7z-compressed"
        "rtf" -> "application/rtf"
        "ics" -> "text/calendar"
        "epub" -> "application/epub+zip"
        "md", "txt", "log" -> "text/plain"
        else -> if (isTextName(name)) "text/plain" else "application/octet-stream"
    }

    /** Nama aman untuk salinan sementara di cache/share. */
    fun cacheName(name: String): String {
        val n = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').ifEmpty { "file" }
        return if (n.length > 80) n.takeLast(80) else n
    }

    // ---------------------------------------------------------------- Catatan file buatan agen

    fun encode(rec: ArtifactRec): String =
        JSONObject().put("p", rec.path).put("k", rec.kind).put("t", rec.time).toString()

    fun decode(lines: List<String>): List<ArtifactRec> {
        val out = ArrayList<ArtifactRec>()
        for (l in lines) {
            if (l.isBlank()) continue
            try {
                val o = JSONObject(l)
                val p = o.optString("p")
                if (p.startsWith("/")) out.add(ArtifactRec(p, o.optString("k", "file"), o.optLong("t")))
            } catch (e: Exception) { logQuiet("WorkspaceList.kt", e) }
        }
        return out
    }

    /** Tambah [rec]; satu path hanya sekali (yang terbaru), terbaru di depan, dibatasi [max]. APK tidak diturunkan menjadi "file". */
    fun addRec(list: List<ArtifactRec>, rec: ArtifactRec, max: Int = MAX_LOG): List<ArtifactRec> {
        val prev = list.firstOrNull { it.path == rec.path }
        val kind = if (prev?.kind == "apk" || rec.kind == "apk") "apk" else rec.kind
        val merged = listOf(ArtifactRec(rec.path, kind, rec.time)) + list.filter { it.path != rec.path }
        return merged.take(max)
    }

    fun removeRec(list: List<ArtifactRec>, path: String): List<ArtifactRec> = list.filter { it.path != path }
}
