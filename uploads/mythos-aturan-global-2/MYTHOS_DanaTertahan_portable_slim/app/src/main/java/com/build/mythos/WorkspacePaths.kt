package com.build.mythos

/** Validasi dan resolusi path workspace. Murni JVM agar aturan yang sama bisa diuji tanpa Android/Termux. */
internal object WorkspacePaths {
    // Lokasi tetap di Termux dan penyimpanan bersama. Satu-satunya tempat path ini ditulis; ubah di sini saja.
    const val TERMUX_FILES = "/data/data/com.termux/files"
    const val TERMUX_HOME = "$TERMUX_FILES/home"
    const val TERMUX_PREFIX = "$TERMUX_FILES/usr"
    const val TERMUX_BIN = "$TERMUX_PREFIX/bin"
    const val TERMUX_TMP = "$TERMUX_PREFIX/tmp"
    const val TERMUX_PRELOAD_LIB = "$TERMUX_PREFIX/lib/libtermux-exec-ld-preload.so"
    const val SDCARD_DOWNLOAD = "/sdcard/Download"
    const val SDCARD_ATTACHMENTS = "$SDCARD_DOWNLOAD/MYTHOS/attachments"

    /**
     * Normalisasi input pengaturan tanpa mengakses filesystem.
     * Null berarti input berbahaya/ambigu; string kosong dan "~" berarti HOME Termux.
     */
    fun normalizeSetting(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value == "~") return value
        if (value.any { it == '\u0000' || Character.isISOControl(it) || it in "\"'`$;|&\\" }) return null
        if (value.startsWith("~") && !value.startsWith("~/")) return null

        val prefix = when {
            value.startsWith("~/") -> "~/"
            value.startsWith("/") -> "/"
            else -> ""
        }
        val body = value.removePrefix(prefix)
        val parts = body.split('/')
        if (parts.any { it == ".." }) return null
        val clean = parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        return when {
            prefix == "~/" -> if (clean.isEmpty()) "~" else "~/$clean"
            prefix == "/" -> if (clean.isEmpty()) "/" else "/$clean"
            else -> clean
        }
    }

    /** Ubah preferensi menjadi path absolut yang konsisten. Preferensi lama tidak valid kembali ke ~/MYTHOS. */
    fun resolve(raw: String, home: String): String {
        val value = normalizeSetting(raw) ?: "MYTHOS"
        return when {
            value.isEmpty() || value == "~" -> collapsePath(home)
            value.startsWith("~/") -> collapsePath(home.trimEnd('/') + "/" + value.removePrefix("~/"))
            value.startsWith("/") -> collapsePath(value)
            else -> collapsePath(home.trimEnd('/') + "/" + value)
        }
    }

    /** Tolak root filesystem dan area internal paket Termux; root HOME tetap boleh dipilih secara eksplisit. */
    fun validationError(raw: String, home: String): String? {
        val normalized = normalizeSetting(raw)
            ?: return "Path tidak valid. Jangan gunakan '..', karakter kontrol/shell, atau awalan '~nama'."
        val resolved = resolve(normalized, home)
        if (resolved == "/") return "Root '/' tidak boleh dijadikan folder kerja. Pilih folder khusus."

        val reference = collapsePath(home.trimEnd('/') + "/MYTHOS/reference")
        if (isWithin(resolved, reference)) {
            return "Folder kerja tidak boleh berada di MYTHOS/reference karena direktori itu dilindungi sebagai sumber hanya-baca."
        }

        val termuxUsr = collapsePath(home.substringBeforeLast("/home", home).trimEnd('/') + "/usr")
        if (isWithin(resolved, termuxUsr)) {
            return "Folder kerja tidak boleh berada di direktori sistem Termux (/usr). Pilih folder data pengguna."
        }
        return null
    }


    /**
     * Skrip baca-saja untuk mengenali repository Git yang menaungi workspace.
     * Marker IN_GIT_REPO menyertakan root sebenarnya agar UI tidak menebak repository mana yang terdeteksi.
     */
    fun gitRootProbeCommand(workspaceRoot: String, quote: (String) -> String): String =
        "target=\$(realpath -m -- ${quote(workspaceRoot)}) || { echo MYTHOS_WORKDIR_CHECK_FAILED; exit 90; }; " +
            "probe=\$target; while [ -n \"\$probe\" ]; do " +
            "if [ -d \"\$probe/.git\" ] || [ -f \"\$probe/.git\" ]; then printf 'MYTHOS_WORKDIR_IN_GIT_REPO=%s\\n' \"\$probe\"; exit 0; fi; " +
            "[ \"\$probe\" = / ] && break; probe=\$(dirname -- \"\$probe\"); done; " +
            "printf 'MYTHOS_WORKDIR_NOT_IN_GIT_REPO=%s\\n' \"\$target\""

    /**
     * Siapkan workspace sebelum preferensi disimpan: validasi path canonical, buat folder bila belum ada,
     * lalu laporkan root Git terdekat. Path relatif/~ wajib tetap berada di HOME Termux setelah symlink
     * diselesaikan; path absolut eksplisit tetap boleh dipakai selama lolos larangan root, /usr, dan reference.
     */
    fun prepareAndProbeCommand(
        workspaceRoot: String,
        termuxHome: String,
        referenceRoot: String,
        termuxUsr: String,
        requireHomeContainment: Boolean,
        quote: (String) -> String
    ): String {
        val containmentCheck = if (requireHomeContainment) """
            if ! is_within "${'$'}target_pre" "${'$'}home_real"; then
                echo MYTHOS_WORKDIR_OUTSIDE_HOME
                exit 93
            fi
        """.trimIndent() else ""
        val finalContainmentCheck = if (requireHomeContainment) """
            if ! is_within "${'$'}target_real" "${'$'}home_real"; then
                echo MYTHOS_WORKDIR_OUTSIDE_HOME
                exit 93
            fi
        """.trimIndent() else ""
        return """
            set -eu
            command -v realpath >/dev/null 2>&1 || { echo MYTHOS_WORKDIR_REALPATH_UNAVAILABLE; exit 90; }
            home_real=${'$'}(realpath -e -- ${quote(termuxHome)}) || { echo MYTHOS_WORKDIR_HOME_UNAVAILABLE; exit 90; }
            target_input=${quote(workspaceRoot)}
            target_pre=${'$'}(realpath -m -- "${'$'}target_input") || { echo MYTHOS_WORKDIR_CHECK_FAILED; exit 90; }
            reference_real=${'$'}(realpath -m -- ${quote(referenceRoot)}) || { echo MYTHOS_WORKDIR_CHECK_FAILED; exit 90; }
            usr_real=${'$'}(realpath -m -- ${quote(termuxUsr)}) || { echo MYTHOS_WORKDIR_CHECK_FAILED; exit 90; }
            is_within() {
                [ "${'$'}1" = "${'$'}2" ] && return 0
                case "${'$'}1" in "${'$'}2"/*) return 0 ;; esac
                return 1
            }
            if [ "${'$'}target_pre" = / ]; then echo MYTHOS_WORKDIR_ROOT_FORBIDDEN; exit 92; fi
            if is_within "${'$'}target_pre" "${'$'}usr_real"; then echo MYTHOS_WORKDIR_USR_FORBIDDEN; exit 92; fi
            if is_within "${'$'}target_pre" "${'$'}reference_real"; then echo MYTHOS_WORKDIR_REFERENCE_FORBIDDEN; exit 92; fi
            $containmentCheck
            mkdir -p -- "${'$'}target_input" || { echo MYTHOS_WORKDIR_CREATE_FAILED; exit 94; }
            target_real=${'$'}(realpath -e -- "${'$'}target_input") || { echo MYTHOS_WORKDIR_RESOLVE_FAILED; exit 90; }
            [ -d "${'$'}target_real" ] || { echo MYTHOS_WORKDIR_NOT_DIRECTORY; exit 94; }
            if [ "${'$'}target_real" = / ]; then echo MYTHOS_WORKDIR_ROOT_FORBIDDEN; exit 92; fi
            if is_within "${'$'}target_real" "${'$'}usr_real"; then echo MYTHOS_WORKDIR_USR_FORBIDDEN; exit 92; fi
            if is_within "${'$'}target_real" "${'$'}reference_real"; then echo MYTHOS_WORKDIR_REFERENCE_FORBIDDEN; exit 92; fi
            $finalContainmentCheck
            printf 'MYTHOS_WORKDIR_READY=%s\n' "${'$'}target_real"
            probe=${'$'}target_real
            while [ -n "${'$'}probe" ]; do
                if [ -d "${'$'}probe/.git" ] || [ -f "${'$'}probe/.git" ]; then
                    printf 'MYTHOS_WORKDIR_IN_GIT_REPO=%s\n' "${'$'}probe"
                    exit 0
                fi
                [ "${'$'}probe" = / ] && break
                probe=${'$'}(dirname -- "${'$'}probe")
            done
            printf 'MYTHOS_WORKDIR_NOT_IN_GIT_REPO=%s\n' "${'$'}target_real"
        """.trimIndent()
    }

    /** Skrip pemeriksaan Termux: semua target di-resolve melalui realpath sebelum operasi tulis. */
    fun referenceGuardCommand(paths: List<String>, referenceRoot: String, quote: (String) -> String): String {
        val checks = paths.mapIndexed { index, path ->
            "target$index=\$(realpath -m -- ${quote(path)}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 91; }; " +
                "case \"\$target$index\" in \"\$reference_real\"|\"\$reference_real\"/*) echo MYTHOS_REFERENCE_BLOCKED; exit 92;; esac"
        }.joinToString("\n")
        return """
            command -v realpath >/dev/null 2>&1 || { echo MYTHOS_PATH_GUARD_UNAVAILABLE; exit 90; }
            reference_real=${'$'}(realpath -m -- ${quote(referenceRoot)}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 91; }
            $checks
            echo MYTHOS_REFERENCE_PATH_OK
        """.trimIndent()
    }

    fun isWithin(path: String, root: String): Boolean {
        val p = collapsePath(path).trimEnd('/').ifEmpty { "/" }
        val r = collapsePath(root).trimEnd('/').ifEmpty { "/" }
        if (p == r) return true
        return if (r == "/") p.startsWith("/") else p.startsWith("$r/")
    }
}
