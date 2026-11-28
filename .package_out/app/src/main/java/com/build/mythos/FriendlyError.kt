package com.build.mythos

/** Penanda tampilan pesan error di layar chat, supaya bisa dihapus saat tugas dilanjutkan. */
private const val ERROR_NOTE_TAG = "error_note"

/** Awal teks riwayat untuk tugas gagal; dipakai untuk membersihkan riwayat saat tugas dilanjutkan. */
internal const val FAILED_HIST_PREFIX = "Tugas tidak selesai"

/**
 * Ubah pesan error teknis (HTTP, JSON, dll) jadi kalimat singkat yang mudah dibaca.
 * Hanya untuk tampilan: pesan asli tetap disimpan di log/checkpoint.
 */
internal fun friendlyErrorText(raw: String?): String {
    val t = (raw ?: "").trim()
    val low = t.lowercase()
    fun has(vararg k: String) = k.any { low.contains(it) }
    return when {
        has("free-models-per-day", "per-day", "daily limit", "free_tier_daily") ->
            "Jatah model gratis untuk hari ini sudah habis. Coba lagi besok, atau pilih model lain."
        has("http 429", "rate limit", "too many requests") ->
            "Terlalu banyak permintaan. Tunggu sebentar, lalu tekan Lanjutkan tugas."
        has("http 401", "http 403", "invalid api key", "unauthorized", "kunci api") ->
            "Kunci API ditolak. Periksa kunci API di Pengaturan."
        has("http 402", "insufficient", "credits", "kredit", "saldo") ->
            "Saldo atau kredit di penyedia AI tidak cukup."
        has("http 404", "model not found", "no endpoints found") ->
            "Model yang dipilih tidak tersedia. Pilih model lain."
        has("http 5", "overloaded", "bad gateway", "unavailable") ->
            "Server AI sedang sibuk. Coba lagi beberapa saat lagi."
        has("jaringan", "timeout", "unknownhost", "unable to resolve", "failed to connect") ->
            "Koneksi internet bermasalah. Periksa sinyal, lalu coba lagi."
        has("batas", "langkah tercapai") ->
            "Tugas terlalu panjang dan berhenti di tengah jalan. Tekan Lanjutkan tugas."
        has("terjebak") ->
            "Tugas terhenti karena gagal berulang kali. Coba beri petunjuk baru."
        // Pesan lain yang sudah berbahasa sederhana (tanpa kurung kurawal/JSON) boleh tampil, dipendekkan.
        t.isNotEmpty() && !t.contains('{') && !t.contains("http", ignoreCase = true) && t.length <= 140 -> t
        else -> "Ada kendala saat menghubungi AI. Coba lagi nanti."
    }
}

/** Teks riwayat yang berasal dari tugas gagal/terhenti (dibersihkan saat tugas dilanjutkan). */
internal fun isFailedHistText(text: String): Boolean =
    text.startsWith(FAILED_HIST_PREFIX) || text.startsWith("Berhenti:") || text.startsWith("Pengaturan penyedia belum lengkap") ||
        text == "(respons model kosong)"

/** Pesan error ringkas di layar chat (bisa dihapus lewat [clearErrorNotes]). */
internal fun MainActivity.addErrorNote(raw: String?) = addNote(friendlyErrorText(raw), ERROR_NOTE_TAG)

/** Catatan kegagalan yang teksnya sudah sederhana (dipakai apa adanya), tetap bisa dihapus saat dilanjutkan. */
internal fun MainActivity.addFailureNote(text: String) = addNote(text, ERROR_NOTE_TAG)

/** Hapus semua pesan error lama dari layar dan riwayat. Dipanggil saat pengguna menekan Lanjutkan tugas. */
internal fun MainActivity.clearErrorNotes() {
    synchronized(hist) {
        hist.removeAll { it.role == 1 && isFailedHistText(it.text) }
    }
    bg { saveHist() } // tulis ke disk di luar thread UI
    runOnUiThread {
        for (i in chatBox.childCount - 1 downTo 0) {
            if (chatBox.getChildAt(i).tag == ERROR_NOTE_TAG) chatBox.removeViewAt(i)
        }
    }
}
