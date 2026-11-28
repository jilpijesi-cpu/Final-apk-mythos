package com.build.mythos

// Satu chat = satu folder kerja: <workspace>/chats/<idChat>. Agen hanya bekerja di folder chat yang aktif; semua lampiran chat itu
// disalin ke <folder chat>/attachments/. Chat dihapus -> foldernya ikut dihapus. Chat berbeda = ruang kerja berbeda, tanpa berbagi.
// Aturan murni (path, perintah hapus, pemilihan folder yatim) ada di ChatWorkspaceRules agar bisa diuji tanpa Android.

internal object ChatWorkspaceRules {
    const val DIR = "chats"
    /** Folder chat sah: <apa pun>/chats/<id aman>, tanpa "..". Satu-satunya bentuk yang boleh dihapus otomatis. */
    private val CHAT_DIR = Regex("^/[^\\u0000]+/$DIR/[A-Za-z0-9_-]{1,64}$")
    /** Id chat buatan aplikasi (ChatStore.newId = "c" + waktu). Penyapu folder yatim hanya menyentuh nama berbentuk ini. */
    private val APP_ID = Regex("^c[0-9]{6,}$")
    private val LEGACY = listOf("projects", "attachments", "backups", "artifacts")

    fun chatsRoot(base: String): String = base.trimEnd('/') + "/" + DIR

    fun chatDir(base: String, id: String): String? = ChatStore.safeId(id)?.let { chatsRoot(base) + "/" + it }

    fun isChatDir(dir: String): Boolean = !dir.contains("..") && CHAT_DIR.matches(dir)

    /** Folder di dalam chats/ yang BUKAN milik chat aktif [currentDir]. */
    fun isOtherChat(path: String, base: String, currentDir: String): Boolean {
        val root = chatsRoot(base)
        val p = path.trimEnd('/')
        val inChats = p.startsWith("$root/")
        val inCurrent = p == currentDir.trimEnd('/') || p.startsWith(currentDir.trimEnd('/') + "/")
        return inChats && !inCurrent
    }

    /** Nama folder yatim: berbentuk id aplikasi, bukan chat yang tercatat dan bukan chat aktif. */
    fun orphans(names: List<String>, keep: Set<String>): List<String> =
        names.map { it.trim() }.filter { APP_ID.matches(it) && it !in keep }.distinct()

    /** Perintah hapus satu folder chat; null bila [dir] bukan folder chat sah. Symlink hanya dilepas, isinya tidak diikuti. */
    fun deleteCommand(dir: String, quote: (String) -> String): String? {
        if (!isChatDir(dir)) return null
        val d = quote(dir)
        return "d=$d; if [ -L \"\$d\" ]; then rm -f -- \"\$d\"; elif [ -d \"\$d\" ]; then chmod -R u+w -- \"\$d\" 2>/dev/null; rm -rf -- \"\$d\"; fi; " +
            "[ ! -e \"\$d\" ] && echo MYTHOS_WS_DELETED"
    }

    /** Hapus sisa workspace lama (satu kali). Null bila [base] sama dengan HOME atau kosong, agar folder pribadi tidak ikut terhapus. */
    fun legacyWipeCommand(base: String, home: String, quote: (String) -> String): String? {
        val b = base.trimEnd('/')
        if (b.isEmpty() || b == home.trimEnd('/') || b.contains("..")) return null
        return "rm -rf -- " + LEGACY.joinToString(" ") { quote("$b/$it") } + " && echo MYTHOS_LEGACY_WIPED"
    }
}

private const val PENDING_KEY = "ws_del_pending"
private const val LEGACY_WIPE_KEY = "legacy_wipe_v1"
private val pendingLock = Any()

/** Akar workspace sesuai Pengaturan (tanpa folder chat). Dipakai untuk data global: memori, log error, dan folder chats/ itu sendiri. */
internal fun MainActivity.workspaceBase(): String = WorkspacePaths.resolve(workdir(), termuxHome)

internal fun MainActivity.isOtherChatPath(path: String): Boolean =
    ChatWorkspaceRules.isOtherChat(collapsePath(path), workspaceBase(), workspaceRoot())

private fun MainActivity.pendingSet(): MutableSet<String> = HashSet(prefs.getStringSet(PENDING_KEY, emptySet()) ?: emptySet())

/** Antrikan penghapusan folder chat [id], lalu jalankan. Bila Termux belum siap, antrian dicoba lagi saat aplikasi dibuka. */
internal fun MainActivity.deleteChatWorkspace(id: String) {
    val dir = ChatWorkspaceRules.chatDir(workspaceBase(), id) ?: return
    synchronized(pendingLock) {
        prefs.edit().putStringSet(PENDING_KEY, pendingSet().also { it.add(dir) }).apply()
    }
    bg { try { runPendingWorkspaceDeletes() } catch (e: Exception) { logQuiet("ChatWorkspace.kt", e) } }
}

@Synchronized
internal fun MainActivity.runPendingWorkspaceDeletes() {
    val snapshot = synchronized(pendingLock) { pendingSet().toList() }
    for (dir in snapshot) {
        val cmd = ChatWorkspaceRules.deleteCommand(dir) { sh(it) }
        if (cmd == null) {
            synchronized(pendingLock) { prefs.edit().putStringSet(PENDING_KEY, pendingSet().also { it.remove(dir) }).apply() }
            continue
        }
        val r = try { termux(cmd, limit = 300, timeoutSec = 60, useWorkdir = false, killable = false) } catch (e: Exception) { "exit=1" }
        if (r.startsWith("exit=0") && r.contains("MYTHOS_WS_DELETED")) {
            synchronized(pendingLock) { prefs.edit().putStringSet(PENDING_KEY, pendingSet().also { it.remove(dir) }).apply() }
        }
    }
}

/** Folder chat yang chat-nya sudah tidak ada (mis. dihapus saat Termux mati) ikut dihapus. Dilewati bila daftar chat kosong atau agen sedang bekerja. */
internal fun MainActivity.sweepOrphanWorkspaces() {
    if (busy) return
    val known = try { listChats().map { it.id }.toSet() } catch (e: Exception) { return }
    if (known.isEmpty()) return // daftar kosong bisa berarti indeks gagal dibaca: jangan menyapu apa pun
    val keep = known + prefs.getString("chat_id", "").orEmpty()
    val root = ChatWorkspaceRules.chatsRoot(workspaceBase())
    val r = termux("ls -1 -- ${sh(root)} 2>/dev/null; true", limit = 20000, timeoutSec = 30, useWorkdir = false, killable = false)
    if (!r.startsWith("exit=0")) return
    val dead = ChatWorkspaceRules.orphans(r.lines(), keep)
    if (dead.isEmpty()) return
    synchronized(pendingLock) {
        val set = pendingSet()
        for (n in dead) ChatWorkspaceRules.chatDir(workspaceBase(), n)?.let { set.add(it) }
        prefs.edit().putStringSet(PENDING_KEY, set).apply()
    }
    runPendingWorkspaceDeletes()
}

/** Di awal aplikasi: hapus workspace lama sekali (setelah migrasi lama selesai), selesaikan penghapusan tertunda, sapu folder yatim. */
internal fun MainActivity.startWorkspaceHousekeeping() {
    bg {
        try {
            if (!prefs.getBoolean(LEGACY_WIPE_KEY, false) && prefs.getBoolean(WorkspaceMigration.DONE, false)) {
                val cmd = ChatWorkspaceRules.legacyWipeCommand(workspaceBase(), termuxHome) { sh(it) }
                if (cmd == null) prefs.edit().putBoolean(LEGACY_WIPE_KEY, true).apply() // base = HOME: tidak ada yang dihapus
                else {
                    val r = termux(cmd, limit = 300, timeoutSec = 120, useWorkdir = false, killable = false)
                    if (r.startsWith("exit=0") && r.contains("MYTHOS_LEGACY_WIPED")) prefs.edit().putBoolean(LEGACY_WIPE_KEY, true).apply()
                }
            }
            runPendingWorkspaceDeletes()
            sweepOrphanWorkspaces()
        } catch (e: Exception) { logQuiet("ChatWorkspace.kt", e) }
    }
}
