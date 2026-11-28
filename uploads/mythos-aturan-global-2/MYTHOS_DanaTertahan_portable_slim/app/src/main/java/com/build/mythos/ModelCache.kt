package com.build.mythos

// Logika murni (tanpa android.*) untuk masa berlaku cache hasil uji model dan pembuangan model yang sudah tidak ada.
// Dipisah supaya bisa diuji sebagai unit test JVM.

/** Hasil uji model dianggap basi setelah 24 jam: penyedia sering menghapus, mengganti nama, atau menambah model. */
internal const val PROBE_TTL_MS = 24L * 60 * 60 * 1000

/** Jeda minimum antar percobaan penyegaran otomatis di latar (agar penyegaran yang gagal tidak diulang tiap onResume). */
internal const val PROBE_RETRY_GAP_MS = 60L * 60 * 1000

/** Cache segar bila punya waktu simpan, belum lewat [ttlMs], dan jam perangkat tidak mundur. Cache lama tanpa waktu dianggap basi. */
internal fun probeFresh(savedAtMs: Long, nowMs: Long, ttlMs: Long = PROBE_TTL_MS): Boolean =
    savedAtMs > 0L && (nowMs - savedAtMs) in 0L..ttlMs

/** Penyegaran otomatis di latar boleh jalan lagi bila percobaan terakhir sudah lewat [gapMs] (atau jam mundur). */
internal fun retryDue(lastTryMs: Long, nowMs: Long, gapMs: Long = PROBE_RETRY_GAP_MS): Boolean =
    lastTryMs <= 0L || (nowMs - lastTryMs) !in (0L until gapMs)

/** Nama model sama walau satu memakai awalan "models/" (Gemini). */
internal fun sameModel(a: String, b: String): Boolean = a.removePrefix("models/") == b.removePrefix("models/")

/** Buang [model] dari daftar lolos uji dan daftar sibuk. */
internal fun dropModel(good: List<String>, busy: List<String>, model: String): Pair<List<String>, List<String>> =
    Pair(good.filter { !sameModel(it, model) }, busy.filter { !sameModel(it, model) })
