package com.build.mythos

// Pemeriksaan APK sebelum dipasang (logika murni, tanpa android.*, diuji di ApkVerifyTest).
// Pembacaan fakta APK dari PackageManager ada di SelfUpgradeIntegration.kt.
//
// Android hanya mau memperbarui aplikasi bila paket sama, versionCode lebih tinggi, dan tanda tangan sama.
// Kalau salah satunya tidak terpenuhi, pemasang sistem hanya menolak dengan pesan samar (atau, bila pengguna
// menghapus dulu aplikasinya, kunci API dan riwayat chat ikut hilang). Pemeriksaan di sini memberi tahu lebih awal
// dan jelas. Ini pagar untuk kesalahan, bukan batas keamanan: batas sebenarnya adalah ketukan pengguna di pemasang Android.

/** [certs] = sidik jari SHA-256 (hex huruf kecil) sertifikat penanda tangan saat ini. */
internal class ApkFacts(val pkg: String, val versionCode: Long, val certs: Set<String>)

internal class ApkCheck(val problems: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = problems.isEmpty()
}

/**
 * [candidate] null berarti APK tidak terbaca. APK milik aplikasi LAIN (paket berbeda) bukan upgrade diri: lolos tanpa
 * pemeriksaan, kecuali [requireOwn] (dipakai self_upgrade tahap=pasang, yang memang hanya boleh memasang aplikasi ini).
 */
internal fun evaluateApk(ownPkg: String, installed: ApkFacts, candidate: ApkFacts?, requireOwn: Boolean = false): ApkCheck {
    if (candidate == null) return ApkCheck(listOf("APK tidak bisa dibaca (rusak, terpotong, atau bukan APK)."), emptyList())
    if (candidate.pkg != ownPkg) {
        return if (requireOwn) {
            ApkCheck(listOf("APK ini milik paket \"${candidate.pkg}\", bukan aplikasi ini (\"$ownPkg\"). Upgrade diri hanya memasang aplikasi ini."), emptyList())
        } else {
            ApkCheck(emptyList(), emptyList())
        }
    }
    val problems = ArrayList<String>()
    val warnings = ArrayList<String>()
    if (candidate.versionCode <= installed.versionCode) {
        problems.add(
            "versionCode APK (${candidate.versionCode}) tidak lebih tinggi dari yang terpasang (${installed.versionCode}). " +
                "Android menolak pembaruan seperti ini. Naikkan versionCode di build.gradle.kts lalu build ulang."
        )
    }
    when {
        candidate.certs.isEmpty() || installed.certs.isEmpty() ->
            warnings.add("Tanda tangan tidak terbaca di perangkat ini, jadi kesinambungannya belum bisa dipastikan. Android tetap memeriksanya saat memasang.")
        candidate.certs.intersect(installed.certs).isEmpty() ->
            problems.add(
                "Tanda tangan APK berbeda dari aplikasi yang terpasang, jadi Android menolak sebagai pembaruan (jalan lain: hapus dulu aplikasinya, " +
                    "yang MENGHILANGKAN kunci API dan riwayat chat). Bangun dengan keystore yang sama: isi keystore.properties, atau gunakan " +
                    "keystore yang sama di GitHub Actions dan di Termux."
            )
    }
    return ApkCheck(problems, warnings)
}
