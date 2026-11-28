package com.build.mythos

// Kesehatan startup setelah upgrade diri (logika murni, tanpa android.*, diuji di StartupHealthTest).
//
// Sebuah versi dianggap "terbukti stabil" bila aplikasi bertahan terbuka HEALTHY_AFTER_MS pada salah satu bukaan.
// Selama belum terbukti, tiap bukaan dihitung sebagai bukaan tidak sehat. Bila berulang lebih dari MAX_UNHEALTHY_BOOTS,
// versi dilaporkan tidak stabil dan pengguna diarahkan memulihkan versi sebelumnya.
// Versi yang sudah pernah terbukti stabil tidak pernah dilaporkan tidak stabil: menutup aplikasi cepat-cepat
// berkali-kali bukan tanda rusak.
//
// Batas jujur: bila versi baru crash sebelum sempat menampilkan apa pun, kode ini tidak bisa berjalan. Itu sebabnya
// pemulihan bertumpu pada cadangan kode di folder proyek dan git, bukan pada aplikasi yang sedang rusak.

internal const val MAX_UNHEALTHY_BOOTS = 3

/** Berapa lama aplikasi harus bertahan terbuka sebelum versi ini dianggap sehat. */
internal const val HEALTHY_AFTER_MS = 10_000L

internal class BootState(
    /** versionCode pada bukaan terakhir (0 = belum pernah tercatat). */
    val lastBootCode: Long,
    /** Jumlah bukaan berturut-turut dari versi yang sama yang belum terbukti sehat. */
    val unhealthyBoots: Int,
    /** versionCode terakhir yang terbukti stabil (0 = belum ada). */
    val lastGoodCode: Long,
    /** versionCode yang sedang menunggu hasil upgrade lewat self_upgrade (0 = tidak ada). */
    val pendingCode: Long
)

internal enum class BootMode { NORMAL, UPGRADED, UNSTABLE }

internal class BootDecision(val mode: BootMode, val next: BootState, val message: String?)

/** Dipanggil sekali tiap aplikasi dibuka. [next] harus disimpan oleh pemanggil. */
internal fun decideBoot(s: BootState, currentCode: Long, maxUnhealthy: Int = MAX_UNHEALTHY_BOOTS): BootDecision {
    val boots = if (s.lastBootCode == currentCode) s.unhealthyBoots + 1 else 1
    val next = BootState(currentCode, boots, s.lastGoodCode, s.pendingCode)
    val proven = s.lastGoodCode == currentCode
    if (!proven && boots > maxUnhealthy) {
        val good = if (s.lastGoodCode > 0) "kode ${s.lastGoodCode}" else "belum tercatat"
        return BootDecision(
            BootMode.UNSTABLE, next,
            "Versi ini (kode $currentCode) belum pernah terbukti stabil dan sudah dibuka $boots kali tanpa bertahan " +
                (HEALTHY_AFTER_MS / 1000) + " detik. Versi stabil terakhir: $good. " +
                "Minta agen memulihkan versi sebelumnya (self_upgrade tahap=pulihkan): kode lama dibangun ulang dengan nomor versi lebih tinggi, " +
                "karena Android menolak turun versi tanpa menghapus data aplikasi."
        )
    }
    if (!proven && s.pendingCode == currentCode) {
        return BootDecision(
            BootMode.UPGRADED, next,
            "Upgrade ke versi kode $currentCode terpasang. Versi ini dipantau selama beberapa detik pertama; " +
                "bila gagal berulang, agen bisa memulihkan versi sebelumnya."
        )
    }
    return BootDecision(BootMode.NORMAL, next, null)
}

/** Dipanggil setelah aplikasi bertahan terbuka [HEALTHY_AFTER_MS]. */
internal fun markHealthy(s: BootState, currentCode: Long): BootState =
    BootState(currentCode, 0, currentCode, if (s.pendingCode == currentCode) 0L else s.pendingCode)
