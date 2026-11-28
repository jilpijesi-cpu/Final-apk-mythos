package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSyncTest {
    private val inst = AppVersion("2.43", 58L)

    @Test fun salinUlangHanyaBilaVersionCodeBerubahDanTerbaca() {
        assertTrue(SourceSync.needsCopy(inst, 0L))
        assertTrue(SourceSync.needsCopy(inst, 57L))
        assertFalse(SourceSync.needsCopy(inst, 58L))
        assertFalse(SourceSync.needsCopy(null, 0L))
        assertFalse(SourceSync.needsCopy(AppVersion("?", -1L), 0L))
    }

    @Test fun catatanNaikVersiHanyaBilaBenarBenarNaik() {
        val n = SourceSync.upgradeNote(inst, 57L)
        assertNotNull(n)
        assertTrue(n!!.contains("57"))
        assertTrue(n.contains("2.43"))
        assertTrue(n.contains("tetap versi lama"))
        assertNull(SourceSync.upgradeNote(inst, 0L)) // salinan pertama, bukan upgrade
        assertNull(SourceSync.upgradeNote(inst, 58L))
        assertNull(SourceSync.upgradeNote(inst, 60L)) // versi turun: tidak dikarang sebagai "naik"
        assertNull(SourceSync.upgradeNote(null, 57L))
    }

    @Test fun celahSourceMuncul_HanyaBilaSemuaFolderLebihLama() {
        val lama = listOf(FolderVersion("2.42", 57L), FolderVersion("2.40", 55L))
        val n = SourceSync.sourceGapNote(inst, lama)
        assertNotNull(n)
        assertTrue(n!!.contains("kode 57")) // versi tertinggi yang ada
        assertTrue(n.contains("mengimpor zip"))
    }

    @Test fun celahSourceTidakMuncul_BilaAdaYangSamaAtauBaruAtauTidakAdaFakta() {
        assertNull(SourceSync.sourceGapNote(inst, listOf(FolderVersion("2.43", 58L), FolderVersion("2.40", 55L))))
        assertNull(SourceSync.sourceGapNote(inst, listOf(FolderVersion("2.50", 60L))))
        assertNull(SourceSync.sourceGapNote(inst, emptyList()))
        assertNull(SourceSync.sourceGapNote(inst, listOf(FolderVersion(null, null)))) // versi tak terbaca: jangan menuduh
        assertNull(SourceSync.sourceGapNote(AppVersion("?", -1L), listOf(FolderVersion("2.1", 1L))))
        assertNull(SourceSync.sourceGapNote(null, listOf(FolderVersion("2.1", 1L))))
    }

    @Test fun blokKonteksMemuatCatatanSalinDanCelahSource() {
        val b = WorkContextRules.block(
            "~/MYTHOS", listOf("projects/aktif/app"), emptyList(), inst,
            mapOf("projects/aktif/app" to FolderVersion("2.42", 57L)),
            mapOf("mythos-2.42" to FolderVersion("2.42", 57L)),
            "Berangkas disalin ulang."
        )
        assertTrue(b.contains("Berangkas disalin ulang."))
        assertTrue(b.contains("TIDAK ada salinan source berversi terpasang"))
    }

    @Test fun blokKonteksTanpaCelahBilaAdaSourceSama() {
        val b = WorkContextRules.block(
            "~/MYTHOS", listOf("projects/aktif/mythos-2.43"), emptyList(), inst,
            mapOf("projects/aktif/mythos-2.43" to FolderVersion("2.43", 58L))
        )
        assertFalse(b.contains("TIDAK ada salinan source"))
    }

    @Test fun namaFolderDariVersionNameDanMenolakYangTidakAman() {
        assertEquals("mythos-2.43", SourceSync.folderName(inst))
        assertNull(SourceSync.folderName(AppVersion("2/../x", 58L)))
        assertNull(SourceSync.folderName(AppVersion("a b", 58L)))
        assertNull(SourceSync.folderName(AppVersion("..", 58L)))
    }

    @Test fun perintahImporHanyaMenyentuhTujuanDanFolderSementaranya() {
        val c = SourceSync.importCommand("/sdcard/Download/MYTHOS/attachments/mythos-source.zip", "/data/h/MYTHOS/projects/aktif/mythos-2.43")
        assertTrue(c.contains("unzip -q -o '/sdcard/Download/MYTHOS/attachments/mythos-source.zip' -d '/data/h/MYTHOS/projects/aktif/mythos-2.43.kita-tmp'"))
        assertTrue(c.contains("mv '/data/h/MYTHOS/projects/aktif/mythos-2.43.kita-tmp' '/data/h/MYTHOS/projects/aktif/mythos-2.43'"))
        assertTrue(c.contains("SRC_IMPOR=ok"))
        assertFalse(c.contains("reference"))
        assertTrue(SourceSync.importSucceeded("exit=0\nSRC_IMPOR=ok"))
        assertFalse(SourceSync.importSucceeded("exit=1\nSRC_IMPOR=gagal-unzip-tidak-ada"))
        assertFalse(SourceSync.importSucceeded("exit=0\nselesai"))
    }

    @Test fun blokMenunjukFolderSumberAplikasiTerpasangBilaAda() {
        val b = WorkContextRules.block(
            "~/MYTHOS", listOf("projects/aktif/mythos-2.43", "projects/aktif/lama"), emptyList(), inst,
            mapOf(
                "projects/aktif/mythos-2.43" to FolderVersion("2.43", 58L),
                "projects/aktif/lama" to FolderVersion("2.40", 55L)
            )
        )
        assertTrue(b.contains("SOURCE APLIKASI TERPASANG (versi sama): projects/aktif/mythos-2.43."))
        assertFalse(b.contains("versi sama): projects/aktif/mythos-2.43, projects/aktif/lama"))
    }

    @Test fun upgradeDiblokirBilaBuildTidakMengemasSource() {
        assertNull(SourceSync.packBlocker(mapOf("packs_source" to "yes")))
        assertNull(SourceSync.packBlocker(mapOf("packs_source" to " yes ")))
        val no = SourceSync.packBlocker(mapOf("packs_source" to "no"))
        assertNotNull(no)
        assertTrue(no!!.contains("packSource"))
        assertNotNull(SourceSync.packBlocker(emptyMap())) // metadata tak ada (skrip lama): jangan diloloskan
    }
}
