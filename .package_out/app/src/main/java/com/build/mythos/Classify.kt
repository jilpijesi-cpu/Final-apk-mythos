package com.build.mythos

// Penilaian risiko perintah. Sengaja TANPA dependensi Android supaya bisa diuji sebagai unit test JVM biasa.

// Urutan enum = tingkat keparahan (READ paling ringan, DELETE paling berat)
enum class Risk(val label: String, val color: Int) {
    READ("Hanya membaca, tidak mengubah apa pun", 0xFF16A34A.toInt()),
    WRITE("Mengubah atau membuat file", 0xFFF59E0B.toInt()),
    DELETE("Bisa menghapus atau menimpa data", 0xFFDC2626.toInt())
}

private val READ_CMDS = setOf(
    "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "find", "pwd", "echo",
    "wc", "stat", "du", "df", "which", "file", "tree", "diff", "cut", "basename", "dirname"
)
private val VERSION_CMDS = setOf("java", "javac", "gradle", "gradlew", "android", "aapt", "aapt2", "apktool", "jadx", "apksigner", "node", "npm", "python", "python3", "git")
private val VERSION_FLAGS = setOf("-version", "--version", "-v", "-V", "version")
private val DELETE_CMDS = setOf("rm", "rmdir", "shred", "dd", "mkfs", "unlink", "truncate")

// Perintah pembungkus: yang dinilai adalah perintah di belakangnya
private val WRAPPERS = setOf("sudo", "xargs", "env", "nohup", "time", "timeout", "nice", "exec", "command", "busybox")
private val SHELLS = setOf("sh", "bash", "zsh", "eval", "source", ".")

private val FIND_WRITE = setOf("-fprint", "-fprint0", "-fprintf", "-fls")
private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")

private fun unquoteToken(w: String): String = w.replace("\"", "").replace("'", "").replace("\\", "")

/** Lepas wrapper, prefix variabel lingkungan, dan opsi yang hanya mengatur wrapper, bukan command yang dijalankan. */
internal fun unwrap(t0: List<String>): List<String> {
    var t = t0
    while (t.isNotEmpty()) {
        if (ENV_ASSIGN.matches(t[0])) {
            t = t.drop(1)
            continue
        }
        val wrapper = unquoteToken(t[0]).substringAfterLast('/')
        if (wrapper !in WRAPPERS) break
        t = t.drop(1)
        when (wrapper) {
            "timeout" -> {
                while (t.isNotEmpty() && t[0].startsWith("-")) {
                    val option = t[0]
                    t = t.drop(1)
                    if (option in setOf("-k", "--kill-after", "-s", "--signal") && t.isNotEmpty()) t = t.drop(1)
                }
                if (t.isNotEmpty()) t = t.drop(1) // durasi timeout
            }
            "nice" -> {
                while (t.isNotEmpty() && t[0].startsWith("-")) {
                    val option = t[0]
                    t = t.drop(1)
                    if (option in setOf("-n", "--adjustment", "-p", "--priority") && t.isNotEmpty()) t = t.drop(1)
                }
            }
            "xargs" -> {
                while (t.isNotEmpty() && t[0].startsWith("-")) {
                    val option = t[0]
                    t = t.drop(1)
                    if (option in setOf("-E", "--eof", "-I", "--replace", "-L", "--max-lines", "-n", "--max-args", "-P", "--max-procs") && t.isNotEmpty()) t = t.drop(1)
                }
            }
            "env" -> {
                while (t.isNotEmpty() && (t[0].startsWith("-") || t[0].contains('='))) t = t.drop(1)
            }
            else -> t = t.dropWhile { it.startsWith("-") }
        }
    }
    return t
}

/** Risiko dihitung aplikasi sendiri, jangan percaya klaim model. */
fun classify(cmd: String): Risk {
    var worst = Risk.READ
    fun bump(r: Risk) { if (r.ordinal > worst.ordinal) worst = r }

    // Buang redirect yang tidak menulis file: 2>&1, >&2, dan >/dev/null (wajib diikuti batas akhir agar /dev/null.txt tidak lolos)
    val c = cmd.replace(Regex("""\d*>&\d+(?=\s|$|[;&|()])"""), " ").replace(Regex("""\d*>>?\s*/dev/null(?=\s|$|[;&|()])"""), " ")
    // Pertahankan {} dan \; milik find -exec agar tidak terpecah oleh pemisah segmen sebelum semua klausa -exec diperiksa.
    val cSeg = c.replace("\\;", " __FIND_END__ ").replace("{}", " __FIND_ARG__ ")

    // Substitusi perintah / proses sulit diperiksa -> anggap berisiko
    if (cmd.contains("$(") || cmd.contains("`") || cmd.contains("<(") || cmd.contains(">(")) bump(Risk.WRITE)

    // Pisahkan di | & ; baris baru, backtick, dan tanda kurung/kurawal (termasuk & tunggal dan newline)
    for (seg in cSeg.split(Regex("""[|&;\n\r(){}`]+"""))) {
        var t = seg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (t.isEmpty()) continue
        val wrapped = t.firstOrNull()?.let { unquoteToken(it).substringAfterLast('/') } in WRAPPERS
        if (wrapped) bump(Risk.WRITE)
        t = unwrap(t)
        val name = t.firstOrNull()?.let { unquoteToken(it).substringAfterLast('/') } ?: continue
        when {
            name in DELETE_CMDS -> bump(Risk.DELETE)
            name in SHELLS -> {                                 // isi tidak bisa dipastikan
                bump(Risk.WRITE)
                if (t.any { w -> w.split(Regex("[^A-Za-z]+")).any { it in DELETE_CMDS } }) bump(Risk.DELETE)
            }
            name == "unzip" && "-o" in t -> bump(Risk.DELETE)   // menimpa tanpa tanya (diperiksa sebelum -l/-p)
            name == "unzip" && ("-l" in t || "-p" in t) -> {}   // daftar isi zip / baca satu file ke layar
            name == "find" && t.any { it == "-delete" } -> bump(Risk.DELETE)
            name == "find" && t.any { it == "-exec" || it == "-execdir" || it == "-ok" || it == "-okdir" } -> {
                // Periksa SEMUA klausa -exec/-execdir/-ok/-okdir (bukan hanya yang pertama) agar klausa kedua yang destruktif tidak lolos.
                var idx = 0
                while (idx < t.size) {
                    if (t[idx] == "-exec" || t[idx] == "-execdir" || t[idx] == "-ok" || t[idx] == "-okdir") {
                        val nested = t.drop(idx + 1).takeWhile { it != ";" && it != "\\;" && it != "+" && it != "__FIND_END__" }
                        bump(classify(nested.joinToString(" ")))
                        idx += nested.size + 2
                    } else {
                        idx++
                    }
                }
            }
            name == "find" && t.any { it in FIND_WRITE } -> bump(Risk.WRITE)
            name == "tree" && t.any { it == "-o" || it.startsWith("--output") } -> bump(Risk.WRITE)
            name in READ_CMDS -> {}
            // Cek versi (java -version, gradle -v, android --version) hanya membaca. Flag versi harus
            // satu-satunya argumen: "git push -v" atau "python3 skrip.py -v" bukan cek versi.
            VERSION_CMDS.contains(name) && t.size == 2 && t[1] in VERSION_FLAGS -> {}
            else -> bump(Risk.WRITE)                            // tidak dikenal = hati-hati
        }
    }
    // Sisa redirect output (> atau >>, termasuk 2>file) berarti menulis file
    if (c.contains(">")) bump(Risk.WRITE)
    return worst
}

private fun dotName(n: String) = Regex("""(?<![\w])\.""" + n + """(?![\w])""")

private val SENSITIVE = listOf(
    dotName("ssh") to "kunci SSH",
    Regex("id_rsa|id_ed25519") to "kunci SSH",
    dotName("gnupg") to "kunci GPG",
    dotName("netrc") to "kredensial login",
    dotName("git-credentials") to "kredensial git",
    dotName("termux") to "pengaturan Termux",
    Regex("""(?<![\w])\.(bashrc|zshrc|profile)(?![\w])""") to "file startup shell",
    Regex("""(?<![\w])\.(aws|npmrc|pypirc|bash_history|kita-secrets)(?![\w])|\.config/gh(?![\w])|\.codex/auth\.json(?![\w])|\.gemini/oauth_creds\.json(?![\w])|\.claude/\.?credentials\.json(?![\w])|\.gradle/gradle\.properties|(?<![\w])\.env(?![\w])""") to "token/kredensial layanan",
    Regex("""keystore\.properties|\.jks(?![\w])|\.keystore(?![\w])""") to "kunci penandatanganan"
)

/** Alasan peringatan kalau teks (perintah atau path) menyentuh area sensitif; null kalau aman. */
fun sensitiveReason(text: String): String? =
    SENSITIVE.firstOrNull { it.first.containsMatchIn(text) }?.second


/** Folder sistem Termux: ditandai sebagai risiko di dialog persetujuan. Tidak diblokir. */
val TERMUX_SYSTEM = Regex("/files/usr(/|$|\\s)")
