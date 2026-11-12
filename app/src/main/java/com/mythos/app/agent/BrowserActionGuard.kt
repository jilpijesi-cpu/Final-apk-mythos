package com.mythos.app.agent

/**
 * Penjaga aksi final untuk Browser Agent. Mengenali tombol/instruksi yang berdampak
 * (kirim, setujui syarat, tutup kasus, terima penyelesaian, ajukan keberatan, unggah final, hapus, bayar, dst.)
 * supaya agen selalu meminta konfirmasi pengguna sebelum menjalankannya.
 */
object BrowserActionGuard {
    private data class Rule(val reason: String, val pattern: Regex)

    private fun r(reason: String, vararg words: String) =
        Rule(reason, Regex("(?i)(?<![\\p{L}\\p{N}])(?:" + words.joinToString("|") + ")(?![\\p{L}\\p{N}])"))

    private val rules = listOf(
        r("mengirim data", "kirim(?:kan)?", "submit", "send", "ajukan", "simpan\\s+&\\s+kirim", "daftar\\s+sekarang",
            "laporkan", "buat\\s+(?:pengaduan|laporan)", "proses\\s+sekarang", "selesai", "simpan(?!\\s+(?:draf|draft|sementara))"),
        r("memberi persetujuan", "setuju(?:i)?", "saya\\s+setuju", "agree", "accept", "konfirmasi", "ya,?\\s+saya\\s+yakin", "centang\\s+syarat",
            "menyetujui", "persetujuan", "i\\s+agree"),
        r("mengirim tanggapan", "tanggapan", "tanggapi", "balas(?:an)?\\s+final", "respon(?:s)?\\s+final", "reply\\s+final"),
        r("menutup kasus", "tutup\\s+(?:kasus|pengaduan|perkara|tiket|laporan)", "close\\s+(?:case|ticket|complaint)", "selesaikan\\s+(?:kasus|pengaduan)"),
        r("menerima penyelesaian", "terima\\s+(?:penyelesaian|tawaran|usulan|kesepakatan|hasil)", "setuju\\s+(?:penyelesaian|damai)", "accept\\s+(?:settlement|offer)"),
        r("mengajukan keberatan", "keberatan", "banding", "appeal", "objection"),
        r("mengunggah versi final", "unggah\\s+final", "upload\\s+final", "finalisasi", "finalize", "kirim\\s+dokumen", "lampirkan\\s+dan\\s+kirim"),
        r("menghapus data", "hapus", "delete", "remove", "buang", "batalkan\\s+(?:pengaduan|laporan|kasus)", "cabut"),
        r("melakukan pembayaran", "bayar", "pay", "checkout", "transfer"),
        r("mempublikasikan", "publikasikan", "publish", "posting"),
        r("menarik/mencabut pengaduan", "tarik\\s+(?:pengaduan|laporan)", "withdraw"),
    )

    /** Alasan aksi dianggap final, atau null bila tampak aman. Gabungkan beberapa teks (instruksi + teks tombol aktual). */
    fun reasonFor(vararg texts: String?): String? {
        val joined = texts.filterNotNull().joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (joined.isBlank()) return null
        return rules.firstOrNull { it.pattern.containsMatchIn(joined) }?.reason
    }

    fun isFinal(vararg texts: String?): Boolean = reasonFor(*texts) != null

    /** Tombol konfirmasi generik: hanya berbahaya bila SELURUH teks tombol berupa kata ini (mis. dialog "Yakin? [Ya]"). */
    private val bareConfirm = setOf("ok", "oke", "ya", "yes", "lanjutkan", "yakin", "confirm", "proceed", "continue")

    /**
     * Alasan untuk KLIK tombol. Selain kata-kata di [reasonFor], tombol dianggap final bila
     * (a) teks tombol hanya "OK"/"Ya"/dst., atau (b) tombol itu mengirim formulir ([isSubmit], dari DOM).
     */
    fun reasonForButton(instruction: String?, actualText: String?, isSubmit: Boolean = false): String? {
        reasonFor(instruction, actualText)?.let { return it }
        val bare = actualText.orEmpty().trim().trim('.', '!', '?').lowercase()
        if (bare in bareConfirm) return "tombol konfirmasi (\"$actualText\")"
        if (isSubmit) return "mengirim formulir"
        return null
    }
}
