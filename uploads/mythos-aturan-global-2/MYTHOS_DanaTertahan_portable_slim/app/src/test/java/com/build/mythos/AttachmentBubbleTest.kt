package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentBubbleTest {
    private val p = "/data/data/com.termux/files/home/MYTHOS/chats/c1793743708742/attachments/MITHOS.zip"

    @Test fun kalimatOtomatisDanPathDigantiChipNamaFile() {
        val s = AttachmentBubble.split("${AttachmentBubble.DEFAULT_TEXT}\nFile terlampir di \"$p\"")
        assertEquals("", s.text)
        assertEquals(listOf("MITHOS.zip"), s.files)
    }

    @Test fun teksPenggunaDipertahankan() {
        val s = AttachmentBubble.split("Tolong audit ini\nFile terlampir di \"$p\"\nFile terlampir di \"/x/y/log.txt\"")
        assertEquals("Tolong audit ini", s.text)
        assertEquals(listOf("MITHOS.zip", "log.txt"), s.files)
    }

    @Test fun gambarTidakDibuatChipKarenaSudahAdaPratinjau() {
        val s = AttachmentBubble.split("Lihat\nGambar terlampir di \"/x/foto.jpg\"")
        assertEquals("Lihat", s.text)
        assertEquals(emptyList<String>(), s.files)
    }

    @Test fun pesanBiasaTidakBerubah() {
        val s = AttachmentBubble.split("Halo\nFile terlampir di sini ya")
        assertEquals("Halo\nFile terlampir di sini ya", s.text)
        assertEquals(emptyList<String>(), s.files)
    }
}
