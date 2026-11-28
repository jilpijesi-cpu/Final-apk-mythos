package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCheckTest {
    private val installed = AppVersion("2.42", 57L)

    @Test fun bandingkanSamaLebihLamaLebihBaruDanTakDiketahui() {
        assertEquals(VersionRel.SAME, VersionCheck.compare(installed, FolderVersion("2.42", 57L)))
        assertEquals(VersionRel.FOLDER_OLDER, VersionCheck.compare(installed, FolderVersion("2.40", 55L)))
        assertEquals(VersionRel.FOLDER_NEWER, VersionCheck.compare(installed, FolderVersion("2.50", 60L)))
        assertEquals(VersionRel.UNKNOWN, VersionCheck.compare(installed, FolderVersion("2.42", null)))
        assertEquals(VersionRel.UNKNOWN, VersionCheck.compare(installed, null))
        // versionCode terpasang tidak terbaca (-1): jangan menuduh folder lebih baru atau lama
        assertEquals(VersionRel.UNKNOWN, VersionCheck.compare(AppVersion("?", -1L), FolderVersion("2.42", 57L)))
    }

    @Test fun parseOneMembacaBarisDiTengahKeluaranLain() {
        val out = "KITA_CHECK|ok|settings|ada: settings.gradle.kts\nKITA_CHECK_DONE\nKITA_VERSION|55|2.40\nexit=0"
        val v = VersionCheck.parseOne(out)
        assertNotNull(v)
        assertEquals(55L, v!!.code)
        assertEquals("2.40", v.name)
    }

    @Test fun parseOneNullBilaTidakAdaBarisDanNamaKosongMenjadiNull() {
        assertNull(VersionCheck.parseOne("KITA_CHECK_DONE\nexit=0"))
        val v = VersionCheck.parseOne("KITA_VERSION|57|")!!
        assertEquals(57L, v.code)
        assertNull(v.name)
        assertNull(VersionCheck.parseOne("KITA_VERSION|abc|2.1")!!.code)
    }

    @Test fun parseDirsMemetakanPathRelatifDanMenolakPathAsing() {
        val root = "/home/u/MYTHOS"
        val lines = listOf(
            "/home/u/MYTHOS/projects/aktif/app/",
            "KITA_DIRVER|/home/u/MYTHOS/projects/aktif/app/|57|2.42",
            "KITA_DIRVER|/home/u/MYTHOS/reference/old/|50|2.30",
            "KITA_DIRVER|/etc/x/|1|1.0",
            "KITA_DIRVER|/home/u/MYTHOS/../x/|1|1.0",
            "KITA_DIRVER|/home/u/MYTHOS/rusak/|57",
            "KITA_DIRVER|/home/u/MYTHOS/tanpa-versi/||"
        )
        val m = VersionCheck.parseDirs(lines, root)
        assertEquals(setOf("projects/aktif/app", "reference/old", "tanpa-versi"), m.keys)
        assertEquals(57L, m["projects/aktif/app"]!!.code)
        assertEquals("2.30", m["reference/old"]!!.name)
        assertNull(m["tanpa-versi"]!!.code)
        // root berbeda (reference sebagai akar) menghasilkan kunci nama subfolder saja
        assertEquals(setOf("old"), VersionCheck.parseDirs(lines, "/home/u/MYTHOS/reference").keys)
    }

    @Test fun pesanFolderLamaMenyebutKeduaVersiDanMelarangMenyimpulkan() {
        val m = VersionCheck.message(installed, FolderVersion("2.40", 55L))!!
        assertTrue(m.contains("LEBIH LAMA"))
        assertTrue(m.contains("2.40 (kode 55)"))
        assertTrue(m.contains("2.42 (kode 57)"))
        assertTrue(m.contains("jangan menyimpulkan"))
    }

    @Test fun pesanFolderBaruHanyaCatatanDanSamaAtauTakDiketahuiTanpaPesan() {
        val m = VersionCheck.message(installed, FolderVersion("2.50", 60L))!!
        assertTrue(m.contains("lebih baru"))
        assertFalse(m.contains("VERSI BERBEDA"))
        assertNull(VersionCheck.message(installed, FolderVersion("2.42", 57L)))
        assertNull(VersionCheck.message(installed, FolderVersion(null, null)))
        assertNull(VersionCheck.message(installed, null))
    }

    @Test fun temuanAuditFolderLamaTinggiFolderBaruInfoSamaTidakAda() {
        val lama = VersionCheck.finding(mapOf("version_code" to "55", "version_name" to "2.40"), installed)!!
        assertEquals(Sev.HIGH, lama.sev)
        assertEquals("V01", lama.id)
        val baru = VersionCheck.finding(mapOf("version_code" to "60", "version_name" to "2.50"), installed)!!
        assertEquals(Sev.INFO, baru.sev)
        assertEquals("V02", baru.id)
        assertNull(VersionCheck.finding(mapOf("version_code" to "57", "version_name" to "2.42"), installed))
        assertNull(VersionCheck.finding(emptyMap(), installed))
        assertNull(VersionCheck.finding(mapOf("version_code" to " ", "version_name" to ""), installed))
    }

    @Test fun skripProbeTidakMembawaTandaPenggantiDanMemuatPenandaYangDibaca() {
        val one = VersionCheck.oneProbe()
        assertFalse(one.contains('§'))
        assertTrue(one.contains("KITA_VERSION|"))
        assertTrue(one.contains("app/build.gradle.kts"))
        val dir = VersionCheck.dirProbe("'/a/projects/aktif'/*/ '/a'/*/")
        assertFalse(dir.contains('§'))
        assertFalse(dir.contains("%LS%"))
        assertTrue(dir.contains("ls -1dt -- '/a/projects/aktif'/*/ '/a'/*/"))
        assertTrue(dir.contains("KITA_DIRVER|"))
    }
}
