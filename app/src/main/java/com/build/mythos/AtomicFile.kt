package com.build.mythos

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Tulis teks ke [target] secara atomik: ke berkas sementara UNIK di direktori yang sama, di-sync ke disk, lalu
 * dipindahkan menimpa target. Gagal (disk penuh, rename gagal) => exception DILEMPAR, target lama tidak tersentuh,
 * dan berkas sementara dihapus. Pemanggil wajib menangani exception-nya (jangan ditelan diam-diam).
 */
internal fun atomicWriteText(target: File, text: String) {
    val dir = target.absoluteFile.parentFile ?: throw java.io.IOException("Direktori target tidak ada: $target")
    dir.mkdirs()
    val tmp = File.createTempFile(target.name + ".", ".tmp", dir)
    try {
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Throwable) {
        tmp.delete()
        throw e
    }
}

/** Hapus sisa berkas sementara (*.tmp) yang lebih tua dari [olderThanMs] di [dir] (sisa proses yang mati di tengah tulis). */
internal fun cleanStaleTemps(dir: File, olderThanMs: Long = 60 * 60 * 1000L, now: Long = System.currentTimeMillis()): Int {
    var n = 0
    dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }?.forEach { f ->
        if (now - f.lastModified() >= olderThanMs && f.delete()) n++
    }
    return n
}
