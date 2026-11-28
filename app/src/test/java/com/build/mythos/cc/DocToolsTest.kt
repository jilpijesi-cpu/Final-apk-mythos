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

/** MakeDocument (docx/xlsx/pptx/pdf), Write base64, dan Present: murni JVM. */
class DocToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun env(root: File) = ToolEnv(ProjectSandbox(root), SessionState(), { "" }, null, null)
    private fun run(tool: Tool, json: String, env: ToolEnv): ToolResult = runBlocking { tool.run(JSONObject(json), env) }
    private fun parts(f: File): Set<String> = ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.toSet() }

    @Test fun docxBerisiBagianWajibDanTeks() {
        val root = tmp.newFolder("p")
        val r = run(MakeDocument(), """{"path":"out/a.docx","title":"Laporan","markdown":"# Bab\n\nIsi **tebal**.\n- satu\n| A | B |\n|---|---|\n| 1 | 2 |"}""", env(root))
        assertTrue(r.text, r.ok)
        val f = File(root, "out/a.docx")
        assertTrue(parts(f).containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml")))
        val xml = ZipFile(f).use { z -> z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8) }
        assertTrue(xml.contains("Heading1") && xml.contains("Laporan") && xml.contains("<w:tbl>"))
        assertFalse(File(root, "out/a.docx.part").exists())
    }

    @Test fun xlsxMenulisAngkaRumusDanTeks() {
        val root = tmp.newFolder("p")
        val r = run(MakeDocument(), """{"path":"d.xlsx","sheets":[{"name":"Data","rows":[["Item","Qty","Total"],["Pensil","10","=B2*2"]]}]}""", env(root))
        assertTrue(r.text, r.ok)
        val xml = ZipFile(File(root, "d.xlsx")).use { z -> z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).readBytes().toString(Charsets.UTF_8) }
        assertTrue(xml.contains("<v>10</v>") && xml.contains("<f>B2*2</f>") && xml.contains("Pensil"))
    }

    @Test fun pptxMemecahSlidePadatDanPdfBerkepalaBenar() {
        val root = tmp.newFolder("p")
        val bullets = (1..9).joinToString(",") { "\"b$it\"" }
        assertTrue(run(MakeDocument(), """{"path":"s.pptx","slides":[{"title":"Padat","bullets":[$bullets]}]}""", env(root)).ok)
        val names = parts(File(root, "s.pptx"))
        assertTrue(names.contains("ppt/slides/slide1.xml") && names.contains("ppt/slides/slide2.xml"))
        assertTrue(run(MakeDocument(), """{"path":"p.pdf","title":"T","markdown":"Halo dunia"}""", env(root)).ok)
        val head = File(root, "p.pdf").readBytes().copyOf(5).toString(Charsets.ISO_8859_1)
        assertEquals("%PDF-", head)
    }

    @Test fun masukanTidakLengkapDitolakTanpaMembuatFile() {
        val root = tmp.newFolder("p")
        assertFalse(run(MakeDocument(), """{"path":"a.docx"}""", env(root)).ok)
        assertFalse(run(MakeDocument(), """{"path":"a.xlsx"}""", env(root)).ok)
        assertFalse(run(MakeDocument(), """{"path":"a.pptx"}""", env(root)).ok)
        assertFalse(run(MakeDocument(), """{"path":"a.exe","markdown":"x"}""", env(root)).ok)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun writeBase64MenulisByteAsli() {
        val root = tmp.newFolder("p")
        val bytes = byteArrayOf(0, 1, 2, -1, -2, 127)
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val r = run(Write(), """{"file_path":"img/x.bin","content":"$b64","encoding":"base64"}""", env(root))
        assertTrue(r.text, r.ok)
        assertTrue(bytes.contentEquals(File(root, "img/x.bin").readBytes()))
        assertFalse(run(Write(), """{"file_path":"y.bin","content":"@@bukan base64@@","encoding":"base64"}""", env(root)).ok)
    }

    @Test fun presentMenyerahkanFileDanMenolakYangTidakAda() {
        val root = tmp.newFolder("p")
        File(root, "a.txt").writeText("halo")
        val got = ArrayList<Pair<String, String>>()
        val tool = Present { f, n -> got.add(f.name to n); null }
        val ok = run(tool, """{"paths":["a.txt"],"names":["Nama Tampil.txt"]}""", env(root))
        assertTrue(ok.text, ok.ok)
        assertEquals(listOf("a.txt" to "Nama Tampil.txt"), got)
        assertFalse(run(tool, """{"paths":["tidak-ada.pdf"]}""", env(root)).ok)
        assertFalse(run(Present { _, _ -> "gagal" }, """{"paths":["a.txt"]}""", env(root)).ok)
        assertFalse(run(tool, """{"paths":[]}""", env(root)).ok)
    }

    @Test fun presentFolderDisuruhDiZipDulu() {
        val root = tmp.newFolder("p")
        File(root, "dir").mkdirs()
        val r = run(Present { _, _ -> null }, """{"paths":["dir"]}""", env(root))
        assertFalse(r.ok)
        assertTrue(r.text.contains("Zip"))
    }
}
