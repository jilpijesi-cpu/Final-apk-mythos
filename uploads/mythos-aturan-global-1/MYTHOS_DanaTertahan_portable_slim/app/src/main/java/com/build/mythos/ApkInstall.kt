package com.build.mythos

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.TextView
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

// Mencari, mengambil dari Termux, dan memasang APK hasil build.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).


internal fun MainActivity.installApk(path: String, btn: TextView) {
    if (!packageManager.canRequestPackageInstalls()) {
        toast("Izinkan aplikasi ini memasang aplikasi, lalu ketuk Pasang APK lagi")
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        return
    }
    btn.isEnabled = false
    btn.text = "Mengambil APK..."
    bg {
        try {
            val f = fetchApk(path)
            val check = verifyApkFile(f)
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "Pasang APK"
                if (check.ok) {
                    launchInstaller(f)
                } else {
                    addNote("Pemasangan dibatalkan, APK tidak lolos pemeriksaan:\n" + apkCheckText(check))
                    toast("Pasang dibatalkan: " + check.problems.first().take(80))
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "Pasang APK"
                toast("Gagal mengambil APK: " + (e.message ?: "").take(100))
            }
        }
    }
}

/**
 * Ambil APK dari Termux lewat soket lokal (127.0.0.1), tanpa izin penyimpanan.
 * Baris pertama aliran adalah token acak supaya aplikasi lain tidak bisa menyusup.
 */
private val apkFetchLock = Any()

internal fun MainActivity.fetchApk(termuxPath: String): File = synchronized(apkFetchLock) {
    val dir = File(cacheDir, "apk")
    dir.mkdirs()
    val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
    dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    val dest = File(dir, "app-${UUID.randomUUID().toString().take(8)}.apk")
    val expected = termux("stat -c %s ${sh(termuxPath)}").lines().drop(1)
        .firstOrNull { it.isNotBlank() }?.trim()?.toLongOrNull() ?: -1L
    if (expected <= 0L) throw RuntimeException("File APK tidak ditemukan atau ukurannya tidak valid")
    val token = UUID.randomUUID().toString()
    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val activeClient = java.util.concurrent.atomic.AtomicReference<java.net.Socket?>(null)
    val err = arrayOfNulls<Exception>(1)
    val rx = Thread {
        try {
            server.soTimeout = 90000
            var matched = false
            for (attempt in 1..3) {
                server.accept().use { sock ->
                    activeClient.set(sock)
                    try {
                        sock.soTimeout = 30000
                        val ins = sock.getInputStream().buffered()
                        val sb = StringBuilder()
                        while (true) {
                            val b = ins.read()
                            if (b < 0 || b == '\n'.code) break
                            sb.append(b.toChar())
                            if (sb.length > 100) break
                        }
                        if (sb.toString() == token) {
                            dest.outputStream().use { o -> ins.copyTo(o) }
                            matched = true
                        }
                    } finally {
                        activeClient.compareAndSet(sock, null)
                    }
                }
                if (matched) break
            }
            if (!matched) throw RuntimeException("Token tidak cocok")
        } catch (e: Exception) {
            err[0] = e
        }
    }
    rx.start()
    var ok = false
    try {
        val r = termux("{ printf '%s\\n' ${sh(token)}; cat ${sh(termuxPath)}; } > /dev/tcp/127.0.0.1/${server.localPort}")
        if (!r.startsWith("exit=0")) throw RuntimeException(r.take(120))
        rx.join(60000)
        if (rx.isAlive) throw RuntimeException("Pengunduhan APK belum selesai (timeout)")
        err[0]?.let { throw it }
        if (dest.length() < 1000) throw RuntimeException("File APK kosong")
        if (dest.length() != expected) {
            throw RuntimeException("Ukuran APK tidak cocok (${dest.length()} dari $expected byte)")
        }
        ok = true
        dest
    } finally {
        try { activeClient.getAndSet(null)?.close() } catch (_: Exception) {}
        try { server.close() } catch (_: Exception) {}
        try { rx.join(2000) } catch (_: Exception) {}
        if (!ok) try { dest.delete() } catch (_: Exception) {}
    }
}

internal fun MainActivity.launchInstaller(f: File) {
    val uri = Uri.parse("content://$packageName.apk/" + f.name)
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(i)
    } catch (e: Exception) {
        toast("Pemasang tidak bisa dibuka: " + (e.message ?: "").take(80))
    }
}
