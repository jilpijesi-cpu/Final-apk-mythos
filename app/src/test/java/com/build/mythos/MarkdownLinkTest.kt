package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownLinkTest {
    @Test
    fun tautanBerkasLokalTerdeteksiSebagaiTautan() {
        val s = "Tes ([storage.py](/data/data/com.termux/files/home/MYTHOS/storage.py:23)) gagal."
        val m = inlineRe.find(s)!!
        assertEquals("storage.py", m.groupValues[4])
        assertEquals("/data/data/com.termux/files/home/MYTHOS/storage.py:23", m.groupValues[5])
        assertNull(m.groups[1])
    }

    @Test
    fun tautanHttpDanKodeInlineTetapBenar() {
        val a = inlineRe.find("[docs](https://example.com/a)")!!
        assertEquals("https://example.com/a", a.groupValues[5])
        val b = inlineRe.find("`tasks.json`")!!
        assertEquals("tasks.json", b.groupValues[1])
        assertNull(b.groups[4])
    }

    @Test
    fun kurungBiasaBukanTautan() {
        assertNull(inlineRe.find("hasil [belum] (terverifikasi)"))
    }
}
