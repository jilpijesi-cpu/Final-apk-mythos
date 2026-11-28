package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatModeTest {
    @Test
    fun konteksSelaluDiakhiriPesanBaruDanDiawaliPengguna() {
        val prev = listOf(Hist(AI, "sisa jawaban lama"), Hist(USER, "halo"), Hist(AI, "halo juga"))
        val ctx = localChatContext(prev, "apa kabar")
        assertEquals(USER, ctx.first().role)
        assertEquals("apa kabar", ctx.last().text)
        assertEquals(3, ctx.size)
    }

    @Test
    fun jendelaTerbatasDanPesanDipotong() {
        val prev = (1..10).map { Hist(if (it % 2 == 1) USER else AI, "p$it" + "x".repeat(2000)) }
        val ctx = localChatContext(prev, "baru")
        assertTrue(ctx.size <= 7)
        assertTrue(ctx.all { it.text.length <= 600 || it.text == "baru" })
    }

    @Test
    fun galatDanHasilAlatDibuang() {
        val prev = listOf(Hist(USER, "tes"), Hist(AI, "Error: gagal"), Hist(2, "hasil alat"), Hist(AI, "oke"))
        val ctx = localChatContext(prev, "lanjut")
        assertEquals(listOf("tes", "oke", "lanjut"), ctx.map { it.text })
    }

    @Test
    fun sistemMenyebutModelDanTanpaAlat() {
        val s = localChatSystem("qwen-t6:latest", "BOS")
        assertTrue(s.contains("qwen-t6:latest"))
        assertTrue(s.contains("tidak punya alat"))
        assertTrue(s.length < 1500)
    }
}
