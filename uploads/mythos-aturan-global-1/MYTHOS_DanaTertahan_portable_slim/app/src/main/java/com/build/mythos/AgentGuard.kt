package com.build.mythos

// Pengaman keberhasilan agen (logika murni, tanpa android.*, diuji di AgentGuardTest):
// verifikasi: agen tidak boleh berhenti bila build terakhir gagal / kode baru diubah tanpa build yang berhasil sesudahnya.
// Keputusan memakai fakta nyata dari RunLog, bukan tebakan.

/** Awalan pesan sistem di riwayat: tidak ditampilkan sebagai gelembung pengguna saat riwayat dipulihkan. */
internal const val SYS_PREFIX = "[SISTEM] "

/** Berapa kali agen boleh diminta memverifikasi dalam satu tugas (cegah putaran tanpa akhir dan boros kuota). */
internal const val MAX_VERIFY_NUDGES = 3

private val CODE_EXT = setOf("kt", "java", "kts", "gradle")

internal fun isCodeFile(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in CODE_EXT

/**
 * Pesan pengingat bila agen belum boleh dianggap selesai, atau null bila boleh.
 * (a) build terakhir gagal dan belum ada build berhasil sesudahnya; atau
 * (b) file kode (kt/java/kts/gradle) berhasil ditulis dan belum ada build berhasil sesudahnya.
 */
internal fun verificationNudge(
    steps: List<StepRecord>,
    lastFailOut: String? = null,
    nudgesUsed: Int = 0,
    sandbox: Boolean = false
): String? {
    val lastBuildOk = steps.indexOfLast { it.kind == ActivityKind.BUILD && it.state == StepState.OK }
    val lastBuildFail = steps.indexOfLast { it.kind == ActivityKind.BUILD && it.state == StepState.FAIL }
    if (lastBuildFail > lastBuildOk) {
        // Model kecil mudah menyerah bila diberi jalan keluar "jelaskan mengapa tidak bisa". Jalan keluar itu
        // baru dibuka di dorongan terakhir; sebelumnya agen wajib memanggil alat dengan perbaikan hasil diagnosis.
        val dx = lastFailOut?.let { diagnoseFailure("./gradlew assembleDebug", it, sandbox) }
        val finalChance = nudgesUsed >= MAX_VERIFY_NUDGES - 1
        return SYS_PREFIX + "Build terakhir GAGAL dan belum ada build yang berhasil sesudahnya. " +
            (if (dx != null) "Penyebab yang terdeteksi dari error aslinya: ${dx.cause}. Kerjakan sekarang: ${dx.fix} "
            else "Kutip baris error pertama yang sebenarnya, perbaiki akar masalahnya, lalu jalankan build lagi. ") +
            "Jangan menjawab dengan teks saja: panggil alat sekarang. " +
            (if (finalChance) "Ini kesempatan terakhir. Bila setelah dicoba tetap gagal, laporkan error aslinya, bukti, dan apa saja yang sudah dicoba."
            else "Jangan menyatakan mustahil atau tidak bisa sebelum perbaikan di atas benar-benar dijalankan dan hasilnya dibaca.")
    }
    val lastCodeWrite = steps.indexOfLast { it.tool == "write_file" && it.state == StepState.OK && isCodeFile(it.target) }
    if (lastCodeWrite >= 0 && lastCodeWrite > lastBuildOk) {
        return SYS_PREFIX + "Kamu mengubah file kode tetapi belum menjalankan build yang berhasil sesudahnya. " +
            "Jalankan build/tes proyek untuk membuktikan perubahan bekerja, lalu laporkan hasil sebenarnya. " +
            "Bila build memang tidak bisa dijalankan di lingkungan ini (mis. mode sandbox tanpa alat build), " +
            "katakan dengan jujur bahwa perubahan belum terverifikasi."
    }
    return null
}

/** Hasil untuk perintah yang melewati batas waktu. Diawali "exit=" agar pengaman bisa mengenalinya. */
internal fun timeoutResult(timeoutSec: Long): String =
    "exit=124 Timeout: perintah melewati batas $timeoutSec detik dan dihentikan paksa (prosesnya sudah mati).\n" +
        "Cek dulu hasil sebagian di log atau cache. Unduhan dan hasil kompilasi yang sudah selesai biasanya tersimpan, " +
        "jadi untuk build panggil build_apk lagi (melanjutkan, bukan mulai dari nol); jangan mengubah kode hanya karena timeout. " +
        "Untuk perintah lain, pecah pekerjaan menjadi langkah lebih kecil."

/** Respons model kosong (tanpa teks dan tanpa panggilan alat): diulang sekali pada model yang sama, lalu dialihkan ke model lain. */
internal const val MAX_EMPTY_RETRIES = 1
