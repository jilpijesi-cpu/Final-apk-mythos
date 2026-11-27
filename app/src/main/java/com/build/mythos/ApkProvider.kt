package com.build.mythos

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Menyajikan APK di cache/apk ke pemasang Android lewat content:// (pengganti FileProvider, tanpa androidx). */
class ApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        val dir = File(ctx.cacheDir, "apk")
        val f = File(dir, name)
        return if (f.isFile && f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) f else null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = if (projection.isNullOrEmpty()) arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE) else projection
        val c = MatrixCursor(cols, 1)
        val row = arrayOfNulls<Any>(cols.size)
        for (i in cols.indices) {
            row[i] = when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        c.addRow(row)
        return c
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
