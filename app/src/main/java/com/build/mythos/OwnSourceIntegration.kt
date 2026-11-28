package com.build.mythos

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import java.io.File

// Sisi Android dari "APK membawa source sendiri". Logika murni: SourceSync.kt (teruji). Asset mythos-source.zip dibuat Gradle (packSource).
// Jalur pemindahan sama dengan lampiran: titip di Download/MYTHOS/attachments lalu Termux menyalin/mengekstrak; titipan dihapus sesudahnya.

private fun MainActivity.stageOwnSource(): StagedAttachment {
    val name = SourceSync.SOURCE_ASSET
    val ins = assets.open(name)
    return ins.use { src ->
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MYTHOS/attachments")
            }
            val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw RuntimeException("Gagal membuat file di Download")
            try {
                val out = contentResolver.openOutputStream(dest) ?: throw RuntimeException("Gagal menulis file")
                out.use { o -> src.copyTo(o) }
                var finalName = name
                contentResolver.query(dest, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) finalName = c.getString(0)
                }
                StagedAttachment("${WorkspacePaths.SDCARD_ATTACHMENTS}/$finalName") {
                    try { contentResolver.delete(dest, null, null) } catch (ignored: Exception) { logQuiet("OwnSourceIntegration.kt", ignored) }
                }
            } catch (t: Throwable) {
                try { contentResolver.delete(dest, null, null) } catch (ignored: Exception) { logQuiet("OwnSourceIntegration.kt", ignored) }
                throw t
            }
        } else {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "MYTHOS/attachments")
            dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { o -> src.copyTo(o) }
            StagedAttachment("${WorkspacePaths.SDCARD_ATTACHMENTS}/$name") {
                try { f.delete() } catch (ignored: Exception) { logQuiet("OwnSourceIntegration.kt", ignored) }
            }
        }
    }
}

/**
 * Ekstrak source bawaan APK ke ~/MYTHOS/projects/aktif/mythos-<versionName> bila versionCode berubah sejak ekstrak terakhir yang berhasil.
 * Dipanggil di awal tugas (thread latar). Gagal diam-diam (null, penanda tidak dimajukan) lalu dicoba lagi di tugas berikutnya.
 * Tidak menyentuh reference/. Hasil: catatan untuk agen bila berhasil.
 */
internal fun MainActivity.syncOwnSourceIfNeeded(): String? {
    val inst = installedVersion() ?: return null
    val last = prefs.getLong(SourceSync.PREF_IMPORTED, 0L)
    if (!SourceSync.needsImport(inst, last)) return null
    if (readOnlyMode()) return null
    val folder = SourceSync.folderName(inst) ?: return null
    val dest = "$termuxHome/MYTHOS/projects/aktif/$folder"
    referencePathGuard(dest)?.let { return null }
    val staged = try {
        stageOwnSource()
    } catch (e: Exception) {
        logQuiet("OwnSourceIntegration.kt", e)
        return null
    }
    try {
        val out = termux(SourceSync.importCommand(staged.path, dest), limit = 2000, timeoutSec = 180)
        if (!SourceSync.importSucceeded(out)) return null
        prefs.edit().putLong(SourceSync.PREF_IMPORTED, inst.code).apply()
        return SourceSync.importNote(inst, "~/MYTHOS/projects/aktif/$folder")
    } finally {
        staged.cleanup()
    }
}
