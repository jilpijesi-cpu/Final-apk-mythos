package com.build.mythos

import android.app.Application
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saat aplikasi crash, buka layar [CrashActivity] (proses terpisah) berisi penyebabnya agar bisa disalin dan dikirim.
 * Tidak mengubah perilaku lain: setelah itu proses tetap dimatikan seperti crash biasa.
 */
internal object CrashReporter {
    const val EXTRA_TRACE = "trace"
    private const val MAX_CHARS = 30000

    fun install(app: Application) {
        val proc = try { File("/proc/self/cmdline").readText().trim('\u0000') } catch (e: Exception) { "" }
        if (proc.endsWith(":crash")) return // jangan memasang di proses layar crash sendiri
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (e: Exception) { "?" }
                val text = "Mythos $version | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Thread: ${thread.name}\n\n" + sw.toString()
                val intent = Intent(app, CrashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(EXTRA_TRACE, redactSecrets(text).take(MAX_CHARS))
                app.startActivity(intent)
                Thread.sleep(400)
            } catch (ignored: Throwable) {
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }
}
