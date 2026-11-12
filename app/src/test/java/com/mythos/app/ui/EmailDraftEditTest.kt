package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** patch9: validasi hasil ubah draf email langsung di kartu. */
class EmailDraftEditTest {
    @Test fun alamatDipecahDariKomaTitikKomaSpasiDanBarisBaru() {
        assertEquals(listOf("a@x.id", "b@y.id", "c@z.id"), parseRecipients("a@x.id, b@y.id;c@z.id\n a@x.id"))
        assertTrue(parseRecipients("  ,; ").isEmpty())
    }

    @Test fun alamatTidakValidTerdeteksi() {
        assertTrue(invalidRecipients(listOf("a@x.id", "info@ultravoucher.co.id")).isEmpty())
        assertEquals(listOf("tanpa-at", "a@b"), invalidRecipients(listOf("tanpa-at", "a@x.id", "a@b")))
    }

    @Test fun galatEditDilaporkanBerurutan() {
        val ok = listOf("a@x.id")
        assertNull(emailEditError(ok, emptyList(), "Subjek", "Isi"))
        assertNotNull(emailEditError(emptyList(), emptyList(), "Subjek", "Isi"))
        assertTrue(emailEditError(listOf("salah"), emptyList(), "S", "I")!!.contains("Kepada"))
        assertTrue(emailEditError(ok, listOf("salah"), "S", "I")!!.contains("Cc"))
        assertTrue(emailEditError(ok, emptyList(), "  ", "I")!!.contains("Subjek"))
        assertTrue(emailEditError(ok, emptyList(), "S", " ")!!.contains("Isi"))
    }
}
