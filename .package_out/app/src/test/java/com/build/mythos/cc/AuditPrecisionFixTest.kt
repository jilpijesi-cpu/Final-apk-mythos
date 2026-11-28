package com.build.mythos.cc

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/** Tes regresi untuk temuan audit presisi (branchFor .lock dan proteksi Zip). */
class AuditPrecisionFixTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun branchForMenghapusAkhiranLockSetelahTrim() {
        assertEquals("mythos/foo", branchFor("foo.lock."))
        assertEquals("mythos/bar", branchFor("bar.lock.lock"))
        assertEquals("mythos/lock", branchFor(".lock."))
        assertEquals("mythos/proyek", branchFor("..."))
    }

    @Test fun zipMenolakOutputDiDalamRootDanBerhasilDiLuarFolder() {
        val root = tmp.newFolder("zipself")
        File(root, "src").mkdirs()
        File(root, "src/a.txt").writeText("halo")
        val outZip = File(root, "arsip.zip")
        outZip.writeText("isi-lama")
        val env = ToolEnv(ProjectSandbox(root), SessionState(), { "" }, null, null)

        // Output di dalam folder yang dikemas ("." memuat "arsip.zip") harus ditolak.
        val inside = runBlocking { Zip().run(JSONObject("""{"path":".","output":"arsip.zip","overwrite":true}"""), env) }
        assertFalse("output di dalam path sumber harus ditolak", inside.ok)

        // Output di luar folder sumber dengan overwrite=true harus berhasil tanpa menyertakan dirinya sendiri.
        val outside = runBlocking { Zip().run(JSONObject("""{"path":"src","output":"arsip.zip","overwrite":true}"""), env) }
        assertTrue(outside.text, outside.ok)
        ZipFile(outZip).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue("a.txt harus ada: $names", "a.txt" in names)
            assertFalse("arsip.zip tidak boleh masuk ke dalam dirinya sendiri: $names", "arsip.zip" in names)
        }
    }
}
