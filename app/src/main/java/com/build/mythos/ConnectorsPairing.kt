package com.build.mythos

import java.security.SecureRandom
import java.util.Random

// Bagian murni dari alur "Hubungkan satu ketukan" (tanpa android.*, diuji di ConnectorsTest):
//  - ClipDetect : mengenali token/sandi di papan klip supaya kolom terisi otomatis
//  - TgPairing  : pemasangan chat Telegram lewat tautan /start berkode (chat ID tidak perlu diketik)
//  - WaInbox    : pesan WhatsApp yang ditangkap dari notifikasi (WaListenerService mengisinya)

internal object ClipDetect {
    private val TG = Regex("\\d{5,15}:[A-Za-z0-9_-]{30,60}")
    private val GOOGLE_APP = Regex("^[a-z]{4}( ?[a-z]{4}){3}$")
    private val META = Regex("^EAA[A-Za-z0-9]{40,}$")

    /** Token bot Telegram di dalam teks (pesan BotFather yang disalin utuh pun terbaca). */
    fun telegramToken(clip: String): String? = TG.find(clip)?.value

    /** Sandi aplikasi Google (16 huruf kecil, boleh berspasi 4-4-4-4), dikembalikan tanpa spasi. */
    fun googleAppPassword(clip: String): String? = clip.trim().takeIf { GOOGLE_APP.matches(it) }?.replace(" ", "")

    fun metaToken(clip: String): String? = clip.trim().takeIf { META.matches(it) }
}

internal object TgPairing {
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

    fun newCode(rnd: Random = SecureRandom()): String =
        (1..10).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")

    fun deepLink(bot: String, code: String): String = "https://t.me/${bot.removePrefix("@")}?start=$code"

    /** Pesan "/start KODE" dari orang yang mengetuk tautan; hanya dia yang ditambahkan ke daftar izin. */
    fun find(msgs: List<TgMessage>, code: String): TgMessage? = msgs.firstOrNull { it.text.trim() == "/start $code" }

    fun addChat(existing: String, chatId: Long): String = (Telegram.parseChatIds(existing) + chatId).distinct().joinToString(",")
}

internal class WaMsg(val conversation: String, val sender: String, val text: String, val time: Long, @Volatile var seen: Boolean = false)

/** Pesan WhatsApp terbaru yang tertangkap dari notifikasi. Hanya di memori (hilang bila proses aplikasi mati). */
internal object WaInbox {
    private const val MAX = 100
    private val msgs = ArrayList<WaMsg>()

    /** Notifikasi WhatsApp diposting ulang dengan riwayat yang sama; pesan identik diabaikan. */
    @Synchronized fun add(m: WaMsg): Boolean {
        if (msgs.any { it.conversation == m.conversation && it.sender == m.sender && it.text == m.text && it.time == m.time }) return false
        msgs.add(m)
        while (msgs.size > MAX) msgs.removeAt(0)
        return true
    }

    /** Pesan belum dibaca agen (paling lama dulu), maksimal [n]. Bila [mark], ditandai sudah dibaca. */
    @Synchronized fun unseen(n: Int, mark: Boolean): List<WaMsg> {
        val out = msgs.filter { !it.seen }.take(n)
        if (mark) out.forEach { it.seen = true }
        return out
    }

    @Synchronized fun conversations(): List<String> = msgs.map { it.conversation }.distinct()

    @Synchronized fun clear() = msgs.clear()
}
