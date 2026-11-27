package com.build.mythos

// Logika murni (tanpa android.*) untuk hasil uji model: penggolongan kode HTTP, penggabungan hasil, dan kapan model yang
// tidak bisa dipakai layak dicoba lagi. Diuji di ProbeVerdictTest.

internal enum class ProbeVerdict {
    /** 2xx: model bisa dipakai. */
    GOOD,
    /** 429 atau 5xx: sementara sibuk/error, tetap ditampilkan dengan penanda "sibuk". */
    BUSY,
    /** 4xx lain (400, 403, 404, 422, ...): model tidak bisa dipakai di akun ini; disembunyikan dari pilihan. */
    UNAVAILABLE,
    /** Timeout, gagal jaringan (-1), atau 401 (kunci salah): bukan salah modelnya, jadi status lama tidak diubah. */
    UNKNOWN
}

internal fun classifyProbe(code: Int): ProbeVerdict = when {
    code in 200..299 -> ProbeVerdict.GOOD
    code == 429 || code in 500..599 -> ProbeVerdict.BUSY
    code <= 0 || code == 401 || code == 408 -> ProbeVerdict.UNKNOWN
    code in 400..499 -> ProbeVerdict.UNAVAILABLE
    else -> ProbeVerdict.UNKNOWN
}

/** Model yang disembunyikan: kode HTTP terakhir dan kapan diuji. */
internal class UnavailableEntry(val code: Int, val at: Long)

/** Tiga daftar hasil uji untuk satu penyedia. */
internal class ProbeOutcome(
    val good: List<String>,
    val busy: List<String>,
    val bad: Map<String, UnavailableEntry>
)

/** Putaran pertama uji model (satu gelombang paralel); sisanya diuji diam-diam di latar. */
internal const val PROBE_FIRST_ROUND = 5

/** Ukuran tiap gelombang uji lanjutan di latar (lebih kecil agar paket gratis tidak kena batas). */
internal const val PROBE_TAIL_CHUNK = 3

/** Paling banyak model yang diuji ulang dalam satu putaran pemulihan. */
internal const val PROBE_RETRY_MAX = 10

/** Batas waktu satu uji model: cukup singkat agar satu model macet tidak menahan semuanya. */
internal const val PROBE_CONNECT_MS = 8000
internal const val PROBE_READ_MS = 20000

/** Model dengan kode 403/404 (tidak ada / tidak berhak) dicoba ulang paling cepat tiap 24 jam; lainnya tiap jam. */
internal const val GONE_RETRY_MS = 24L * 60 * 60 * 1000
internal const val BAD_RETRY_MS = 60L * 60 * 1000

internal fun shouldRetryUnavailable(code: Int, probedAt: Long, nowMs: Long): Boolean {
    val gap = if (code == 403 || code == 404) GONE_RETRY_MS else BAD_RETRY_MS
    return probedAt <= 0L || (nowMs - probedAt) !in 0L until gap
}

/**
 * Gabungkan hasil uji baru ke daftar sebelumnya. Tiap model yang diuji dipindahkan ke daftar yang sesuai
 * (sehingga model yang pulih otomatis muncul lagi di pilihan, dan yang baru gagal hilang). Hasil UNKNOWN tidak mengubah apa pun.
 */
internal fun mergeProbeResults(prev: ProbeOutcome, results: List<Pair<String, Int>>, nowMs: Long): ProbeOutcome {
    val good = prev.good.toMutableList()
    val busy = prev.busy.toMutableList()
    val bad = LinkedHashMap(prev.bad)
    for ((m, code) in results) {
        val v = classifyProbe(code)
        if (v == ProbeVerdict.UNKNOWN) continue
        good.removeAll { sameModel(it, m) }
        busy.removeAll { sameModel(it, m) }
        bad.keys.removeAll { sameModel(it, m) }
        when (v) {
            ProbeVerdict.GOOD -> good.add(m)
            ProbeVerdict.BUSY -> busy.add(m)
            ProbeVerdict.UNAVAILABLE -> bad[m] = UnavailableEntry(code, nowMs)
            else -> {}
        }
    }
    return ProbeOutcome(good, busy, bad)
}
