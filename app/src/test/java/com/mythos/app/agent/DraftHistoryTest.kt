package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DraftHistoryTest {
    private fun tmp(): File = Files.createTempDirectory("mythos-draf").toFile()
    private val to = listOf("bicara@bi.go.id")

    @Test
    fun firstDraftIsVersionOneAndWritesIndex() {
        val root = tmp()
        val v = DraftHistory.record(root, to, emptyList(), "Pengaduan A", "Halo\nIsi satu", listOf("uploads/a.pdf"), now = 1_000L)
        assertEquals(1, v.number)
        assertEquals("versi pertama", v.change)
        assertEquals(DraftHistory.STATUS_DRAFT, v.status)
        assertTrue(File(root, "draf/bicara-bi-go-id/v001.json").isFile)
        val index = File(root, "draf/RIWAYAT.md").readText()
        assertTrue(index.contains("v001"))
        assertTrue(index.contains("Pengaduan A"))
    }

    @Test
    fun identicalDraftDoesNotCreateNewVersion() {
        val root = tmp()
        DraftHistory.record(root, to, emptyList(), "S", "B", emptyList())
        val again = DraftHistory.record(root, to, emptyList(), "S", "B", emptyList())
        assertEquals(1, again.number)
        assertEquals(1, DraftHistory.list(root, DraftHistory.keyFor(to)).size)
    }

    @Test
    fun changedDraftKeepsOldVersionAndDescribesChange() {
        val root = tmp()
        DraftHistory.record(root, to, emptyList(), "S", "baris 1\nbaris 2", emptyList())
        val v2 = DraftHistory.record(root, to, emptyList(), "S baru", "baris 1\nbaris 3\nbaris 4", listOf("uploads/b.pdf"))
        assertEquals(2, v2.number)
        assertTrue(v2.change.contains("subjek diubah"))
        assertTrue(v2.change.contains("+2 baris, -1 baris"))
        assertTrue(v2.change.contains("lampiran diubah"))
        val all = DraftHistory.list(root, DraftHistory.keyFor(to))
        assertEquals("baris 1\nbaris 2", all[0].body)
        assertEquals(listOf("uploads/b.pdf"), all[1].attachments)
    }

    @Test
    fun differentRecipientsKeepSeparateChains() {
        val root = tmp()
        DraftHistory.record(root, listOf("bicara@bi.go.id"), emptyList(), "S", "B", emptyList())
        val ojk = DraftHistory.record(root, listOf("konsumen@ojk.go.id"), emptyList(), "S", "B", emptyList())
        assertEquals(1, ojk.number)
        assertEquals(2, DraftHistory.listAll(root).size)
    }

    @Test
    fun markOpenedUpdatesMatchingVersionOnlyAndNeverSaysSent() {
        val root = tmp()
        DraftHistory.record(root, to, emptyList(), "S", "B1", emptyList())
        DraftHistory.record(root, to, emptyList(), "S", "B2", emptyList())
        val opened = DraftHistory.markOpened(root, to, emptyList(), "S", "B1", emptyList())
        assertNotNull(opened)
        assertEquals(DraftHistory.STATUS_OPENED, opened!!.status)
        val all = DraftHistory.list(root, DraftHistory.keyFor(to))
        assertEquals(DraftHistory.STATUS_OPENED, all[0].status)
        assertEquals(DraftHistory.STATUS_DRAFT, all[1].status)
        assertFalse(File(root, "draf/RIWAYAT.md").readText().contains("; terkirim;"))
        assertNull(DraftHistory.markOpened(root, to, emptyList(), "S", "tidak ada", emptyList()))
    }

    @Test
    fun emptyRecipientUsesFallbackKey() {
        assertEquals("tanpa-penerima", DraftHistory.keyFor(emptyList()))
        assertEquals("a-b-example-com", DraftHistory.keyFor(listOf("A.B@Example.com")))
    }
}
