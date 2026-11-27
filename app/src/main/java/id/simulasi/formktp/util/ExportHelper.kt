package id.simulasi.formktp.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import id.simulasi.formktp.model.KtpRecord
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ExportHelper {

    fun sanitizeFileName(raw: String): String {
        val base = File(raw).name
        return base.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "Simulasi_KTP.png" }
    }

    fun savePngToDownloads(context: Context, bitmap: Bitmap, rawFileName: String): Result<String> {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return saveBytesToDownloads(context, stream.toByteArray(), rawFileName, "image/png")
    }

    fun saveRecordsJsonToDownloads(context: Context, records: List<KtpRecord>): Result<String> {
        val arr = JSONArray()
        records.forEach { arr.put(it.toExportJson()) }
        val bytes = arr.toString(2).toByteArray(Charsets.UTF_8)
        return saveBytesToDownloads(context, bytes, "data_simulasi_ktp.json", "application/json")
    }

    fun saveRecordsCsvToDownloads(context: Context, records: List<KtpRecord>): Result<String> {
        val headers = listOf(
            "NIK", "Nama", "Tempat Lahir", "Tanggal Lahir", "Jenis Kelamin",
            "Gol Darah", "Alamat", "RT", "RW", "Kelurahan", "Kecamatan",
            "Kabupaten/Kota", "Provinsi", "Agama", "Status Perkawinan",
            "Pekerjaan", "Kewarganegaraan", "Tanggal Terbit"
        )
        val sb = StringBuilder()
        sb.appendLine(headers.joinToString(","))
        records.forEach { r ->
            val cols = listOf(
                "'${r.nik}",
                safeCsvCell(r.nama),
                safeCsvCell(r.tempatLahir),
                safeCsvCell(r.tanggalLahir),
                safeCsvCell(r.jenisKelamin),
                safeCsvCell(r.golDarah),
                safeCsvCell(r.alamat),
                safeCsvCell(r.rt),
                safeCsvCell(r.rw),
                safeCsvCell(r.kelurahan),
                safeCsvCell(r.kecamatan),
                safeCsvCell(r.kabupaten),
                safeCsvCell(r.provinsi),
                safeCsvCell(r.agama),
                safeCsvCell(r.statusPerkawinan),
                safeCsvCell(r.pekerjaan),
                safeCsvCell(r.kewarganegaraan),
                safeCsvCell(r.tanggalTerbitIso)
            )
            sb.appendLine(cols.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
        }
        return saveBytesToDownloads(
            context,
            sb.toString().toByteArray(Charsets.UTF_8),
            "data_simulasi_ktp.csv",
            "text/csv"
        )
    }

    /** Melindungi sel CSV dari Formula Injection di Excel/Calc (=, +, -, @). */
    private fun safeCsvCell(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.isNotEmpty() && trimmed[0] in charArrayOf('=', '+', '-', '@', '\t', '\r')) {
            "'$trimmed"
        } else {
            trimmed
        }
    }

    private fun saveBytesToDownloads(
        context: Context,
        bytes: ByteArray,
        rawFileName: String,
        mimeType: String
    ): Result<String> {
        val fileName = sanitizeFileName(rawFileName)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.failure(IllegalStateException("Gagal membuat entri MediaStore."))
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: return Result.failure(IllegalStateException("Gagal membuka stream tujuan."))
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, fileName)
                FileOutputStream(outFile).use { it.write(bytes) }
            }
            Result.success("Download/$fileName")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun shareBitmapPng(context: Context, bitmap: Bitmap, rawFileName: String) {
        val fileName = sanitizeFileName(rawFileName)
        val cacheDir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val file = File(cacheDir, fileName)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Bagikan Pratinjau Kartu Simulasi"))
    }
}
