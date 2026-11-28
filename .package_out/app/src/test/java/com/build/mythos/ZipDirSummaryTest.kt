package com.build.mythos

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regresi: daftar file zip dipotong 120 berurut abjad, sehingga app/src/test tak terlihat dan agen mengira tes tidak ada. */
class ZipDirSummaryTest {
    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun bigProject(): ByteArray {
        val main = (1..200).map { "app/src/main/java/com/build/mythos/F$it.kt" to "x" }
        val test = (1..40).map { "app/src/test/java/com/build/mythos/T$it.kt" to "x" }
        return zipOf(*(main + test).toTypedArray())
    }

    @Test fun folderTesTerlihatMeskiDaftarFileTerpotong() {
        val scan = scanZip(ByteArrayInputStream(bigProject()))
        val manifest = renderZipManifest("p.zip", scan, null)
        assertFalse("daftar abjad memang tidak memuat file tes", manifest.contains("T1.kt"))
        assertTrue(manifest, manifest.contains("app/src/test/java/com/build/mythos/ 40"))
        assertTrue(manifest, manifest.contains("app/src/main/java/com/build/mythos/ 200"))
    }

    @Test fun manifestMelarangMenyimpulkanFileTidakAdaDariDaftarTerpotong() {
        val scan = scanZip(ByteArrayInputStream(bigProject()))
        val manifest = renderZipManifest("p.zip", scan, null)
        assertTrue(manifest.contains("file lain"))
        assertTrue(manifest.contains("JANGAN menyimpulkan"))
    }

    @Test fun ringkasanUrutDariTerbanyakDanDibatasi() {
        val files = (1..30).flatMap { d -> (1..d).map { ZipFileInfo("d$d/f$it.txt", 1) } }
        val s = zipDirSummary(files, max = 3)
        assertTrue(s, s.startsWith("Folder dengan file terbanyak: d30/ 30, d29/ 29, d28/ 28"))
        assertTrue(s, s.contains("dan 27 folder lain"))
    }

    @Test fun ringkasanKosongUntukSatuFolderSaja() {
        assertEquals("", zipDirSummary(listOf(ZipFileInfo("a/x.txt", 1), ZipFileInfo("a/y.txt", 1))))
    }

    @Test fun fileDiAkarDihitungSebagaiAkar() {
        val s = zipDirSummary(listOf(ZipFileInfo("README.md", 1), ZipFileInfo("a/x.txt", 1)))
        assertTrue(s, s.contains("(akar) 1"))
        assertTrue(s, s.contains("a/ 1"))
    }
}
