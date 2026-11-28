package com.build.mythos

import android.content.ContentResolver
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout

// Strip "Foto terbaru" di lembar "Tambahkan ke chat": thumbnail foto terakhir di galeri, ketuk untuk melampirkan.
// Butuh izin baca gambar. Di Android 14+ pengguna boleh memberi akses sebagian saja (hanya foto pilihan), dan itu cukup.
// Tanpa izin, baris "Tampilkan foto terbaru" meminta izin; bila ditolak, jatuh kembali ke pemilih foto seperti sebelumnya.

internal class RecentPhoto(val uri: Uri, val addedAtSec: Long)

internal object RecentPhotos {
    const val REQUEST_CODE = 3
    const val COUNT = 12
    const val THUMB_PX = 256

    /** Izin yang diminta di [sdk] (nilai Build.VERSION.SDK_INT). */
    fun permissionsFor(sdk: Int): List<String> = when {
        sdk >= 34 -> listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        sdk >= 33 -> listOf("android.permission.READ_MEDIA_IMAGES")
        else -> listOf("android.permission.READ_EXTERNAL_STORAGE")
    }

    /** Cukup bila SALAH SATU izin sudah diberikan: di Android 14 akses sebagian (foto pilihan) hanya memberi izin kedua. */
    fun accessGranted(sdk: Int, isGranted: (String) -> Boolean): Boolean = permissionsFor(sdk).any(isGranted)
}

internal fun MainActivity.hasRecentPhotoAccess(): Boolean =
    RecentPhotos.accessGranted(Build.VERSION.SDK_INT) { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

internal fun MainActivity.askRecentPhotoAccess() {
    requestPermissions(RecentPhotos.permissionsFor(Build.VERSION.SDK_INT).toTypedArray(), RecentPhotos.REQUEST_CODE)
}

/** Foto terbaru dulu. Panggil dari thread latar. Gagal (izin dicabut, penyimpanan error) menghasilkan daftar kosong. */
internal fun MainActivity.queryRecentPhotos(limit: Int): List<RecentPhoto> {
    val out = ArrayList<RecentPhoto>()
    try {
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext() && out.size < limit) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
                out.add(RecentPhoto(uri, c.getLong(dateCol)))
            }
        }
    } catch (e: Exception) {
    }
    return out
}

/** Thumbnail kecil untuk strip. Panggil dari thread latar. */
internal fun MainActivity.loadPhotoThumb(uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= 29) {
        try {
            return contentResolver.loadThumbnail(uri, Size(RecentPhotos.THUMB_PX, RecentPhotos.THUMB_PX), null)
        } catch (e: Exception) {
        }
    }
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > RecentPhotos.THUMB_PX * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }
}

/**
 * Strip geser mendatar berisi foto terbaru. Disembunyikan sendiri bila galeri kosong atau tidak terbaca.
 * [onPick] dipanggil di thread UI dengan uri foto yang diketuk.
 */
internal fun MainActivity.buildRecentStrip(onPick: (Uri) -> Unit): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val strip = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(row)
    }
    val thumbJobs = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.Job>()
    strip.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            thumbJobs.forEach { it.cancel() }
            thumbJobs.clear()
        }
    })
    thumbJobs += bg {
        val photos = queryRecentPhotos(RecentPhotos.COUNT)
        runOnUiThread {
            if (isFinishing || isDestroyed || !strip.isAttachedToWindow) return@runOnUiThread
            if (photos.isEmpty()) strip.visibility = View.GONE
            for (p in photos) {
                val cell = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(Palette.TILE, 16)
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                    contentDescription = "Foto terbaru dari galeri"
                    setOnClickListener { onPick(p.uri) }
                }
                row.addView(cell, LinearLayout.LayoutParams(dp(92), dp(92)).apply { marginEnd = dp(8) })
                thumbJobs += bg {
                    val bmp = loadPhotoThumb(p.uri)
                    if (bmp != null) runOnUiThread {
                        if (!isFinishing && !isDestroyed && strip.isAttachedToWindow) cell.setImageBitmap(bmp)
                    }
                }
            }
        }
    }
    return strip
}
