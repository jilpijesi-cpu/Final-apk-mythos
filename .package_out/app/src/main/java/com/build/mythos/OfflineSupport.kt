package com.build.mythos

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.Gravity
import android.view.View
import android.widget.TextView
import org.json.JSONArray

// Dukungan offline: pantau jaringan, banner status, antrean pesan yang dikirim otomatis saat koneksi pulih,
// dan jeda menunggu koneksi (bukan membakar percobaan ulang) saat jaringan putus di tengah permintaan.
// Bagian murni Kotlin (antrean, teks banner, aturan antre) bisa diuji sebagai tes JVM (OfflineSupportTest).
// Riwayat chat dan daftar model sudah tersimpan lokal (ChatStore, hasil uji model), jadi layar tidak kosong saat offline.

internal const val OFFLINE_QUEUE_PREF = "offline_queue"
internal const val OFFLINE_QUEUE_MAX = 20
internal const val OFFLINE_WAIT_SEC = 120

/** Status jaringan bersama (diisi pemantau, dibaca thread latar). */
internal object NetState {
    @Volatile var online = true
    /** Jumlah pesan antrean yang gelembungnya sudah tampil di sesi ini (agar tidak tampil dua kali saat dikirim). */
    @Volatile var queuedShown = 0
    @Volatile var callback: ConnectivityManager.NetworkCallback? = null
    // Referensi lemah: objek statis ini tidak boleh menahan TextView (dan Activity induknya) setelah layar ditutup.
    @Volatile private var bannerRef: java.lang.ref.WeakReference<TextView>? = null
    var banner: TextView?
        get() = bannerRef?.get()
        set(v) { bannerRef = v?.let { java.lang.ref.WeakReference(it) } }
}

internal fun offlineQueueList(raw: String?): List<String> = try {
    val a = JSONArray(raw ?: "[]")
    (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
} catch (e: Exception) {
    emptyList()
}

internal fun offlineQueueEncode(list: List<String>): String = JSONArray(list).toString()

/** Tambah [msg] ke antrean; null bila antrean sudah penuh. */
internal fun offlineQueueAdd(raw: String?, msg: String): String? {
    val l = offlineQueueList(raw)
    if (l.size >= OFFLINE_QUEUE_MAX) return null
    return offlineQueueEncode(l + msg)
}

/** Pesan diantre hanya bila offline, AI-nya butuh internet, tanpa lampiran, dan ada isinya. */
internal fun shouldQueueOffline(online: Boolean, local: Boolean, hasAttachments: Boolean, typed: String): Boolean =
    !online && !local && !hasAttachments && typed.isNotBlank()

/** Teks banner; null = banner disembunyikan. */
internal fun offlineBannerText(online: Boolean, local: Boolean, queued: Int): String? = when {
    online -> null
    local -> "Offline. Model lokal tetap bisa dipakai."
    queued > 0 -> "Offline. $queued pesan menunggu dikirim. Ketuk untuk coba kirim sekarang, tahan untuk membatalkan antrean."
    else -> "Offline. Pesan yang kamu kirim disimpan dan dikirim otomatis saat koneksi pulih."
}

private fun hasInternet(c: NetworkCapabilities): Boolean =
    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

private fun MainActivity.offlineQueueRaw(): String = prefs.getString(OFFLINE_QUEUE_PREF, "[]") ?: "[]"

internal fun MainActivity.buildOfflineBanner(): TextView {
    val bg = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(Palette.NAV)
        setStroke(dp(1), Palette.BORDER)
    }
    val tv = TextView(this).apply {
        textSize = Typo.SMALL
        setTextColor(Palette.INK_SOFT)
        gravity = Gravity.CENTER_VERTICAL
        background = bg
        setPadding(dp(12), dp(8), dp(12), dp(8))
        visibility = View.GONE
        setOnClickListener { drainOfflineQueue(force = true) }
        setOnLongClickListener { clearOfflineQueue(); true }
    }
    offlineBanner = tv
    NetState.banner = tv
    return tv
}

internal fun MainActivity.updateOfflineBanner() {
    val queued = offlineQueueList(offlineQueueRaw()).size
    val text = offlineBannerText(NetState.online, isLocalUrl(cfgBase()), queued)
    runOnUiThread {
        NetState.banner?.let { b ->
            if (text == null) {
                b.visibility = View.GONE
            } else {
                b.text = text
                b.visibility = View.VISIBLE
            }
        }
    }
}

private fun MainActivity.onNetChanged(on: Boolean) {
    val was = NetState.online
    NetState.online = on
    updateOfflineBanner()
    if (on && !was) runOnUiThread { drainOfflineQueue() }
}

internal fun MainActivity.startNetworkMonitor() {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    NetState.online = try {
        cm.getNetworkCapabilities(cm.activeNetwork)?.let { hasInternet(it) } ?: false
    } catch (e: Exception) {
        true // tidak bisa membaca status: anggap online, perilaku lama
    }
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { onNetChanged(hasInternet(caps)) }
        override fun onLost(network: Network) { onNetChanged(false) }
    }
    networkCallback?.let { old ->
        try { cm.unregisterNetworkCallback(old) } catch (_: Exception) {}
        networkCallback = null
    }
    try {
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
        NetState.callback = cb
    } catch (e: Exception) {
        NetState.online = true
    }
    updateOfflineBanner()
}

internal fun MainActivity.stopNetworkMonitor() {
    val cb = networkCallback
    networkCallback = null
    if (cb != null) {
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
        }
        if (NetState.callback === cb) NetState.callback = null
    }
    if (NetState.banner === offlineBanner) NetState.banner = null
    offlineBanner = null
}

/** Simpan pesan di antrean (optimistic: gelembung langsung tampil). False bila antrean penuh. */
internal fun MainActivity.queueOffline(typed: String): Boolean {
    val updated = offlineQueueAdd(offlineQueueRaw(), typed)
    if (updated == null) {
        toast("Antrean offline penuh ($OFFLINE_QUEUE_MAX pesan)")
        return false
    }
    prefs.edit().putString(OFFLINE_QUEUE_PREF, updated).apply()
    NetState.queuedShown++
    input.setText("")
    hideKeyboard()
    addMsg(USER, typed)
    addNote("Offline: pesan disimpan dan akan dikirim otomatis saat koneksi pulih.")
    updateOfflineBanner()
    return true
}

/** Kirim satu pesan antrean bila tidak sedang sibuk dan online ([force] = pengguna menyuruh coba sekarang). */
internal fun MainActivity.drainOfflineQueue(force: Boolean = false) {
    if (busy || isFinishing) return
    if (!force && !NetState.online) return
    val list = offlineQueueList(offlineQueueRaw())
    if (list.isEmpty()) return
    if (force) NetState.online = true
    val next = list.first()
    prefs.edit().putString(OFFLINE_QUEUE_PREF, offlineQueueEncode(list.drop(1))).apply()
    if (NetState.queuedShown > 0) NetState.queuedShown-- else addMsg(USER, next)
    addNote("Mengirim pesan dari antrean offline.")
    busy = true
    updateOfflineBanner()
    startWorker(next, cfgKey(), addUser = true)
}

internal fun MainActivity.clearOfflineQueue() {
    val n = offlineQueueList(offlineQueueRaw()).size
    if (n == 0) return
    prefs.edit().remove(OFFLINE_QUEUE_PREF).apply()
    NetState.queuedShown = 0
    updateOfflineBanner()
    addNote("Antrean offline dibatalkan: $n pesan tidak dikirim.")
}

/** Tunggu (di thread latar) sampai online; true bila koneksi pulih sebelum [maxSec] habis. Melempar Cancelled bila dihentikan. */
internal fun MainActivity.awaitNetwork(maxSec: Int = OFFLINE_WAIT_SEC): Boolean {
    val snap = saveStatus()
    try {
        for (sec in maxSec downTo 1) {
            if (NetState.online) return true
            setStatus(IconView.CLOCK, "Offline, menunggu koneksi ($sec dtk)")
            Thread.sleep(1000)
            if (cancelled) throw Cancelled()
        }
        return NetState.online
    } finally {
        restoreStatus(snap)
    }
}
