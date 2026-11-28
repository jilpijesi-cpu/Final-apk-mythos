package com.build.mythos

import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout

// Halaman "Semua chat" dan menu tekan-lama pada satu chat (ubah nama / hapus). Ekstensi MainActivity.

internal fun MainActivity.chatSub(c: ChatMeta): String = fmtChatTime(c.updated) + " · " + c.count + " pesan"

/** Menu satu chat. [onChanged] dipanggil setelah nama berubah atau chat terhapus, supaya daftar di layar bisa disegarkan. */
internal fun MainActivity.chatActions(c: ChatMeta, onChanged: () -> Unit = {}) {
    AlertDialog.Builder(this)
        .setTitle(c.title.take(40))
        .setItems(arrayOf("Ubah nama", "Hapus")) { _, which ->
            if (which == 0) askRenameChat(c, onChanged) else confirmDeleteChat(c, onChanged)
        }
        .show()
}

private fun MainActivity.askRenameChat(c: ChatMeta, onChanged: () -> Unit) {
    val field = EditText(this).apply {
        setText(c.title.removeSuffix("…"))
        setSelection(text.length)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        maxLines = 1
        hint = "Nama chat (kosong = otomatis)"
        styleField(this)
    }
    AlertDialog.Builder(this)
        .setTitle("Ubah nama chat")
        .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field, LinearLayout.LayoutParams(MATCH, WRAP)) })
        .setPositiveButton("Simpan") { _, _ ->
            renameChat(c.id, field.text.toString())
            onChanged()
        }
        .setNegativeButton("Batal", null)
        .show()
}

private fun MainActivity.confirmDeleteChat(c: ChatMeta, onChanged: () -> Unit) {
    AlertDialog.Builder(this)
        .setMessage("Hapus chat \"${c.title.take(40)}\"? Ini tidak bisa dibatalkan.")
        .setPositiveButton("Hapus") { _, _ ->
            deleteChat(c.id)
            onChanged()
        }
        .setNegativeButton("Batal", null)
        .show()
}

internal fun MainActivity.showAllChats() {
    val h = pageDialog("Semua chat", "Baru") { page ->
        page.dismiss()
        if (incognito) setIncognito(false) else newChat()
    }
    val search = EditText(this).apply {
        hint = "Cari chat"
        inputType = InputType.TYPE_CLASS_TEXT
        maxLines = 1
        contentDescription = "Cari chat"
        styleField(this)
    }
    h.top.addView(search, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })

    fun render() {
        val all = listChats()
        val cur = currentChatId()
        val shown = all.filter { ChatStore.matches(it, search.text.toString()) }
        h.list.removeAllViews()
        h.info.text = if (all.isEmpty()) "" else "${all.size} chat. Tekan lama untuk ubah nama atau hapus."
        if (shown.isEmpty()) {
            addEmptyNote(h, if (all.isEmpty()) "Belum ada chat tersimpan." else "Tidak ada chat yang cocok.")
            return
        }
        for (c in shown) addRow(h, pageRow(GlyphView.BUBBLE, c.title, chatSub(c),
            badge = if (c.id == cur && !incognito) "Dibuka" else null,
            onLong = { chatActions(c) { render() } }) { h.dismiss(); openChat(c.id) })
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { render() }
    })
    render()
}
