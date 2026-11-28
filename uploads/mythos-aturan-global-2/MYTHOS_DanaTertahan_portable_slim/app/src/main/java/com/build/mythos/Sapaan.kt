package com.build.mythos

/** Sebutan bawaan untuk pengguna. Bisa diganti lewat preferensi "sapaan" tanpa mengubah kode lain. */
internal const val SAPAAN_BAWAAN = "BOS"

/** Instruksi sapaan untuk prompt sistem. Murni Kotlin supaya bisa diuji sebagai tes JVM. */
internal fun sapaanInstruction(nama: String?): String {
    // Buang tanda kutip dan baris baru supaya nilai preferensi tidak bisa menyisipkan instruksi lain.
    val n = (nama ?: "").replace(Regex("[\"'\\r\\n]"), " ").trim().take(30).ifBlank { SAPAAN_BAWAAN }
    return "Panggil pengguna dengan sebutan \"$n\" di setiap balasan teks (misalnya pada sapaan dan penutup), singkat dan wajar. " +
        "Sebutan ini hanya untuk percakapan: jangan dipakai di dalam perintah shell, isi file, kode, nama berkas, atau pesan commit. " +
        EMOJI_RULE
}

/** Aturan emoji untuk semua prompt sistem: alat komunikasi yang relevan, tidak berlebihan, tidak masuk ke kode/perintah. */
internal const val EMOJI_RULE =
    "Emoji adalah alat komunikasi, bukan dekorasi: maks 1 emoji natural saat santai; tanpa emoji untuk bahasan serius, teknis, error, atau formal; " +
    "maks 2 emoji fungsional (✅, ⚠️, ❌, 📌) untuk panduan/status tanpa berderet. Jangan pakai emoji di perintah shell, kode, isi/nama file, atau commit."

/** Nama yang tampil pada sapaan layar kosong dan avatar drawer. Bisa diganti lewat preferensi "nama_tampil". */
internal const val NAMA_TAMPIL_BAWAAN = "Jefri Nicholee"

/** Kalimat sapaan di layar kosong. */
internal fun sapaanKosong(nama: String): String = "Halo, ${nama.trim().ifBlank { NAMA_TAMPIL_BAWAAN }}"

/** Aturan balasan sapaan: sapaan dibalas sapaan yang sama; salam keagamaan hanya bila pengguna memakainya. */
internal const val GREETING_RULE =
    "Balas sapaan dengan sapaan yang sama (\"Halo\" dibalas \"Halo\"); salam keagamaan hanya dibalas bila pengguna memakainya."

/**
 * Teks bawaan untuk uji coba kirim pesan (email, WhatsApp, Telegram) saat pengguna hanya menyuruh "tes kirim"
 * tanpa menyebut isinya. Singkat, jelas, profesional. Murni Kotlin supaya bisa diuji sebagai tes JVM.
 */
internal fun pesanTesBawaan(nama: String?): String {
    // Buang tanda kutip dan baris baru supaya nama dari preferensi tidak bisa menyisipkan instruksi lain.
    val n = (nama ?: "").replace(Regex("[\"'\\r\\n]"), " ").trim().take(40).ifBlank { NAMA_TAMPIL_BAWAAN }
    return "Halo,\nSaya Mythos AI, asisten pribadi $n.\nPesan ini adalah uji coba pengiriman otomatis; tidak perlu dibalas."
}

/** Subjek bawaan untuk uji coba email. */
internal const val SUBJEK_TES_BAWAAN = "Uji coba pesan dari Mythos AI"
