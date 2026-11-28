package com.build.mythos

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Menjalankan perintah di Termux (RUN_COMMAND) dan menghentikannya.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** Hentikan proses perintah di Termux (PID dicatat di pidFile) beserta anak-anaknya; jalan di thread terpisah. */
internal fun MainActivity.killRemote(pidFile: String) {
    bg {
        try {
            termux(
                "kt() { for c in \$(pgrep -P \"\$1\" 2>/dev/null); do kt \"\$c\" \"\$2\"; done; kill -\"\$2\" \"\$1\" 2>/dev/null; }; " +
                    "p=\$(cat ${sh(pidFile)} 2>/dev/null); " +
                    "if [ -n \"\$p\" ]; then case \"\$p\" in ''|*[!0-9]*) ;; *) kt \"\$p\" TERM; sleep 2; kt \"\$p\" KILL ;; esac; fi; rm -f ${sh(pidFile)}",
                4000, 15, false, false
            )
        } catch (e: Exception) {
        }
    }
}

// Jalankan perintah di Termux lewat RUN_COMMAND, lalu tunggu hasilnya (stdout/stderr/exit code).
internal fun MainActivity.termux(
    cmd: String,
    limit: Int = 4000,
    timeoutSec: Long = 600,
    useWorkdir: Boolean = true,
    killable: Boolean = true,
    liveFile: String? = null,
    silentRetry: Boolean = false,
    liveStderrFile: String? = null,
    // Data rahasia (mis. token) dikirim lewat stdin Termux (RUN_COMMAND_STDIN, Termux >= 0.109), BUKAN lewat string perintah,
    // supaya tidak muncul di argumen proses. Skrip membacanya dengan `read`.
    stdin: String? = null
): String {
    // Keluaran dapat ditulis ke file live. Untuk streaming Codex, stderr dipisah agar diagnostik
    // tidak pernah tercampur ke teks jawaban yang dibaca oleh UI.
    val runBody = if (liveFile != null) {
        val stderrRedirect = if (liveStderrFile != null) "2> ${sh(liveStderrFile)}" else "2>&1"
        "mkdir -p ${sh(termuxTmp)}\nset -o pipefail\n(\n$cmd\n) $stderrRedirect | tee -a ${sh(liveFile)}"
    } else cmd
    val action = "com.build.mythos.RESULT." + UUID.randomUUID()
    val pidFile = "$termuxTmp/kita_run_${UUID.randomUUID()}.pid"
    val pidPrefix = if (killable)
        "mkdir -p ${sh(termuxTmp)}; trap ${sh("rm -f $pidFile")} EXIT; echo \$\$ > ${sh(pidFile)}; "
    else ""
    val latch = CountDownLatch(1)
    var out = "Tidak ada hasil."
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val b = i.getBundleExtra("result")
            if (b != null) {
                val errmsg = b.getString("errmsg") ?: ""
                val body = (b.getString("stdout") ?: "") + (b.getString("stderr") ?: "")
                // Awal DAN akhir dipertahankan: error kompilasi pertama ada di awal, ringkasan di akhir.
                val shown = if (body.length > limit) {
                    val head = limit / 4
                    "…(dipotong, ${body.length - limit} karakter di tengah dibuang)\n" + body.take(head) +
                        "\n…\n" + body.takeLast(limit - head)
                } else body
                out = (if (errmsg.isNotEmpty()) "ERR: $errmsg\n" else "") +
                    "exit=${b.getInt("exitCode")}\n" + shown
            }
            latch.countDown()
        }
    }
    val filter = IntentFilter(action)
    if (Build.VERSION.SDK_INT >= 33) {
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(receiver, filter, "com.termux.permission.RUN_COMMAND", null)
    }
    var savedStatus: StatusSnap? = null
    try {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(
            this, reqCode.getAndIncrement(), Intent(action).setPackage(packageName), flags
        )
        val home = WorkspacePaths.TERMUX_HOME
        val bin = WorkspacePaths.TERMUX_BIN
        // Pembungkus LD_PRELOAD: lihat preloadWrapper() di TermuxWrap.kt.
        val wrap = preloadWrapper(bin)
        val i = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            this.action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "$bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrap, "kita",
                "export PATH=$bin:\$PATH; $pidPrefix${if (useWorkdir) cdPrefix(home) else ""}$runBody"))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", home)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            if (stdin != null) putExtra("com.termux.RUN_COMMAND_STDIN", stdin)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        }
        var tries = 0
        while (true) {
            try {
                startService(i)
                break
            } catch (e: IllegalStateException) {
                // Android 12+: aplikasi di latar belakang tidak boleh memulai service. Tunggu sampai dibuka lagi.
                if (++tries > 300) throw e
                if (savedStatus == null && !silentRetry) {
                    savedStatus = saveStatus()
                    setStatus(KIND_DOTS, "Buka aplikasi ini lagi agar perintah bisa jalan")
                }
                if (cancelled) throw InterruptedException()
                Thread.sleep(2000)
            }
        }
        savedStatus?.let { restoreStatus(it); savedStatus = null }
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            if (killable) killRemote(pidFile)
            return timeoutResult(timeoutSec)
        }
    } catch (e: InterruptedException) {
        if (killable) killRemote(pidFile)
        throw Cancelled()
    } catch (e: Exception) {
        return "Gagal memanggil Termux: ${e.message}"
    } finally {
        savedStatus?.let { restoreStatus(it); savedStatus = null }
        try { unregisterReceiver(receiver) } catch (e: IllegalArgumentException) { logQuiet("TermuxShell.kt", e) }
    }
    return out
}
