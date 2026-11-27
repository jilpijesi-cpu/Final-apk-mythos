package com.build.mythos.cc

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Paket alat dalam-aplikasi untuk agen API: file, arsip, hitung, waktu. Semua murni JVM. */
class KitToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun env(root: File, journal: TaskJournal? = null) = ToolEnv(ProjectSandbox(root), SessionState(), { "" }, null, journal)
    private fun run(tool: Tool, json: String, env: ToolEnv): ToolResult = runBlocking { tool.run(JSONObject(json), env) }

    @Test fun mkdirMembuatFolderBersarang() {
        val root = tmp.newFolder("p")
        val e = env(root)
        assertTrue(run(Mkdir(), """{"path":"a/b/c"}""", e).ok)
        assertTrue(File(root, "a/b/c").isDirectory)
        assertTrue(run(Mkdir(), """{"path":"a/b/c"}""", e).text.contains("already exists"))
    }

    @Test fun moveMemindahkanFileDanMewarisiCatatanBaca() {
        val root = tmp.newFolder("p")
        File(root, "a.txt").writeText("isi")
        val e = env(root)
        e.session.known["a.txt"] = 5
        val r = run(Move(), """{"from":"a.txt","to":"sub/b.txt"}""", e)
        assertTrue(r.text, r.ok)
        assertFalse(File(root, "a.txt").exists())
        assertEquals("isi", File(root, "sub/b.txt").readText())
        assertEquals(5, e.session.known["sub/b.txt"])
        assertNull(e.session.known["a.txt"])
    }

    @Test fun moveMenolakTujuanYangSudahAda() {
        val root = tmp.newFolder("p")
        File(root, "a.txt").writeText("1")
        File(root, "b.txt").writeText("2")
        val r = run(Move(), """{"from":"a.txt","to":"b.txt"}""", env(root))
        assertFalse(r.ok)
        assertEquals("2", File(root, "b.txt").readText())
        assertEquals("1", File(root, "a.txt").readText())
    }

    @Test fun moveFolderBesertaIsinya() {
        val root = tmp.newFolder("p")
        File(root, "old/x").mkdirs()
        File(root, "old/x/f.kt").writeText("k")
        File(root, "old/g.kt").writeText("g")
        val r = run(Move(), """{"from":"old","to":"new"}""", env(root))
        assertTrue(r.text, r.ok)
        assertFalse(File(root, "old").exists())
        assertEquals("k", File(root, "new/x/f.kt").readText())
        assertEquals("g", File(root, "new/g.kt").readText())
    }

    @Test fun moveFolderKeDalamDirinyaDitolak() {
        val root = tmp.newFolder("p")
        File(root, "d").mkdirs()
        File(root, "d/f").writeText("x")
        assertFalse(run(Move(), """{"from":"d","to":"d/inner"}""", env(root)).ok)
        assertTrue(File(root, "d/f").exists())
    }

    @Test fun moveTidakBisaKeluarDariProyek() {
        val root = tmp.newFolder("p")
        File(root, "a.txt").writeText("x")
        try {
            run(Move(), """{"from":"a.txt","to":"../out.txt"}""", env(root))
            fail("Move ke luar folder proyek harus melempar SecurityException")
        } catch (_: SecurityException) {
            // diharapkan: ProjectSandbox.resolve menolak path traversal
        }
        assertFalse(File(tmp.root, "out.txt").exists())
        assertTrue(File(root, "a.txt").exists())
        assertEquals("x", File(root, "a.txt").readText())
    }

    @Test fun moveMencatatSetiapFileKeJurnal() {
        val root = tmp.newFolder("p")
        File(root, "a.txt").writeText("x")
        val journal = TaskJournal(root, tmp.newFolder("j"))
        assertTrue(journal.isEmpty)
        assertTrue(run(Move(), """{"from":"a.txt","to":"b.txt"}""", env(root, journal)).ok)
        assertFalse(journal.isEmpty)
    }

    @Test fun copyFileDanFolderMenyisakanAsli() {
        val root = tmp.newFolder("p")
        File(root, "src").mkdirs()
        File(root, "src/a.kt").writeText("a")
        val e = env(root)
        assertTrue(run(Copy(), """{"from":"src","to":"backup/src2"}""", e).ok)
        assertEquals("a", File(root, "backup/src2/a.kt").readText())
        assertEquals("a", File(root, "src/a.kt").readText())
        assertTrue(run(Copy(), """{"from":"src/a.kt","to":"copy.kt"}""", e).ok)
        assertEquals("a", File(root, "copy.kt").readText())
        assertFalse(run(Copy(), """{"from":"src/a.kt","to":"copy.kt"}""", e).ok) // tujuan sudah ada
    }

    @Test fun zipLaluUnzipMelewatiFolderSampah() {
        val root = tmp.newFolder("p")
        File(root, "proj/src").mkdirs()
        File(root, "proj/.git").mkdirs()
        File(root, "proj/node_modules/m").mkdirs()
        File(root, "proj/src/Main.kt").writeText("fun main() {}")
        File(root, "proj/README.md").writeText("hi")
        File(root, "proj/.git/config").writeText("x")
        File(root, "proj/node_modules/m/i.js").writeText("x")
        val e = env(root)
        val z = run(Zip(), """{"path":"proj","output":"dist/proj.zip"}""", e)
        assertTrue(z.text, z.ok)
        val u = run(Unzip(), """{"file_path":"dist/proj.zip","dest":"out"}""", e)
        assertTrue(u.text, u.ok)
        assertEquals("fun main() {}", File(root, "out/src/Main.kt").readText())
        assertEquals("hi", File(root, "out/README.md").readText())
        assertFalse(File(root, "out/.git").exists())
        assertFalse(File(root, "out/node_modules").exists())
    }

    @Test fun zipMenolakKeluaranDiDalamFolderYangDikemas() {
        val root = tmp.newFolder("p")
        File(root, "proj").mkdirs()
        File(root, "proj/a.txt").writeText("a")
        assertFalse(run(Zip(), """{"path":"proj","output":"proj/x.zip"}""", env(root)).ok)
    }

    @Test fun zipMenolakTimpaTanpaOverwrite() {
        val root = tmp.newFolder("p")
        File(root, "a.txt").writeText("a")
        val e = env(root)
        assertTrue(run(Zip(), """{"path":"a.txt","output":"a.zip"}""", e).ok)
        assertFalse(run(Zip(), """{"path":"a.txt","output":"a.zip"}""", e).ok)
        assertTrue(run(Zip(), """{"path":"a.txt","output":"a.zip","overwrite":true}""", e).ok)
    }

    @Test fun unzipMenolakZipSlip() {
        val root = tmp.newFolder("p")
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            z.putNextEntry(ZipEntry("../evil.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        File(root, "bad.zip").writeBytes(bo.toByteArray())
        val r = run(Unzip(), """{"file_path":"bad.zip","dest":"out"}""", env(root))
        assertFalse(r.ok)
        assertFalse(File(root, "evil.txt").exists())
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test fun timeMemberiTanggalHariIni() {
        val r = run(Time(), "{}", env(tmp.newFolder("p")))
        assertTrue(r.ok)
        assertTrue(r.text.matches(Regex("""\d{4}-\d{2}-\d{2}T.*""")))
    }

    @Test fun calcMelaluiAlat() {
        val e = env(tmp.newFolder("p"))
        assertEquals("7", run(Calc(), """{"expression":"1+2*3"}""", e).text)
        assertFalse(run(Calc(), """{"expression":"1/0"}""", e).ok)
        assertFalse(run(Calc(), """{"expression":"foo(1)"}""", e).ok)
    }

    @Test fun webDanExportMemakaiFungsiSuntikan() {
        val root = tmp.newFolder("p")
        File(root, "r.txt").writeText("data")
        val e = env(root)
        assertEquals("hasil", run(WebSearch { _ -> true to "hasil" }, """{"query":"x y"}""", e).text)
        assertFalse(run(WebFetch { _ -> false to "ditolak" }, """{"url":"http://127.0.0.1"}""", e).ok)
        var got: Pair<String, String>? = null
        val r = run(Export { f, n -> got = f.name to n; "Download/MYTHOS/export/$n" }, """{"path":"r.txt","name":"laporan.txt"}""", e)
        assertTrue(r.text, r.ok)
        assertEquals("r.txt" to "laporan.txt", got)
        assertFalse(run(Export { _, _ -> "x" }, """{"path":"tidak-ada.txt"}""", e).ok)
    }

    @Test fun exportSelaluMintaPersetujuanDanFolderDitolak() {
        assertEquals(Risk.DANGEROUS, Export { _, _ -> "" }.risk)
        val root = tmp.newFolder("p")
        File(root, "d").mkdirs()
        assertFalse(run(Export { _, _ -> "x" }, """{"path":"d"}""", env(root)).ok)
    }

    @Test fun registriMemuatPaketLengkapDanSemuaPunyaSkema() {
        val extra = listOf<Tool>(WebSearch { _ -> true to "" }, WebFetch { _ -> true to "" }, Export { _, _ -> "" })
        val reg = ToolRegistry.default(withBuild = false, extra = extra)
        val want = listOf("Mkdir", "Move", "Copy", "Zip", "Unzip", "Time", "Calc", "WebSearch", "WebFetch", "Export", "Read", "Write", "Edit")
        for (n in want) assertTrue("alat $n hilang", reg.get(n) != null)
        val specs = ToolSchemas.specs(reg).associateBy { it.name }
        for (n in listOf("Mkdir", "Move", "Copy", "Zip", "Unzip", "Calc", "WebSearch", "WebFetch", "Export")) {
            assertTrue("skema $n tanpa properti", specs.getValue(n).schema.getJSONObject("properties").length() > 0)
        }
        // alat riset baca-saja tetap SAFE supaya boleh dipakai di mode rencana
        for (n in listOf("Time", "Calc", "WebSearch", "WebFetch")) assertEquals(Risk.SAFE, reg.get(n)!!.risk)
    }
}
