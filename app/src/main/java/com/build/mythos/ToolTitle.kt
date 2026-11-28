package com.build.mythos

// Judul baris status untuk alat agen: satu kalimat yang langsung menyebut APA yang akan dikerjakan
// (file, pola, folder, kata yang dicari), bukan nama alatnya ("Menjalankan LS", "Mencari file").
// Bisa panjang, tetapi dipotong agar tetap satu baris. Murni Kotlin (tanpa Android), diuji di ToolTitleTest.

private const val TITLE_MAX = 64

private fun ttField(summary: String, key: String): String? {
    val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(summary) ?: return null
    return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/").ifBlank { null }
}

private fun ttIntField(summary: String, key: String): Int? =
    Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\\d+)").find(summary)?.groupValues?.get(1)?.toIntOrNull()

/** Nama berkas/folder terakhir dari [path]; "." atau kosong berarti folder proyek. */
private fun ttLeaf(path: String?): String {
    val p = path?.trim()?.trim('"', '\'')?.trimEnd('/') ?: return "proyek"
    if (p.isEmpty() || p == ".") return "proyek"
    return p.substringAfterLast('/').ifEmpty { p }
}

private fun ttWhere(path: String?): String =
    ttLeaf(path).let { if (it == "proyek") "seluruh proyek" else "folder $it" }

private fun ttClip(s: String, n: Int): String = if (s.length > n) s.take(n - 1).trimEnd() + "…" else s

/**
 * [tool] = nama alat agen (LS, Read, Glob, ...), [summary] = ringkasan yang dibuat Tool.describe() (berisi argumen),
 * [target] = path hasil ccTarget bila ada. Hasil selalu satu baris, maksimal sekitar 64 karakter.
 */
internal fun ccToolTitle(tool: String, target: String, summary: String): String {
    val rest = summary.removePrefix(tool).trim()
    val path = ttField(summary, "file_path") ?: ttField(summary, "path") ?: target.ifBlank { null }
    val title = when (tool) {
        "LS" -> "Melihat daftar file dan folder di ${ttLeaf(ttField(summary, "path"))}"
        "Read" -> {
            val off = ttIntField(summary, "offset")
            val name = ttLeaf(path)
            if (off != null && off > 1) "Membaca lanjutan $name mulai baris $off"
            else "Membaca isi $name untuk memahami kodenya"
        }
        "Glob" -> {
            val pat = ttField(summary, "pattern")
            val dir = ttWhere(ttField(summary, "path"))
            if (pat != null) "Mencari file berpola ${ttClip(pat, 24)} di $dir" else "Mencari file yang cocok di $dir"
        }
        "Grep" -> {
            val pat = ttField(summary, "pattern")
            val g = ttField(summary, "glob")
            val dir = if (g != null) "file $g" else ttWhere(ttField(summary, "path"))
            if (pat != null) "Mencari \"${ttClip(pat, 24)}\" di dalam $dir" else "Mencari teks di dalam $dir"
        }
        "Write" -> "Menulis file ${ttLeaf(path ?: rest.substringBefore(" (").trim())} dengan isi yang baru"
        "Edit" -> "Mengubah sebagian isi ${ttLeaf(path ?: rest)} sesuai perbaikan"
        "Delete" -> "Menghapus file ${ttLeaf(path ?: rest)} dari proyek"
        "Mkdir" -> "Membuat folder ${ttLeaf(path ?: rest)} untuk menyimpan file"
        "Move", "Copy" -> {
            val parts = rest.split(" -> ")
            val verb = if (tool == "Move") "Memindahkan" else "Menyalin"
            if (parts.size == 2) "$verb ${ttLeaf(parts[0])} ke ${ttLeaf(parts[1])}" else "$verb file di dalam proyek"
        }
        "Zip" -> {
            val parts = rest.split(" -> ")
            if (parts.size == 2) "Mengemas ${ttLeaf(parts[0])} menjadi arsip ${ttLeaf(parts[1])}" else "Mengemas hasil pekerjaan menjadi arsip zip"
        }
        "Unzip" -> "Mengekstrak arsip ${ttLeaf(path ?: rest)} ke folder kerja"
        "Time" -> "Memeriksa tanggal dan jam saat ini"
        "Calc" -> if (rest.isNotBlank()) "Menghitung ${rest.trim()}" else "Menghitung angka yang dibutuhkan"
        "WebSearch" -> if (rest.isNotBlank()) "Mencari di web tentang \"${ttClip(rest, 36)}\"" else "Mencari informasi terbaru di web"
        "WebFetch" -> {
            val host = rest.substringAfter("://", rest).substringBefore('/').ifBlank { null }
            if (host != null) "Membaca halaman web dari $host" else "Membaca isi halaman web"
        }
        "buat_gambar" -> if (rest.isNotBlank()) "Membuat gambar: ${ttClip(rest, 40)}" else "Membuat gambar"
        "edit_gambar" -> "Mengedit gambar ${ttLeaf(path ?: rest)}"
        "cari_leakosint" -> if (rest.isNotBlank()) "Mencari di LeakOSINT: ${ttClip(rest, 36)}" else "Mencari di MYTHOS LeakOSINT"
        "Export" -> "Menyimpan ${ttLeaf(path ?: rest)} ke folder Download"
        "MakeDocument" -> "Membuat dokumen ${ttLeaf(path ?: rest)}"
        "Present" -> "Menampilkan file hasil kerja di chat"
        "GithubBuild" -> "Membangun APK lewat GitHub Actions lalu menunggu hasilnya"
        "TodoWrite" -> "Menyusun daftar langkah yang akan dikerjakan"
        "AskUserQuestion" -> "Menanyakan hal yang perlu dipastikan kepada Anda"
        "ExitPlanMode" -> "Menyelesaikan rencana lalu mulai mengerjakannya"
        else -> if (rest.isBlank()) "Menjalankan alat $tool" else "Menjalankan $tool: ${rest.replace(Regex("\\s+"), " ")}"
    }
    return ttClip(title.replace(Regex("\\s+"), " ").trim(), TITLE_MAX)
}
