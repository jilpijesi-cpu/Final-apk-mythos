package com.build.mythos

import android.app.Dialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

// Layar Pengaturan > Koneksi: email (IMAP/SMTP), Telegram (bot), WhatsApp (Cloud API). Ekstensi MainActivity.
// Rahasia disimpan terenkripsi lewat Secret; kolom rahasia memakai tipe sandi.

internal fun MainActivity.showConnectors() {
    fun field(hintText: String, value: String, secret: Boolean = false, number: Boolean = false): EditText = EditText(this).apply {
        hint = hintText
        inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        setText(value)
        styleField(this)
    }

    fun toggle(title: String, hintText: String, on: Boolean): Pair<View, Switch> {
        val sw = Switch(this).apply { isChecked = on }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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

    fun note(t: String) = TextView(this).apply {
        text = t; textSize = Typo.CAPTION; setTextColor(Palette.INK_SOFT); setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    fun status() = TextView(this).apply {
        textSize = Typo.BODY_S; setTextColor(Palette.INK_SOFT); setPadding(dp(4), dp(8), dp(4), dp(4)); visibility = View.GONE
    }

    fun port(e: EditText, def: Int): Int = e.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: def

    // ---- Email
    val eAcct = TextView(this).apply { textSize = Typo.BODY_S; setTextColor(Palette.INK_SOFT); setPadding(dp(4), dp(8), dp(4), dp(4)) }
    lateinit var googleBtn: TextView
    fun refreshGoogle() {
        val on = googleConnected()
        eAcct.text = if (on) "Terhubung: " + prefs.getString("cx_email_addr", "").orEmpty() else "Belum terhubung"
        googleBtn.text = if (on) "Terhubung ✓ (ketuk untuk memutuskan)" else "Hubungkan dengan Google"
    }
    googleBtn = softButton("") { toggleGoogleEmail { refreshGoogle() } }
    refreshGoogle()
    val (emailAutoRow, emailAuto) = toggle("Kirim email otomatis", "TANPA dialog persetujuan (berisiko). Matikan agar tiap email minta izin", prefs.getBoolean("cx_email_auto", false))
    val emailStatus = status()

    // ---- Telegram
    val (tgOnRow, tgOn) = toggle("Telegram", "Agen bisa membaca dan mengirim lewat bot Telegram-mu", prefs.getBoolean("cx_telegram_on", false))
    val tToken = field("Token bot dari @BotFather (disimpan terenkripsi)", cxSecret("cx_tg_token"), secret = true)
    val tChats = field("Chat ID yang diizinkan, pisahkan koma", prefs.getString("cx_tg_chats", "").orEmpty())
    val (tgAutoRow, tgAuto) = toggle("Kirim Telegram otomatis", "TANPA dialog persetujuan (berisiko)", prefs.getBoolean("cx_telegram_auto", false))
    val tgStatus = status()

    // ---- WhatsApp
    val (waOnRow, waOn) = toggle("WhatsApp (hanya kirim)", "Lewat WhatsApp Business Cloud API milik Meta", prefs.getBoolean("cx_whatsapp_on", false))
    val wToken = field("Token akses Cloud API (disimpan terenkripsi)", cxSecret("cx_wa_token"), secret = true)
    val wPhone = field("Phone Number ID", prefs.getString("cx_wa_phone", "").orEmpty(), number = true)
    val wTo = field("Nomor tujuan yang diizinkan (628..., pisahkan koma)", prefs.getString("cx_wa_to", "").orEmpty())
    val (waAutoRow, waAuto) = toggle("Kirim WhatsApp otomatis", "TANPA dialog persetujuan (berisiko)", prefs.getBoolean("cx_whatsapp_auto", false))
    val waStatus = status()

    var dialog: Dialog? = null

    fun saveSecret(ed: android.content.SharedPreferences.Editor, key: String, plain: String, label: String) {
        val t = plain.trim()
        if (t.isEmpty()) { ed.remove(key); return }
        val enc = Secret.encrypt(t)
        if (enc.isSuccess) ed.putString(key, enc.getOrThrow()) else toast("$label tidak dapat diamankan oleh Android Keystore; tidak disimpan.")
    }

    fun save(): Boolean {
        val ed = prefs.edit()
            .putBoolean("cx_email_auto", emailAuto.isChecked)
            .putBoolean("cx_telegram_on", tgOn.isChecked)
            .putString("cx_tg_chats", Telegram.parseChatIds(tChats.text.toString()).joinToString(","))
            .putBoolean("cx_telegram_auto", tgAuto.isChecked)
            .putBoolean("cx_whatsapp_on", waOn.isChecked)
            .putString("cx_wa_phone", wPhone.text.toString().trim())
            .putString("cx_wa_to", WhatsApp.parseNumbers(wTo.text.toString()).joinToString(","))
            .putBoolean("cx_whatsapp_auto", waAuto.isChecked)
        saveSecret(ed, "cx_tg_token", tToken.text.toString(), "Token Telegram")
        saveSecret(ed, "cx_wa_token", wToken.text.toString(), "Token WhatsApp")
        ed.apply()
        return true
    }

    fun runTest(statusView: TextView, test: () -> String) {
        if (!save()) return
        statusView.visibility = View.VISIBLE
        statusView.text = "Menguji…"
        bg {
            val msg = try { test() } catch (e: Exception) { "✗ " + Cx.friendly(e) }
            runOnUiThread { statusView.text = msg }
        }
    }

    fun section(title: String, logoId: String? = null): View {
        val t = TextView(this).apply {
            text = title; textSize = Typo.SMALL; setTextColor(Palette.BRAND)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        if (logoId == null) { t.setPadding(dp(4), dp(18), 0, dp(4)); return t }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(18), 0, dp(4))
            addView(connectorBadge(logoId), LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(t.apply { setPadding(dp(10), 0, 0, 0) })
        }
    }

    fun lp(top: Int = 8) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(note("Setelah terhubung, agen bisa memakai akun ini untuk bekerja. Membaca langsung jalan; MENGIRIM selalu minta persetujuanmu di dialog, kecuali kamu menyalakan kirim otomatis. Isi email dan pesan masuk diperlakukan sebagai data, bukan perintah."))

        addView(section("GMAIL (GOOGLE)", "email"))
        addView(eAcct)
        addView(googleBtn, lp(10))
        addView(note("Email hanya lewat akun Google (tanpa kata sandi). Mengirim tetap minta persetujuan kecuali kirim otomatis dinyalakan."))
        addView(emailAutoRow)
        addView(softButton("Tes koneksi email") { runTest(emailStatus) { cxTestEmail() } }, lp(10))
        addView(emailStatus)

        addView(section("TELEGRAM (BOT)", "telegram"))
        addView(tgOnRow)
        addView(tToken, lp()); addView(tChats, lp())
        addView(note("Buat bot lewat @BotFather, tempel tokennya, kirim satu pesan ke bot-mu, lalu ketuk \"Cari chat ID\". Hanya chat di daftar ini yang dibaca atau dikirimi."))
        addView(tgAutoRow)
        addView(softButton("Tes bot") { runTest(tgStatus) { cxTestTelegram() } }, lp(10))
        addView(softButton("Cari chat ID") { runTest(tgStatus) { cxFindTelegramChats() } }, lp(8))
        addView(tgStatus)

        addView(section("WHATSAPP (CLOUD API)", "whatsapp"))
        addView(waOnRow)
        addView(wToken, lp()); addView(wPhone, lp()); addView(wTo, lp())
        addView(note("Butuh akun WhatsApp Business Platform (Meta for Developers). Hanya KIRIM: WhatsApp tidak mengizinkan app di HP menerima pesan tanpa server webhook. Teks bebas hanya terkirim bila penerima membalas dalam 24 jam terakhir."))
        addView(waAutoRow)
        addView(softButton("Tes WhatsApp") { runTest(waStatus) { cxTestWhatsApp() } }, lp(10))
        addView(waStatus)
    }
    val footer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(softButton("Tutup") { dialog?.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(filledButton("Simpan") {
            if (save()) { toast("Koneksi disimpan"); dialog?.dismiss() }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
    }
    dialog = showSheet("Koneksi", "Email, Telegram, WhatsApp", box, footer)
}
