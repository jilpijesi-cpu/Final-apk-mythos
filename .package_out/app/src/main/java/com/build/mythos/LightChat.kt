package com.build.mythos

// Obrolan ringan di model lokal: pesan pendek tanpa kata kerja tindakan (mis. "Waalaikum salam, sangat siap")
// dijawab lewat jalur sapaan (prompt ringkas, tanpa definisi alat, konteks satu pesan), bukan jalur agen penuh.
// Murni Kotlin supaya bisa diuji sebagai tes JVM (LightChatTest).
//
// Prinsip: salah mengira TUGAS sebagai obrolan merugikan (model kehilangan alat dan riwayat), sedangkan salah mengira
// obrolan sebagai tugas hanya lebih lambat (perilaku lama). Jadi semua ragu-ragu diputuskan "bukan obrolan ringan".

private const val LIGHT_MAX_CHARS = 48
private const val LIGHT_MAX_WORDS = 6

/** Potongan kata kerja tindakan / permintaan kerja. Dicocokkan dengan `contains`, jadi "buatkan", "membuat", "dibuat" ikut tertangkap. */
private val ACTION_STEMS = listOf(
    "buat", "bikin", "tulis", "baca", "cek", "periksa", "jalan", "pasang", "install", "hapus", "ubah", "edit", "perbaik",
    "cari", "tampil", "lihat", "buka", "tutup", "unduh", "download", "kirim", "kompil", "build", "tes", "uji", "coba",
    "tambah", "ganti", "salin", "copy", "pindah", "kerjakan", "selesaikan", "mulai", "hentikan", "update", "upgrade",
    "deploy", "push", "commit", "analis", "eksekusi", "lakukan", "tolong", "bantu", "please", "show", "create", "make",
    "write", "read", "run", "open", "fix", "list", "delete", "remove", "lanjut", "ulang", "simpan", "hitung", "terjemah",
    "ringkas", "jelaskan", "buatin", "carikan", "berikan", "minta"
)

/** Kata yang merujuk ke proyek, berkas, atau giliran sebelumnya: butuh alat dan/atau riwayat. */
private val CONTEXT_WORDS = setOf(
    "proyek", "project", "file", "berkas", "folder", "apk", "error", "galat", "kode", "code", "aplikasi", "app", "git",
    "github", "repo", "log", "tugas", "tadi", "hasil", "hasilnya", "tersebut", "sebelumnya", "status", "progres",
    "bagaimana", "gimana", "kenapa", "mengapa", "gagal", "berhasil", "selesai", "tersisa", "sisanya", "yang"
)

/** Jawaban pendek atas pertanyaan agen / rujukan ke giliran sebelumnya: butuh riwayat, jangan diisolasi. */
private val FOLLOW_UP_WORDS = setOf(
    "ya", "iya", "yup", "yap", "oke", "ok", "okay", "boleh", "jangan", "tidak", "nggak", "gak", "enggak", "benar", "betul", "setuju",
    "pertama", "kedua", "ketiga", "a", "b", "c"
)

private val NOT_PLAIN_TEXT = Regex("[0-9/\\\\`$(){}\\[\\]<>=_@#|&;*~^%+-]|https?:|www\\.|\\w\\.\\w{1,5}\\b")
private val WORD_SPLIT = Regex("[^\\p{L}]+")

/** True bila [message] hanya obrolan singkat yang aman dijawab tanpa alat dan tanpa riwayat tugas. */
internal fun isLightChat(message: String): Boolean {
    val t = message.trim()
    if (t.isEmpty() || t.length > LIGHT_MAX_CHARS || '\n' in t) return false
    if (NOT_PLAIN_TEXT.containsMatchIn(t)) return false
    val words = t.lowercase().split(WORD_SPLIT).filter { it.isNotEmpty() }
    if (words.isEmpty() || words.size > LIGHT_MAX_WORDS) return false
    if (words.any { it in FOLLOW_UP_WORDS || it in CONTEXT_WORDS }) return false
    if (words.any { w -> ACTION_STEMS.any { w.contains(it) } }) return false
    return true
}

/**
 * Pesan ini boleh lewat jalur sapaan? Sapaan baku selalu; obrolan ringan hanya di model lokal (di penyedia internet
 * jalur agen penuh cepat, dan riwayat lebih berharga), dan hanya bila AI tidak sedang menunggu jawaban atas pertanyaannya.
 */
internal fun useLightPath(message: String, local: Boolean, lastAiAskedQuestion: Boolean): Boolean =
    isSimpleGreeting(message) || (local && !lastAiAskedQuestion && isLightChat(message))

/** Giliran model terakhir diakhiri tanda tanya = pengguna kemungkinan sedang menjawabnya. */
internal fun endsWithQuestion(lastAiText: String?): Boolean = lastAiText?.trimEnd()?.endsWith("?") == true

// ---- Mode obrolan model lokal (selalu aktif, tanpa saklar): semua pesan jadi obrolan murni, tanpa alat.
// Tujuannya memangkas waktu baca prompt di CPU HP: prompt sistem pendek, tanpa definisi alat, riwayat hanya beberapa pesan
// terakhir (teks saja), dan panjang jawaban dibatasi. Murni Kotlin supaya bisa diuji sebagai tes JVM.

internal const val LOCAL_CHAT_PREF = "local_chat_mode"
internal const val LOCAL_CHAT_MAX_TOKENS = 400
private const val CHAT_WINDOW_MAX = 8
private const val CHAT_WINDOW_MIN = 4
private const val CHAT_WINDOW_STEP = 4
private const val CHAT_MSG_MAX_CHARS = 600

/** Batas jawaban per giliran agen di model lokal (bukan mode obrolan): mencegah satu giliran menulis berpuluh menit. */
internal const val LOCAL_AGENT_MAX_TOKENS = 1500

/**
 * Indeks pesan pertama yang dikirim dari [total] pesan teks sebelumnya. Jendela melompat per blok (4 pesan), bukan bergeser
 * satu per giliran: awalan prompt tetap sama beberapa giliran sehingga cache Ollama terpakai. Hasilnya 4 sampai 8 pesan.
 */
internal fun chatWindowStart(total: Int): Int =
    if (total <= CHAT_WINDOW_MAX) 0 else ((total - CHAT_WINDOW_MIN - 1) / CHAT_WINDOW_STEP) * CHAT_WINDOW_STEP

/** Prompt sistem ringkas untuk mode obrolan. Menyebut nama model asli supaya model kecil tidak mengarang jati dirinya. */
internal fun localChatSystem(model: String, sapaan: String?): String {
    val m = model.replace(Regex("[\"'\\r\\n]"), " ").trim().take(60).ifBlank { "model lokal" }
    return "Kamu asisten AI berbahasa Indonesia. Kamu berjalan sebagai model bahasa kecil lokal ($m) lewat Ollama di HP pengguna, di dalam aplikasi Mythos. " +
        "Jawab singkat dan jelas, paling banyak 3 kalimat kecuali diminta lebih panjang. " +
        "Di mode ini kamu tidak punya alat: jangan menjalankan perintah atau menulis file. " +
        "Bila ditanya tentang dirimu, sebut hanya fakta di atas dan jangan mengarang; bila tidak tahu, katakan tidak tahu. " +
        "Bila pengguna butuh pekerjaan (membuat atau mengubah file, menjalankan perintah), sarankan memakai AI internet (Gemini, Groq, OpenRouter, dan lainnya) di Pengaturan. " +
        "Bila pesan pengguna diawali blok [Ingatan dari obrolan lama], itu catatan obrolan sebelumnya: pakai bila relevan, jangan mengarang di luar itu. " +
        sapaanInstruction(sapaan)
}

/**
 * Konteks untuk mode obrolan: beberapa pesan teks terakhir dari [prev] (riwayat SEBELUM pesan baru ditambahkan), lalu [typed].
 * Hasil alat, panggilan alat, dan pesan galat dibuang; tiap pesan dipotong; selalu diawali pesan pengguna.
 */
internal fun localChatContext(prev: List<Hist>, typed: String, memory: String = ""): List<Hist> {
    val all = prev.filter { (it.role == USER || it.role == AI) && it.calls.isEmpty() && it.text.isNotBlank() && !it.text.startsWith("Error:") }
    val turns = all
        .drop(chatWindowStart(all.size))
        .map { Hist(it.role, it.text.take(CHAT_MSG_MAX_CHARS)) }
        .dropWhile { it.role != USER }
    return turns + Hist(USER, ChatMemory.withMemory(memory, typed))
}
