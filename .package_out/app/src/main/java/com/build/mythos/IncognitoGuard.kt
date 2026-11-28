package com.build.mythos

import java.io.File

// Pengaman Chat samaran.
// - Peringatan sekali per sesi samaran saat agen menulis file di folder kerja (file itu TIDAK ikut terhapus).
// - Sapuan saat aplikasi dibuka: pratinjau gambar di filesDir/chat_images yang tidak dipakai chat mana pun
//   (sisa Chat samaran bila aplikasi dimatikan paksa sebelum keluar dari mode itu) dibuang.
// Logika murni ada di [IncognitoGuard] supaya bisa dites di JVM.

internal object IncognitoGuard {
    /** Berkas yang lebih baru dari ini dibiarkan: bisa saja baru dibuat saat sapuan berjalan. */
    const val GRACE_MS = 120_000L

    fun shouldWarnAboutWrite(incognito: Boolean, alreadyWarned: Boolean): Boolean = incognito && !alreadyWarned

    fun writeNotice(path: String): String =
        "Chat samaran aktif, tetapi file ini ditulis ke folder kerja dan TIDAK ikut terhapus saat Anda keluar dari mode ini:\n$path"

    /**
     * Nama berkas di chat_images yang tidak disebut chat mana pun dan sudah lewat masa tenggang.
     * [files] berisi pasangan (nama berkas, waktu ubah terakhir). [referenced] boleh berisi path penuh; hanya nama berkasnya
     * yang dibandingkan (nama pratinjau berupa UUID).
     */
    fun orphanImageNames(
        files: List<Pair<String, Long>>,
        referenced: Collection<String>,
        now: Long,
        graceMs: Long = GRACE_MS
    ): List<String> {
        val keep = referenced.map { it.substringAfterLast('/') }.toSet()
        return files.filter { (name, modified) -> name !in keep && now - modified >= graceMs }.map { it.first }
    }
}


/**
 * Buang pratinjau gambar yatim. Dijalankan di latar saat aplikasi dibuka.
 * Bila satu file chat gagal dibaca, sapuan dibatalkan sama sekali: lebih baik menyisakan berkas daripada menghapus gambar milik chat.
 */
internal fun MainActivity.sweepOrphanImages() {
    bg {
        try {
            val dir = File(filesDir, "chat_images")
            val files = dir.listFiles()?.filter { it.isFile } ?: return@bg
            if (files.isEmpty()) return@bg
            val referenced = ArrayList<String>()
            val chatFiles = chatsDir().listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != CHAT_INDEX } ?: return@bg
            for (f in chatFiles) {
                referenced += ChatStore.imagePaths(ChatStore.parseFile(readChatText(f), f.nameWithoutExtension, f.lastModified()).messages)
            }
            // Gambar milik chat yang sedang terbuka di layar ikut dilindungi, walau belum tertulis ke disk.
            synchronized(hist) {
                for (h in hist) for (im in h.images) {
                    referenced += im.path
                    im.previewPath?.let { referenced += it }
                }
            }
            val orphans = IncognitoGuard.orphanImageNames(
                files.map { it.name to it.lastModified() }, referenced, System.currentTimeMillis()
            )
            if (orphans.isNotEmpty()) deleteChatImagePaths(orphans.map { File(dir, it).absolutePath })
        } catch (e: Exception) {
        }
    }
}
