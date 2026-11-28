package com.build.mythos

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

// "Jelajahi konektor": daftar kartu dengan tombol Hubungkan. Tiap konektor punya alur pendek yang mengisi sendiri sebanyak mungkin
// (papan klip, preset server, pemasangan chat Telegram lewat tautan, izin notifikasi) lalu menguji dan menyalakan konektornya.
// Yang tetap harus dilakukan pengguna di luar aplikasi (membuat bot, sandi aplikasi, token Meta, saklar izin Android) dijelaskan di tiap alur.

private class HubCard(val id: String, val name: String, val glyph: String, val desc: String, val keywords: String)

private val HUB_CARDS = listOf(
    HubCard("email", "Gmail", "✉", "Baca, ringkas, dan kirim email dari akun Google-mu", "gmail google email surat"),
    HubCard("telegram", "Telegram", "✈", "Baca dan kirim pesan lewat bot Telegram-mu", "bot chat pesan"),
    HubCard("leakosint", "LeakOSINT", "🔎", "Cari data bocor dengan token LeakOSINT milikmu; saat terhubung, chat menjadi kotak pencarian", "leak osint pencarian data bocor"),
    HubCard("wa_hp", "WhatsApp di HP", "💬", "Baca pesan masuk dan balas dari notifikasi WhatsApp di HP ini", "wa whatsapp notifikasi pribadi"),
    HubCard("whatsapp", "WhatsApp Business API", "🏢", "Kirim pesan ke nomor yang kamu izinkan lewat akun Meta Business", "wa whatsapp meta cloud api bisnis")
)

// ---------------------------------------------------------------- pembantu

private fun MainActivity.clipText(): String = try {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip
    if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString().orEmpty().trim() else ""
} catch (e: Exception) {
    ""
}

private fun MainActivity.openHubUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast("Tidak bisa membuka tautan")
    }
}

private fun MainActivity.openSettings(action: String, pkg: Boolean = false) {
    try {
        val i = Intent(action)
        if (pkg) i.data = Uri.fromParts("package", packageName, null)
        startActivity(i)
    } catch (e: Exception) {
        toast("Tidak bisa membuka pengaturan Android")
    }
}

private fun MainActivity.blackButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = Typo.BODY
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(Palette.BG)
    gravity = Gravity.CENTER
    background = pressable(rounded(Palette.INK, 24))
    setPadding(dp(24), dp(12), dp(24), dp(12))
    setOnClickListener { onClick() }
}

private fun MainActivity.noteText(t: String) = TextView(this).apply {
    text = t; textSize = Typo.SMALL; setTextColor(Palette.INK_SOFT); setPadding(dp(4), dp(8), dp(4), dp(4))
}

private fun MainActivity.statusText() = TextView(this).apply {
    textSize = Typo.BODY_S; setTextColor(Palette.INK); setPadding(dp(4), dp(12), dp(4), dp(4)); visibility = View.GONE
}

private fun TextView.show(msg: String) { visibility = View.VISIBLE; text = msg }

private fun MainActivity.inputField(hintText: String, value: String = "", secret: Boolean = false, number: Boolean = false): EditText =
    EditText(this).apply {
        hint = hintText
        inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        setText(value)
        styleField(this)
    }

private fun MainActivity.hubLp(top: Int = 10) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

private fun MainActivity.vbox(block: LinearLayout.() -> Unit): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; block() }

private fun MainActivity.hubToggle(title: String, hintText: String, on: Boolean): Pair<View, Switch> {
    val sw = Switch(this).apply { isChecked = on }
    val texts = vbox {
        addView(TextView(context).apply { text = title; textSize = Typo.BODY; setTextColor(Palette.INK) })
        addView(TextView(context).apply { text = hintText; textSize = Typo.CAPTION; setTextColor(Palette.INK_SOFT) })
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), 0, dp(8))
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
        setOnClickListener { sw.toggle() }
    }
    return row to sw
}

private fun MainActivity.sheet(title: String, subtitle: String?, content: View, onDismiss: () -> Unit = {}): Dialog =
    showSheet(title, subtitle, content, null).also { it.setOnDismissListener { onDismiss() } }

private fun MainActivity.disconnect(kind: String) {
    val ed = prefs.edit().putBoolean("cx_${kind}_on", false).putBoolean("cx_${kind}_auto", false)
    when (kind) {
        "email" -> googleSignOut()
        "telegram" -> ed.remove("cx_tg_token").remove("cx_tg_chats").remove("cx_tg_offset")
        "whatsapp" -> ed.remove("cx_wa_token")
        "wa_hp" -> { WaInbox.clear(); WaReply.actions.clear() }
        "leakosint" -> ed.remove(PREF_LEAKOSINT_TOKEN).putBoolean(PREF_LEAKOSINT_MODE, false)
    }
    ed.apply()
    if (kind == "leakosint") refreshLeakOsintLook()
}

// ---------------------------------------------------------------- layar utama

internal fun MainActivity.showConnectorHub() {
    var filter = "semua"
    var query = ""
    val list = vbox { }
    val chipViews = LinkedHashMap<String, TextView>()

    fun render() {
        for ((k, tv) in chipViews) {
            tv.background = if (filter == k) rounded(Palette.FIELD, 20) else null
            tv.setTextColor(if (filter == k) Palette.INK else Palette.INK_SOFT)
        }
        list.removeAllViews()
        val shown = HUB_CARDS.filter { c ->
            val on = cxReady(c.id)
            (filter == "semua" || (filter == "ok" && on) || (filter == "no" && !on)) &&
                (query.isBlank() || (c.name + " " + c.desc + " " + c.keywords).lowercase().contains(query.trim().lowercase()))
        }
        if (shown.isEmpty()) list.addView(noteText("Tidak ada konektor yang cocok."))
        for (c in shown) {
            val on = cxReady(c.id)
            val icon = connectorBadge(c.id)
            val titles = vbox {
                addView(TextView(context).apply {
                    text = c.name; textSize = Typo.TITLE_S; setTextColor(Palette.INK)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(TextView(context).apply {
                    text = if (on) "✓ Terhubung" else "Belum terhubung"; textSize = Typo.BODY_S
                    setTextColor(if (on) Palette.GREEN else Palette.INK_SOFT)
                })
            }
            fun disconnectBtn(onClick: () -> Unit): TextView = softButton("Putuskan", onClick).apply {
                background = pressable(rounded(Palette.CARD, 24, Palette.BORDER, 1))
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val btn = if (c.id == "email") {
                // Satu tombol: belum terhubung -> login Google; terhubung -> ketuk untuk memutuskan.
                if (on) disconnectBtn { toggleGoogleEmail { render() } }
                else blackButton("Hubungkan") { toggleGoogleEmail { render() } }
            } else if (on) disconnectBtn { disconnect(c.id); toast("${c.name} diputuskan"); render() }
            else blackButton("Hubungkan") { startWizard(c.id) { render() } }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14); marginEnd = dp(8) })
                addView(btn, LinearLayout.LayoutParams(WRAP, WRAP))
            }
            val card = vbox {
                background = rounded(Palette.FIELD, 20)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(top)
                addView(TextView(context).apply { text = c.desc; textSize = Typo.BODY; setTextColor(Palette.INK); setPadding(0, dp(12), 0, 0) })
            }
            list.addView(card, hubLp(10))
        }
    }

    val search = EditText(this).apply {
        hint = "Cari konektor"
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        styleField(this)
        background = rounded(Palette.CARD, 24, Palette.FIELD_BORDER)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); render() }
        })
    }
    val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for ((k, label) in listOf("semua" to "Semua", "ok" to "Terhubung", "no" to "Belum terhubung")) {
        val tv = TextView(this).apply {
            text = label; textSize = Typo.BODY
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { filter = k; render() }
        }
        chipViews[k] = tv
        chipRow.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
    }
    var dialog: Dialog? = null
    val content = vbox {
        addView(search, hubLp(6))
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }, hubLp(12))
        addView(list, hubLp(4))
        addView(noteText("Membaca langsung jalan. MENGIRIM atau membalas selalu minta persetujuanmu, kecuali kamu menyalakan kirim otomatis."), hubLp(8))
        addView(softButton("Pengaturan lengkap") { dialog?.dismiss(); showConnectors() }, hubLp(8))
    }
    render()
    dialog = sheet("Jelajahi konektor", null, content)
}

private fun MainActivity.startWizard(id: String, onDone: () -> Unit) {
    when (id) {
        "telegram" -> wizardTelegram(onDone)
        "leakosint" -> wizardLeakOsint(onDone)
        "wa_hp" -> wizardWaPhone(onDone)
        else -> wizardWaApi(onDone)
    }
}

// ---------------------------------------------------------------- LeakOSINT

private fun MainActivity.wizardLeakOsint(onDone: () -> Unit) {
    val token = inputField("Token API LeakOSINT", leakOsintToken(), secret = true)
    val status = statusText()
    var dlg: Dialog? = null
    val connect = blackButton("Hubungkan") {
        val t = token.text.toString().trim()
        if (t.length < 8 || !Cx.noCtl(t)) { status.show("Token belum valid. Tempel token API dari akun LeakOSINT-mu."); return@blackButton }
        val enc = Secret.encrypt(t)
        if (enc.isFailure) { status.show("Token tidak bisa diamankan oleh Android Keystore."); return@blackButton }
        // Terhubung = token tersimpan + chat menjadi kotak pencarian. Tidak ada uji ke server: setiap pencarian berbayar.
        prefs.edit().putString(PREF_LEAKOSINT_TOKEN, enc.getOrThrow()).putBoolean(PREF_LEAKOSINT_MODE, true).apply()
        refreshLeakOsintLook()
        toast("LeakOSINT terhubung"); dlg?.dismiss(); onDone()
    }
    val content = vbox {
        addView(noteText("Tempel token API LeakOSINT-mu. Disimpan terenkripsi di perangkat. Setelah terhubung, chat menjadi kotak pencarian; matikan lewat menu \"MYTHOS LeakOSINT\" atau ketuk tombolnya lagi di sini untuk memutuskan."))
        addView(token, hubLp())
        addView(connect, hubLp(14))
        addView(status)
    }
    dlg = sheet("Hubungkan LeakOSINT", null, content)
}

// ---------------------------------------------------------------- Telegram

private fun MainActivity.wizardTelegram(onDone: () -> Unit) {
    val token = inputField("Token bot dari @BotFather", cxSecret("cx_tg_token").ifBlank { ClipDetect.telegramToken(clipText()).orEmpty() }, secret = true)
    val status = statusText()
    val active = AtomicBoolean(true)
    val busy = AtomicBoolean(false)
    var link = ""
    var dlg: Dialog? = null
    val reopen = softButton("Buka Telegram lagi") { if (link.isNotEmpty()) openHubUrl(link) }
    reopen.visibility = View.GONE

    fun fail(msg: String) {
        busy.set(false)
        runOnUiThread { if (!isFinishing && !isDestroyed) status.show("✗ $msg") }
    }

    fun finish() {
        prefs.edit().putBoolean("cx_telegram_on", true).apply()
        runOnUiThread { if (!isFinishing && !isDestroyed) { toast("Telegram terhubung"); dlg?.dismiss(); onDone() } }
    }

    val connect = blackButton("Hubungkan") {
        val t = ClipDetect.telegramToken(token.text.toString()) ?: token.text.toString().trim()
        if (!Telegram.validToken(t)) { status.show("Token tidak dikenali. Contoh: 123456789:AAE..."); return@blackButton }
        if (!busy.compareAndSet(false, true)) return@blackButton
        status.show("Memeriksa bot…")
        bg {
            try {
                val bot = Telegram.getMe(t)
                val enc = Secret.encrypt(t)
                if (enc.isFailure) { fail("Token tidak bisa diamankan oleh Android Keystore."); return@bg }
                prefs.edit().putString("cx_tg_token", enc.getOrThrow()).apply()
                if (tgChats().isNotEmpty()) { finish(); return@bg } // sudah pernah dipasangkan
                val code = TgPairing.newCode()
                link = TgPairing.deepLink(bot, code)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.show("Bot $bot ditemukan. Di Telegram, ketuk MULAI (Start). Aku menunggu…")
                        reopen.visibility = View.VISIBLE
                        openHubUrl(link)
                    }
                }
                val deadline = System.currentTimeMillis() + 150_000
                while (active.get() && System.currentTimeMillis() < deadline) {
                    val up = Telegram.updates(t, 0L, 50)
                    val hit = TgPairing.find(up.messages, code)
                    if (hit != null) {
                        prefs.edit()
                            .putString("cx_tg_chats", TgPairing.addChat(prefs.getString("cx_tg_chats", "").orEmpty(), hit.chatId))
                            .putLong("cx_tg_offset", hit.updateId + 1).apply()
                        try {
                            Telegram.send(t, hit.chatId, "✅ MYTHOS terhubung. Agen kini bisa membaca dan mengirim pesan di chat ini; tiap pengiriman tetap minta persetujuanmu di aplikasi.")
                        } catch (e: Exception) { logQuiet("ConnectorHub.kt", e) }
                        finish()
                        return@bg
                    }
                    Thread.sleep(2000)
                }
                if (active.get()) fail("Waktu habis. Ketuk Hubungkan lagi, lalu tekan MULAI di Telegram.")
            } catch (e: Exception) {
                fail(Cx.friendly(e, listOf(t)))
            }
        }
    }
    val content = vbox {
        addView(noteText("1. Buat bot di @BotFather (kirim /newbot), salin tokennya.\n2. Kembali ke sini: token terisi otomatis dari papan klip.\n3. Ketuk Hubungkan, lalu tekan MULAI di Telegram. Chat ID terpasang sendiri."))
        addView(softButton("Buka @BotFather") { openHubUrl("https://t.me/BotFather") }, hubLp(10))
        addView(token, hubLp(10))
        addView(softButton("Tempel token dari papan klip") {
            val tk = ClipDetect.telegramToken(clipText())
            if (tk == null) toast("Tidak ada token di papan klip") else token.setText(tk)
        }, hubLp(8))
        addView(connect, hubLp(14))
        addView(reopen, hubLp(8))
        addView(status)
    }
    dlg = sheet("Hubungkan Telegram", null, content) { active.set(false) }
}

// ---------------------------------------------------------------- WhatsApp di HP (notifikasi)

private fun MainActivity.wizardWaPhone(onDone: () -> Unit) {
    val status = statusText()
    val active = AtomicBoolean(true)
    var dlg: Dialog? = null
    val content = vbox {
        addView(noteText("Agen membaca pesan masuk dari NOTIFIKASI WhatsApp di HP ini dan membalas lewat tombol Balas pada notifikasi. Hanya notifikasi WhatsApp yang diproses; aplikasi lain diabaikan. Pesan yang dibaca agen dikirim ke penyedia AI pilihanmu. Setiap balasan tetap minta persetujuanmu."))
        addView(blackButton("1. Aktifkan akses notifikasi") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }, hubLp(12))
        addView(noteText("Nyalakan saklar MYTHOS. Bila Android menolak (\"setelan terbatas\", Android 13+), pakai tombol di bawah: ketuk ⋮ di pojok kanan atas > Izinkan setelan terbatas, lalu ulangi langkah 1."))
        addView(softButton("2. Buka info aplikasi") { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg = true) }, hubLp(8))
        addView(status)
        addView(noteText("Batasan: hanya pesan yang tiba setelah izin aktif, dan hanya percakapan yang notifikasinya masih ada yang bisa dibalas. Kontak baru tidak bisa dihubungi lewat jalur ini."))
    }
    status.show("Menunggu izin diaktifkan…")
    dlg = sheet("Hubungkan WhatsApp di HP", null, content) { active.set(false) }
    bg {
        while (active.get()) {
            if (waListenerEnabled()) {
                prefs.edit().putBoolean("cx_wa_hp_on", true).apply()
                runOnUiThread { if (!isFinishing && !isDestroyed) { toast("WhatsApp di HP terhubung"); dlg?.dismiss(); onDone() } }
                return@bg
            }
            Thread.sleep(1500)
        }
    }
}

// ---------------------------------------------------------------- WhatsApp Business API

private fun MainActivity.wizardWaApi(onDone: () -> Unit) {
    val token = inputField("Token akses Cloud API", cxSecret("cx_wa_token").ifBlank { ClipDetect.metaToken(clipText()).orEmpty() }, secret = true)
    val phone = inputField("Phone Number ID", prefs.getString("cx_wa_phone", "").orEmpty(), number = true)
    val me = inputField("Nomor WhatsApp tujuan, mis. 628123456789", waNumbers().firstOrNull().orEmpty())
    val status = statusText()
    var dlg: Dialog? = null
    var busy = false
    val connect = blackButton("Hubungkan") {
        if (busy) return@blackButton
        val tk = token.text.toString().trim()
        val pid = phone.text.toString().trim()
        val to = WhatsApp.normalizeNumber(me.text.toString())
        if (tk.isEmpty() || !WhatsApp.validPhoneId(pid)) { status.show("Isi token akses dan Phone Number ID (angka)."); return@blackButton }
        if (to == null) { status.show("Nomor tujuan belum valid (format internasional, 8-15 digit)."); return@blackButton }
        val enc = Secret.encrypt(tk)
        if (enc.isFailure) { status.show("Token tidak bisa diamankan oleh Android Keystore."); return@blackButton }
        prefs.edit().putString("cx_wa_token", enc.getOrThrow()).putString("cx_wa_phone", pid)
            .putString("cx_wa_to", (waNumbers() + to).distinct().joinToString(",")).apply()
        busy = true
        status.show("Menghubungkan…")
        bg {
            val msg = cxTestWhatsApp()
            val ok = msg.startsWith("✓")
            if (ok) prefs.edit().putBoolean("cx_whatsapp_on", true).apply()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    busy = false
                    status.show(msg)
                    if (ok) { toast("WhatsApp Business terhubung"); dlg?.dismiss(); onDone() }
                }
            }
        }
    }
    val content = vbox {
        addView(noteText("Jalur resmi Meta: butuh akun WhatsApp Business Platform. Ambil token akses dan Phone Number ID dari dasbor aplikasimu, lalu isi nomor tujuan yang boleh dikirimi. Hanya KIRIM; teks bebas hanya terkirim bila penerima membalas dalam 24 jam terakhir."))
        addView(softButton("Buka Meta for Developers") { openHubUrl("https://developers.facebook.com/apps/") }, hubLp(10))
        addView(token, hubLp(10)); addView(phone, hubLp()); addView(me, hubLp())
        addView(connect, hubLp(14))
        addView(status)
    }
    dlg = sheet("Hubungkan WhatsApp Business", null, content)
}
