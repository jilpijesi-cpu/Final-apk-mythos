package com.build.mythos

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Halaman "Ruang kerja": penjelajah berkas di folder privat agen API (filesDir/cc_projects/<chatId>/).
// Read-only untuk menjelajah isi, pratinjau berkas teks, dan ekspor file tunggal atau ZIP ke Download/MYTHOS/export/.

private const val PREVIEW_MAX_BYTES = 256 * 1024L

private fun fmtBytes(n: Long): String = when {
    n < 1024L -> "$n B"
    n < 1024L * 1024L -> "${n / 1024L} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024.0))
}

private fun zipFolderSafely(root: File, destZip: File): Int {
    val rootCanon = root.canonicalFile
    var count = 0
    ZipOutputStream(FileOutputStream(destZip)).use { zos ->
        fun walk(dir: File) {
            val children = dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: return
            for (f in children) {
                if (Files.isSymbolicLink(f.toPath())) continue
                val canon = try { f.canonicalFile } catch (_: Exception) { continue }
                if (canon != rootCanon && !canon.path.startsWith(rootCanon.path + File.separator)) continue
                val rel = canon.relativeTo(rootCanon).invariantSeparatorsPath
                if (rel.isEmpty()) continue
                if (f.isDirectory) {
                    walk(f)
                } else if (f.isFile) {
                    zos.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    count++
                }
            }
        }
        walk(rootCanon)
    }
    return count
}

private class AgentWorkspacePage(val a: MainActivity) {
    private val rootDir: File = a.ccProjectDir()
    private val rootCanon: File = try { rootDir.canonicalFile } catch (_: Exception) { rootDir }
    private var curDir: File = rootCanon
    private lateinit var h: PageHandle
    private lateinit var pathTv: TextView
    private lateinit var upBtn: TextView

    fun open() {
        h = a.pageDialog("Ruang kerja", "Unduh ZIP") { exportCurrentFolderZip() }
        pathTv = TextView(a).apply {
            textSize = Typo.SMALL
            setTextColor(Palette.INK_SOFT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        upBtn = TextView(a).apply {
            text = "‹ Naik"
            textSize = Typo.BODY_S
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.BRAND)
            setPadding(a.dp(12), a.dp(6), 0, a.dp(6))
            setOnClickListener { goUp() }
        }
        h.top.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(20), a.dp(2), a.dp(20), a.dp(4))
            addView(pathTv, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(upBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        render()
    }

    private fun isInsideRoot(f: File): Boolean {
        val c = try { f.canonicalFile } catch (_: Exception) { return false }
        return c == rootCanon || c.path.startsWith(rootCanon.path + File.separator)
    }

    private fun goUp() {
        val parent = curDir.parentFile ?: return
        if (curDir != rootCanon && isInsideRoot(parent)) {
            curDir = parent.canonicalFile
            render()
        }
    }

    private fun relLabel(dir: File): String {
        if (dir == rootCanon) return "Ruang kerja/"
        val rel = try { dir.relativeTo(rootCanon).invariantSeparatorsPath } catch (_: Exception) { dir.name }
        return "Ruang kerja/$rel/"
    }

    private fun render() {
        if (!h.showing) return
        pathTv.text = relLabel(curDir)
        upBtn.visibility = if (curDir != rootCanon) android.view.View.VISIBLE else android.view.View.GONE
        h.list.removeAllViews()
        if (!curDir.exists() || !curDir.isDirectory) {
            h.info.text = "Belum ada berkas"
            a.addEmptyNote(h, "Ruang kerja chat ini masih kosong.\nBerkas yang dibuat atau diperbaiki agen akan muncul di sini.")
            return
        }
        val items = (curDir.listFiles()?.filter { !Files.isSymbolicLink(it.toPath()) && isInsideRoot(it) } ?: emptyList())
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        val dirCount = items.count { it.isDirectory }
        val fileCount = items.size - dirCount
        h.info.text = "$dirCount folder · $fileCount berkas"
        if (items.isEmpty()) {
            a.addEmptyNote(h, "Folder ini kosong.")
            return
        }
        for (f in items) {
            if (f.isDirectory) {
                val subCount = f.listFiles()?.size ?: 0
                a.addRow(h, a.pageRow(
                    glyph = GlyphView.PROJECT,
                    title = f.name,
                    sub = "$subCount item · ${fmtDateTime(f.lastModified())}",
                    badge = "Folder",
                    onLong = { exportFolderZip(f) },
                ) {
                    curDir = f.canonicalFile
                    render()
                })
            } else {
                a.addRow(h, a.pageRow(
                    glyph = GlyphView.ARTIFACT,
                    title = f.name,
                    sub = "${fmtBytes(f.length())} · ${fmtDateTime(f.lastModified())}",
                    onLong = { exportSingleFile(f) },
                ) {
                    showFileOptions(f)
                })
            }
        }
    }

    private fun showFileOptions(f: File) {
        val opts = arrayOf("Lihat isi berkas", "Simpan ke Download")
        AlertDialog.Builder(a)
            .setTitle(f.name)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> previewTextFile(f)
                    1 -> exportSingleFile(f)
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun previewTextFile(f: File) {
        if (f.length() > PREVIEW_MAX_BYTES) {
            a.toast("Berkas terlalu besar untuk pratinjau (${fmtBytes(f.length())}). Pilih Simpan ke Download.")
            return
        }
        bg {
            val content = try {
                val bytes = f.readBytes()
                if (bytes.take(4096).any { it == 0.toByte() }) null
                else String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (content == null) {
                    a.toast("Berkas biner tidak dapat dipratinjau. Pilih Simpan ke Download.")
                    return@runOnUiThread
                }
                val tv = TextView(a).apply {
                    text = content
                    textSize = Typo.CAPTION
                    typeface = Typeface.MONOSPACE
                    setTextColor(Palette.INK)
                    setTextIsSelectable(true)
                    setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12))
                }
                val scroll = ScrollView(a).apply { addView(tv) }
                AlertDialog.Builder(a)
                    .setTitle(f.name)
                    .setView(scroll)
                    .setPositiveButton("Simpan ke Download") { _, _ -> exportSingleFile(f) }
                    .setNegativeButton("Tutup", null)
                    .show()
            }
        }
    }

    private fun exportSingleFile(f: File) {
        a.toast("Menyimpan ${f.name}…")
        bg {
            val res = try {
                a.exportToDownloads(f, f.name)
            } catch (e: Exception) {
                "Gagal: ${e.message ?: e.javaClass.simpleName}"
            }
            a.runOnUiThread {
                if (!a.isFinishing && !a.isDestroyed) a.toast(res)
            }
        }
    }

    private fun exportCurrentFolderZip() = exportFolderZip(curDir)

    private fun exportFolderZip(folder: File) {
        if (!folder.exists() || !folder.isDirectory) {
            a.toast("Ruang kerja masih kosong")
            return
        }
        val baseName = if (folder == rootCanon) "ruang_kerja_mythos" else folder.name
        val zipName = "${WorkspaceList.cacheName(baseName)}.zip"
        a.toast("Mengemas $zipName…")
        bg {
            val tmp = File(a.cacheDir, "ws_export_${System.currentTimeMillis()}.zip")
            val msg = try {
                val n = zipFolderSafely(folder, tmp)
                if (n == 0) "Folder kosong, tidak ada berkas untuk dikemas."
                else a.exportToDownloads(tmp, zipName)
            } catch (e: Exception) {
                "Gagal mengemas ZIP: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                tmp.delete()
            }
            a.runOnUiThread {
                if (!a.isFinishing && !a.isDestroyed) a.toast(msg)
            }
        }
    }
}

internal fun MainActivity.showAgentWorkspacePage() = AgentWorkspacePage(this).open()
