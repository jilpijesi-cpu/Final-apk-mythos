package com.build.mythos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Kerja latar sekali jalan (jaringan, Termux, berkas) di Dispatchers.IO, pengganti `Thread { ... }.start()`.
 * Lingkup berumur sepanjang proses, sama seperti thread lepas sebelumnya; kegagalan satu tugas tidak
 * membatalkan tugas lain (SupervisorJob). Blok boleh memblokir. Untuk pekerjaan yang dibatalkan lewat
 * Thread.interrupt() pakai [bgCancellable] (AgentLoop) atau Job sendiri (LiveTail), bukan ini.
 */
internal object BackgroundScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

internal fun bg(block: () -> Unit): Job = BackgroundScope.scope.launch { block() }

/**
 * Menjalankan [work] (boleh memblokir) sebagai Job yang bisa dibatalkan dengan `cancel()`: thread yang sedang
 * memblokir diinterupsi lewat runInterruptible, sehingga Thread.sleep/IO terputus seperti pada Thread.interrupt().
 * [onError] menerima semua Throwable dari [work], termasuk CancellationException bila dibatalkan. [onFinally]
 * SELALU dijalankan sekali, juga bila Job dibatalkan sebelum [work] sempat mulai (start ATOMIC), jadi pembersihan
 * status UI tidak pernah terlewat. Kegagalan di [onError]/[onFinally] sendiri ditelan di [onError] saja; [onFinally]
 * harus menangani galatnya sendiri.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class) // CoroutineStart.ATOMIC dipakai sengaja, alasannya di KDoc di atas
internal fun bgCancellable(onError: (Throwable) -> Unit, onFinally: () -> Unit, work: () -> Unit): Job =
    BackgroundScope.scope.launch(start = CoroutineStart.ATOMIC) {
        var completed = false
        try {
            runInterruptible { work(); completed = true }
        } catch (e: Throwable) {
            // Kerja sudah selesai normal tetapi Job keburu dibatalkan: runInterruptible melempar CancellationException
            // saat kembali. Itu bukan pembatalan di tengah jalan, jadi jangan dilaporkan sebagai galat/dihentikan.
            if (!(completed && e is CancellationException)) {
                try { onError(e) } catch (ignored: Throwable) { logQuiet("Background.kt", ignored) }
            }
        } finally {
            onFinally()
        }
    }
