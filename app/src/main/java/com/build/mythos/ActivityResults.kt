package com.build.mythos

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import java.util.concurrent.CountDownLatch

// Penanganan hasil Activity / izin dan helper thread UI, dipecah dari MainActivity (isi tidak berubah).
// Override tetap di MainActivity (syarat Android) dan hanya meneruskan ke fungsi di sini.

/** Hasil kamera (80), pemilih foto/file (78, 79), dan input suara (77). */
internal fun MainActivity.handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == 80) {
        val u = cameraUri
        cameraUri = null
        if (u != null) {
            if (resultCode == Activity.RESULT_OK) importFile(u) else try { contentResolver.delete(u, null, null) } catch (ignored: Exception) { logQuiet("ActivityResults.kt", ignored) }
        }
        return
    }
    if ((requestCode == 78 || requestCode == 79) && resultCode == Activity.RESULT_OK) {
        val uri = data?.data
        if (uri != null) importFile(uri)
    }
    if (requestCode == 77 && resultCode == Activity.RESULT_OK) {
        val r = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (!r.isNullOrEmpty()) {
            input.setText(r[0])
            input.setSelection(input.text.length)
            if (voiceTurnPending) {
                voiceTurnPending = false
                send()
            }
        }
    } else if (requestCode == 77) {
        voiceTurnPending = false
    }
}

/** Hasil permintaan izin: foto terbaru, penyimpanan untuk impor file, atau pemeriksaan ulang penyiapan Termux. */
internal fun MainActivity.handlePermissionsResult(requestCode: Int, grantResults: IntArray) {
    if (requestCode == RecentPhotos.REQUEST_CODE) {
        if (hasRecentPhotoAccess()) showAddSheet()   // lembar dibuka ulang, kini dengan strip foto terbaru
        else { toast("Izin foto ditolak, memakai pemilih foto"); pickImage() }
        return
    }
    if (requestCode == 2) {
        val u = pendingImport
        pendingImport = null
        if (u != null) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) importFile(u)
            else toast("Izin penyimpanan ditolak, file tidak dilampirkan")
        }
        return
    }
    setupRecheck?.invoke()
}

/** Jalankan [f] di thread UI dan tunggu hasilnya (langsung bila sudah di thread UI). */
internal fun <T> MainActivity.onUi(f: () -> T): T {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return f()
    val box = arrayOfNulls<Any>(1)
    val err = arrayOfNulls<Throwable>(1)
    val l = CountDownLatch(1)
    runOnUiThread {
        try { box[0] = f() } catch (t: Throwable) { err[0] = t } finally { l.countDown() }
    }
    l.await()
    err[0]?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return box[0] as T
}
