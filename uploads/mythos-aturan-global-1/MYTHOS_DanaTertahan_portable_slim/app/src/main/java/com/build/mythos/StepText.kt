package com.build.mythos

// Teks status yang menyebut pekerjaan agen yang SEBENARNYA (sasaran konkret: file, pola, tugas gradle), diturunkan dari
// perintah yang benar-benar dijalankan. Dipakai bila model tidak menulis 'deskripsi' atau menulis kalimat umum.
// Fungsi murni (tanpa Android) agar teruji di StepTextTest.

private val GENERIC = Regex(
    """^(menjalankan( sebuah)? (perintah|pemeriksaan|langkah|alat)|memproses|bekerja|sedang (bekerja|berpikir|menjalankan.*)|""" +
        """menjalankan|mengerjakan( tugas)?|melakukan( pemeriksaan)?|memeriksa)$""",
    RegexOption.IGNORE_CASE
)

/** Kalimat umum yang tidak memberi tahu apa-apa tentang langkahnya. */
internal fun isGenericStepText(s: String): Boolean = GENERIC.matches(s.trim().trimEnd('.', '…'))

private fun base(path: String): String = path.trim().trim('"', '\'').trimEnd('/').substringAfterLast('/').ifEmpty { path.trim() }

private fun words(seg: String): List<String> =
    Regex("""("[^"]*"|'[^']*'|\S+)""").findAll(seg).map { it.value }.toList()

private fun unq(s: String) = s.trim().trim('"', '\'')

/** Terjemahan tugas gradle yang umum menjadi kalimat natural; null bila tugas tidak dikenal. */
private fun gradlePhrase(task: String): String? {
    val t = task.substringAfterLast(':').lowercase()
    return when {
        t == "tasks" -> "Memeriksa daftar tugas yang tersedia di proyek"
        t == "dependencies" -> "Menelusuri pustaka dan dependensi yang dipakai proyek"
        t == "clean" -> "Membersihkan hasil build lama agar proyek bersih kembali"
        t.startsWith("assemble") && t.contains("release") -> "Merakit APK rilis dari seluruh kode proyek"
        t.startsWith("assemble") -> "Merakit APK debug dari seluruh kode proyek"
        t.startsWith("bundle") -> "Menyusun paket AAB untuk dipublikasikan"
        t.startsWith("compile") && t.contains("kotlin") -> "Mengompilasi kode Kotlin dan memeriksa galat penulisan"
        t.startsWith("compile") -> "Mengompilasi kode sumber dan memeriksa galat"
        t.startsWith("lint") -> "Memeriksa kualitas kode dan potensi masalah dengan lint"
        t.startsWith("test") || t.endsWith("unittest") -> "Menjalankan uji otomatis untuk memastikan kode berfungsi"
        t.startsWith("install") -> "Memasang aplikasi hasil build ke perangkat"
        t == "build" -> "Membangun proyek lengkap beserta pemeriksaannya"
        t == "check" -> "Menjalankan seluruh pemeriksaan dan pengujian proyek"
        t == "projects" -> "Melihat daftar modul yang ada di proyek"
        else -> null
    }
}

/** Satu kalimat Indonesia tentang perintah shell [command]; null bila tidak ada yang bisa disimpulkan. */
internal fun describeCommand(command: String): String? {
    val segs = command.split(Regex("""\s*(&&|\|\||;|\|)\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
    // Lewati pindah folder / persiapan agar yang disebut adalah pekerjaan intinya.
    val seg = segs.firstOrNull { !Regex("""^(cd|export|set|source|echo|true|:)\b""").containsMatchIn(it) } ?: return null
    val w = words(seg)
    if (w.isEmpty()) return null
    val cmd = w[0].substringAfterLast('/')
    val args = w.drop(1)
    val nonFlags = args.filter { !it.startsWith("-") }
    return when (cmd) {
        "cat", "head", "tail", "less", "bat", "nl" ->
            nonFlags.lastOrNull()?.let { "Membaca isi ${base(unq(it))} untuk memahami cara kerjanya" }
        "sed" -> if (args.any { it == "-i" || it.startsWith("-i") }) nonFlags.lastOrNull()?.let { "Memperbarui beberapa baris di ${base(unq(it))}" }
                 else nonFlags.lastOrNull()?.let { "Membaca bagian tertentu dari ${base(unq(it))}" }
        "wc" -> nonFlags.lastOrNull()?.let { "Menghitung jumlah baris dan ukuran ${base(unq(it))}" }
        "diff" -> if (nonFlags.size >= 2) "Membandingkan perbedaan antara ${base(unq(nonFlags[0]))} dan ${base(unq(nonFlags[1]))}" else null
        "grep", "rg", "egrep", "fgrep", "ag" -> {
            val pat = nonFlags.firstOrNull()?.let { unq(it) }
            val where = nonFlags.drop(1).lastOrNull()?.let { base(unq(it)) }
            when {
                pat == null -> null
                where != null -> "Mencari kemunculan \"${pat.take(28)}\" di dalam $where"
                else -> "Mencari kemunculan \"${pat.take(28)}\" di seluruh proyek"
            }
        }
        "find", "locate", "fd" -> {
            val name = args.indexOf("-name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
                ?: args.indexOf("-iname").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
            if (name != null) "Menelusuri folder untuk menemukan file ${unq(name)}"
            else nonFlags.firstOrNull()?.let { "Menelusuri seluruh isi folder ${base(unq(it))}" } ?: "Menelusuri seluruh isi folder proyek"
        }
        "ls", "tree", "du" -> nonFlags.lastOrNull()?.let { "Melihat daftar file dan folder di ${base(unq(it))}" } ?: "Melihat daftar file dan folder di proyek"
        "pwd" -> "Memastikan posisi folder kerja yang sedang dipakai"
        "unzip" -> {
            val zip = nonFlags.firstOrNull { it.contains(".zip", ignoreCase = true) }?.let { base(unq(it)) }
            when {
                zip == null -> "Mengekstrak arsip ke folder kerja"
                args.any { it == "-l" || it == "-Z" } -> "Memeriksa daftar isi arsip $zip"
                args.any { it == "-p" } -> "Membaca langsung isi file di dalam $zip"
                else -> "Mengekstrak $zip ke folder kerja"
            }
        }
        "zip" -> nonFlags.firstOrNull()?.let { "Mengemas hasil pekerjaan menjadi arsip ${base(unq(it))}" }
        "tar" -> nonFlags.firstOrNull { it.contains(".tar") || it.endsWith(".tgz") }?.let { "Mengolah isi arsip ${base(unq(it))}" }
        "mkdir" -> nonFlags.lastOrNull()?.let { "Menyiapkan folder baru bernama ${base(unq(it))}" }
        "cp" -> nonFlags.lastOrNull()?.let { "Menyalin file ke ${base(unq(it))}" }
        "mv" -> nonFlags.lastOrNull()?.let { "Memindahkan atau mengganti nama menjadi ${base(unq(it))}" }
        "rm" -> nonFlags.lastOrNull()?.let { "Menghapus ${base(unq(it))} yang sudah tidak diperlukan" }
        "touch" -> nonFlags.lastOrNull()?.let { "Membuat file kosong ${base(unq(it))}" }
        "chmod" -> nonFlags.lastOrNull()?.let { "Mengatur izin akses untuk ${base(unq(it))}" }
        "curl", "wget" -> nonFlags.lastOrNull()?.let { "Mengunduh data dari ${base(unq(it)).take(40)}" }
        "git" -> when (nonFlags.firstOrNull()) {
            "status" -> "Memeriksa perubahan yang belum disimpan di proyek"
            "diff" -> "Melihat detail perubahan kode yang sudah dibuat"
            "log" -> "Membaca riwayat perubahan proyek"
            "commit" -> "Menyimpan perubahan ke riwayat proyek"
            "clone" -> "Mengunduh salinan proyek dari repositori"
            null -> null
            else -> "Menjalankan perintah git ${nonFlags.first()} pada proyek"
        }
        "python", "python3" -> nonFlags.firstOrNull()?.let { "Menjalankan skrip ${base(unq(it))} untuk memproses data" }
        "node" -> nonFlags.firstOrNull()?.let { "Menjalankan skrip ${base(unq(it))} untuk memproses data" }
        "bash", "sh" -> nonFlags.firstOrNull()?.let { "Menjalankan skrip ${base(unq(it))} langkah demi langkah" }
        "gradlew", "gradle" -> {
            val tasks = nonFlags.filter { !it.startsWith("--") }
            val first = tasks.firstOrNull()
            val known = first?.let { gradlePhrase(it) }
            when {
                known != null -> known
                first != null -> "Menjalankan tugas gradle ${tasks.take(2).joinToString(" ")} pada proyek"
                else -> "Menjalankan gradle untuk memeriksa proyek"
            }
        }
        "apktool", "jadx" -> "Membongkar APK untuk membaca isi dan strukturnya"
        "pkg", "apt", "apt-get" -> nonFlags.drop(1).firstOrNull()?.let { "Memasang paket ${unq(it)} yang dibutuhkan proyek" }
        else -> if (cmd.startsWith(".") || cmd.isBlank()) null else "Menjalankan $cmd untuk melanjutkan pekerjaan"
    }
}

/** Kalimat untuk write_file dari path tujuan. */
internal fun describeWrite(path: String): String =
    if (path.isBlank()) "Menulis file baru ke dalam proyek" else "Menulis dan menyimpan perubahan pada ${base(path)}"

/** Teks bawaan seperti di Claude: AI sedang memikirkan langkah berikutnya / alat baru dipanggil. */
internal const val THINK_TEXT = "Proses berpikir"
internal const val RUNNING_TEXT = "Sedang menjalankan perintah"

/** Lama minimal (ms) kedua teks itu tetap terlihat sebelum diganti teks lain. */
internal const val THINK_HOLD_MS = 1600L
internal const val RUNNING_HOLD_MS = 1400L

/** Tahan lama teks ini bila sedang tampil; teks lain memakai tahan biasa. */
internal fun holdFor(shown: String, normal: Long): Long = when (shown) {
    THINK_TEXT -> THINK_HOLD_MS
    RUNNING_TEXT -> RUNNING_HOLD_MS
    else -> normal
}

/** Teks fase antar-langkah: AI membaca hasil alat dan menyusun langkah berikutnya. */
@Suppress("UNUSED_PARAMETER")
internal fun afterStepText(lastStepTitle: String): String = THINK_TEXT

// ---------------------------------------------------------------- Label langkah dari teks yang model tulis sendiri

private const val STEP_LABEL_MAX = 64
private const val STEP_LABEL_MIN = 8
/** Kalimat pengantar yang lebih panjang dari ini dianggap paragraf jawaban, bukan label langkah. */
private const val STEP_THOUGHT_MAX = 90

private val DESC_FIELD = Regex("\"(?:deskripsi|description)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
private val FIRST_SENTENCE = Regex("^(.+?[.!?:])(\\s|$)")

/**
 * Ubah teks yang model tulis (deskripsi alat atau kalimat pengantar) menjadi satu baris label: tanpa markdown, hanya kalimat pertama,
 * dipotong dengan "…". Null bila kosong, terlalu pendek, atau kalimat umum yang tidak memberi tahu apa-apa ("Menjalankan perintah").
 */
internal fun oneLineStepLabel(raw: String?, max: Int = STEP_LABEL_MAX): String? {
    val first = raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    var s = first.replace("**", "").replace("`", "").trimStart('#', '>', '-', '*', ' ')
    FIRST_SENTENCE.find(s)?.let { s = it.groupValues[1] }
    s = s.trimEnd('.', ':', '!', ' ')
    if (s.length < STEP_LABEL_MIN || isGenericStepText(s)) return null
    return if (s.length > max) s.take(max - 1).trimEnd() + "\u2026" else s
}

/** Isi argumen "deskripsi" / "description" dari ringkasan pemanggilan alat (JSON); null bila tidak ada. */
internal fun stepDescriptionFromSummary(summary: String): String? =
    DESC_FIELD.find(summary)?.groupValues?.get(1)
        ?.replace("\\\"", "\"")?.replace("\\n", " ")?.replace("\\\\", "\\")
        ?.ifBlank { null }

/**
 * Judul baris langkah. Urutan: (1) deskripsi yang model tulis untuk pemanggilan alat ini, (2) kalimat pengantar pendek yang model tulis
 * tepat sebelum alat (paragraf panjang tidak dipakai: itu jawaban, bukan label), (3) [fallback] dari alat dan perintah yang dijalankan.
 */
internal fun pickStepTitle(summary: String, thought: String?, fallback: String): String =
    oneLineStepLabel(stepDescriptionFromSummary(summary))
        ?: oneLineStepLabel(thought?.trim()?.takeIf { it.length <= STEP_THOUGHT_MAX })
        ?: fallback
