package com.build.mythos

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Penyimpanan memori obrolan terenkripsi (ChatCrypt/Keystore) di filesDir/memory/chat_memory.json. Semua tetap di perangkat;
// hanya dibaca oleh mode obrolan model lokal (di HP juga), jadi isinya tidak dikirim ke AI internet. Ekstensi MainActivity; logikanya ada di ChatMemory.kt.

private val memLock = Any()

// Mirroring plaintext ke Termux dinonaktifkan secara default agar tidak membocorkan isi chat terenkripsi keluar sandbox.
private const val TERMUX_MEMORY_MIRROR_ENABLED = false
private val mirrorLock = Any()
private var mirrorBusy = false
private var lastMirrorAt = 0L
private var lastMirrorHash = 0
private val restoredThisProcess = AtomicBoolean(false)
private val clearEpoch = AtomicLong(0L)
// Cadangan baru boleh ditimpa setelah isi lamanya selesai dibaca dan digabung; kalau tidak, memori baru yang masih sedikit bisa menimpa cadangan yang lengkap.
@Volatile private var restoreDone = false
private const val MIRROR_MIN_GAP_MS = 60_000L

internal fun MainActivity.memoryMirrorPath(): String = "${workspaceBase()}/memory/chat_memory.json"

internal fun MainActivity.memoryFile(): File = File(File(filesDir, "memory").also { it.mkdirs() }, "chat_memory.json")

private fun MainActivity.loadMemoryRaw(): List<MemoryEntry> {
    val f = memoryFile()
    if (!f.exists()) return emptyList()
    return try {
        val raw = f.readText()
        if (raw.startsWith("enc1:")) {
            ChatMemory.fromJson(readChatText(f))
        } else {
            // Migrasi aman: berkas lama masih plaintext, baca lalu tulis ulang terenkripsi dengan Android Keystore.
            val entries = ChatMemory.fromJson(raw)
            if (entries.isNotEmpty()) {
                try { writeChatText(f, ChatMemory.toJson(entries)) } catch (e: Exception) { logQuiet("ChatMemoryStore.kt", e) }
            }
            entries
        }
    } catch (e: Exception) {
        logQuiet("ChatMemoryStore.kt", e)
        emptyList()
    }
}

private fun MainActivity.saveMemoryRaw(list: List<MemoryEntry>) {
    try {
        writeChatText(memoryFile(), ChatMemory.toJson(list))
    } catch (e: Exception) { logQuiet("ChatMemoryStore.kt", e) }
}

internal fun MainActivity.memoryCount(): Int = synchronized(memLock) { loadMemoryRaw().size }

internal fun MainActivity.clearMemory() {
    synchronized(memLock) {
        clearEpoch.incrementAndGet()
        try { memoryFile().delete() } catch (e: Exception) { logQuiet("ChatMemoryStore.kt", e) }
    }
    // Cadangan lama ikut dihapus agar memori yang sengaja dihapus tidak pulih kembali.
    synchronized(mirrorLock) { lastMirrorHash = 0 }
    bg {
        try {
            val paths = listOf(memoryMirrorPath(), "$termuxHome/MYTHOS/memory/chat_memory.json").distinct()
            val blocked = referencePathsGuard(paths)
            if (blocked != null) {
                logQuiet("ChatMemoryStore.kt", IllegalStateException("Penghapusan cadangan memori diblokir: $blocked"))
                return@bg
            }
            termux("rm -f " + paths.joinToString(" ") { sh(it) }, useWorkdir = false)
        } catch (e: Exception) { logQuiet("ChatMemoryStore.kt", e) }
    }
}

/** Mirroring memori plaintext ke Termux dinonaktifkan secara default untuk menjaga jaminan enkripsi ChatCrypt. */
internal fun MainActivity.mirrorMemoryAsync() {
    if (!TERMUX_MEMORY_MIRROR_ENABLED || incognito || !restoreDone) return
    val now = System.currentTimeMillis()
    synchronized(mirrorLock) {
        if (mirrorBusy || now - lastMirrorAt < MIRROR_MIN_GAP_MS) return
        mirrorBusy = true
    }
    bg {
        try {
            val json = synchronized(memLock) { ChatMemory.toJson(loadMemoryRaw()) }
            val h = json.hashCode()
            val prevHash = synchronized(mirrorLock) { lastMirrorHash }
            if (h != prevHash && ChatMemory.fromJson(json).isNotEmpty()) {
                val r = writeFileChunked(memoryMirrorPath(), json)
                if (r.trimEnd().endsWith("OK")) synchronized(mirrorLock) { lastMirrorHash = h }
            }
        } catch (e: Exception) {
            logQuiet("ChatMemoryStore.kt", e)
        } finally {
            synchronized(mirrorLock) { mirrorBusy = false; lastMirrorAt = System.currentTimeMillis() }
        }
    }
}

/** Sekali per proses: gabungkan cadangan di Termux ke memori perangkat (mengisi kembali setelah pasang ulang). Bila Termux belum menjawab, dicoba lagi saat aplikasi dibuka berikutnya. */
internal fun MainActivity.restoreMemoryFromMirrorOnce() {
    if (!restoredThisProcess.compareAndSet(false, true)) return
    val epoch = clearEpoch.get()
    bg {
        try {
            var text = readOld(memoryMirrorPath())
            if (text == null) { restoredThisProcess.set(false); return@bg } // tidak terbaca: jangan anggap selesai
            if (text.isBlank() && memoryMirrorPath() != "$termuxHome/MYTHOS/memory/chat_memory.json") {
                val legacy = readOld("$termuxHome/MYTHOS/memory/chat_memory.json")
                if (legacy == null) { restoredThisProcess.set(false); return@bg }
                if (legacy.isNotBlank()) text = legacy
            }
            if (text.isNotBlank()) {
                val remote = ChatMemory.fromJson(text)
                if (remote.isNotEmpty()) synchronized(memLock) {
                    if (clearEpoch.get() != epoch) return@synchronized
                    val local = loadMemoryRaw()
                    val merged = ChatMemory.merge(local, remote, System.currentTimeMillis())
                    if (ChatMemory.toJson(merged) != ChatMemory.toJson(local)) saveMemoryRaw(merged)
                }
            }
            restoreDone = true
        } catch (e: Exception) {
            restoredThisProcess.set(false)
            logQuiet("ChatMemoryStore.kt", e)
        }
    }
}

/** Simpan pertukaran terakhir (pertanyaan pengguna dan jawaban akhir AI) dari semua penyedia. Chat samaran tidak menyimpan apa pun. */
internal fun MainActivity.rememberLastExchange() {
    if (incognito) return
    val (u, a) = synchronized(hist) { ChatMemory.lastExchange(ArrayList(hist)) } ?: return
    synchronized(memLock) {
        val before = loadMemoryRaw()
        val after = ChatMemory.add(before, u, a, System.currentTimeMillis())
        if (after !== before) saveMemoryRaw(after)
    }
    mirrorMemoryAsync()
}

/** Blok ingatan yang cocok dengan [query] untuk mode obrolan model lokal; kosong bila tidak ada yang relevan. */
internal fun MainActivity.recallBlock(query: String): String {
    if (incognito) return ""
    val found = synchronized(memLock) { ChatMemory.recall(loadMemoryRaw(), query, System.currentTimeMillis()) }
    if (found.isEmpty()) return ""
    // Hitung pemakaian di latar supaya tidak menahan pengiriman pesan.
    bg { synchronized(memLock) { saveMemoryRaw(ChatMemory.markUsed(loadMemoryRaw(), found)) } }
    return ChatMemory.format(found)
}

private val memoryImportRunning = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * F-04: impor riwayat chat lama ke memori secara idempoten dengan status belum mulai / berjalan / selesai.
 * Flag "done" / "memory_imported" HANYA disimpan setelah seluruh berkas berhasil diproses dan disimpan.
 */
internal fun MainActivity.importOldChatsOnce() {
    val state = prefs.getString("memory_import_state", null)
    if (state == "done" || (state == null && prefs.getBoolean("memory_imported", false))) return
    if (!memoryImportRunning.compareAndSet(false, true)) return
    prefs.edit().putString("memory_import_state", "in_progress").apply()
    val epoch = clearEpoch.get()
    bg {
        try {
            var list = synchronized(memLock) { loadMemoryRaw() }
            val files = chatsDir().listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != CHAT_INDEX }.orEmpty()
            val failedFiles = ArrayList<String>()
            for (f in files.sortedBy { it.lastModified() }) {
                try {
                    val id = f.name.removeSuffix(".json")
                    if (ChatStore.safeId(id) == null) continue
                    val cf = ChatStore.parseFile(readChatText(f), id, f.lastModified())
                    for ((u, a) in ChatMemory.pairsFrom(cf.messages)) list = ChatMemory.add(list, u, a, cf.meta.updated)
                } catch (e: Exception) {
                    failedFiles.add(f.name)
                    logQuiet("ChatMemoryStore.kt", e)
                }
            }
            var saved = false
            synchronized(memLock) {
                if (clearEpoch.get() == epoch) {
                    saveMemoryRaw(ChatMemory.merge(loadMemoryRaw(), list, System.currentTimeMillis()))
                    saved = true
                }
            }
            if (saved && failedFiles.isEmpty()) {
                prefs.edit()
                    .putString("memory_import_state", "done")
                    .putBoolean("memory_imported", true)
                    .remove("memory_import_failed")
                    .apply()
                mirrorMemoryAsync()
            } else {
                prefs.edit()
                    .putString("memory_import_state", "pending")
                    .putBoolean("memory_imported", false)
                    .putStringSet("memory_import_failed", failedFiles.toSet())
                    .apply()
            }
        } catch (e: Exception) {
            prefs.edit()
                .putString("memory_import_state", "pending")
                .putBoolean("memory_imported", false)
                .apply()
            logQuiet("ChatMemoryStore.kt", e)
        } finally {
            memoryImportRunning.set(false)
        }
    }
}
