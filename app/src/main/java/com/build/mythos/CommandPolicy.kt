package com.build.mythos

// Kebijakan perintah untuk MODE SANDBOX. Sengaja TANPA dependensi Android supaya bisa diuji sebagai unit test JVM.
//
// classify() TIDAK diubah. assessCommand() memanggil classify() lalu hanya MENAMBAH pembatasan
// (blokir, tanda jaringan, pemeriksaan workdir). Hasilnya tidak pernah lebih longgar dari classify().
//
// BATAS YANG JUJUR: ini analisis teks (heuristik), bukan penjaga di tingkat sistem operasi. Variabel shell,
// symlink, skrip python/node, atau trik quoting yang tidak terduga bisa lolos dari analisis teks. Karena itu
// lapisan ini dipasangkan dengan isolasi proot-distro (lihat SandboxRunner.kt) dan persetujuan pengguna.

const val DEFAULT_SANDBOX_WORKDIR = "/root/sandbox/work"
const val DEFAULT_GUEST_HOME = "/root"

enum class Decision {
    /** Ada di allowlist, hanya membaca, di dalam workdir, tanpa jaringan. Boleh tanpa dialog (bila autoread aktif). */
    SAFE,
    /** Harus lewat dialog persetujuan. */
    NEEDS_APPROVAL,
    /** Tidak dijalankan sama sekali dan tidak ditawarkan ke pengguna. */
    BLOCKED
}

data class Assessment(
    val decision: Decision,
    val risk: Risk,
    val network: Boolean,
    val reasons: List<String>
) {
    /** Teks peringatan untuk dialog persetujuan; null bila tidak ada yang perlu disorot. */
    fun warning(): String? = if (reasons.isEmpty()) null else reasons.joinToString("\n")
}

/** Persetujuan boleh dilewati ("Ya, semua" / mode otonom) kecuali [force]. */
internal fun bypassesApproval(allowAll: Boolean, autonomous: Boolean, force: Boolean): Boolean =
    !force && (allowAll || autonomous)

/**
 * Konfirmasi minimal: penulisan file lewat tool write_file (selalu dicadangkan dulu) jalan tanpa dialog. Perintah SHELL
 * ([shell] = true) hanya jalan tanpa dialog bila terbukti READ: perintah tak dikenal, interpreter, dan skrip (risiko WRITE)
 * SELALU meminta persetujuan, karena isi skrip tidak bisa dinilai dari teks perintahnya. Aplikasi juga tetap bertanya untuk
 * penghapusan/penimpaan data, tindakan bertanda peringatan (area sensitif, di luar folder kerja), dan [force].
 */
internal fun minimalAutoApproves(minimal: Boolean, risk: Risk, warning: String?, force: Boolean, shell: Boolean = false): Boolean =
    minimal && !force && warning == null && risk != Risk.DELETE && (!shell || risk == Risk.READ)

// Allowlist sengaja himpunan bagian dari perintah baca di classify(): yang tidak dikenal classify() tidak pernah SAFE.
private val SAFE_ALLOWLIST = setOf(
    "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "find", "pwd", "echo", "wc", "stat",
    "du", "df", "which", "file", "tree", "diff", "cut", "basename", "dirname"
)

// Perintah yang tidak pernah boleh jalan di sandbox (bukan sekadar "berisiko").
private val BLOCK_CMDS = setOf(
    "dd", "fdisk", "sfdisk", "cfdisk", "parted", "mkswap", "swapon", "swapoff", "wipefs", "blkdiscard",
    "mount", "umount", "losetup", "chroot", "shred",
    "shutdown", "reboot", "halt", "poweroff", "init", "telinit",
    "su", "proot", "proot-distro", "am", "pm", "svc", "settings", "cmd",
    "insmod", "rmmod", "modprobe", "setenforce", "iptables"
)

private val NETWORK_CMDS = setOf(
    "curl", "wget", "ssh", "scp", "sftp", "rsync", "nc", "ncat", "netcat", "telnet", "ftp", "tftp",
    "ping", "ping6", "traceroute", "tracepath", "nslookup", "dig", "host", "nmap", "socat", "aria2c",
    "lynx", "w3m", "pip", "pip3", "npm", "npx", "yarn", "pnpm", "gem", "cargo", "bundle", "composer",
    "apt", "apt-get", "aptitude", "apk", "pkg", "gradle", "gradlew", "mvn"
)
private val GIT_NETWORK_SUB = setOf("clone", "fetch", "pull", "push", "ls-remote", "submodule")
private val INTERPRETERS = setOf("node", "nodejs", "perl", "ruby", "php", "lua")
private val SHELL_NAMES = setOf("sh", "bash", "zsh", "dash", "ash", "ksh", "eval", "source", ".")
private val RM_LIKE = setOf("rm", "rmdir", "unlink")
private val PERM_CMDS = setOf("chmod", "chown", "chgrp")

// Perintah yang menulis ke path yang disebut sebagai argumen.
private val WRITERS = setOf(
    "mkdir", "touch", "cp", "mv", "install", "ln", "tee", "truncate",
    "chmod", "chown", "chgrp", "rm", "rmdir", "unlink", "curl", "wget", "rsync", "scp"
)

private val SEGMENT_SPLIT = Regex("""[|&;\n\r(){}`]+""")
private val NON_WORD = Regex("[^A-Za-z0-9_.-]+")
private val WHITESPACE = Regex("\\s+")
private val FORK_BOMB = Regex(""":\s*\(\s*\)\s*\{""")
private val DEV_NET = Regex("""/dev/(tcp|udp)/""")
private val URL_RE = Regex("""(?i)\b[a-z][a-z0-9+.\-]*://""")
private val SCP_RE = Regex("""\b[\w.-]+@[\w.-]+:""")
private val REDIRECT_RE = Regex("""\d*>>?\s*([^\s&|;<>()]+)""")

private fun unquote(w: String) = w.replace("\"", "").replace("'", "").replace("\\", "")

private fun isBlockedName(n: String) = n in BLOCK_CMDS || n.startsWith("mkfs") || n.startsWith("termux-")

private fun isRecursiveFlag(a: String) =
    a == "--recursive" || (a.startsWith("-") && !a.startsWith("--") && a.contains('R'))

private fun isFollowFlag(a: String) =
    (a.startsWith("-") && !a.startsWith("--") && (a.contains('f') || a.contains('F'))) || a.startsWith("--follow")

private fun isPathLike(v: String) =
    v == "." || v == ".." || v.startsWith("/") || v.startsWith("~") ||
        v.contains('$') || v.contains('`') || v.split('/').any { it == ".." || it == "." }

/** Argumen yang tampak seperti path. Nilai setelah "=" pada opsi (--output=/x) ikut diperiksa. */
private fun candidatePaths(args: List<String>): List<String> = args.mapNotNull { a ->
    val v = if (a.startsWith("-")) (if (a.contains('=')) a.substringAfter('=') else null) else a
    if (v != null && isPathLike(v)) v else null
}

/**
 * Ubah path menjadi path absolut ternormalisasi di dalam sandbox. Null bila tidak bisa dipastikan
 * (variabel shell, substitusi, ~user). Path relatif dihitung dari [workdir].
 */
internal fun resolvePath(raw: String, workdir: String, guestHome: String): String? {
    var p = raw
    if (p.contains('$') || p.contains('`')) return null
    if (p == "~") p = guestHome
    else if (p.startsWith("~/")) p = guestHome.trimEnd('/') + p.substring(1)
    else if (p.startsWith("~")) return null
    val base = if (p.startsWith("/")) p else workdir.trimEnd('/') + "/" + p
    val out = ArrayList<String>()
    for (part in base.split('/')) {
        when (part) {
            "", "." -> {}
            ".." -> { if (out.isNotEmpty()) out.removeAt(out.size - 1) }
            else -> out.add(part)
        }
    }
    return "/" + out.joinToString("/")
}

internal fun isInside(path: String, workdir: String): Boolean {
    val w = workdir.trimEnd('/')
    return path == w || path.startsWith("$w/")
}

private fun isWriter(name: String, args: List<String>, segRisk: Risk): Boolean =
    segRisk == Risk.DELETE || name in WRITERS ||
        (name == "sed" && args.any { it.startsWith("-i") || it == "--in-place" })

/**
 * Nilai satu perintah untuk mode sandbox.
 * @param workdir path workdir DI DALAM sandbox (guest), bukan path Termux.
 */
fun assessCommand(
    cmd: String,
    workdir: String = DEFAULT_SANDBOX_WORKDIR,
    guestHome: String = DEFAULT_GUEST_HOME
): Assessment {
    val reasons = ArrayList<String>()
    var blocked = false
    var network = false
    var allNamesSafe = true
    var outsideRead = false
    var neverEnds = false
    val wd = workdir.trimEnd('/')

    fun block(msg: String) { blocked = true; reasons.add("DIBLOKIR: $msg") }

    val risk = classify(cmd)

    if (FORK_BOMB.containsMatchIn(cmd)) block("pola fork bomb")
    if (DEV_NET.containsMatchIn(cmd) || URL_RE.containsMatchIn(cmd) || SCP_RE.containsMatchIn(cmd)) {
        network = true
        reasons.add("Menyentuh jaringan (alamat atau URL terdeteksi)")
    }

    // Sama seperti classify(): buang redirect yang tidak menulis file sebelum memecah perintah (dengan batas akhir).
    val c = cmd.replace(Regex("""\d*>&\d+(?=\s|$|[;&|()])"""), " ").replace(Regex("""\d*>>?\s*/dev/null(?=\s|$|[;&|()])"""), " ")

    for (m in REDIRECT_RE.findAll(c)) {
        val target = unquote(m.groupValues[1])
        val r = resolvePath(target, workdir, guestHome)
        if (r == null || !isInside(r, workdir)) block("menulis ke luar workdir lewat redirect: $target")
    }

    for (seg in c.split(SEGMENT_SPLIT)) {
        val words = seg.trim().split(WHITESPACE).map { unquote(it) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) continue
        val t = unwrap(words)
        val name = t.firstOrNull()?.substringAfterLast('/') ?: continue
        val args = t.drop(1)
        val segRisk = classify(seg)

        if (name !in SAFE_ALLOWLIST) allNamesSafe = false
        if (name == "tail" && args.any { isFollowFlag(it) }) {
            neverEnds = true
            reasons.add("tail -f tidak berakhir sendiri")
        }

        // Isi shell (bash -c '...') tidak bisa dipastikan: periksa kata-katanya.
        val nested = if (name in SHELL_NAMES) t.flatMap { it.split(NON_WORD) }.filter { it.isNotEmpty() } else emptyList()

        if (isBlockedName(name)) block("perintah '$name' tidak diizinkan di sandbox")
        val nestedBad = nested.firstOrNull { isBlockedName(it) }
        if (nestedBad != null) block("'$nestedBad' di dalam shell tidak diizinkan di sandbox")
        if (name in PERM_CMDS && args.any { isRecursiveFlag(it) }) block("perubahan izin atau pemilik massal (-R) tidak diizinkan")

        val gitSub = if (name == "git") args.firstOrNull { !it.startsWith("-") } else null
        val isNet = name in NETWORK_CMDS || (gitSub != null && gitSub in GIT_NETWORK_SUB) || nested.any { it in NETWORK_CMDS }
        if (isNet) {
            network = true
            reasons.add("Menyentuh jaringan: $name")
        }
        if (name.startsWith("python") || name in INTERPRETERS) {
            reasons.add("'$name' bisa memakai jaringan lewat skrip; tidak bisa dipastikan")
        }

        val paths = candidatePaths(args)
        val outside = paths.filter { p ->
            val r = resolvePath(p, workdir, guestHome)
            r == null || !isInside(r, workdir)
        }
        if (outside.isNotEmpty()) {
            val shown = outside.take(3).joinToString(", ")
            if (name == "cd" || name == "pushd") {
                block("'$name' tidak boleh pindah ke luar workdir ($shown)")
            } else if (isWriter(name, args, segRisk)) {
                block("'$name' menulis atau menghapus di luar workdir ($shown)")
            } else {
                outsideRead = true
                reasons.add("Menyentuh path di luar workdir: $shown")
            }
        }
        // "cd" tanpa tujuan pindah ke home tamu, yaitu di luar workdir; perintah relatif sesudahnya jadi tidak terukur.
        if ((name == "cd" || name == "pushd") && args.none { !it.startsWith("-") }) {
            block("'$name' tanpa tujuan pindah ke luar workdir")
        }
        if (name in RM_LIKE && paths.any { resolvePath(it, workdir, guestHome) == wd }) {
            block("tidak boleh menghapus workdir itu sendiri")
        }
    }

    val decision = when {
        blocked -> Decision.BLOCKED
        risk == Risk.READ && allNamesSafe && !network && !outsideRead && !neverEnds &&
            !cmd.contains('$') && !cmd.contains('`') -> Decision.SAFE
        else -> Decision.NEEDS_APPROVAL
    }
    return Assessment(decision, risk, network, reasons.distinct())
}


// ---- Kehati-hatian tambahan untuk KONFIRMASI MINIMAL (berlaku di luar sandbox juga).
// classify() menganggap semua perintah tak dikenal sebagai WRITE, dan konfirmasi minimal menjalankan WRITE tanpa dialog.
// Daftar ini menandai pola yang berbahaya tetapi tampak "WRITE biasa". Hasilnya dipakai sebagai peringatan, sehingga
// dialog persetujuan tetap muncul dan "Ya, semua" tidak melewatinya. Ini heuristik teks, bukan jaminan:
// skrip yang dibuat lalu dijalankan (bash skrip.sh) ditandai lewat scriptTargets() dan isinya ditampilkan + dikunci hash saat persetujuan
// (lihat inspectScripts di ToolExecution.kt); keamanan sebenarnya tetap butuh isolasi OS, bukan hanya analisis teks.
private val CARE_PIPE_SHELL = Regex("""\|\s*(sudo\s+)?(\S*/)?(sh|bash|zsh|dash|ash|ksh)\b""")
private val CARE_SUBST_SHELL = Regex("""(sh|bash|zsh|dash|source|\.)\s+<\(|(sh|bash|zsh|dash)\s+-c\s+["']?\$\(""")
private val CARE_INLINE = Regex("""\b(python3?|node|nodejs|perl|ruby|php|lua)(?:\s+--?[A-Za-z0-9_=-]+)*\s+-[a-zA-Z]*[ce]\b|\bawk\b[^\n;|&]*\bsystem\s*\(""")
private val CARE_GIT = Regex("""\bgit\s+(?:-C\s+\S+\s+)?(reset\s+--hard|clean\b|checkout\s+(--\s|\.)|restore\b|stash\s+(drop|clear)|branch\s+-D|push\b|rebase\b|filter-branch|update-ref\s+-d)""")
// Perintah jaringan di posisi perintah (awal segmen, setelah sudo/env/xargs/busybox/timeout/nice dst.): bisa mengirim data keluar.
// "grep -rn curl src/" tidak ikut ditandai karena kata itu hanya argumen.
private val CARE_NET = Regex("""(?:^|[|&;\n(){}]|\$\()\s*(?:(?:(?:sudo|nohup|time|env|command|exec|xargs|busybox)\s+(?:-\S+\s+)*)|(?:timeout\s+(?:-\S+\s+)*\S+\s+)|(?:nice\s+(?:(?:-n|--adjustment|--priority|-p)\s+\S+\s+|-\S+\s+)*)|(?:\w+=\S*\s+))*(?:\S*/)?(?:curl|wget|nc|ncat|netcat|socat|scp|sftp|rsync|ssh|ftp|tftp|telnet)(?:\s|$)|/dev/(?:tcp|udp)/""")
private val CARE_TRUNC = Regex("""(?<![>\d&])>(?!>)\s*['"]?([^\s&|;<>()'"]+)""")
private val CARE_CODE_EXT = setOf("kt", "java", "kts", "gradle", "xml", "sh", "py", "js", "json", "properties", "toml", "yml", "yaml")

private val SCRIPT_RUNNERS = setOf("sh", "bash", "zsh", "dash", "ash", "ksh", "source", ".", "node", "nodejs", "perl", "ruby", "php", "lua")
private val SCRIPT_EXT = setOf("sh", "bash", "py", "js", "mjs", "cjs", "rb", "pl", "php", "lua")
private val CARE_SHELL_C = Regex("""\b(sh|bash|zsh|dash|ash|ksh)\s+(-[a-zA-Z]*\s+)*-[a-zA-Z]*c\b""")

/** Path skrip yang AMAN dipakai di perintah shell (tanpa spasi, kutip, atau karakter khusus). */
val SAFE_SCRIPT_PATH = Regex("^[A-Za-z0-9_./-]+$")

/**
 * Berkas skrip lokal yang akan dieksekusi perintah ini: argumen pertama sh/bash/source, python/node/perl/ruby/php/lua,
 * atau berkas yang dijalankan langsung (./x.sh, ./gradlew). Tidak menyertakan kode inline (-c) atau modul (python -m).
 * Hanya analisis teks: skrip yang dibangun lewat variabel atau substitusi tidak terdeteksi di sini, tetapi perintah
 * seperti itu tetap berisiko WRITE sehingga tetap meminta persetujuan.
 */
fun scriptTargets(cmd: String): List<String> {
    val out = LinkedHashSet<String>()
    for (seg in cmd.split(SEGMENT_SPLIT)) {
        val words = seg.trim().split(WHITESPACE).map { unquote(it) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) continue
        val t = unwrap(words)
        val first = t.firstOrNull() ?: continue
        val name = first.substringAfterLast('/')
        val args = t.drop(1)
        val isPython = name.startsWith("python")
        if (name in SCRIPT_RUNNERS || isPython) {
            // Kode inline (-c) dan modul (python -m) hanya diabaikan bila muncul sebagai flag sebelum argumen skrip pertama.
            val leadFlags = args.takeWhile { it.startsWith("-") }
            if (leadFlags.any { it == "-c" || it == "-m" }) continue
            val cand = args.firstOrNull { !it.startsWith("-") } ?: continue
            val ext = cand.substringAfterLast('.', "").lowercase()
            val isShellLike = name in setOf("sh", "bash", "zsh", "dash", "ash", "ksh", "source", ".")
            if (isShellLike || ext in SCRIPT_EXT) out.add(cand)
        } else if (first.startsWith("./") || first.startsWith("../") ||
            (first.contains('/') && first.substringAfterLast('.', "").lowercase() in SCRIPT_EXT)
        ) {
            out.add(first)
        }
    }
    return out.toList()
}

/**
 * Awalan shell yang memastikan tiap skrip masih sama persis dengan yang dilihat pengguna saat menyetujui
 * ([hashes]: path -> SHA-256 hex). Bila berubah, perintah dibatalkan dengan kode 97 dan TIDAK dijalankan.
 * Hanya path aman dan hash hex 64 karakter yang dipakai, jadi tidak ada celah injeksi.
 */
fun scriptPinGuard(hashes: Map<String, String>): String {
    val sha = Regex("^[0-9a-f]{64}$")
    val sb = StringBuilder()
    for ((path, hash) in hashes) {
        if (!SAFE_SCRIPT_PATH.matches(path) || !sha.matches(hash)) continue
        sb.append("printf '%s  %s\\n' '").append(hash).append("' '").append(path)
            .append("' | sha256sum -c --status - 2>/dev/null || { echo 'DIBATALKAN: skrip ")
            .append(path).append(" berubah setelah disetujui'; exit 97; }; ")
    }
    return sb.toString()
}

/** Alasan perintah perlu persetujuan walau risikonya WRITE, atau null bila rutin. */
fun needsExtraCare(cmd: String): String? {
    if (CARE_PIPE_SHELL.containsMatchIn(cmd) || CARE_SUBST_SHELL.containsMatchIn(cmd))
        return "menjalankan teks sebagai skrip shell (pipe ke sh/bash)"
    if (CARE_INLINE.containsMatchIn(cmd)) return "interpreter dengan kode di baris perintah (isi tidak bisa diperiksa)"
    if (CARE_SHELL_C.containsMatchIn(cmd)) return "shell dengan kode di baris perintah (isi tidak bisa diperiksa)"
    val scripts = scriptTargets(cmd)
    if (scripts.isNotEmpty())
        return "menjalankan skrip lokal (${scripts.take(3).joinToString(", ")}): isi skrip tidak dinilai otomatis, periksa isi dan SHA-256 di bawah"
    if (CARE_NET.containsMatchIn(cmd)) return "menyentuh jaringan (bisa mengirim data keluar)"
    val g = CARE_GIT.find(cmd)
    if (g != null)
        return "git yang bisa membuang perubahan atau mengubah repo jarak jauh (${g.groupValues[1].trim()})"
    if (Regex("""\bchmod\s+(-\w*R|--recursive)""").containsMatchIn(cmd) || Regex("""\bchown\s+(-\w*R|--recursive)""").containsMatchIn(cmd))
        return "ubah izin/pemilik massal"
    // Redirect ">" (menimpa) ke file kode/konfigurasi. Log build (> build.log) tidak ikut ditandai.
    val c = cmd.replace(Regex("""\d*>&\d+(?=\s|$|[;&|()])"""), " ").replace(Regex("""\d*>>?\s*/dev/null(?=\s|$|[;&|()])"""), " ")
    for (m in CARE_TRUNC.findAll(c)) {
        val ext = m.groupValues[1].substringAfterLast('.', "").lowercase()
        if (ext in CARE_CODE_EXT) return "menimpa file lewat redirect (${m.groupValues[1].takeLast(40)})"
    }
    return null
}

/** Normalkan "." dan ".." pada path (tanpa menyentuh disk). Path relatif tetap relatif. */
fun collapsePath(p: String): String {
    val abs = p.startsWith("/")
    val out = ArrayList<String>()
    for (part in p.split('/')) {
        when (part) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1) else if (!abs) out.add("..")
            else -> out.add(part)
        }
    }
    return (if (abs) "/" else "") + out.joinToString("/")
}
