package com.build.mythos

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

// Sisi Android dari audit dan upgrade diri (fungsi-fungsi ini ekstensi MainActivity). Logika keputusan ada di
// SelfAudit.kt, SelfUpgrade.kt, ApkVerify.kt, dan StartupHealth.kt (murni, teruji). Penjelasan utuh: SELF_UPGRADE.md.

internal const val SELF_ASSET = "kita-self.sh"

private const val PREF_PENDING = "upg_pending_code"
private const val PREF_BOOT_LAST = "boot_last_code"
private const val PREF_BOOT_UNHEALTHY = "boot_unhealthy"
private const val PREF_BOOT_GOOD = "boot_good_code"

// ---------------------------------------------------------------- fakta APK dan aplikasi terpasang

private fun sha256Hex(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

@Suppress("DEPRECATION")
private fun sigFlags(): Int =
    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

@Suppress("DEPRECATION")
private fun certsOf(info: PackageInfo?): Set<String> {
    if (info == null) return emptySet()
    val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    return sigs?.map { sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
}

@Suppress("DEPRECATION")
internal fun versionOf(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

@Suppress("DEPRECATION")
internal fun MainActivity.installedFacts(): ApkFacts {
    val info = packageManager.getPackageInfo(packageName, sigFlags())
    return ApkFacts(packageName, versionOf(info), certsOf(info))
}

/** Fakta APK di [f], atau null bila tidak terbaca. */
@Suppress("DEPRECATION")
internal fun MainActivity.apkFileFacts(f: File): ApkFacts? = try {
    val info = packageManager.getPackageArchiveInfo(f.absolutePath, sigFlags())
    if (info == null) null else ApkFacts(info.packageName ?: "", versionOf(info), certsOf(info))
} catch (e: Exception) {
    null
}

/** Dipakai installApk untuk SEMUA APK: hanya APK paket aplikasi ini yang diperiksa (APK aplikasi lain lolos). */
internal fun MainActivity.verifyApkFile(f: File, requireOwn: Boolean = false): ApkCheck {
    val installed = try {
        installedFacts()
    } catch (e: Exception) {
        return ApkCheck(listOf("Versi terpasang tidak terbaca; verifikasi keamanan APK tidak dapat dilakukan."), emptyList())
    }
    return evaluateApk(packageName, installed, apkFileFacts(f), requireOwn)
}

internal fun apkCheckText(c: ApkCheck): String =
    (c.problems.map { "- $it" } + c.warnings.map { "- (catatan) $it" }).joinToString("\n")


// ---------------------------------------------------------------- kesehatan startup

internal fun MainActivity.loadBootState(): BootState = BootState(
    prefs.getLong(PREF_BOOT_LAST, 0L), prefs.getInt(PREF_BOOT_UNHEALTHY, 0),
    prefs.getLong(PREF_BOOT_GOOD, 0L), prefs.getLong(PREF_PENDING, 0L)
)

internal fun MainActivity.saveBootState(s: BootState) {
    prefs.edit()
        .putLong(PREF_BOOT_LAST, s.lastBootCode)
        .putInt(PREF_BOOT_UNHEALTHY, s.unhealthyBoots)
        .putLong(PREF_BOOT_GOOD, s.lastGoodCode)
        .putLong(PREF_PENDING, s.pendingCode)
        .apply()
}

/** Dipanggil di akhir onCreate (setelah UI siap): catat bukaan, tampilkan peringatan bila perlu, dan jadwalkan tanda "sehat". */
internal fun MainActivity.startBootHealth() {
    val code = try {
        installedFacts().versionCode
    } catch (e: Exception) {
        return
    }
    val d = decideBoot(loadBootState(), code)
    saveBootState(d.next)
    d.message?.let { addNote(it) }
    bootHealthRunnable?.let { window.decorView.removeCallbacks(it) }
    val task = Runnable {
        if (!isFinishing && !isDestroyed) {
            saveBootState(markHealthy(loadBootState(), code))
        }
    }
    bootHealthRunnable = task
    window.decorView.postDelayed(task, HEALTHY_AFTER_MS)
}

// Catatan: jalur tool self_audit/self_upgrade lama sudah dipensiunkan setelah migrasi ke paket cc.






