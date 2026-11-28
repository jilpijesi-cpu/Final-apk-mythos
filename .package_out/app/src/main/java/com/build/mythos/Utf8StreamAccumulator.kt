package com.build.mythos

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Menggabungkan potongan byte UTF-8 tanpa merusak karakter yang terbelah di batas pembacaan file. */
internal class Utf8StreamAccumulator {
    private val pending = ByteArrayOutputStream()
    private val collected = StringBuilder()

    /** Mengembalikan hanya teks baru yang sudah utuh dan menyimpan akhiran UTF-8 yang belum lengkap. */
    fun append(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val old = pending.toByteArray()
        val joined = ByteArray(old.size + bytes.size)
        System.arraycopy(old, 0, joined, 0, old.size)
        System.arraycopy(bytes, 0, joined, old.size, bytes.size)
        pending.reset()
        val safeLength = completeUtf8PrefixLength(joined)
        val text = String(joined, 0, safeLength, StandardCharsets.UTF_8)
        if (safeLength < joined.size) pending.write(joined, safeLength, joined.size - safeLength)
        collected.append(text)
        return text
    }

    /** Dipanggil saat stream berakhir; malformed suffix tetap terlihat sebagai karakter pengganti UTF-8. */
    fun finish(): String {
        if (pending.size() == 0) return ""
        val text = String(pending.toByteArray(), StandardCharsets.UTF_8)
        pending.reset()
        collected.append(text)
        return text
    }

    fun snapshot(): String = collected.toString()

    fun reset() {
        pending.reset()
        collected.setLength(0)
    }

    private fun completeUtf8PrefixLength(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        var leadIndex = bytes.size - 1
        while (leadIndex >= 0 && (bytes[leadIndex].toInt() and 0xC0) == 0x80) leadIndex--
        if (leadIndex < 0) return bytes.size
        val lead = bytes[leadIndex].toInt() and 0xFF
        val expected = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return if (bytes.size - leadIndex < expected) leadIndex else bytes.size
    }
}
