package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FormPlanHistoryTest {
    private val host = "kontak157.ojk.go.id"
    private fun root() = Files.createTempDirectory("mythos-form").toFile()

    @Test
    fun savesVersionsAndSkipsIdenticalPlan() {
        val r = root()
        val a = listOf(FormPlanHistory.Entry("isi", "Uraian", "Keberatan versi 1"))
        val v1 = FormPlanHistory.record(r, host, a)!!
        assertEquals(1, v1.number)
        assertEquals(1, FormPlanHistory.record(r, host, a)!!.number)
        val v2 = FormPlanHistory.record(r, host, listOf(FormPlanHistory.Entry("isi", "Uraian", "Keberatan versi 2")))!!
        assertEquals(2, v2.number)
        val all = FormPlanHistory.list(r, host)
        assertEquals(listOf("Keberatan versi 1", "Keberatan versi 2"), all.map { it.entries.single().value })
        assertTrue(r.resolve("form/kontak157-ojk-go-id/v002.json").isFile)
    }

    @Test
    fun emptyPlanSavesNothing() {
        val r = root()
        assertNull(FormPlanHistory.record(r, host, emptyList()))
        assertTrue(FormPlanHistory.list(r, host).isEmpty())
    }

    @Test
    fun credentialsAreRedactedBeforeSaving() {
        val r = root()
        FormPlanHistory.record(r, host, listOf(FormPlanHistory.Entry("isi", "Catatan", "Kode OTP 123456 jangan dibagikan")))
        val saved = FormPlanHistory.list(r, host).single().entries.single().value
        assertTrue(!saved.contains("123456"))
    }

    @Test
    fun differentSitesKeepSeparateChains() {
        val r = root()
        val e = listOf(FormPlanHistory.Entry("isi", "A", "x"))
        FormPlanHistory.record(r, "kontak157.ojk.go.id", e)
        assertEquals(1, FormPlanHistory.record(r, "www.bi.go.id", e)!!.number)
    }
}
