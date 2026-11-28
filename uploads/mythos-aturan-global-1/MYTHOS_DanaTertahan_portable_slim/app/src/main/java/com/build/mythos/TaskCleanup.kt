package com.build.mythos

import java.io.File

// Salinan lampiran (di ruang kerja Termux dan preview di cache aplikasi).
// Dulu dihapus otomatis setiap tugas selesai, sehingga agen "lupa" file/foto yang sudah dikirim pada pesan berikutnya.
// Sekarang releaseTaskCopies() hanya dipanggil saat chat dibuang (resetChatState dengan saveOld = false);
// chat yang disimpan dan bisa dibuka lagi mempertahankan lampirannya. Penghapusan chat memanggil purgeChatAttachments().

private val pendingTermuxCopies: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

/** Catat salinan milik tugas yang baru dikirim. Dipanggil saat pesan dengan lampiran dikirim. */
internal fun registerTaskCopies(termuxPaths: List<String>) {
    pendingTermuxCopies.addAll(termuxPaths)
}

/** Hapus semua salinan yang tercatat, lepaskan gambar dari riwayat, lalu simpan riwayat. */
internal fun MainActivity.releaseTaskCopies() {
    val images = synchronized(hist) { hist.flatMap { it.images } }
    val allowedPrefixes = listOf("${workspaceRoot()}/attachments/", "$termuxHome/MYTHOS/attachments/").distinct()
    val pendingSnapshot = synchronized(pendingTermuxCopies) {
        val snap = pendingTermuxCopies.toList()
        pendingTermuxCopies.clear()
        snap
    }
    val termuxPaths = (pendingSnapshot + images.map { it.path })
        .filter { path -> allowedPrefixes.any { path.startsWith(it) } && !path.contains("..") }.distinct()
    if (termuxPaths.isEmpty() && images.none { !it.released }) return
    images.forEach { it.released = true; it.cachedPayload = null }
    bg {
        if (termuxPaths.isNotEmpty()) {
            try {
                val blocked = referencePathsGuard(termuxPaths)
                if (blocked != null) throw IllegalStateException("Pembersihan lampiran diblokir: $blocked")
                termux("rm -f -- " + termuxPaths.joinToString(" ") { sh(it) }, limit = 500, timeoutSec = 30)
            } catch (ignored: Exception) { logQuiet("TaskCleanup.kt", ignored) }
        }
        saveHist()
    }
}
