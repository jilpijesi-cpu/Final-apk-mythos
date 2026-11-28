package com.build.mythos

import com.build.mythos.cc.ImagePart
import com.build.mythos.cc.Msg
import com.build.mythos.cc.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Perilaku yang dijaga oleh perbaikan performa panel asisten layar (regex konstanta, gambar sekali kirim). */
class AssistPerfTest {

    @Test fun cleanMembuangKarakterKontrolTapiMenjagaBarisBaruDanTab() {
        assertEquals("a\nb\tc", Cx.clean("a\u0000\nb\u0007\tc\u007F"))
    }

    @Test fun cleanTextsTigaRibuNodeTetapBenar() {
        val raw = (1..3000).map { "  item   $it \n\n teks\u0000 " }
        val out = AssistLogic.cleanTexts(raw, maxNodes = 3000)
        assertEquals(3000, out.size)
        assertEquals("item 1\nteks", out.first())
    }

    @Test fun dropImagesMembuangGambarTapiMenjagaTeksDanPeran() {
        val shot = listOf(ImagePart("image/jpeg", "AAAA"))
        val history = listOf(
            Msg(Role.USER, "pertanyaan 1", shot),
            Msg(Role.ASSISTANT, "jawaban 1"),
            Msg(Role.USER, "pertanyaan 2")
        )
        val out = AssistLogic.dropImages(history)
        assertEquals(3, out.size)
        assertTrue(out.all { it.images.isEmpty() })
        assertTrue(out[0].text.startsWith("pertanyaan 1"))
        assertEquals(Role.USER, out[0].role)
        // Pesan tanpa gambar dipakai apa adanya (tidak disalin).
        assertSame(history[1], out[1])
        assertSame(history[2], out[2])
    }
}
