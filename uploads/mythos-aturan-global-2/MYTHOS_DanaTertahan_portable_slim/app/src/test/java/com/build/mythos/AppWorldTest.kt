package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Dua dunia terpisah: hanya CLI yang memakai Termux; agen API bekerja di dalam aplikasi. */
class AppWorldTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((n, c) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(c.toByteArray())
                z.closeEntry()
            }
        }
        return bo.toByteArray()
    }

    @Test fun hanyaCliMemakaiTermux() {
        assertTrue(usesTermuxWorld("cli"))
        assertFalse(usesTermuxWorld("openai"))
        assertFalse(usesTermuxWorld("gemini"))
        assertFalse(usesTermuxWorld("anthropic"))
    }

    @Test fun pathRelatifTerhadapProyek() {
        assertEquals("attachments/a.txt", relativeToProject("/data/files/cc_projects/c1/attachments/a.txt", "/data/files/cc_projects/c1"))
        assertEquals("attachments/a.txt", relativeToProject("/data/files/cc_projects/c1/attachments/a.txt", "/data/files/cc_projects/c1/"))
        // di luar folder proyek: tidak diubah
        assertEquals("/sdcard/x.txt", relativeToProject("/sdcard/x.txt", "/data/files/cc_projects/c1"))
        // folder bersaudara dengan awalan sama bukan bagian proyek
        assertEquals("/data/files/cc_projects/c10/a.txt", relativeToProject("/data/files/cc_projects/c10/a.txt", "/data/files/cc_projects/c1"))
    }

    @Test fun namaLampiranUnikDanAman() {
        val dir = tmp.newFolder("att")
        assertEquals("a.txt", uniqueAttachmentName(dir, "a.txt"))
        File(dir, "a.txt").writeText("x")
        assertEquals("a (1).txt", uniqueAttachmentName(dir, "a.txt"))
        File(dir, "a (1).txt").writeText("x")
        assertEquals("a (2).txt", uniqueAttachmentName(dir, "a.txt"))
        assertEquals("evil.txt", uniqueAttachmentName(dir, "../evil.txt").substringAfterLast('_'))
        assertFalse(uniqueAttachmentName(dir, "../evil.txt").contains("/"))
        assertEquals("file", uniqueAttachmentName(dir, "   "))
    }

    @Test fun ekstrakZipNormal() {
        val dest = File(tmp.root, "out")
        val res = extractZipSafely(ByteArrayInputStream(zipOf("src/Main.kt" to "fun main() {}", "README.md" to "hi")), dest)
        assertNull(res.error)
        assertEquals(2, res.files)
        assertEquals("fun main() {}", File(dest, "src/Main.kt").readText())
        assertEquals("hi", File(dest, "README.md").readText())
    }

    @Test fun zipSlipDitolakDanTidakMenulisKeluar() {
        val dest = tmp.newFolder("dest")
        val res = extractZipSafely(ByteArrayInputStream(zipOf("ok.txt" to "a", "../evil.txt" to "x")), dest)
        assertNotNull(res.error)
        assertTrue(res.error!!.contains("berbahaya"))
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test fun pathAbsolutDitolak() {
        val dest = tmp.newFolder("dest2")
        val res = extractZipSafely(ByteArrayInputStream(zipOf("/etc/passwd" to "x")), dest)
        assertNotNull(res.error)
    }

    @Test fun folderSampahDilewati() {
        val dest = File(tmp.root, "out2")
        val res = extractZipSafely(ByteArrayInputStream(zipOf(".git/config" to "x", "node_modules/a/b.js" to "x", "app.kt" to "k")), dest)
        assertNull(res.error)
        assertEquals(1, res.files)
        assertFalse(File(dest, ".git/config").exists())
        assertTrue(File(dest, "app.kt").exists())
    }

    @Test fun batasUkuranDanJumlahDipatuhi() {
        val big = extractZipSafely(ByteArrayInputStream(zipOf("a.txt" to "x".repeat(50))), File(tmp.root, "o3"), maxBytes = 10)
        assertEquals("ukuran melebihi batas", big.error)
        val many = extractZipSafely(ByteArrayInputStream(zipOf("a.txt" to "1", "b.txt" to "2")), File(tmp.root, "o4"), maxEntries = 1)
        assertEquals("terlalu banyak file", many.error)
    }
}

/** Penyapu folder yatim dunia agen API. */
class AppWorldOrphanTest {
    @Test fun idDisanitasiSamaDenganFolder() {
        assertEquals("c_1_x", sanitizeChatId("c.1/x"))
        assertEquals("chat", sanitizeChatId(""))
        assertEquals("c123", sanitizeChatId("c123"))
    }

    @Test fun hanyaFolderTanpaChatYangDisapu() {
        val dead = orphanProjectDirs(listOf("c1", "c2", "c3"), setOf("c1", "c3"))
        assertEquals(listOf("c2"), dead)
    }

    @Test fun chatDenganIdBerkarakterAnehTidakSalahDisapu() {
        // id "c.1" disimpan sebagai folder "c_1": harus dianggap masih dipakai
        assertEquals(emptyList<String>(), orphanProjectDirs(listOf("c_1"), setOf("c.1")))
    }

    @Test fun daftarChatKosongTidakMenyapuApaPun() {
        assertEquals(emptyList<String>(), orphanProjectDirs(listOf("c1", "c2"), emptySet()))
    }
}
