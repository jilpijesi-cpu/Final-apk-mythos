package com.build.mythos

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Membaca keluaran Termux selama proses aktif.
 * Mode snapshot mempertahankan perilaku lama. Mode incremental membaca berdasarkan offset byte,
 * lalu mengirim hanya teks baru; mode ini digunakan oleh streaming Codex agar output panjang tidak
 * terpotong oleh batas hasil satu panggilan Termux dan supaya potongan tidak ditambahkan dua kali.
 */
internal class LiveTail(
    private val act: MainActivity,
    private val path: String,
    private val pollMs: Long = POLL_MS,
    private val maxChars: Int = 8000,
    private val incremental: Boolean = false,
    private val deliverPendingOnStop: Boolean = false,
    /** Untuk stream protokol: konsumsi tiap potongan berurutan di worker agar batas item tidak tertukar. */
    private val callbackOnMainThread: Boolean = true,
    /** Dipanggil true saat pembacaan gagal berturut-turut (macet), false saat pulih. Tidak untuk mode snapshot. */
    private val onStall: ((Boolean) -> Unit)? = null,
    private val onText: (String) -> Unit
) {
    @Volatile private var running = false
    private var job: Job? = null
    private val readLock = Any()
    private var byteOffset = 0L
    private var lastSnapshot = ""
    private val utf8 = Utf8StreamAccumulator()
    // Jumlah pembacaan gagal berturut-turut; diubah hanya sambil memegang readLock.
    private var failStreak = 0
    private var stalled = false

    fun start() {
        synchronized(readLock) {
            byteOffset = 0L
            lastSnapshot = ""
            utf8.reset()
            failStreak = 0
            stalled = false
        }
        running = true
        job = BackgroundScope.scope.launch {
            while (running && isActive) {
                try {
                    if (incremental) {
                        val chunk = runInterruptible { readIncrementalChunk() }
                        if (chunk.isNotEmpty()) {
                            if (callbackOnMainThread) act.runOnUiThread { if (running || deliverPendingOnStop) onText(chunk) }
                            else if (running || deliverPendingOnStop) onText(chunk)
                        }
                    } else {
                        val r = runInterruptible {
                            act.termux(
                                "tail -c ${maxChars.coerceIn(1000, 100000)} ${act.sh(path)} 2>/dev/null",
                                limit = maxChars.coerceIn(1000, 100000), timeoutSec = 10,
                                useWorkdir = false, killable = false, silentRetry = true
                            )
                        }
                        if (running && r.startsWith("exit=0")) {
                            val text = r.substringAfter("\n")
                            if (text != lastSnapshot) {
                                lastSnapshot = text
                                act.runOnUiThread { if (running) onText(text) }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Tampilan live hanya pelengkap; kegagalan baca tidak boleh menghentikan perintah.
                }
                delay(pollMs)
            }
        }
    }

    /** Teks lengkap yang sudah dibaca. Tetap tersedia setelah finish/stop untuk menyimpan jawaban utuh. */
    fun snapshot(): String = synchronized(readLock) { utf8.snapshot() }

    /** Hentikan polling dan baca sisa bytes sebelum file sementara dibersihkan. */
    fun finish() {
        running = false
        val priorJob = job
        priorJob?.cancel()
        job = null
        // Jangan mulai membaca sisa file sebelum callback potongan lama selesai diproses. Ini penting
        // untuk stream event berurutan: tanpa join, delta terakhir bisa diproses sesudah event item berikutnya.
        if (priorJob != null) {
            try { kotlinx.coroutines.runBlocking { priorJob.join() } } catch (e: Exception) { logQuiet("LiveTail.kt", e) }
        }
        if (incremental) {
            synchronized(readLock) {
                // Biasanya hanya satu iterasi yang diperlukan. Loop juga menampung hasil yang lebih besar
                // dari satu chunk tanpa bergantung pada batas output stdout dari RUN_COMMAND.
                var attempts = 0
                while (attempts++ < 10000) {
                    val chunk = readIncrementalChunkLocked()
                    if (chunk.isEmpty()) break
                    if (callbackOnMainThread) act.runOnUiThread { onText(chunk) } else onText(chunk)
                }
                val tail = utf8.finish()
                if (tail.isNotEmpty()) {
                    if (callbackOnMainThread) act.runOnUiThread { onText(tail) } else onText(tail)
                }
            }
        }
        cleanupFile()
    }

    /** Hentikan pembacaan tanpa flush sinkron; sesuai untuk log/preview yang bukan isi jawaban. */
    fun stop() {
        running = false
        job?.cancel()
        job = null
        cleanupFile()
    }

    private fun readIncrementalChunk(): String = synchronized(readLock) { readIncrementalChunkLocked() }

    /** Harus dipanggil sambil memegang readLock. Data dikirim base64 agar pemotongan UTF-8 tidak merusak karakter. */
    private fun readIncrementalChunkLocked(): String {
        val chunkFile = "$path.readchunk"
        val start = byteOffset + 1L
        val command = "if [ -f ${act.sh(path)} ]; then tail -c +$start ${act.sh(path)} | head -c $CHUNK_BYTES > ${act.sh(chunkFile)}; else : > ${act.sh(chunkFile)}; fi; " +
            "n=\$(wc -c < ${act.sh(chunkFile)} | tr -d ' '); printf '%s\\n' \"\$n\"; " +
            "base64 ${act.sh(chunkFile)} | tr -d '\\n'; printf '\\n'; rm -f ${act.sh(chunkFile)}"
        val result = try {
            act.termux(command, limit = 80000, timeoutSec = 10, useWorkdir = false, killable = false, silentRetry = true)
        } catch (_: Exception) {
            return readFailed()
        }
        if (!result.startsWith("exit=0")) return readFailed()
        val payload = result.substringAfter('\n', "")
        val lengthText = payload.substringBefore('\n').trim()
        val count = lengthText.toLongOrNull() ?: return readFailed()
        if (count <= 0L) { readOk(); return "" }   // belum ada data baru: bukan kegagalan
        val encoded = payload.substringAfter('\n', "").trim()
        val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: Exception) { return readFailed() }
        if (bytes.isEmpty() || bytes.size.toLong() != count) return readFailed()
        readOk()
        // Offset bergerak berdasarkan jumlah byte dari file, bukan jumlah karakter UTF-16.
        byteOffset += count
        return utf8.append(bytes)
    }

    /** Catat kegagalan baca; setelah [STALL_AFTER] kali berturut-turut laporkan macet satu kali. */
    private fun readFailed(): String {
        failStreak++
        if (failStreak >= STALL_AFTER && !stalled) {
            stalled = true
            onStall?.let { cb -> act.runOnUiThread { cb(true) } }
        }
        return ""
    }

    private fun readOk() {
        failStreak = 0
        if (stalled) {
            stalled = false
            onStall?.let { cb -> act.runOnUiThread { cb(false) } }
        }
    }

    private fun cleanupFile() {
        bg {
            try {
                act.termux(
                    "rm -f ${act.sh(path)} ${act.sh("$path.readchunk")}",
                    limit = 200, timeoutSec = 10, useWorkdir = false, killable = false, silentRetry = true
                )
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val POLL_MS = 1500L
        private const val CHUNK_BYTES = 49152
        private const val STALL_AFTER = 15
    }
}
