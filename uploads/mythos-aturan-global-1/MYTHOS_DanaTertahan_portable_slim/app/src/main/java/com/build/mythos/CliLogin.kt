package com.build.mythos

import android.content.Intent

// Login CLI berlangganan lewat Termux (data CLI ada di CliLogins.kt).
// Sesi login dibuka terlihat (foreground) supaya link login bisa dibuka atau disalin.
// Fungsi-fungsi di sini adalah ekstensi MainActivity.

/** Buka sesi Termux: pasang CLI kalau belum ada, lalu jalankan perintah login-nya. */
internal fun MainActivity.cliLogin(c: CliLogin) {
    val bin = WorkspacePaths.TERMUX_BIN
    val home = WorkspacePaths.TERMUX_HOME
    val ready = c.ready.ifBlank { "command -v ${c.bin} >/dev/null 2>&1" }
    val script = "export BROWSER=termux-open-url; " +
        "if ! (${ready}) >/dev/null 2>&1; then " +
        "echo 'CLI belum siap; memasang atau memperbaiki instalasi…'; " +
        "${c.install} || exit \$?; fi; " +
        "${c.login}; echo; echo 'Selesai. Kembali ke aplikasi Mythos.'"
    try {
        val i = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            this.action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "$bin/bash")
            // Pembungkus LD_PRELOAD sama seperti TermuxShell: tanpa ini gemini/codex gagal "bad interpreter" saat login.
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", preloadWrapper(bin), "kita", "export PATH=$bin:\$PATH; $script"))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", home)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        startService(i)
        if (c.tip.isNotEmpty()) toast(c.tip)
    } catch (e: Exception) {
        toast("Gagal membuka Termux: " + (e.message ?: "").take(80))
    }
}

/** True jika perintah CLI berasal dari implementasi yang sesuai, bukan pembungkus/mock bernama sama. */
internal fun MainActivity.cliReady(c: CliLogin): Boolean {
    val cmd = c.ready.ifBlank { "command -v ${c.bin} >/dev/null 2>&1" }
    return termux(cmd, timeoutSec = 15, useWorkdir = false, killable = false).startsWith("exit=0")
}

/** Cek autentikasi saja; gunakan setelah cliReady() berhasil. */
internal fun MainActivity.cliAuthenticated(c: CliLogin): Boolean =
    termux(c.check, timeoutSec = 15, useWorkdir = false, killable = false).startsWith("exit=0")

internal val cliAuthCache = CliAuthCache { android.os.SystemClock.elapsedRealtime() }

/**
 * Status CLI untuk chat dan pengaturan. Satu panggilan Termux (siap DAN login) dengan batas 45 detik, karena Termux yang
 * baru dibangunkan bisa butuh puluhan detik; hasil sukses di-cache 10 menit. Panggilan kedua hanya bila gagal, untuk
 * membedakan "belum terpasang" dari "belum masuk". Hasil tidak pasti (timeout/dibunuh) = UNKNOWN, bukan "belum masuk".
 */
internal fun MainActivity.cliStatus(c: CliLogin, useCache: Boolean = true): CliStatus {
    if (useCache && cliAuthCache.isFresh(c.name)) return CliStatus.OK
    val ready = c.ready.ifBlank { "command -v ${c.bin} >/dev/null 2>&1" }
    when (preflightVerdict(termux("( $ready ) && ( ${c.check} )", timeoutSec = 45, useWorkdir = false, killable = false))) {
        true -> { cliAuthCache.markOk(c.name); return CliStatus.OK }
        null -> return CliStatus.UNKNOWN
        false -> {}
    }
    return when (preflightVerdict(termux(ready, timeoutSec = 45, useWorkdir = false, killable = false))) {
        true -> CliStatus.NOT_LOGGED_IN
        false -> CliStatus.NOT_READY
        null -> CliStatus.UNKNOWN
    }
}

/** True hanya jika CLI kompatibel tersedia dan autentikasi aktif. */
internal fun MainActivity.cliLoggedIn(c: CliLogin): Boolean =
    cliReady(c) && cliAuthenticated(c)

/**
 * Login satu ketukan, tanpa pengguna perlu tahu Termux: cek Termux, pasang CLI kalau belum ada, lalu buka login.
 * Kabar tiap langkah dikirim lewat [say] (boleh dipanggil dari thread mana pun, tampilkan lewat runOnUiThread).
 */
internal fun MainActivity.cliSetup(c: CliLogin, say: (String) -> Unit) {
    say("1/3 Memeriksa Termux…")
    bg {
        val ping = termux("echo ok", timeoutSec = 8, useWorkdir = false, killable = false)
        if (!(ping.startsWith("exit=0") && ping.contains("ok"))) {
            say("Termux belum siap. Ikuti langkah di layar Siapkan Termux.")
            runOnUiThread { showSetup() }
            return@bg
        }
        val has = cliReady(c)
        if (!has) {
            say("2/3 Memasang ${c.bin}… tunggu beberapa menit")
            val r = termux(c.install, timeoutSec = 1800, useWorkdir = false, killable = false)
            if (!r.startsWith("exit=0")) {
                val why = r.lineSequence().drop(1).firstOrNull { it.isNotBlank() }.orEmpty().take(80)
                say("Gagal memasang ${c.bin}: $why. Ketuk \"Salin laporan masalah\" di bawah untuk melaporkan.")
                return@bg
            }
        }
        say("3/3 Selesaikan login di browser, lalu kembali ke sini")
        runOnUiThread { cliLogin(c) }
    }
}
