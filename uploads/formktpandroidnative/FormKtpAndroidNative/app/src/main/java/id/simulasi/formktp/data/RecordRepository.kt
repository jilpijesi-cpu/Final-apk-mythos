package id.simulasi.formktp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import id.simulasi.formktp.model.KtpRecord
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * Penyimpanan lokal privat aplikasi (Context.filesDir) untuk daftar KtpRecord dan aset foto/tanda tangan.
 * Menggantikan localStorage (batas ~5 MB) agar gambar tersimpan terpisah dan aman (allowBackup=false).
 */
class RecordRepository(private val context: Context) {

    private val recordsFile: File
        get() = File(context.filesDir, "ktp_records_v2.json")

    private val mediaDir: File
        get() = File(context.filesDir, "ktp_media").apply { if (!exists()) mkdirs() }

    fun loadAll(): MutableList<KtpRecord> {
        val file = recordsFile
        if (!file.exists()) return mutableListOf()
        return try {
            val raw = file.readText(Charsets.UTF_8)
            val arr = JSONArray(raw)
            MutableList(arr.length()) { idx -> KtpRecord.fromJson(arr.getJSONObject(idx)) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveAll(records: List<KtpRecord>): Boolean {
        return try {
            val arr = JSONArray()
            records.forEach { arr.put(it.toJson()) }
            recordsFile.writeText(arr.toString(2), Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun saveBitmapAsset(prefix: String, recordId: String, bitmap: Bitmap, isPng: Boolean): String {
        val safeId = recordId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val ext = if (isPng) "png" else "jpg"
        val fileName = "${prefix}_${safeId}.$ext"
        val outFile = File(mediaDir, fileName)
        FileOutputStream(outFile).use { fos ->
            if (isPng) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos)
            }
        }
        return fileName
    }

    fun loadBitmapAsset(fileName: String): Bitmap? {
        if (fileName.isBlank()) return null
        val safeName = File(fileName).name
        val file = File(mediaDir, safeName)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun deleteBitmapAsset(fileName: String) {
        if (fileName.isBlank()) return
        val safeName = File(fileName).name
        val file = File(mediaDir, safeName)
        if (file.exists()) file.delete()
    }

    fun clearAll() {
        if (recordsFile.exists()) recordsFile.delete()
        mediaDir.listFiles()?.forEach { it.delete() }
    }
}
