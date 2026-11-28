package com.build.mythos

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Menu tekan-lama pada pesan: salin, bagikan, ulangi, edit; serta chip saran.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.showMsgMenu(v: View) {
    val meta = v.tag as? MsgMeta ?: return
    if (meta.text.isBlank()) return
    val labels = ArrayList<String>()
    val acts = ArrayList<() -> Unit>()
    labels.add("Salin"); acts.add { copyText("pesan", meta.text); toast("Tersalin") }
    labels.add("Pilih teks"); acts.add { selectDialog(meta.text) }
    labels.add("Bagikan"); acts.add { shareText(meta.text) }
    if (!busy && preset(prov).fmt != "cli") {
        if (meta.role == AI && isLastAi(v)) { labels.add("Ulangi jawaban"); acts.add { regenerate() } }
        if (meta.role == USER) { labels.add("Edit & kirim ulang"); acts.add { editFrom(v) } }
    }
    AlertDialog.Builder(this)
        .setItems(labels.toTypedArray()) { _, i -> acts[i]() }
        .show()
}

internal fun MainActivity.selectDialog(text: String) {
    val tv = TextView(this).apply {
        this.text = text
        textSize = Typo.BODY
        setTextIsSelectable(true)
        setPadding(dp(20), dp(12), dp(20), dp(4))
    }
    AlertDialog.Builder(this)
        .setView(ScrollView(this).apply { addView(tv) })
        .setPositiveButton("Tutup", null)
        .show()
}

internal fun MainActivity.shareText(text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try { startActivity(Intent.createChooser(i, "Bagikan")) } catch (e: Exception) { toast("Tidak bisa membagikan") }
}

internal fun MainActivity.metaOf(i: Int): MsgMeta? = chatBox.getChildAt(i)?.tag as? MsgMeta

internal fun MainActivity.isLastAi(v: View): Boolean {
    for (i in chatBox.childCount - 1 downTo 0) {
        val m = metaOf(i) ?: continue
        if (m.role == AI) return chatBox.getChildAt(i) === v
    }
    return false
}

/** Hapus tampilan mulai indeks `from` (kecuali baris status). */
internal fun MainActivity.removeViewsFrom(from: Int) {
    if (from < 0) return
    clearChips()
    for (i in chatBox.childCount - 1 downTo from) {
        val c = chatBox.getChildAt(i)
        if (c !== statusRoot) chatBox.removeViewAt(i)
    }
    if (chatBox.childCount == 0) emptyState.visibility = View.VISIBLE
}

internal fun MainActivity.regenerate() {
    if (busy) return
    val key = cfgKey()
    if (needsKey() && key.isEmpty()) { showApiKeyDialog(); return }
    val picked: Triple<String, List<HistImage>, Int> = synchronized(hist) {
        val idx = hist.indexOfLast { it.role == 0 }
        if (idx < 0) return@synchronized Triple("", emptyList(), -1)
        val entry = hist[idx]
        while (hist.size > idx + 1) hist.removeAt(hist.size - 1)
        Triple(entry.text, ArrayList(entry.images), hist.size)
    }
    if (picked.third < 0) { toast("Tidak ada pesan untuk diulang"); return }
    if (picked.third < sumUpto) clearSummary()
    var lastUser = -1
    for (i in chatBox.childCount - 1 downTo 0) {
        if (metaOf(i)?.role == USER) { lastUser = i; break }
    }
    removeViewsFrom(lastUser + 1)
    saveHist()
    busy = true
    startWorker(picked.first, key, addUser = false, images = picked.second)
}

internal fun MainActivity.editFrom(v: View) {
    if (busy) return
    var k = 0
    for (i in 0 until chatBox.childCount) {
        val c = chatBox.getChildAt(i)
        if (c === v) break
        if (metaOf(i)?.role == USER) k++
    }
    var idx = -1
    var seen = 0
    synchronized(hist) {
        for ((i, h) in hist.withIndex()) {
            if (h.role == 0) {
                if (seen == k) { idx = i; break }
                seen++
            }
        }
    }
    val viewIdx = chatBox.indexOfChild(v)
    if (idx < 0 || viewIdx < 0) { toast("Pesan ini tidak bisa diedit"); return }
    val text = (v.tag as MsgMeta).text
    val remaining = synchronized(hist) {
        while (hist.size > idx) hist.removeAt(hist.size - 1)
        hist.size
    }
    if (remaining < sumUpto) clearSummary()
    removeViewsFrom(viewIdx)
    saveHist()
    input.setText(text)
    input.setSelection(input.text.length)
    input.requestFocus()
}

internal fun MainActivity.clearChips() {
    chipsRow?.let { (it.parent as? ViewGroup)?.removeView(it) }
    chipsRow = null
}

internal fun MainActivity.showChips(text: String, unfinished: Boolean = false): Unit = runOnUiThread {
    if (isFinishing || isDestroyed) return@runOnUiThread
    // Jawaban masih mengalir: tunda saran supaya tidak muncul di bawah teks yang belum selesai diketik.
    if (typewriterRunnable != null) {
        chatBox.postDelayed({ showChips(text, unfinished) }, 150L)
        return@runOnUiThread
    }
    clearChips()
    // Chip mengikuti isi balasan: pilihan yang ditanyakan AI, ya/tidak, atau perbaikan/lanjutan yang memang perlu.
    // Tanpa konteks yang cocok, baris saran tidak ditampilkan.
    val items = contextualChips(text, sawFail, sawBuild, unfinished).map { it.label to it.prompt }
    if (items.isEmpty()) return@runOnUiThread
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for ((label, prompt) in items.take(4)) {
        val chip = TextView(this).apply {
            this.text = label
            textSize = Typo.BODY_S
            setTextColor(Palette.INK)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = pressable(rounded(Palette.CARD, 20, Palette.DIVIDER))
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener {
                if (busy) return@setOnClickListener
                clearChips()
                input.setText(prompt)
                send()
            }
        }
        row.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
    }
    val hs = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }
    chipsRow = hs
    insertBeforeStatus(hs, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
    scrollDown()
}
