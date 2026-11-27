package com.build.mythos

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.build.mythos.cc.PdfText
import java.io.File
import java.util.UUID

// Lampirkan file dari penyimpanan HP ke folder attachments workspace.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.pickFile() {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }
    try {
        startActivityForResult(i, 78)
    } catch (e: Exception) {
        toast("Pemilih file tidak tersedia")
    }
}

/** Pilih gambar langsung lewat Photo Picker / Galeri HP, dengan cadangan ke pemilih dokumen bila tidak tersedia. */
internal fun MainActivity.pickImage() {
    val candidates = ArrayList<Intent>()
    if (Build.VERSION.SDK_INT >= 33) {
        candidates.add(Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" })
    }
    candidates.add(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" })
    candidates.add(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
    })
    for (intent in candidates) {
        try {
            startActivityForResult(intent, 79)
            return
        } catch (_: Exception) {
            // Coba pemilih berikutnya bila perangkat tidak mendukung intent ini.
        }
    }
    toast("Pemilih gambar tidak tersedia")
}

internal fun MainActivity.displayName(uri: Uri): String {
    var n = "file"
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val v = c.getString(0)
            if (!v.isNullOrBlank()) n = v
        }
    }
    return sanitizeFileName(n)
}

private const val MAX_IMPORT_BYTES = 100L * 1024L * 1024L

private fun sanitizeFileName(raw: String): String =
    raw.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trim('.').ifBlank { "file" }

private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long = MAX_IMPORT_BYTES) {
    val buf = ByteArray(16384)
    var total = 0L
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Penyalinan dibatalkan")
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) {
            throw RuntimeException("Ukuran file melebihi batas ${maxBytes / (1024 * 1024)} MB")
        }
        output.write(buf, 0, n)
    }
}

internal class StagedAttachment(val path: String, val cleanup: () -> Unit)

/** F-21: bersihkan sisa salinan staging lampiran yang tertinggal di Download/MYTHOS/attachments (mis. akibat crash/force-stop). */
internal fun MainActivity.cleanupStaleDownloadAttachments(maxAgeMs: Long = 5 * 60_000L) {
    val cutoffSec = (System.currentTimeMillis() - maxAgeMs) / 1000L
    if (Build.VERSION.SDK_INT >= 29) {
        try {
            val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND (${MediaStore.MediaColumns.DATE_MODIFIED} < ? OR ${MediaStore.MediaColumns.IS_PENDING} = 1)"
            val args = arrayOf("Download/MYTHOS/attachments%", cutoffSec.toString())
            contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel, args)
        } catch (e: Exception) {
            logQuiet("FileImport.kt", e)
        }
    } else {
        try {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "MYTHOS/attachments")
            val cutoffMs = System.currentTimeMillis() - maxAgeMs
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoffMs) f.delete()
            }
        } catch (e: Exception) {
            logQuiet("FileImport.kt", e)
        }
    }
}

/** Tahap pertama: salin file pilihan ke Download/MYTHOS/attachments agar dapat dibaca Termux. */
internal fun MainActivity.copyToDownload(uri: Uri, name: String): StagedAttachment {
    val safeName = sanitizeFileName(name)
    val src = contentResolver.openInputStream(uri) ?: throw RuntimeException("File tidak bisa dibaca")
    return src.use { ins ->
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MYTHOS/attachments")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw RuntimeException("Gagal membuat file di Download")
            try {
                val out = contentResolver.openOutputStream(dest) ?: throw RuntimeException("Gagal menulis file")
                out.use { o -> copyLimited(ins, o) }
                // Selesai menulis: lepaskan IS_PENDING agar Termux dapat membaca file tepat sebelum disalin, lalu segera dihapus di finally.
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(dest, ready, null, null)
            } catch (t: Throwable) {
                try { contentResolver.delete(dest, null, null) } catch (ignored: Exception) { logQuiet("FileImport.kt", ignored) }
                throw t
            }
            var finalName = safeName
            contentResolver.query(dest, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) finalName = sanitizeFileName(c.getString(0).orEmpty())
            }
            StagedAttachment("${WorkspacePaths.SDCARD_ATTACHMENTS}/$finalName") {
                try { contentResolver.delete(dest, null, null) } catch (ignored: Exception) { logQuiet("FileImport.kt", ignored) }
            }
        } else {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "MYTHOS/attachments")
            dir.mkdirs()
            val f = File(dir, safeName)
            require(f.canonicalFile.parentFile == dir.canonicalFile) { "Nama file tidak valid" }
            try {
                f.outputStream().use { o -> copyLimited(ins, o) }
            } catch (t: Throwable) {
                try { f.delete() } catch (ignored: Exception) { logQuiet("FileImport.kt", ignored) }
                throw t
            }
            StagedAttachment("${WorkspacePaths.SDCARD_ATTACHMENTS}/$safeName") { try { f.delete() } catch (ignored: Exception) { logQuiet("FileImport.kt", ignored) } }
        }
    }
}

/** Tahap kedua: salin dari penyimpanan bersama ke folder attachments workspace Termux. */
internal fun MainActivity.copyToMythosAttachments(uri: Uri, name: String): String {
    val staged = copyToDownload(uri, name)
    val finalName = staged.path.substringAfterLast('/')
    val attachmentsRoot = "${workspaceRoot()}/attachments"
    val target = "$attachmentsRoot/$finalName"
    try {
        referencePathGuard(target)?.let { throw RuntimeException(it) }
        val r = termux(
            "mkdir -p ${sh(attachmentsRoot)} && " +
                "cp -f ${sh(staged.path)} ${sh(target)} && echo MYTHOS_ATTACHMENT=ok",
            limit = 2000, timeoutSec = 120
        )
        if (!r.startsWith("exit=0") || !r.contains("MYTHOS_ATTACHMENT=ok")) {
            val why = r.lineSequence().drop(1).firstOrNull { it.isNotBlank() }?.trim()?.take(100).orEmpty()
            val hint = if (r.contains("Permission denied", ignoreCase = true) || r.contains("No such file", ignoreCase = true))
                " Jalankan termux-setup-storage di Termux dan izinkan akses penyimpanan." else ""
            throw RuntimeException("Gagal menyalin lampiran ke Termux" + (if (why.isNotEmpty()) " ($why)." else ".") + hint)
        }
        return target
    } finally {
        // File perantara tidak lagi diperlukan setelah percobaan penyalinan selesai.
        staged.cleanup()
    }
}

/**
 * Salin lampiran ke <folder proyek agen API>/uploads (penyimpanan aplikasi). Tidak memakai Termux maupun penyimpanan bersama.
 * Nama "uploads" sengaja: prompt agen menyebutnya, dan GithubBuild tidak mengirim file langsung di uploads/ ke GitHub (privasi lampiran).
 */
internal fun MainActivity.copyToAppAttachments(uri: Uri, name: String): File {
    val dir = File(ccProjectDir(), "uploads").also { it.mkdirs() }
    cleanStaleTemps(dir) // sisa salinan parsial dari proses yang mati di tengah jalan
    val safeName = sanitizeFileName(name)
    // F-06: reservasi target secara atomik (dimiliki eksklusif oleh job ini) agar impor paralel tidak bertabrakan.
    val out = reserveUniqueAttachmentFile(dir, safeName)
    require(out.canonicalFile.parentFile == dir.canonicalFile) { "Nama file tidak valid" }
    val tmp = File.createTempFile("upload-", ".tmp", dir)
    try {
        val ins = contentResolver.openInputStream(uri) ?: throw RuntimeException("File tidak bisa dibuka")
        ins.use { i -> tmp.outputStream().use { o -> copyLimited(i, o); o.flush() } }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Impor dibatalkan")
        try {
            java.nio.file.Files.move(
                tmp.toPath(), out.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Throwable) {
        tmp.delete()
        out.delete() // aman: file ini direservasi eksklusif oleh job ini lewat reserveUniqueAttachmentFile
        throw e
    }
    return out
}

/** Serahkan satu file proyek agen API ke pengguna: salin ke Download/MYTHOS/export. Mengembalikan lokasinya untuk ditampilkan. */
internal fun MainActivity.exportToDownloads(src: File, name: String): String {
    val clean = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { src.name }
    if (Build.VERSION.SDK_INT >= 29) {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, clean)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MYTHOS/export")
        }
        val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw RuntimeException("Gagal membuat file di Download")
        val out = contentResolver.openOutputStream(dest) ?: throw RuntimeException("Gagal menulis file")
        out.use { o -> src.inputStream().use { it.copyTo(o) } }
        var finalName = clean
        contentResolver.query(dest, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) finalName = c.getString(0)
        }
        return "Download/MYTHOS/export/$finalName"
    }
    val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "MYTHOS/export")
    dir.mkdirs()
    val out = File(dir, uniqueAttachmentName(dir, clean))
    src.copyTo(out, overwrite = false)
    return "Download/MYTHOS/export/${out.name}"
}

/** Path lampiran seperti yang ditulis ke pesan: relatif terhadap folder proyek untuk agen API, path Termux asli untuk agen CLI. */
internal fun MainActivity.attachmentRef(path: String?): String {
    val p = path ?: return ""
    return if (usesTermuxWorld(preset(prov).fmt)) p else relativeToProject(p, ccProjectDir().path)
}

internal fun MainActivity.makeImagePreview(uri: Uri, name: String): String? {
    if (!isImageName(name)) return null
    return try {
        val dir = File(filesDir, "chat_images").apply { mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + ".jpg")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        try {
            out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
        } finally {
            bitmap.recycle()
        }
        out.absolutePath
    } catch (ignored: Exception) { null }
}

internal fun MainActivity.importFile(uri: Uri) {
    if (Build.VERSION.SDK_INT < 29 &&
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    ) {
        pendingImport = uri
        requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        return
    }
    val name = displayName(uri)
    val att = Attachment(name, previewUri = if (isImageName(name)) uri else null)
    attachments.add(att)
    refreshAttachments()   // kartu langsung tampil (redup selama disalin)
    focusInput()           // keyboard terbuka: ketik perintah untuk file ini
    if (VideoFrames.isVideoName(name)) attachVideoFrames(uri, name)   // video juga dilampirkan sebagai beberapa gambar (frame)
    var createdAppFile: File? = null
    var createdPreview: String? = null
    val importJob = bgCancellable(
        onError = { e ->
            // F-07: bersihkan berkas yatim bila impor dibatalkan atau gagal.
            try { createdAppFile?.delete() } catch (_: Exception) {}
            try { createdPreview?.let { File(it).delete() } } catch (_: Exception) {}
            val isCancel = e is kotlinx.coroutines.CancellationException || e is InterruptedException
            runOnUiThread {
                attachments.remove(att)
                if (!isFinishing && !isDestroyed) {
                    refreshAttachments()
                    if (!isCancel) toast("Gagal melampirkan: " + (e.message ?: "").take(180))
                }
            }
        },
        onFinally = {}
    ) {
        if (!usesTermuxWorld(preset(prov).fmt)) {
            // Agen API: salin ke folder proyek di penyimpanan aplikasi (dunia dalam-aplikasi), tanpa Termux.
            val file = copyToAppAttachments(uri, name)
            createdAppFile = file
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Impor dibatalkan")
            val preview = makeImagePreview(uri, name)
            createdPreview = preview
            try { analyzeAttachmentInApp(att, uri, file) } catch (e: Exception) { logQuiet("FileImport.kt", e) }
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Impor dibatalkan")
            att.previewPath = preview
            att.path = file.absolutePath
            createdAppFile = null
            createdPreview = null
            runOnUiThread { if (!isFinishing && !isDestroyed) refreshAttachments() }
            return@bgCancellable
        }
        val copied = copyToMythosAttachments(uri, name)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Impor dibatalkan")
        val preview = makeImagePreview(uri, name)
        createdPreview = preview
        // Isi file dibaca SEKARANG (bukan nanti oleh agen) supaya ikut tersimpan di riwayat percakapan.
        // path diisi paling akhir: kartu baru "siap" setelah analisis selesai.
        try { analyzeAttachment(att, uri, copied) } catch (e: Exception) { logQuiet("FileImport.kt", e) }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Impor dibatalkan")
        att.previewPath = preview
        att.path = copied
        createdPreview = null
        runOnUiThread { if (!isFinishing && !isDestroyed) refreshAttachments() }
    }
    importJobs.add(importJob)
    importJob.invokeOnCompletion { importJobs.remove(importJob) }
}

// ---------------------------------------------------------------- Baca isi lampiran

/** Batas karakter isi file teks yang disimpan per lampiran; jatah akhir per pesan ditentukan saat kirim (fitDigests). */
private const val ATT_TEXT_READ_MAX = 24_000
private const val ATT_DOCX_MAX_BYTES = 30L * 1024L * 1024L
private const val ATT_PDF_MAX_BYTES = PdfText.MAX_BYTES
private const val ATT_PDF_MAX_PAGES = 60

private fun readBytesLimited(input: java.io.InputStream, max: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16384)
    var total = 0L
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}

/** Badan keluaran termux() bila exit=0, selain itu null. */
private fun termuxBody(r: String): String? = if (r.startsWith("exit=0")) r.substringAfter('\n', "") else null

/**
 * Isi att.digest, att.summary, dan att.extractedDir dari isi file: teks dibaca langsung, docx diekstrak teksnya,
 * pdf dibaca langsung di aplikasi (PdfText; pdftotext di Termux hanya cadangan), zip dipindai (manifest) lalu diekstrak ke projects/aktif pada workspace. Dipanggil di thread latar.
 */
internal fun MainActivity.analyzeAttachment(att: Attachment, uri: Uri, termuxPath: String) {
    val name = att.name
    when {
        isImageName(name) -> att.summary = "gambar; dikirim ke model sebagai gambar di beberapa pesan terakhir"
        isZipName(name) -> analyzeZip(att, uri, termuxPath)
        isDocxName(name) -> {
            val bytes = contentResolver.openInputStream(uri)?.use { readBytesLimited(it, ATT_DOCX_MAX_BYTES) }
            val text = bytes?.let { docxToText(it, ATT_TEXT_READ_MAX) }
            if (text.isNullOrBlank()) {
                att.summary = "dokumen Word; teks tidak bisa diekstrak otomatis, buka dari path dengan alat"
            } else {
                att.digest = text
                att.summary = "dokumen Word, ${text.length} karakter teks sudah dikirim di pesan"
            }
        }
        isPdfName(name) -> analyzePdf(att, uri, termuxPath)
        else -> {
            val res = contentResolver.openInputStream(uri)?.use { readTextLimited(it, ATT_TEXT_READ_MAX) }
            if (res == null) {
                att.summary = "file biner; buka dari path dengan alat bila perlu"
            } else {
                att.digest = res.first
                att.summary = if (res.second) "teks panjang; ${res.first.length} karakter awal sudah dikirim, sisanya di path"
                else "teks ${res.first.length} karakter, sudah dikirim utuh di pesan"
            }
        }
    }
}

/**
 * Teks PDF dibaca langsung di aplikasi lewat [PdfText] (Java murni), tanpa Termux. Bila gagal dan Termux ada (termuxPath tidak kosong),
 * pdftotext dicoba sebagai cadangan. Bila tetap gagal, att.summary memuat alasan yang jelas (terkunci, scan, font tak terpetakan, rusak)
 * supaya agen meminta screenshot/teks dari pengguna alih-alih menebak isinya.
 */
internal fun MainActivity.analyzePdf(att: Attachment, uri: Uri, termuxPath: String) {
    val bytes = try {
        contentResolver.openInputStream(uri)?.use { readBytesLimited(it, ATT_PDF_MAX_BYTES) }
    } catch (e: Exception) {
        logQuiet("FileImport.kt", e)
        null
    }
    var reason = "PDF lebih dari 30 MB atau tidak bisa dibuka; teksnya tidak terbaca"
    if (bytes != null) {
        val r = PdfText.extract(bytes, ATT_TEXT_READ_MAX, ATT_PDF_MAX_PAGES)
        if (r.status == PdfText.Status.OK) {
            att.digest = r.text
            att.summary = pdfSummary(r)
            return
        }
        reason = pdfSummary(r)
    }
    if (termuxPath.isNotEmpty()) {
        val r = termux(
            "command -v pdftotext >/dev/null 2>&1 || { echo MYTHOS_NO_PDFTOTEXT; exit 0; }; " +
                "pdftotext -layout -l 40 ${sh(termuxPath)} - 2>/dev/null | head -c $ATT_TEXT_READ_MAX",
            limit = ATT_TEXT_READ_MAX + 500, timeoutSec = 90
        )
        val body = termuxBody(r)
        if (body != null && !body.contains("MYTHOS_NO_PDFTOTEXT") && body.isNotBlank()) {
            att.digest = body.trim()
            att.summary = "PDF, teks halaman awal sudah dikirim di pesan (dibaca lewat pdftotext di Termux)"
            return
        }
    }
    att.summary = reason
}

/** Versi tanpa Termux untuk agen API: gambar, zip (dipindai lalu diekstrak ke folder di samping zip), PDF (teks dibaca di aplikasi), selebihnya dibaca langsung dari URI. */
internal fun MainActivity.analyzeAttachmentInApp(att: Attachment, uri: Uri, file: File) {
    val name = att.name
    when {
        isImageName(name) -> att.summary = "gambar; dikirim ke model sebagai gambar di beberapa pesan terakhir"
        isZipName(name) -> analyzeZipInApp(att, uri, file)
        isPdfName(name) -> analyzePdf(att, uri, "")
        else -> analyzeAttachment(att, uri, "") // teks dan docx dibaca langsung dari URI; cabang yang butuh Termux sudah ditangani di atas
    }
}

private fun MainActivity.analyzeZipInApp(att: Attachment, uri: Uri, zipFile: File) {
    val scan = contentResolver.openInputStream(uri)?.use { scanZip(it) }
    if (scan == null) {
        att.summary = "zip tidak bisa dibuka"
        return
    }
    val projectPath = ccProjectDir().path
    var rel: String? = null
    var note = ""
    if (scan.safeToExtract && scan.files.isNotEmpty()) {
        val target = File(zipFile.parentFile, safeFolderName(zipFile.name))
        val res = try { contentResolver.openInputStream(uri)?.use { extractZipSafely(it, target) } } catch (e: Exception) { null }
        if (res != null && res.error == null) {
            rel = relativeToProject(target.path, projectPath)
        } else {
            try { target.deleteRecursively() } catch (e: Exception) { logQuiet("FileImport.kt", e) }
            note = "Ekstraksi otomatis gagal" + (res?.error?.let { " ($it)" } ?: "") + ". Isi zip hanya tersedia lewat manifest ini."
        }
    } else if (scan.error == null) {
        note = "Tidak diekstrak otomatis (melewati batas ukuran atau berisi path berbahaya)."
    }
    att.extractedDir = rel
    att.summary = zipSummary(scan, rel)
    att.digest = renderZipManifest(att.name, scan, rel, note)
}

private fun MainActivity.analyzeZip(att: Attachment, uri: Uri, termuxPath: String) {
    val scan = contentResolver.openInputStream(uri)?.use { scanZip(it) }
    if (scan == null) {
        att.summary = "zip tidak bisa dibuka"
        return
    }
    var dir: String? = null
    var note = ""
    if (scan.safeToExtract && scan.files.isNotEmpty()) {
        try {
            val extractionTarget = "${workspaceRoot()}/projects/aktif/${safeFolderName(att.name)}"
            val blocked = referencePathGuard(extractionTarget)
            if (blocked != null) {
                note = "Ekstraksi diblokir karena lokasi tujuan tidak aman: $blocked"
            } else {
                val r = termux(
                    buildExtractCommand(
                        termuxPath,
                        "${workspaceRoot()}/projects/aktif",
                        safeFolderName(att.name),
                        protectedRoot = referenceRoot()
                    ) { sh(it) },
                    limit = 2000, timeoutSec = 300
                )
                dir = parseExtractDir(r)
                if (dir == null) note = "Ekstraksi otomatis gagal. Ekstrak sendiri dengan unzip dari path zip."
            }
        } catch (e: Exception) {
            note = "Ekstraksi otomatis gagal. Ekstrak sendiri dengan unzip dari path zip."
        }
    } else if (scan.error == null) {
        note = "Tidak diekstrak otomatis (melewati batas ukuran atau berisi path berbahaya). Periksa dulu lewat unzip -l dari path zip."
    }
    att.extractedDir = dir
    att.summary = zipSummary(scan, dir)
    att.digest = renderZipManifest(att.name, scan, dir, note)
}

/**
 * Simpan teks panjang yang ditempel di kolom ketik menjadi file lampiran otomatis
 * di folder `uploads/` proyek agar tidak terpotong oleh batas konteks/output AI.
 */
internal fun MainActivity.attachLongTextAsFile(rawText: String): Boolean {
    if (incognito) return false
    if (usesTermuxWorld(preset(prov).fmt)) return false
    val baseName = LongTextAttachment.buildFileName(rawText)
    val att = Attachment(baseName)
    attachments.add(att)
    refreshAttachments()
    val importJob = bgCancellable(
        onError = { e ->
            val isCancel = e is kotlinx.coroutines.CancellationException || e is InterruptedException
            runOnUiThread {
                attachments.remove(att)
                if (!isFinishing && !isDestroyed) {
                    refreshAttachments()
                    if (!isCancel) toast("Gagal menyimpan teks panjang ke file: " + (e.message ?: "").take(120))
                }
            }
        },
        onFinally = {}
    ) {
        val dir = File(ccProjectDir(), "uploads").also { it.mkdirs() }
        cleanStaleTemps(dir)
        val out = reserveUniqueAttachmentFile(dir, baseName)
        val tmp = File.createTempFile("paste-", ".tmp", dir)
        try {
            tmp.writeText(rawText, Charsets.UTF_8)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Impor dibatalkan")
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), out.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            tmp.delete()
            out.delete()
            throw e
        }
        att.kind = "teks"
        att.digest = LongTextAttachment.buildDigest(rawText)
        att.summary = LongTextAttachment.buildSummary(rawText)
        att.path = out.absolutePath
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                refreshAttachments()
                toast("Teks panjang otomatis dijadikan file (${out.name})")
            }
        }
    }
    importJobs.add(importJob)
    importJob.invokeOnCompletion { importJobs.remove(importJob) }
    return true
}
