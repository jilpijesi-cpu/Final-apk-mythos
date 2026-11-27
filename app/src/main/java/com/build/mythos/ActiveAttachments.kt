package com.build.mythos

import org.json.JSONArray
import org.json.JSONObject

// Daftar lampiran aktif per chat. Disimpan di prefs (kunci att_<idChat>) supaya tetap ada setelah aplikasi ditutup,
// dan dibaca setiap giliran lewat sysText() sehingga tidak ikut terpangkas bersama riwayat.
// Chat samaran tidak menulis apa pun ke disk: daftarnya hanya di memori.

internal object AttachState {
    val incognito = java.util.concurrent.CopyOnWriteArrayList<ActiveAtt>()
}

internal fun activeAttsToJson(list: List<ActiveAtt>): String {
    val arr = JSONArray()
    for (a in list) {
        arr.put(JSONObject().put("name", a.name).put("kind", a.kind).put("path", a.path).put("summary", a.summary).put("dir", a.dir))
    }
    return arr.toString()
}

internal fun activeAttsFromJson(s: String): List<ActiveAtt> {
    if (s.isBlank()) return emptyList()
    val arr = JSONArray(s)
    val out = ArrayList<ActiveAtt>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val path = o.optString("path")
        if (path.isBlank()) continue
        out.add(ActiveAtt(o.optString("name"), o.optString("kind", "file"), path, o.optString("summary"), o.optString("dir")))
    }
    return out
}

private fun MainActivity.attKey(): String = "att_" + currentChatId()

internal fun MainActivity.activeAtts(): List<ActiveAtt> {
    if (incognito) return AttachState.incognito.toList()
    return try {
        activeAttsFromJson(prefs.getString(attKey(), "") ?: "")
    } catch (e: Exception) {
        logQuiet("ActiveAttachments.kt", e)
        emptyList()
    }
}

private fun MainActivity.saveActiveAtts(list: List<ActiveAtt>) {
    if (incognito) {
        AttachState.incognito.clear()
        AttachState.incognito.addAll(list)
        return
    }
    try {
        prefs.edit().putString(attKey(), activeAttsToJson(list)).apply()
    } catch (e: Exception) {
        logQuiet("ActiveAttachments.kt", e)
    }
}

/** Catat lampiran yang baru dikirim sebagai lampiran aktif chat ini. */
internal fun MainActivity.rememberActiveAttachments(sent: List<Attachment>) {
    val added = sent.mapNotNull { a ->
        val p = a.path ?: return@mapNotNull null
        ActiveAtt(a.name, a.kind, p, a.summary, a.extractedDir.orEmpty())
    }
    if (added.isEmpty()) return
    saveActiveAtts(mergeActive(activeAtts(), added))
}

/** Catatan lampiran aktif untuk prompt sistem; kosong bila tidak ada. [tools] false untuk mode obrolan tanpa alat. */
internal fun MainActivity.activeAttNoteText(tools: Boolean = true): String {
    val list = activeAtts()
    if (list.isEmpty()) return ""
    return activeAttNote(list, if (isLocalUrl(cfgBase())) 1200 else 4000, tools)
}

/** Hapus daftar lampiran aktif sebuah chat dan salinan file-nya di folder attachments workspace. Folder hasil ekstrak zip TIDAK dihapus (itu milik proyek). */
internal fun MainActivity.purgeChatAttachments(id: String) {
    val key = "att_$id"
    val list = try {
        activeAttsFromJson(prefs.getString(key, "") ?: "")
    } catch (e: Exception) {
        emptyList()
    }
    prefs.edit().remove(key).apply()
    val allowedPrefixes = listOf("${workspaceRoot()}/attachments/", "$termuxHome/MYTHOS/attachments/").distinct()
    val paths = list.map { it.path }.filter { path ->
        !path.contains("..") && allowedPrefixes.any { path.startsWith(it) }
    }.distinct()
    if (paths.isEmpty()) return
    bg {
        try {
            val blocked = referencePathsGuard(paths)
            if (blocked != null) throw IllegalStateException("Penghapusan lampiran diblokir: $blocked")
            termux("rm -f -- " + paths.joinToString(" ") { sh(it) }, limit = 500, timeoutSec = 30)
        } catch (ignored: Exception) {
            logQuiet("ActiveAttachments.kt", ignored)
        }
    }
}
