package com.build.mythos

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Riwayat percakapan: tiap chat satu file di filesDir/chats (history.json lama dipindahkan otomatis).
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.histFile() = File(filesDir, "history.json")

internal fun MainActivity.callJson(c: Call) = JSONObject().put("id", c.id).put("name", c.name).put("args", c.args)

internal fun MainActivity.callFrom(o: JSONObject) =
    Call(o.optString("id"), o.optString("name"), o.optJSONObject("args") ?: JSONObject())

internal fun MainActivity.histToJson(h: Hist): JSONObject {
    val o = JSONObject().put("role", h.role).put("text", h.text)
    if (h.calls.isNotEmpty()) {
        val a = JSONArray()
        for (c in h.calls) a.put(callJson(c))
        o.put("calls", a)
    }
    if (h.results.isNotEmpty()) {
        val a = JSONArray()
        for ((c, r) in h.results) a.put(JSONObject().put("call", callJson(c)).put("out", r))
        o.put("results", a)
    }
    h.raw?.let { o.put("raw", it) }
    if (h.images.isNotEmpty()) {
        val images = JSONArray()
        h.images.forEach { images.put(JSONObject().put("path", it.path).put("preview", it.previewPath ?: "").put("released", it.released)) }
        o.put("images", images)
    }
    if (h.files.isNotEmpty()) {
        val files = JSONArray()
        h.files.forEach { files.put(JSONObject().put("rel", it.rel).put("name", it.name).put("size", it.size)) }
        o.put("files", files)
    }
    h.trail?.takeIf { it.length() > 0 }?.let { o.put("trail", it) }
    return o
}

internal fun MainActivity.histFromJson(o: JSONObject): Hist {
    val calls = ArrayList<Call>()
    o.optJSONArray("calls")?.let { a ->
        for (i in 0 until a.length()) calls.add(callFrom(a.getJSONObject(i)))
    }
    val results = ArrayList<Pair<Call, String>>()
    o.optJSONArray("results")?.let { a ->
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            results.add(Pair(callFrom(r.getJSONObject("call")), r.optString("out")))
        }
    }
    val images = ArrayList<HistImage>()
    o.optJSONArray("images")?.let { a ->
        for (i in 0 until a.length()) {
            val item = a.opt(i)
            if (item is JSONObject) {
                val path = item.optString("path")
                if (path.isNotBlank()) images.add(HistImage(path, item.optString("preview").ifBlank { null }).also { it.released = item.optBoolean("released") })
            } else {
                val path = a.optString(i)
                if (path.isNotBlank()) images.add(HistImage(path))
            }
        }
    }
    val files = ArrayList<HistFile>()
    o.optJSONArray("files")?.let { a ->
        for (i in 0 until a.length()) {
            val f = a.optJSONObject(i) ?: continue
            val rel = f.optString("rel")
            if (rel.isNotBlank()) files.add(HistFile(rel, f.optString("name").ifBlank { rel.substringAfterLast('/') }, f.optLong("size")))
        }
    }
    return Hist(o.optInt("role"), o.optString("text"), calls, results, o.optJSONObject("raw"), images, files, o.optJSONArray("trail"))
}

// ------------------------------------------------------------------ Penyimpanan banyak chat
// Tiap chat satu file: filesDir/chats/<id>.json; filesDir/chats/index.json menyimpan ringkasannya untuk drawer.

internal const val CHAT_INDEX = "index.json"

private val chatStoreLock = Any()
private val deletedChatIds = HashSet<String>()

internal fun MainActivity.chatsDir(): File = File(filesDir, "chats").also { it.mkdirs() }

internal fun MainActivity.chatFile(id: String): File =
    File(chatsDir(), (ChatStore.safeId(id) ?: throw IllegalArgumentException("id chat tidak valid")) + ".json")

/** Id chat yang sedang dibuka; dibuat saat pertama dibutuhkan. */
internal fun MainActivity.currentChatId(): String = synchronized(chatStoreLock) {
    val saved = prefs.getString("chat_id", "").orEmpty()
    if (ChatStore.safeId(saved) != null) return@synchronized saved
    val id = ChatStore.newId(System.currentTimeMillis())
    prefs.edit().putString("chat_id", id).apply()
    id
}

private fun MainActivity.rebuildIndex(): List<ChatMeta> = synchronized(chatStoreLock) {
    val out = ArrayList<ChatMeta>()
    chatsDir().listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != CHAT_INDEX }?.forEach { f ->
        try {
            val id = f.name.removeSuffix(".json")
            if (ChatStore.safeId(id) != null) out.add(ChatStore.parseFile(readChatText(f), id, f.lastModified()).meta)
        } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
    }
    val sorted = out.sortedByDescending { it.updated }
    writeIndex(sorted)
    sorted
}

@Volatile private var chatIndexDirty = false

private fun MainActivity.writeIndex(list: List<ChatMeta>) = synchronized(chatStoreLock) {
    try {
        writeChatText(File(chatsDir(), CHAT_INDEX), ChatStore.indexToJson(list))
        chatIndexDirty = false
        prefs.edit().putBoolean("chat_index_dirty", false).apply()
    } catch (e: Exception) {
        // F-05: tandai indeks dirty agar pembacaan berikutnya memaksa rebuild dari berkas chat di disk.
        chatIndexDirty = true
        prefs.edit().putBoolean("chat_index_dirty", true).apply()
        logQuiet("ChatHistory.kt", e)
        notifyPersistFailed(e)
    }
}

/** Daftar chat tersimpan, terbaru dulu. Rebuild otomatis bila indeks dirty, rusak, atau tidak sinkron dengan disk (F-05). */
internal fun MainActivity.listChats(): List<ChatMeta> = synchronized(chatStoreLock) {
    migrateLegacyHistory()
    val dirty = chatIndexDirty || prefs.getBoolean("chat_index_dirty", false)
    val f = File(chatsDir(), CHAT_INDEX)
    val idx = if (!dirty && f.exists()) try { ChatStore.parseIndex(readChatText(f)) } catch (e: Exception) { null } else null
    val diskIds = chatsDir().listFiles { file -> file.isFile && file.name.endsWith(".json") && file.name != CHAT_INDEX }
        ?.mapNotNull { file -> ChatStore.safeId(file.name.removeSuffix(".json")) }
        ?.toSet()
        .orEmpty()
    val idxIds = idx?.map { it.id }?.toSet().orEmpty()
    val needsRebuild = idx == null || diskIds.any { it !in idxIds }
    val list = if (needsRebuild) rebuildIndex() else idx!!
    list.filter { chatFile(it.id).exists() }
}

/** history.json lama (satu chat) dimigrasikan secara atomik & terenkripsi; sumber hanya dihapus setelah terverifikasi (F-20). */
internal fun MainActivity.migrateLegacyHistory() = synchronized(chatStoreLock) {
    val legacy = histFile()
    if (!legacy.exists()) return@synchronized
    val has = chatsDir().listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != CHAT_INDEX }?.isNotEmpty() == true
    if (has) return@synchronized
    val id = ChatStore.newId(legacy.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis())
    val target = chatFile(id)
    try {
        val plain = readChatText(legacy)
        writeChatText(target, plain)
        // Verifikasi hasil terenkripsi dapat dibaca kembali sebelum menghapus file plaintext lama.
        if (readChatText(target) == plain) {
            legacy.delete()
            prefs.edit().putString("chat_id", id).apply()
            File(chatsDir(), CHAT_INDEX).delete() // paksa bangun ulang indeks
        }
    } catch (e: Exception) {
        logQuiet("ChatHistory.kt", e)
    }
}

/** F-20: enkripsi ulang berkas riwayat legacy yang masih plaintext secara atomik. */
internal fun MainActivity.migratePlaintextChats() = synchronized(chatStoreLock) {
    migrateLegacyHistory()
    chatsDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
        try {
            val raw = f.readText()
            if (raw.isNotBlank() && !isEncryptedText(raw)) {
                writeChatText(f, raw)
            }
        } catch (e: Exception) {
            logQuiet("ChatHistory.kt", e)
        }
    }
}

private val histSaveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread(r, "mythos-hist-save").apply { isDaemon = true }
}

internal fun MainActivity.saveHist() {
    if (incognito) return // Chat samaran: tidak ditulis ke disk
    val snapshot = synchronized(hist) {
        if (hist.none { it.role == 0 && it.text.isNotBlank() && !it.text.startsWith(SYS_PREFIX) }) return
        ArrayList(hist)
    }
    val id = currentChatId()
    val dir = chatsDir()
    val targetFile = chatFile(id)
    histSaveExecutor.execute {
        try {
            val arr = JSONArray()
            val resIdx = snapshot.indices.filter { snapshot[it].role == 2 }
            val keepFrom = if (resIdx.size > 20) resIdx[resIdx.size - 20] else 0
            for ((i, h) in snapshot.withIndex()) {
                val hh = if (h.role == 2 && i < keepFrom) {
                    Hist(2, results = h.results.map { (c, r) -> Pair(c, if (r.length > 500) r.take(500) + "\n…(dipangkas)" else r) })
                } else h
                arr.put(histToJson(hh))
            }
            synchronized(chatStoreLock) {
                if (id in deletedChatIds) return@synchronized
                val currentIdx = listChats()
                val old = currentIdx.firstOrNull { it.id == id }
                val title = if (old?.custom == true) old.title else ChatStore.titleFrom(ChatStore.firstUserText(arr))
                val meta = ChatMeta(id, title, System.currentTimeMillis(), ChatStore.userCount(arr), old?.custom == true)
                writeChatText(targetFile, ChatStore.fileToJson(meta, arr))
                writeIndex(ChatStore.upsert(currentIdx, meta))
                cleanStaleTemps(dir)
            }
        } catch (e: Exception) {
            logQuiet("ChatHistory.kt", e)
            notifyPersistFailed(e)
        }
    }
}

@Volatile private var lastPersistToastAt = 0L

/** Beri tahu pengguna (paling sering sekali per 30 dtk) bahwa riwayat gagal disimpan, bukan diam-diam. */
private fun MainActivity.notifyPersistFailed(e: Exception) {
    val now = System.currentTimeMillis()
    if (now - lastPersistToastAt < 30_000L) return
    lastPersistToastAt = now
    runOnUiThread { toast("Riwayat chat gagal disimpan: " + (e.message ?: e.javaClass.simpleName).take(120)) }
}

/** Ubah nama chat. [name] kosong = kembali ke judul otomatis dari pesan pertama. */
internal fun MainActivity.renameChat(id: String, name: String) = synchronized(chatStoreLock) {
    try {
        val f = chatFile(id)
        if (!f.exists()) return@synchronized
        val parsed = ChatStore.parseFile(readChatText(f), id, f.lastModified())
        val clean = ChatStore.cleanRename(name)
        val title = clean ?: ChatStore.titleFrom(ChatStore.firstUserText(parsed.messages))
        val meta = ChatMeta(id, title, parsed.meta.updated, parsed.meta.count, clean != null)
        // Atomik: bila gagal (disk penuh, rename gagal) exception dilempar SEBELUM indeks diubah, jadi judul di indeks
        // tidak pernah berbeda dari isi berkas chat.
        writeChatText(f, ChatStore.fileToJson(meta, parsed.messages))
        writeIndex(ChatStore.upsert(listChats(), meta))
    } catch (e: Exception) {
        logQuiet("ChatHistory.kt", e)
        notifyPersistFailed(e)
    }
}

/** Hapus file chat beserta gambar pratinjaunya di chat_images dan entrinya di indeks. */
internal fun MainActivity.eraseChatFiles(id: String) = synchronized(chatStoreLock) {
    try {
        deletedChatIds.add(id)
        purgeChatAttachments(id) // salinan lampiran chat ini di folder attachments workspace dan catatan lampiran aktifnya
        val f = chatFile(id)
        if (f.exists()) {
            try { deleteChatImagePaths(ChatStore.imagePaths(ChatStore.parseFile(readChatText(f), id, 0L).messages)) } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
            f.delete()
        }
        writeIndex(ChatStore.remove(listChats(), id))
        deleteChatWorkspace(id) // folder kerja chat ini di Termux (agen CLI) ikut dihapus
        ccDeleteProject(id)     // folder kerja agen API (penyimpanan aplikasi) beserta lampirannya ikut dihapus
    } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
}

/** Hapus hanya file di filesDir/chat_images; salinan di Termux dibersihkan oleh releaseTaskCopies. */
internal fun MainActivity.deleteChatImagePaths(paths: List<String>) {
    val root = File(filesDir, "chat_images").canonicalPath + File.separator
    for (p in paths) {
        try {
            val f = File(p)
            if (f.canonicalPath.startsWith(root)) f.delete()
        } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
    }
}

/** Buang giliran model yang memanggil tool tapi belum sempat dapat hasil (aplikasi ditutup / dihentikan). */
internal fun MainActivity.trimDangling() {
    synchronized(hist) {
        val last = hist.lastOrNull() ?: return
        if (last.role == 1 && last.calls.isNotEmpty()) {
            hist.removeAt(hist.size - 1)
            if (last.text.isNotBlank()) hist.add(Hist(1, last.text))
        }
    }
}

internal fun MainActivity.restoreHistory() {
    migrateLegacyHistory()
    val id = currentChatId()
    try {
        val f = chatFile(id)
        if (!f.exists()) return
        val arr = ChatStore.parseFile(readChatText(f), id, f.lastModified()).messages
        for (i in 0 until arr.length()) {
            try { hist.add(histFromJson(arr.getJSONObject(i))) } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
        }
    } catch (e: Exception) {
        hist.clear()
        clearSummary() // ringkasan lama menunjuk indeks riwayat yang sudah tidak ada
        try { chatFile(id).renameTo(File(chatsDir(), "$id.json.bad")) } catch (e2: Exception) { logQuiet("ChatHistory.kt", e2) }
    }
    trimDangling()
    for ((i, h) in hist.withIndex()) {
        // Narasi + baris langkah agen dipulihkan SEBELUM jawaban akhirnya, dengan urutan yang sama seperti saat agen bekerja.
        if (h.role == 1) h.trail?.let { t -> runOnUiThread { restoreTrail(t) } }
        if (h.text.isNotBlank()) {
            if (h.role == 0 && h.text.startsWith(SYS_PREFIX)) { /* pesan sistem: tidak jadi gelembung */ }
            else if (h.role == 0) addMsg(USER, stripAttachmentBlocks(h.text), h.images, pin = false) else if (h.role == 1) {
                val restoredLeak = LeakOsintEngine.parseStoredChatReport(h.text)
                if (restoredLeak != null) addLeakOsintReportMsg(restoredLeak, animate = false) else addMsg(AI, h.text)
            }
        }
        if (h.role == 1 && h.files.isNotEmpty()) showFileCards(h.files) // kartu unduh file yang diserahkan agen
        // Langkah alat ikut dipulihkan sebagai kartu Aktivitas Agen (diringkas), bukan hilang saat aplikasi dibuka ulang.
        if (h.role == 1 && h.calls.isNotEmpty()) {
            val next = hist.getOrNull(i + 1)
            runOnUiThread { restoreFeed(h.calls, if (next?.role == 2) next.results else emptyList()) }
        }
    }
}
