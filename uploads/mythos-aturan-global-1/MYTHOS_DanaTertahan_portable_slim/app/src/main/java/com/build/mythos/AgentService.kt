package com.build.mythos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

/**
 * Layanan latar depan: selama AI bekerja, aplikasi dianggap aktif oleh Android sehingga
 * perintah ke Termux tetap jalan walau layar mati atau Anda pindah aplikasi.
 */
class AgentService : Service() {

    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Layanan latar depan saja tidak mencegah CPU/Wi-Fi tidur. Saat layar mati atau pindah ke Termux,
     * koneksi streaming ke AI sering putus; kunci ini menjaganya tetap hidup selama AI bekerja.
     */
    private fun acquireLocks() {
        try {
            if (cpuLock?.isHeld != true) {
                val lock = cpuLock ?: applicationContext.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kita:agent").apply {
                        setReferenceCounted(false)
                    }.also { cpuLock = it }
                lock.acquire(6 * 60 * 60 * 1000L) // pengaman: lepas otomatis setelah 6 jam, diakuisisi ulang bila sudah habis
            }
            if (wifiLock?.isHeld != true) {
                val lock = wifiLock ?: run {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wm.createWifiLock(mode, "kita:agent").apply { setReferenceCounted(false) }.also { wifiLock = it }
                }
                lock.acquire()
            }
        } catch (e: Exception) {
            logQuiet("AgentService.kt", e)
        }
    }

    private fun releaseLocks() {
        try { cpuLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { logQuiet("AgentService.kt", e) }
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { logQuiet("AgentService.kt", e) }
        cpuLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val standby = intent?.action == ACTION_STANDBY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "MYTHOS bekerja", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH)
            .setContentTitle(if (standby) "MYTHOS siaga" else "MYTHOS sedang bekerja")
            .setContentText(if (standby) "Menunggu perintah berikutnya. Ketuk untuk membuka" else "Ketuk untuk membuka")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        // Catatan Android 15 (API 35): FGS dataSync dibatasi 6 jam. Saat compileSdk/targetSdk naik ke 35,
        // tambahkan override onTimeout(startId: Int, fgsType: Int) { releaseLocks(); stopSelf(startId) }.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
        // Siaga: layanan tetap hidup tetapi CPU/Wi-Fi boleh tidur (hemat baterai). Kunci hanya dipakai saat AI bekerja.
        if (standby) releaseLocks() else acquireLocks()
        return START_NOT_STICKY
    }

    companion object {
        private const val CH = "agent_work"
        const val ACTION_STANDBY = "com.build.mythos.STANDBY"
    }
}
