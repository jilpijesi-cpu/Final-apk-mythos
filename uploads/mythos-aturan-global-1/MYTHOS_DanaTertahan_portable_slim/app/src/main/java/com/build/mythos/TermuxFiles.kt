package com.build.mythos

import android.util.Base64
import java.io.File

// Path, cadangan, dan penulisan file lewat Termux (dunia CLI).
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** Isi file lama: "" kalau belum ada, null kalau tidak bisa dibaca penuh. */
internal fun MainActivity.readOld(path: String): String? {
    val r = termux("test -f ${sh(path)} && cat ${sh(path)}", 300000)
    return when {
        // Persis exit=1 (file tidak ada). startsWith("exit=1") juga cocok dengan exit=124/127/137.
        r == "exit=1" || r.startsWith("exit=1\n") -> ""
        r.startsWith("exit=0\n…(dipotong") -> null
        r.startsWith("exit=0\n") -> r.removePrefix("exit=0\n")
        else -> null
    }
}

/** Perluas awalan ~ (tanda kutip pada perintah mencegah shell memperluasnya sendiri). */
internal fun MainActivity.normPath(p: String): String {
    val t = p.trim()
    return when {
        t == "~" -> termuxHome
        t.startsWith("~/") -> termuxHome + "/" + t.removePrefix("~/")
        else -> t
    }
}

internal fun MainActivity.sh(s: String) = "'" + s.replace("'", "'\\''") + "'"

/** Folder kerja CHAT AKTIF: <workspace>/chats/<idChat>. Satu chat = satu ruang kerja; chat lain tidak terlihat dan tidak boleh disentuh. */
internal fun MainActivity.workspaceRoot(): String =
    ChatWorkspaceRules.chatDir(workspaceBase(), currentChatId()) ?: workspaceBase()

/** Source resmi tetap berada pada lokasi migrasi HOME/MYTHOS dan tidak ikut berpindah saat workdir diganti. */
internal fun MainActivity.referenceRoot(): String = "$termuxHome/MYTHOS/reference"

internal fun MainActivity.isProtectedReferencePath(path: String): Boolean {
    val p = collapsePath(if (path.startsWith("/")) path else workspaceRoot() + "/" + path)
    val r = collapsePath(referenceRoot())
    return p == r || p.startsWith("$r/")
}

/**
 * Pemeriksaan filesystem tambahan untuk write_file/build/create. realpath -m menyelesaikan symlink pada
 * parent path, termasuk saat target akhir belum ada. Gagal memverifikasi berarti ditolak (fail closed).
 */
internal fun MainActivity.referencePathGuard(path: String): String? = referencePathsGuard(listOf(path))

/** Periksa beberapa tujuan dalam satu proses Termux agar startup tidak melakukan satu panggilan per folder. */
internal fun MainActivity.referencePathsGuard(paths: Collection<String>): String? {
    val absolutePaths = paths.map { absPath(it) }.distinct()
    if (absolutePaths.isEmpty()) return null
    if (absolutePaths.any { isOtherChatPath(it) }) {
        return "Ditolak: path itu milik chat lain. Kamu hanya boleh bekerja di folder chat ini: ${workspaceRoot()}"
    }
    if (absolutePaths.any { isProtectedReferencePath(it) }) {
        return "Ditolak: target berada di MYTHOS/reference yang dilindungi. Salin source ke proyek aktif sebelum mengubahnya."
    }
    val cmd = WorkspacePaths.referenceGuardCommand(absolutePaths, referenceRoot()) { sh(it) }
    val result = try { termux(cmd, limit = 500, timeoutSec = 10, useWorkdir = false, killable = false) }
    catch (e: Exception) { "guard error: ${e.message}" }
    return when {
        result.startsWith("exit=0") && result.contains("MYTHOS_REFERENCE_PATH_OK") -> null
        result.contains("MYTHOS_REFERENCE_BLOCKED") -> "Ditolak: target mengarah melalui symlink ke MYTHOS/reference yang dilindungi."
        else -> "Ditolak: keamanan path tidak dapat diverifikasi oleh Termux (realpath). File tidak diubah."
    }
}


/** Path absolut yang dinormalkan terhadap workspace terpilih. */
internal fun MainActivity.absPath(p: String): String =
    collapsePath(if (p.startsWith("/")) p else workspaceRoot() + "/" + p)


/** Buat struktur operasional workspace terpilih tanpa mengubah atau memindahkan isi yang sudah ada. */
internal fun MainActivity.ensureWorkspaceLayout() {
    val root = workspaceRoot()
    val normalized = WorkspacePaths.normalizeSetting(workdir()) ?: "MYTHOS"
    val termuxUsr = termuxHome.substringBeforeLast("/home", termuxHome).trimEnd('/') + "/usr"
    val prepare = WorkspacePaths.prepareAndProbeCommand(
        workspaceRoot = root,
        termuxHome = termuxHome,
        referenceRoot = referenceRoot(),
        termuxUsr = termuxUsr,
        requireHomeContainment = !normalized.startsWith("/"),
        quote = { sh(it) }
    )
    val prepared = termux(prepare, limit = 800, timeoutSec = 20, useWorkdir = false, killable = false)
    if (!prepared.startsWith("exit=0") || !prepared.contains("MYTHOS_WORKDIR_READY=")) {
        logQuiet("TermuxFiles.kt", RuntimeException("Workspace tidak aman/tidak dapat disiapkan: ${prepared.take(240)}"))
        return
    }
    // Periksa setiap direktori operasional setelah symlink di-resolve; jangan membuat subfolder di luar HOME
    // atau di MYTHOS/reference melalui symlink yang ditanam di dalam workspace.
    val relativeDirs = listOf("projects/aktif", "projects/arsip", "artifacts", "attachments", "backups", "logs", "memory")
    val requireHome = !normalized.startsWith("/")
    val subdirChecks = relativeDirs.mapIndexed { index, relative ->
        "dir$index=\$(realpath -m -- \"\$root_real/$relative\") || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
            "is_within \"\$dir$index\" \"\$usr_real\" && { echo MYTHOS_WORKDIR_USR_FORBIDDEN; exit 1; }; " +
            "is_within \"\$dir$index\" \"\$reference_real\" && { echo MYTHOS_REFERENCE_BLOCKED; exit 1; }; " +
            (if (requireHome) "is_within \"\$dir$index\" \"\$home_real\" || { echo MYTHOS_WORKDIR_OUTSIDE_HOME; exit 1; }; " else "")
    }.joinToString("\n")
    val mkdirTargets = relativeDirs.joinToString(" ") { "\"\$root_real/$it\"" }
    val command = "set -eu; " +
        "home_real=\$(realpath -e -- ${sh(termuxHome)}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
        "root_real=\$(realpath -e -- ${sh(root)}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
        "reference_real=\$(realpath -m -- ${sh(referenceRoot())}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
        "usr_real=\$(realpath -m -- ${sh(termuxUsr)}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
        "is_within() { [ \"\$1\" = \"\$2\" ] && return 0; case \"\$1\" in \"\$2\"/*) return 0;; esac; return 1; }; " +
        subdirChecks + "\nmkdir -p -- $mkdirTargets"
    val result = termux(command, limit = 700, timeoutSec = 30, useWorkdir = false, killable = false)
    if (!result.startsWith("exit=0")) logQuiet("TermuxFiles.kt", RuntimeException("Gagal menyiapkan workspace: ${result.take(180)}"))
}

/** Path absolut folder kerja di Termux, atau null kalau memakai home. */
internal fun MainActivity.wdTarget(home: String): String? {
    val wd = WorkspacePaths.normalizeSetting(workdir()) ?: "MYTHOS"
    return when {
        wd.isEmpty() || wd == "~" -> null
        else -> WorkspacePaths.resolve(wd, home)
    }
}

/** Awalan perintah yang pindah ke folder kerja yang dipilih di Pengaturan. */
internal fun MainActivity.cdPrefix(home: String): String {
    val normalized = WorkspacePaths.normalizeSetting(workdir()) ?: "MYTHOS"
    val target = workspaceRoot()   // satu chat = satu folder kerja (dibuat otomatis di bawah)
    val requireHome = !normalized.startsWith("/")
    val createRoot = true
    val homeCheck = if (requireHome) "if ! is_within \"\$target_pre\" \"\$home_real\"; then echo MYTHOS_WORKDIR_OUTSIDE_HOME; exit 1; fi; " else ""
    val finalHomeCheck = if (requireHome) "if ! is_within \"\$target_real\" \"\$home_real\"; then echo MYTHOS_WORKDIR_OUTSIDE_HOME; exit 1; fi; " else ""
    val create = if (createRoot) "mkdir -p -- \"\$target_input\" 2>/dev/null || { printf 'Gagal membuat folder kerja: %s\\n' \"\$target_input\"; exit 1; }; " else ""
    val guardedDirs = listOf("projects/aktif", "projects/arsip", "artifacts", "attachments", "backups", "logs", "memory")
    val checks = guardedDirs.mapIndexed { index, relative ->
        "candidate$index=\$(realpath -m -- \"\$target_real/$relative\") || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
            "is_within \"\$candidate$index\" \"\$usr_real\" && { echo MYTHOS_WORKDIR_USR_FORBIDDEN; exit 1; }; " +
            (if (requireHome) "is_within \"\$candidate$index\" \"\$home_real\" || { echo MYTHOS_WORKDIR_OUTSIDE_HOME; exit 1; }; " else "") +
            "case \"\$candidate$index\" in \"\$reference_real\"|\"\$reference_real\"/*) echo MYTHOS_REFERENCE_BLOCKED; exit 1;; esac"
    }.joinToString("\n")
    val dirs = guardedDirs.joinToString(" ") { "\"\$target_real/$it\"" }
    return "command -v realpath >/dev/null 2>&1 || { echo MYTHOS_PATH_GUARD_UNAVAILABLE; exit 1; }; " +
        "home_real=\$(realpath -e -- ${sh(home)}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
        "target_input=${sh(target)}; target_pre=\$(realpath -m -- \"\$target_input\") || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
        "reference_real=\$(realpath -m -- ${sh(referenceRoot())}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
        "usr_real=\$(realpath -m -- ${sh(home.substringBeforeLast("/home", home).trimEnd('/') + "/usr")}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
        "is_within() { [ \"\$1\" = \"\$2\" ] && return 0; case \"\$1\" in \"\$2\"/*) return 0;; esac; return 1; }; " +
        "[ \"\$target_pre\" != / ] || { echo MYTHOS_WORKDIR_ROOT_FORBIDDEN; exit 1; }; " +
        "is_within \"\$target_pre\" \"\$usr_real\" && { echo MYTHOS_WORKDIR_USR_FORBIDDEN; exit 1; }; " +
        "is_within \"\$target_pre\" \"\$reference_real\" && { echo MYTHOS_WORKDIR_REFERENCE_FORBIDDEN; exit 1; }; " +
        homeCheck + create +
        "target_real=\$(realpath -e -- \"\$target_input\") || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
        "[ -d \"\$target_real\" ] || { echo MYTHOS_WORKDIR_NOT_DIRECTORY; exit 1; }; " +
        "[ \"\$target_real\" != / ] || { echo MYTHOS_WORKDIR_ROOT_FORBIDDEN; exit 1; }; " +
        "is_within \"\$target_real\" \"\$usr_real\" && { echo MYTHOS_WORKDIR_USR_FORBIDDEN; exit 1; }; " +
        "is_within \"\$target_real\" \"\$reference_real\" && { echo MYTHOS_WORKDIR_REFERENCE_FORBIDDEN; exit 1; }; " +
        finalHomeCheck + checks + "\nmkdir -p -- $dirs || { echo MYTHOS_WORKSPACE_LAYOUT_FAILED; exit 1; }; " +
        "cd \"\$target_real\" 2>/dev/null || { printf 'Folder kerja tidak dapat dibuka: %s\\n' \"\$target_input\"; exit 1; }; "
}

/**
 * Tulis file lewat potongan base64 kecil (satu argumen perintah dibatasi ±128 KB), ke file sementara,
 * lalu ganti file tujuan dengan mv. File lama tidak terkosongkan kalau gagal; izin (chmod) file lama dipertahankan.
 * Hasil berakhiran "OK" kalau berhasil.
 */
internal fun MainActivity.writeFileChunked(path: String, content: String): String {
    referencePathGuard(path)?.let { return "exit=1 $it" }
    val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
    val tmpDir = WorkspacePaths.TERMUX_TMP
    val tmpB = "$tmpDir/kita_${System.nanoTime()}.b64"
    val tmpOut = path + ".kita-tmp"
    fun cleanup() { try { termux("rm -f ${sh(tmpB)} ${sh(tmpOut)}") } catch (e: Exception) { logQuiet("TermuxFiles.kt", e) } }
    var r = termux("mkdir -p \"\$(dirname ${sh(path)})\" && mkdir -p ${sh(tmpDir)} && : > ${sh(tmpB)}")
    if (!r.startsWith("exit=0")) return r
    val size = 60000
    var i = 0
    while (i < b64.length) {
        if (cancelled) { cleanup(); throw Cancelled() }
        val part = b64.substring(i, minOf(i + size, b64.length))
        r = termux("printf '%s' '$part' >> ${sh(tmpB)}")
        if (!r.startsWith("exit=0")) { cleanup(); return r }
        i += size
    }
    return termux(
        "{ base64 -d ${sh(tmpB)} > ${sh(tmpOut)} && " +
            "{ [ ! -e ${sh(path)} ] || chmod --reference=${sh(path)} ${sh(tmpOut)} 2>/dev/null; true; } && " +
            "mv -f ${sh(tmpOut)} ${sh(path)} && echo OK; }; rc=\$?; rm -f ${sh(tmpB)} ${sh(tmpOut)}; exit \$rc"
    )
}

