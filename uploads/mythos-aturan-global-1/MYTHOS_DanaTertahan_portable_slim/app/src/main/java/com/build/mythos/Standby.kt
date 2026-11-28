package com.build.mythos

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

// Mode siaga: setelah tugas selesai AI tetap "hidup". Layanan latar tetap berjalan (tanpa wake lock, hemat baterai)
// dan di chat tampil logo bintang "bernapas" dengan kata yang berganti-ganti sampai pesan berikutnya.

internal fun MainActivity.clearStandby() {
    standbyRow?.let { (it.parent as? ViewGroup)?.removeView(it) }
    standbyRow = null
}

/**
 * Baris siaga di akhir jawaban (logo bintang + kata "Menunggu…", "Merenung…", "Melamun…") sengaja dimatikan:
 * setelah tugas selesai tidak ada teks atau ikon tambahan di bawah jawaban. Layanan latar siaga
 * ([enterStandbyService]) tetap berjalan seperti biasa.
 */
internal fun MainActivity.showStandby() = runOnUiThread {
    clearStandby()
}

/** Layanan latar tetap hidup tetapi tidak menahan CPU/Wi-Fi: dipakai saat tidak ada tugas berjalan. */
internal fun MainActivity.enterStandbyService() {
    try {
        startForegroundService(Intent(this, AgentService::class.java).setAction(AgentService.ACTION_STANDBY))
    } catch (e: Exception) {
    }
}
