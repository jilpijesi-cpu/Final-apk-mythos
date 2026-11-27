package com.build.mythos

import android.app.AlertDialog
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

// Halaman "Proyek": folder kerja agen di projects/aktif dan projects/arsip pada workspace terpilih.

/** Folder induk proyek: workspace dari Pengaturan; default awalnya ~/MYTHOS. */
internal fun MainActivity.workRoot(): String = workspaceRoot().trimEnd('/')

internal fun MainActivity.homeShort(p: String): String = if (p.startsWith("$termuxHome/")) "~/" + p.removePrefix("$termuxHome/") else p

/** True bila hasil termux() menandakan Termux tidak menjawab (bukan sekadar perintah yang gagal). */
internal fun termuxNoAnswer(r: String?): Boolean =
    r == null || r.startsWith("ERR:") || r.startsWith("Tidak ada hasil") || !r.startsWith("exit=")

private class ProjectsPage(val a: MainActivity) {
    val root = a.workRoot()
    var projects: List<ProjectEntry>? = null
    var noAnswer = false
    var archive = false
    lateinit var h: PageHandle
    lateinit var tabs: LinearLayout

    fun open() {
        h = a.pageDialog("Proyek", "+ Baru") { page ->
            page.dismiss()
            a.input.setText("Buat proyek Android baru bernama ")
            a.input.setSelection(a.input.text.length)
            a.input.requestFocus()
        }
        h.top.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(20), a.dp(2), a.dp(20), a.dp(2))
            addView(TextView(context).apply {
                text = "Folder kerja: " + a.homeShort(root)
                textSize = Typo.SMALL
                setTextColor(Palette.INK_SOFT)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(TextView(context).apply {
                text = "Ubah"
                textSize = Typo.BODY_S
                setTextColor(Palette.INK)
                setPadding(a.dp(12), a.dp(8), 0, a.dp(8))
                setOnClickListener { h.dismiss(); a.showSettings() }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        tabs = LinearLayout(a)
        h.top.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
        render()
        load()
    }

    fun load() {
        h.info.text = "Memuat…"
        bg {
            val r = try {
                a.termux(
                    "find ${a.sh("$root/projects/aktif")} ${a.sh("$root/projects/arsip")} -mindepth 1 -maxdepth 1 -type d " +
                        "-printf '%T@\\t%p\\n' 2>/dev/null; true",
                    limit = 60000, timeoutSec = 30, useWorkdir = false
                )
            } catch (e: Exception) { null }
            a.runOnUiThread {
                if (!h.showing) return@runOnUiThread
                noAnswer = termuxNoAnswer(r)
                projects = if (noAnswer) null else WorkspaceList.parseProjects(r.orEmpty(), root)
                render()
            }
        }
    }

    fun render() {
        val all = projects
        tabs.removeAllViews()
        tabs.addView(a.pillRow(
            a.pill("Aktif (${all?.count { !it.archived } ?: 0})", !archive) { archive = false; render() },
            a.pill("Arsip (${all?.count { it.archived } ?: 0})", archive) { archive = true; render() }
        ))
        h.list.removeAllViews()
        if (noAnswer) {
            h.info.text = ""
            a.addEmptyNote(h, "Tidak bisa membaca folder dari Termux. Pastikan Termux terpasang dan izin RUN_COMMAND sudah diberikan.")
            return
        }
        if (all == null) return
        val shown = all.filter { it.archived == archive }
        h.info.text = if (shown.isEmpty()) "" else "Ketuk proyek untuk melihat pilihan."
        if (shown.isEmpty()) {
            a.addEmptyNote(h, if (archive) "Belum ada proyek di arsip." else "Belum ada proyek aktif.\nKetuk \"+ Baru\" lalu minta agen membuatnya.")
            return
        }
        val inUse = a.activeStudioProjectFolder.trimEnd('/').substringAfterLast('/')
        for (p in shown) a.addRow(h, a.pageRow(
            GlyphView.PROJECT, p.name, "Diubah " + fmtDateTime(p.modified * 1000),
            badge = if (!p.archived && inUse == p.name) "Dipakai agen" else null
        ) { actions(p) })
    }

    fun actions(p: ProjectEntry) {
        val items = if (p.archived) arrayOf("Pulihkan ke aktif", "Hapus") else arrayOf("Lanjutkan di chat", "Arsipkan", "Hapus")
        AlertDialog.Builder(a)
            .setTitle(p.name.take(40))
            .setItems(items) { _, which ->
                when (items[which]) {
                    "Lanjutkan di chat" -> {
                        h.dismiss()
                        a.input.setText("Lanjutkan proyek projects/aktif/${p.name}: ")
                        a.input.setSelection(a.input.text.length)
                        a.input.requestFocus()
                    }
                    "Arsipkan", "Pulihkan ke aktif" -> move(p)
                    "Hapus" -> confirmDelete(p)
                    else -> {}
                }
            }
            .show()
    }

    fun move(p: ProjectEntry) {
        if (a.busy) { a.toast("Tunggu agen selesai"); return }
        val src = WorkspaceList.projectPath(root, p.name, p.archived) ?: return
        val dst = WorkspaceList.projectPath(root, p.name, !p.archived) ?: return
        bg {
            val r = try {
                val blocked = a.referencePathsGuard(listOf(src, dst))
                if (blocked != null) "exit=1 $blocked" else a.termux(
                    "if [ -e ${a.sh(dst)} ]; then echo EXISTS; else mkdir -p ${a.sh(dst.substringBeforeLast('/'))} && mv -- ${a.sh(src)} ${a.sh(dst)} && echo MOVED; fi",
                    useWorkdir = false, timeoutSec = 60
                )
            } catch (e: Exception) { "gagal: ${e.message}" }
            a.runOnUiThread {
                when {
                    r.contains("MOVED") -> { a.toast(if (p.archived) "Dipulihkan" else "Diarsipkan"); if (h.showing) load() }
                    r.contains("EXISTS") -> a.toast("Sudah ada proyek bernama sama di tujuan")
                    else -> a.toast("Gagal memindahkan: " + r.take(80))
                }
            }
        }
    }

    fun confirmDelete(p: ProjectEntry) {
        AlertDialog.Builder(a)
            .setMessage("Hapus proyek \"${p.name}\" beserta semua isinya dari Termux? Ini tidak bisa dibatalkan.")
            .setPositiveButton("Hapus") { _, _ -> delete(p) }
            .setNegativeButton("Batal", null)
            .show()
    }

    fun delete(p: ProjectEntry) {
        if (a.busy) { a.toast("Tunggu agen selesai"); return }
        val path = WorkspaceList.projectPath(root, p.name, p.archived) ?: return
        if (a.isProtectedReferencePath(path)) { a.toast("Folder ini terkunci"); return }
        bg {
            val r = try {
                val blocked = a.referencePathGuard(path)
                if (blocked != null) "exit=1 $blocked" else a.termux("rm -rf -- ${a.sh(path)} && echo DELETED", useWorkdir = false, timeoutSec = 120)
            } catch (e: Exception) { "gagal: ${e.message}" }
            val ok = r.contains("DELETED")
            if (ok) a.forgetArtifacts(a.artifactLogLoad().filter { it.path.startsWith("$path/") }.map { it.path })
            a.runOnUiThread {
                if (ok) { a.toast("Proyek dihapus"); if (h.showing) load() } else a.toast("Gagal menghapus: " + r.take(80))
            }
        }
    }
}

internal fun MainActivity.showProjectsPage() = ProjectsPage(this).open()
