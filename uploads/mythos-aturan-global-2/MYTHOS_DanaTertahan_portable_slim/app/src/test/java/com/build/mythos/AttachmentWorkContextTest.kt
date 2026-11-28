package com.build.mythos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lampiran yang sudah disalin tidak boleh dilaporkan sebagai "folder kerja kosong" ke agen. */
class AttachmentWorkContextTest {
    private val root = "~/MYTHOS/chats/c1"
    private val ref = "~/MYTHOS/reference"
    private val inst = AppVersion("2.44", 59L)
    private val refVer = mapOf("mythos-2.44" to FolderVersion("2.44", 59L))

    @Test fun adaLampiranTanpaProyekTidakDisebutKosong() {
        val b = WorkContextRules.block(root, emptyList(), emptyList(), inst, emptyMap(), refVer, null, ref, listOf("laporan.pdf", "data.csv"))
        assertTrue(b.contains("TIDAK kosong"))
        assertTrue(b.contains("laporan.pdf"))
        assertTrue(b.contains("$root/attachments/"))
        assertFalse(b.contains("KOSONG"))
        assertTrue(b.contains("$ref/mythos-2.44")) // petunjuk reference tetap ada
    }

    @Test fun daftarProyekBelumDipindaiBukanKosong() {
        val b = WorkContextRules.block(root, emptyList(), emptyList(), inst, emptyMap(), refVer, null, ref, emptyList(), projectsKnown = false)
        assertTrue(b.contains("BUKAN berarti kosong"))
        assertFalse(b.contains("Belum ada proyek di ruang kerja ini."))
        assertFalse(b.contains("KOSONG"))
    }

    @Test fun perilakuLamaTetapUntukRuangKerjaBenarBenarKosong() {
        val b = WorkContextRules.block(root, emptyList(), emptyList(), inst, emptyMap(), refVer, null, ref)
        assertTrue(b.contains("Belum ada proyek di ruang kerja ini."))
        assertTrue(b.contains("KOSONG"))
    }

    @Test fun hintDefaultMasihMenyebutKosong() {
        assertTrue(WorkContextRules.emptyWorkspaceHint(root, ref, listOf("a"))!!.contains("KOSONG"))
        assertFalse(WorkContextRules.emptyWorkspaceHint(root, ref, listOf("a"), claimEmpty = false)!!.contains("KOSONG"))
    }
}
