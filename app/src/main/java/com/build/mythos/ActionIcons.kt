package com.build.mythos

import org.json.JSONObject

// Memilih ikon langkah agen sesuai apa yang sedang dikerjakan (baca, cari, tulis, build, ...).
// iconForCommand() murni Kotlin sehingga bisa di-unit-test (ActionIconsTest).
// Untuk menambah jenis aksi: tambahkan satu baris di COMMAND_RULES (urutan = prioritas, yang pertama cocok menang).
// Urutan: deskripsi AI (DESC_RULES) lebih dulu agar sejalan dengan judul langkah; bila tidak cocok, perintah (COMMAND_RULES); bila tetap tidak ada -> ikon terminal.
// Ikon: terminal (perintah umum), buku (membaca/memeriksa), jam (menunggu, waktu, tidur/timeout), roda gigi (build), centang (uji), dst.
// Nama berkas dan path dibuang dari deskripsi sebelum dicocokkan, supaya membaca "build.gradle.kts" tidak dianggap build.

private class IconRule(val icon: Int, val pattern: Regex)

private val COMMAND_RULES = listOf(
    IconRule(IconView.GEAR, Regex("""\b(gradlew|gradle|assemble\w*|make|cmake|ninja)\b""")),
    IconRule(IconView.ARCHIVE, Regex("""\b(unzip|zip|tar|gunzip|gzip|7z|unrar)\b""")),
    IconRule(IconView.TRASH, Regex("""\brm\b""")),
    IconRule(IconView.GLOBE, Regex("""\b(curl|wget|ping|nc|ssh|scp)\b""")),
    IconRule(
        IconView.DOWNLOAD,
        Regex("""\b(pkg|apt|apt-get|pip|pip3|npm|yarn)\s+(install|add|update|upgrade)\b|\bgit\s+(clone|pull|fetch)\b""")
    ),
    IconRule(IconView.CHECK, Regex("""\b(test\w*|lint\w*)\b""")),
    // menulis/mengubah: sed -i, tee, atau pengalihan > / >> (bukan 2>&1 atau > /dev/null)
    IconRule(IconView.NEW, Regex("""\bsed\s+(-\w*i\b|--in-place)|\btee\b|(?<![\d&>=-])>{1,2}\s*(?!/dev/null)[^\s&>]""")),
    IconRule(IconView.GRID, Regex("""\b(grep|egrep|rg|find|locate|which|whereis)\b""")),
    IconRule(IconView.CLOCK, Regex("""\b(sleep|timeout|watch|date|uptime)\b""")),
    IconRule(IconView.BOOK, Regex("""\b(cat|head|tail|less|more|sed|awk|wc|nl|stat|file|diff|xxd)\b""")),
    IconRule(IconView.FOLDER, Regex("""\b(ls|tree|cd|pwd|du|mkdir|mv|cp|touch|chmod)\b"""))
)

private val DESC_RULES = listOf(
    IconRule(IconView.GEAR, Regex("""build|membangun|kompil|compile""")),
    IconRule(IconView.ARCHIVE, Regex("""zip|arsip|archive""")),
    IconRule(IconView.CLOCK, Regex("""menunggu|tunggu|\bwait|\bsleep|waktu|\bjam\b|tanggal|\btimer\b|jadwal|\btime\b""")),
    IconRule(IconView.GRID, Regex("""cari|mencari|menelusuri|informasi|search|\bfind\b|look up""")),
    IconRule(IconView.FOLDER, Regex("""melihat (isi|daftar)|daftar file|isi folder|direktori|\bfolder\b.*\b(daftar|isi)\b""")),
    IconRule(IconView.CHECK, Regex("""\b(uji|menguji|verifikasi|memastikan|tes|mengetes|test|verify|validate|validasi|memvalidasi)\b""")),
    IconRule(IconView.BOOK, Regex("""baca|membaca|periksa|memeriksa|lihat|melihat|memahami|\bcheck|\bread|\bview|inspect|review""")),
    IconRule(IconView.NEW, Regex("""tulis|menulis|menyusun|mengubah|memperbaiki|membuat|edit|\bwrite|\bcreate|\bfix\b|\bupdate\b""")),
    IconRule(IconView.DOWNLOAD, Regex("""unduh|mengunduh|memasang|pasang|install|download"""))
)

/** Nama berkas ("build.gradle.kts", "v1.2") dan path ("app/src") dibuang dari deskripsi agar tidak memicu aturan ikon yang salah. */
private val FILE_LIKE = Regex("""\S*\w\.\w+\S*|\S*/\S*""")

internal fun stripFileNames(desc: String): String = desc.replace(FILE_LIKE, " ")

/** Ikon (konstanta IconView.*) untuk satu langkah. [tool] = nama alat, [command] = perintah bash bila ada. */
internal fun iconForCommand(tool: String, command: String, desc: String = ""): Int {
    if (tool == "write_file" || tool == "buat_gambar" || tool == "edit_gambar") return IconView.NEW
    if (tool == "cari_web" || tool == "baca_web" || tool == "cari_leakosint") return IconView.GLOBE
    if (tool in CONNECTOR_TOOL_NAMES) return IconView.GLOBE
    if (tool in DANA_TOOL_NAMES) return IconView.FILE
    // Utamakan kalimat deskripsi AI: itulah judul yang dibaca pengguna, jadi ikon dan judul selalu sejalan.
    val d = stripFileNames(desc.lowercase())
    if (d.isNotBlank()) DESC_RULES.firstOrNull { it.pattern.containsMatchIn(d) }?.let { return it.icon }
    val c = command.lowercase()
    COMMAND_RULES.firstOrNull { it.pattern.containsMatchIn(c) }?.let { return it.icon }
    return IconView.TERMINAL
}

internal fun iconFor(name: String, args: JSONObject, desc: String = ""): Int =
    iconForCommand(name, args.optString("command"), desc)

internal fun isLogoKind(kind: Int): Boolean =
    kind == KIND_LOGO_GMAIL || kind == KIND_LOGO_TELEGRAM || kind == KIND_LOGO_PDF || kind == KIND_LOGO_WHATSAPP || kind == KIND_LOGO_GITHUB

// Alat yang bicara dengan GitHub (push + tunggu GitHub Actions): build lama, jadi logo GitHub tampil selama menunggu.
private val GITHUB_TOOLS = setOf("GithubBuild", "build_apk")

// Alat shell: logo GitHub tampil bila perintahnya git atau gh (clone, pull, push, commit, gh run, gh auth, ...) atau menyentuh github.com.
private val SHELL_TOOLS = setOf("run_command", "Bash")
private val GITHUB_COMMAND = Regex("""(?:^|[\s;&|(])(?:git|gh)(?=\s|$)|\bgithub\.com\b""", RegexOption.IGNORE_CASE)

// Alat berkas: logo GitHub tampil bila yang dikerjakan ada di folder .github (workflow Actions, dependabot, templat issue).
private val GITHUB_PATH_TOOLS = setOf("Read", "Write", "Edit", "Delete", "Move", "Copy", "Mkdir", "LS", "Zip", "Unzip", "write_file")
private val GITHUB_PATH = Regex("""(^|[/\s"'])\.github(?=[/\s"']|$)""")

// Alat yang bekerja pada berkas: logo PDF tampil bila berkas yang dikerjakan berekstensi .pdf.
private val PDF_FILE_TOOLS = setOf("Read", "Write", "Edit", "Delete", "Move", "Copy", "MakeDocument", "Export", "Present", "write_file", "berangkas_salin")
private val PDF_NAME = Regex("""\.pdf\b""", RegexOption.IGNORE_CASE)

// Alat ambil halaman web: logo GitHub bila alamatnya di github.com (termasuk raw.githubusercontent.com), logo PDF bila alamatnya berkas .pdf.
private val GITHUB_URL = Regex("""^\s*(?:https?://)?(?:[\w-]+\.)*(?:github\.com|githubusercontent\.com)(?=[/:?#\s]|$)""", RegexOption.IGNORE_CASE)
private val PDF_URL = Regex("""\.pdf(?=[?#\s]|$)""", RegexOption.IGNORE_CASE)

/**
 * Berkas yang dikerjakan satu langkah, diambil dari ringkasan alat ([summary] = hasil describe()). Dua bentuk ringkasan:
 * JSON bawaan (`Read {"file_path":"a.pdf"}`) dan kalimat singkat (`Present out/a.pdf (+1)`, `Export a.pdf`, `MakeDocument a.pdf`,
 * `Move a -> b`). ccTarget hanya paham bentuk JSON dan Write/Edit/Delete, sehingga Present/Export/MakeDocument/Move/Copy/Mkdir
 * dulu selalu bertarget kosong dan logo PDF tidak pernah muncul untuk alat-alat itu. Murni Kotlin, diuji di LogoKindTest.
 */
internal fun logoTargetOf(tool: String, summary: String): String {
    Regex("""\"(?:file_path|path)\"\s*:\s*\"([^\"]*)\"""").find(summary)?.let { return it.groupValues[1] }
    return summary.removePrefix("$tool ").substringBefore(" (").trim()
}

/**
 * Penanda logo (KIND_LOGO_*) bagi langkah agen yang memakai keahlian tertentu, atau null bila tidak ada logonya.
 * [tool] = nama alat, [target] = berkas/path yang dikerjakan (kosong bila tidak ada), [command] = perintah shell bila ada.
 * email_* -> Gmail, telegram_* -> Telegram, whatsapp_* -> WhatsApp, alat berkas dengan target .pdf -> PDF, GithubBuild/build_apk, perintah git/gh, dan berkas di folder .github -> GitHub. Murni Kotlin, diuji di LogoKindTest.
 */
internal fun logoKindFor(tool: String, target: String = "", command: String = ""): Int? {
    when (connectorOf(tool)) {
        "email" -> return KIND_LOGO_GMAIL
        "telegram" -> return KIND_LOGO_TELEGRAM
        "whatsapp", "wa_hp" -> return KIND_LOGO_WHATSAPP
    }
    if (tool in GITHUB_TOOLS) return KIND_LOGO_GITHUB
    if (tool in SHELL_TOOLS && GITHUB_COMMAND.containsMatchIn(command)) return KIND_LOGO_GITHUB
    if (tool in GITHUB_PATH_TOOLS && GITHUB_PATH.containsMatchIn(target.replace('\\', '/'))) return KIND_LOGO_GITHUB
    if (tool in PDF_FILE_TOOLS && PDF_NAME.containsMatchIn(target)) return KIND_LOGO_PDF
    if (tool == "WebFetch") {
        if (GITHUB_URL.containsMatchIn(target)) return KIND_LOGO_GITHUB
        if (PDF_URL.containsMatchIn(target)) return KIND_LOGO_PDF
    }
    return null
}
