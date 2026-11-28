package com.build.mythos

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun menulisDanMenimpaTanpaSisaSementara() {
        val f = File(tmp.root, "data.json")
        atomicWriteText(f, "satu")
        atomicWriteText(f, "dua")
        assertEquals("dua", f.readText())
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun gagalMelemparExceptionDanTidakMerusakTargetLama() {
        // target berupa direktori tidak kosong: pemindahan harus gagal dan isi direktori target tetap utuh
        val dir = File(tmp.root, "dir").also { it.mkdirs(); File(it, "x").writeText("x") }
        var gagal = false
        try { atomicWriteText(dir, "baru") } catch (e: Exception) { gagal = true }
        assertTrue(gagal)
        assertEquals("x", File(dir, "x").readText())
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun cleanStaleTempsHanyaMenghapusYangUsang() {
        val lama = File(tmp.root, "a.tmp").also { it.writeText("x"); it.setLastModified(1_000L) }
        val baru = File(tmp.root, "b.tmp").also { it.writeText("x") }
        val biasa = File(tmp.root, "c.json").also { it.writeText("x"); it.setLastModified(1_000L) }
        val n = cleanStaleTemps(tmp.root, olderThanMs = 60_000L)
        assertEquals(1, n)
        assertFalse(lama.exists()); assertTrue(baru.exists()); assertTrue(biasa.exists())
    }
}
