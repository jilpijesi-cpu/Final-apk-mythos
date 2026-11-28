package com.build.mythos.cc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/*
 * Titik pulih per tugas.
 *
 * Cara kerja: sebelum alat Write/Edit/Delete mengubah sebuah file untuk PERTAMA kalinya dalam satu tugas,
 * isi aslinya disalin ke folder jurnal (copy-on-write). Tidak ada salinan seluruh proyek, jadi murah.
 * Dari jurnal itu bisa dihitung daftar file yang berubah, diff-nya, dan seluruh perubahan bisa dibatalkan.
 * Jurnal ada di disk (bukan memori) sehingga tetap bisa dibatalkan setelah aplikasi dimatikan.
 */

enum class ChangeStatus { ADDED, MODIFIED, DELETED }

/** [restorable] false bila file asli terlalu besar untuk dicadangkan (perubahannya tak bisa dibatalkan). */
data class FileChange(
    val path: String,
    val status: ChangeStatus,
    val added: Int,
    val removed: Int,
    val binary: Boolean = false,
    val restorable: Boolean = true,
)

data class UndoResult(val restored: List<String>, val deleted: List<String>, val failed: List<String>) {
    val ok: Boolean get() = failed.isEmpty()
}

class TaskJournal(root: File, private val dir: File) {
    private class Entry(val rel: String, val existed: Boolean, val blob: String?, val tooBig: Boolean, var after: String? = null)

    private val root: File = root.canonicalFile
    private val entries = LinkedHashMap<String, Entry>()
    private val lock = Any()
    private var isSealed = false

    init { load() }

    /** Dipanggil SEBELUM file diubah/dihapus. Hanya panggilan pertama per path yang menyimpan; melempar IOException bila gagal mencadangkan. */
    fun record(f: File, rel: String) {
        synchronized(lock) {
            if (rel in entries) return
            val e = when {
                f.isFile && f.length() > MAX_BLOB -> Entry(rel, true, null, true)
                f.isFile -> {
                    dir.mkdirs()
                    val name = "b${entries.size}"
                    f.copyTo(File(dir, name), overwrite = true)
                    Entry(rel, true, name, false)
                }
                else -> Entry(rel, false, null, false)
            }
            entries[rel] = e
            save()
        }
    }

    val isEmpty: Boolean get() = synchronized(lock) { entries.isEmpty() }

    /** Catat keadaan akhir tugas (dasar deteksi "file diubah lagi di luar tugas ini"). */
    fun seal() {
        synchronized(lock) {
            for (e in entries.values) e.after = currentHash(File(root, e.rel))
            isSealed = true
            if (entries.isNotEmpty()) save()
        }
    }

    /** File yang kini berbeda dari keadaan akhir tugas (diubah pengguna/tugas lain sesudahnya). Kosong bila belum di-seal. */
    fun conflicts(): List<String> = synchronized(lock) {
        if (!isSealed) return emptyList()
        entries.values.filter { it.after != null && currentHash(File(root, it.rel)) != it.after }.map { it.rel }.sorted()
    }

    /** Daftar file yang benar-benar berbeda dari sebelum tugas (file yang diubah lalu dikembalikan persis tidak dihitung). */
    fun changes(): List<FileChange> = synchronized(lock) {
        entries.values.mapNotNull { compute(it) }.sortedBy { it.path }
    }

    /** Diff unified satu file (teks); null bila file tidak termasuk perubahan. */
    fun diff(rel: String, maxLines: Int = 600): String? = synchronized(lock) {
        val e = entries[rel] ?: return null
        val c = compute(e) ?: return null
        if (c.binary) return "(file biner: ${c.status.name.lowercase()}, isi tidak ditampilkan)"
        val cur = File(root, e.rel)
        if (cur.isFile && cur.length() > MAX_DIFF_BYTES) return "(file terlalu besar untuk ditampilkan)"
        val old = oldBytes(e)
        if (e.existed && old == null) return "(isi asli terlalu besar dicadangkan; tidak bisa ditampilkan)"
        val a = if (old == null) emptyList() else LineDiff.split(String(old, Charsets.UTF_8))
        val b = if (cur.isFile) LineDiff.split(cur.readText()) else emptyList()
        val head = when (c.status) {
            ChangeStatus.ADDED -> "--- /dev/null\n+++ b/${e.rel}\n"
            ChangeStatus.DELETED -> "--- a/${e.rel}\n+++ /dev/null\n"
            ChangeStatus.MODIFIED -> "--- a/${e.rel}\n+++ b/${e.rel}\n"
        }
        val body = LineDiff.unified(LineDiff.compute(a, b), 3, maxLines)
        head + body.ifEmpty { "(hanya akhiran baris yang berbeda)" }
    }

    /** Kembalikan semua file ke keadaan sebelum tugas. Bila semua berhasil, jurnal dihapus. */
    fun undo(): UndoResult = synchronized(lock) {
        val restored = ArrayList<String>()
        val deleted = ArrayList<String>()
        val failed = ArrayList<String>()
        for (e in entries.values) {
            try {
                val target = safe(e.rel) ?: throw IOException("path tidak aman")
                when {
                    e.existed && e.blob != null -> {
                        target.parentFile?.mkdirs()
                        File(dir, e.blob).copyTo(target, overwrite = true)
                        restored.add(e.rel)
                    }
                    e.existed -> failed.add(e.rel + " (terlalu besar untuk dicadangkan)")
                    else -> if (target.isFile) {
                        if (!target.delete()) throw IOException("gagal menghapus")
                        deleted.add(e.rel)
                        pruneEmptyParents(target.parentFile)
                    }
                }
            } catch (ex: Exception) {
                failed.add(e.rel)
            }
        }
        if (failed.isEmpty()) {
            dir.deleteRecursively()
            entries.clear()
            isSealed = false
        }
        UndoResult(restored.sorted(), deleted.sorted(), failed.sorted())
    }

    /** Hapus jurnal tanpa mengembalikan apa pun (mis. saat chat dihapus). */
    fun discard() = synchronized(lock) { entries.clear(); dir.deleteRecursively(); Unit }

    // ---------- internal ----------

    private fun safe(rel: String): File? {
        val f = File(root, rel).canonicalFile
        return if (f.path.startsWith(root.path + File.separator)) f else null
    }

    private fun pruneEmptyParents(start: File?) {
        var d = start
        while (d != null && d != root && d.path.startsWith(root.path + File.separator) && d.isDirectory && d.list()?.isEmpty() == true) {
            if (!d.delete()) break
            d = d.parentFile
        }
    }

    private fun oldBytes(e: Entry): ByteArray? = if (e.existed && e.blob != null) File(dir, e.blob).takeIf { it.isFile }?.readBytes() else null

    private fun compute(e: Entry): FileChange? {
        val cur = File(root, e.rel)
        val exists = cur.isFile
        if (!e.existed && !exists) return null                       // dibuat lalu dihapus lagi
        if (e.existed && e.tooBig) {
            return FileChange(e.rel, if (exists) ChangeStatus.MODIFIED else ChangeStatus.DELETED, 0, 0, binary = true, restorable = false)
        }
        val old = oldBytes(e)
        if (e.existed && old == null) return null                    // cadangan hilang: tak bisa dibandingkan
        val now = if (exists) cur.readBytes() else null
        if (old != null && now != null && old.contentEquals(now)) return null
        val status = when {
            old == null -> ChangeStatus.ADDED
            now == null -> ChangeStatus.DELETED
            else -> ChangeStatus.MODIFIED
        }
        if ((old != null && looksBinary(old)) || (now != null && looksBinary(now))) {
            return FileChange(e.rel, status, 0, 0, binary = true)
        }
        val a = if (old == null) emptyList() else LineDiff.split(String(old, Charsets.UTF_8))
        val b = if (now == null) emptyList() else LineDiff.split(String(now, Charsets.UTF_8))
        val (add, rem) = LineDiff.count(LineDiff.compute(a, b))
        return FileChange(e.rel, status, add, rem)
    }

    private fun currentHash(f: File): String {
        if (!f.isFile) return ABSENT
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        f.inputStream().use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun load() {
        val m = File(dir, "manifest.json")
        if (!m.isFile) return
        try {
            val o = JSONObject(m.readText())
            isSealed = o.optBoolean("sealed")
            val arr = o.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val rel = j.getString("rel")
                entries[rel] = Entry(
                    rel, j.optBoolean("existed"), if (j.isNull("blob")) null else j.optString("blob"),
                    j.optBoolean("tooBig"), if (j.isNull("after")) null else j.optString("after")
                )
            }
        } catch (e: Exception) {
            entries.clear()
        }
    }

    private fun save() {
        dir.mkdirs()
        val arr = JSONArray()
        for (e in entries.values) {
            arr.put(
                JSONObject().put("rel", e.rel).put("existed", e.existed).put("blob", e.blob ?: JSONObject.NULL)
                    .put("tooBig", e.tooBig).put("after", e.after ?: JSONObject.NULL)
            )
        }
        val text = JSONObject().put("sealed", isSealed).put("entries", arr).toString()
        val tmp = File(dir, "manifest.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, "manifest.json"))) {
            File(dir, "manifest.json").writeText(text)
            tmp.delete()
        }
    }

    companion object {
        const val MAX_BLOB = 5_000_000L
        const val MAX_DIFF_BYTES = 1_000_000L
        private const val ABSENT = "absent"

        private fun looksBinary(b: ByteArray): Boolean {
            val n = minOf(b.size, 512)
            for (i in 0 until n) if (b[i].toInt() == 0) return true
            return false
        }

        private fun sha256(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        /** Folder jurnal satu tugas: <base>/<chatId>/<taskId>. */
        fun dirFor(base: File, chatId: String, taskId: String): File = File(File(base, chatId), taskId)
    }
}

// ---------------------------------------------------------------- diff baris

enum class DiffOp { SAME, ADD, DEL }

data class DiffLine(val op: DiffOp, val text: String, val oldNo: Int, val newNo: Int)

object LineDiff {
    private const val LCS_CELL_LIMIT = 2_000_000L

    /** Pisah per baris tanpa baris kosong palsu di akhir; CRLF disamakan; teks kosong = tanpa baris. */
    fun split(text: String): List<String> =
        if (text.isEmpty()) emptyList() else contentLines(text.replace("\r\n", "\n"))

    fun compute(a: List<String>, b: List<String>): List<DiffLine> {
        var s = 0
        while (s < a.size && s < b.size && a[s] == b[s]) s++
        var ea = a.size
        var eb = b.size
        while (ea > s && eb > s && a[ea - 1] == b[eb - 1]) { ea--; eb-- }

        val out = ArrayList<DiffLine>()
        var oi = 0
        var ni = 0
        fun emit(op: DiffOp, text: String) {
            out.add(DiffLine(op, text, oi + 1, ni + 1))
            if (op != DiffOp.ADD) oi++
            if (op != DiffOp.DEL) ni++
        }
        for (i in 0 until s) emit(DiffOp.SAME, a[i])

        val n = ea - s
        val m = eb - s
        if (n > 0 || m > 0) {
            if (n.toLong() * m > LCS_CELL_LIMIT) {
                for (i in s until ea) emit(DiffOp.DEL, a[i])
                for (j in s until eb) emit(DiffOp.ADD, b[j])
            } else {
                val w = m + 1
                val l = IntArray((n + 1) * w)
                for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                    l[i * w + j] = if (a[s + i] == b[s + j]) l[(i + 1) * w + j + 1] + 1
                    else maxOf(l[(i + 1) * w + j], l[i * w + j + 1])
                }
                var i = 0
                var j = 0
                while (i < n && j < m) {
                    when {
                        a[s + i] == b[s + j] -> { emit(DiffOp.SAME, a[s + i]); i++; j++ }
                        l[(i + 1) * w + j] >= l[i * w + j + 1] -> { emit(DiffOp.DEL, a[s + i]); i++ }
                        else -> { emit(DiffOp.ADD, b[s + j]); j++ }
                    }
                }
                while (i < n) { emit(DiffOp.DEL, a[s + i]); i++ }
                while (j < m) { emit(DiffOp.ADD, b[s + j]); j++ }
            }
        }
        for (k in ea until a.size) emit(DiffOp.SAME, a[k])
        return out
    }

    /** (ditambah, dihapus). */
    fun count(lines: List<DiffLine>): Pair<Int, Int> =
        lines.count { it.op == DiffOp.ADD } to lines.count { it.op == DiffOp.DEL }

    /** Format unified dengan [context] baris konteks; kosong bila tak ada perubahan. */
    fun unified(lines: List<DiffLine>, context: Int = 3, maxLines: Int = 600): String {
        val changed = lines.indices.filter { lines[it].op != DiffOp.SAME }
        if (changed.isEmpty()) return ""
        val sb = StringBuilder()
        var emitted = 0
        var k = 0
        while (k < changed.size) {
            val start = maxOf(0, changed[k] - context)
            var end = minOf(lines.size - 1, changed[k] + context)
            k++
            while (k < changed.size && changed[k] - context <= end + 1) {
                end = minOf(lines.size - 1, changed[k] + context)
                k++
            }
            val range = lines.subList(start, end + 1)
            val oldCount = range.count { it.op != DiffOp.ADD }
            val newCount = range.count { it.op != DiffOp.DEL }
            val oldStart = range.firstOrNull { it.op != DiffOp.ADD }?.oldNo ?: (range.first().oldNo - 1)
            val newStart = range.firstOrNull { it.op != DiffOp.DEL }?.newNo ?: (range.first().newNo - 1)
            sb.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
            for (ln in range) {
                if (emitted >= maxLines) {
                    sb.append("… (diff dipotong)\n")
                    return sb.toString().trimEnd('\n')
                }
                sb.append(
                    when (ln.op) { DiffOp.SAME -> ' '; DiffOp.ADD -> '+'; DiffOp.DEL -> '-' }
                ).append(ln.text.take(400)).append('\n')
                emitted++
            }
        }
        return sb.toString().trimEnd('\n')
    }
}
