package com.build.mythos

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

// Halaman "Artefak": file yang dibuat agen (write_file, APK hasil build) dan isi artifacts pada workspace aktif.
// Bisa dibuka, dibagikan, disalin path-nya, atau dihapus. Ekstensi MainActivity.

private const val MAX_SHARE_BYTES = 25L * 1024 * 1024
private const val VIEW_BYTES = 60000
private val shareFetchLock = Any()

private class ArtifactsPage(val a: MainActivity) {
    lateinit var h: PageHandle
    lateinit var tabs: android.widget.LinearLayout
    var files: List<FileEntry>? = null
    var kinds: Map<String, String> = emptyMap()
    var noAnswer = false
    var all = false

    fun open() {
        h = a.pageDialog("Artefak")
        tabs = android.widget.LinearLayout(a)
        h.top.addView(tabs, android.widget.LinearLayout.LayoutParams(MATCH, WRAP))
        render()
        load()
    }

    fun isDeliverable(f: FileEntry): Boolean = kinds[f.path] == "apk" || WorkspaceList.isDeliverable(f.path)

    fun load() {
        h.info.text = "Memuat…"
        val recs = a.artifactLogLoad().take(WorkspaceList.MAX_LOG)
        val art = a.workRoot() + "/artifacts"
        bg {
            val loop = if (recs.isEmpty()) "" else
                "for p in ${recs.joinToString(" ") { a.sh(it.path) }}; do [ -f \"\$p\" ] && stat --printf='%s\\t%Y\\t%n\\n' -- \"\$p\"; done; "
            val r = try {
                a.termux(
                    loop + "[ -d ${a.sh(art)} ] && find ${a.sh(art)} -type f -printf '%s\\t%T@\\t%p\\n' 2>/dev/null | head -n 300; true",
                    limit = 200000, timeoutSec = 60, useWorkdir = false
                )
            } catch (e: Exception) { null }
            val none = termuxNoAnswer(r)
            val list = if (none) null else WorkspaceList.parseFiles(r.orEmpty())
            // File yang sudah tidak ada dibuang dari catatan, tapi hanya bila Termux benar-benar menjawab.
            if (list != null && r != null && r.startsWith("exit=0")) {
                val present = list.map { it.path }.toSet()
                a.forgetArtifacts(recs.map { it.path }.filter { it !in present })
            }
            a.runOnUiThread {
                if (!h.showing) return@runOnUiThread
                noAnswer = none
                files = list
                kinds = recs.associate { it.path to it.kind }
                render()
            }
        }
    }

    fun render() {
        val src = files
        tabs.removeAllViews()
        val deliver = src?.count { isDeliverable(it) } ?: 0
        tabs.addView(a.pillRow(
            a.pill("Hasil ($deliver)", !all) { all = false; render() },
            a.pill("Semua (${src?.size ?: 0})", all) { all = true; render() }
        ))
        h.list.removeAllViews()
        if (noAnswer) {
            h.info.text = ""
            a.addEmptyNote(h, "Tidak bisa membaca file dari Termux. Pastikan Termux terpasang dan izin RUN_COMMAND sudah diberikan.")
            return
        }
        if (src == null) return
        val shown = if (all) src else src.filter { isDeliverable(it) }
        h.info.text = if (shown.isEmpty()) "" else "Ketuk file untuk membuka, membagikan, atau menghapus."
        if (shown.isEmpty()) {
            a.addEmptyNote(h, if (all) "Belum ada file yang ditulis agen." else "Belum ada hasil kerja.\nAPK hasil build dan file di luar folder proyek muncul di sini; file sumber proyek ada di tab Semua.")
            return
        }
        for (f in shown) a.addRow(h, a.pageRow(
            GlyphView.ARTIFACT, f.name,
            a.homeShort(f.dir) + " · " + WorkspaceList.formatSize(f.size) + " · " + fmtDateTime(f.modified * 1000),
            badge = if (WorkspaceList.ext(f.name) == "apk") "APK" else null
        ) { actions(f) })
    }

    fun actions(f: FileEntry) {
        val isApk = WorkspaceList.ext(f.name) == "apk"
        val items = arrayOf(if (isApk) "Pasang" else "Buka", "Bagikan", "Salin path", "Hapus")
        AlertDialog.Builder(a)
            .setTitle(f.name.take(40))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (isApk) a.installApk(f.path, TextView(a)) else if (WorkspaceList.isTextName(f.name)) a.viewTextFile(f) else a.sendFile(f, share = false)
                    1 -> a.sendFile(f, share = true)
                    2 -> {
                        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("path", f.path))
                        a.toast("Path disalin")
                    }
                    3 -> confirmDelete(f)
                    else -> {}
                }
            }
            .show()
    }

    fun confirmDelete(f: FileEntry) {
        AlertDialog.Builder(a)
            .setMessage("Hapus file \"${f.name}\" dari Termux? Ini tidak bisa dibatalkan.")
            .setPositiveButton("Hapus") { _, _ -> delete(f) }
            .setNegativeButton("Batal", null)
            .show()
    }

    fun delete(f: FileEntry) {
        if (!f.path.startsWith("${a.termuxHome}/") || f.path.split('/').any { it == ".." } || a.isProtectedReferencePath(f.path)) { a.toast("File ini tidak boleh dihapus dari sini"); return }
        bg {
            val r = try {
                val blocked = a.referencePathGuard(f.path)
                if (blocked != null) "exit=1 $blocked" else a.termux("rm -f -- ${a.sh(f.path)} && echo DELETED", useWorkdir = false, timeoutSec = 30)
            } catch (e: Exception) { "gagal: ${e.message}" }
            val ok = r.contains("DELETED")
            if (ok) a.forgetArtifacts(listOf(f.path))
            a.runOnUiThread {
                if (ok) { a.toast("File dihapus"); if (h.showing) load() } else a.toast("Gagal menghapus: " + r.take(80))
            }
        }
    }
}

/** Ambil file dari Termux lewat soket lokal ke cache/share dengan nama unik per unduhan. Tanpa izin penyimpanan. */
internal fun MainActivity.fetchTermuxFile(termuxPath: String): File = synchronized(shareFetchLock) {
    sensitiveReason(termuxPath)?.let { throw RuntimeException("Ditolak: berkas sensitif ($it)") }
    val dir = File(cacheDir, "share")
    dir.mkdirs()
    val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
    dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    val uniq = UUID.randomUUID().toString().take(8)
    val dest = File(dir, "${uniq}_${WorkspaceList.cacheName(termuxPath.substringAfterLast('/'))}")
    val expected = termux("stat -c %s ${sh(termuxPath)}", useWorkdir = false).lines().drop(1)
        .firstOrNull { it.isNotBlank() }?.trim()?.toLongOrNull() ?: -1L
    if (expected < 0) throw RuntimeException("File tidak ditemukan")
    if (expected > MAX_SHARE_BYTES) throw RuntimeException("File terlalu besar (maks 25 MB)")
    val token = UUID.randomUUID().toString()
    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val activeClient = AtomicReference<Socket?>(null)
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
        val r = termux("{ printf '%s\\n' ${sh(token)}; cat ${sh(termuxPath)}; } > /dev/tcp/127.0.0.1/${server.localPort}", useWorkdir = false)
        if (!r.startsWith("exit=0")) throw RuntimeException(r.take(120))
        rx.join(60000)
        if (rx.isAlive) throw RuntimeException("Pengambilan file belum selesai (timeout)")
        err[0]?.let { throw it }
        if (dest.length() != expected) throw RuntimeException("Ukuran file tidak cocok (${dest.length()} dari $expected byte)")
        ok = true
        dest
    } finally {
        try { activeClient.getAndSet(null)?.close() } catch (_: Exception) {}
        try { server.close() } catch (_: Exception) {}
        try { rx.join(2000) } catch (_: Exception) {}
        if (!ok) try { dest.delete() } catch (_: Exception) {}
    }
}

// Otoritas = ${applicationId}.share (manifest); dibaca dari packageName supaya tetap cocok bila applicationId diganti lewat -PappId.
private fun shareUri(pkg: String, f: File): Uri = Uri.Builder().scheme("content").authority("$pkg.share").appendPath(f.name).build()

/** Buka ([share] false) atau bagikan ([share] true) file Termux lewat aplikasi lain. */
internal fun MainActivity.sendFile(f: FileEntry, share: Boolean) {
    toast("Mengambil file…")
    bg {
        try {
            val local = fetchTermuxFile(f.path)
            val uri = shareUri(packageName, local)
            val mime = WorkspaceList.mimeFor(f.name)
            runOnUiThread {
                try {
                    if (share) {
                        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = ClipData.newRawUri("", uri)
                        startActivity(Intent.createChooser(send, "Bagikan file").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } catch (e: ActivityNotFoundException) {
                    toast("Tidak ada aplikasi untuk membuka file ini")
                }
            }
        } catch (e: Exception) {
            runOnUiThread { toast("Gagal mengambil file: " + (e.message ?: "").take(100)) }
        }
    }
}

/** Tampilkan isi file teks (maks 60 KB pertama) di dialog; bisa disalin atau dibagikan. */
internal fun MainActivity.viewTextFile(f: FileEntry) {
    sensitiveReason(f.path)?.let { toast("Ditolak: berkas sensitif ($it)"); return }
    toast("Membuka…")
    bg {
        val r = try { termux("head -c $VIEW_BYTES -- ${sh(f.path)}", limit = VIEW_BYTES + 4000, timeoutSec = 30, useWorkdir = false) } catch (e: Exception) { null }
        runOnUiThread {
            if (r == null || !r.startsWith("exit=0")) { toast("Gagal membuka file"); return@runOnUiThread }
            val body = r.substringAfter("\n", "")
            val tv = TextView(this).apply {
                text = body.ifEmpty { "(file kosong)" }
                typeface = Typeface.MONOSPACE
                textSize = Typo.CAPTION
                setTextColor(Palette.INK)
                setTextIsSelectable(true)
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            AlertDialog.Builder(this)
                .setTitle(f.name.take(40))
                .setView(ScrollView(this).apply { addView(tv) })
                .setPositiveButton("Tutup", null)
                .setNeutralButton("Bagikan") { _, _ -> sendFile(f, share = true) }
                .show()
        }
    }
}

internal fun MainActivity.showArtifactsPage() = ArtifactsPage(this).open()
