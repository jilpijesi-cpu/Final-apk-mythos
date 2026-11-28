package com.build.mythos

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDigestTest {
    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun blokLampiranDisembunyikanDariTampilan() {
        val block = buildAttachmentBlock(listOf(AttDigest("a.txt", "teks", "isi rahasia")), 5000)
        val text = "tolong kerjakan\nFile terlampir di \"/x/a.txt\"" + block
        assertTrue(text.contains("isi rahasia"))
        val shown = stripAttachmentBlocks(text)
        assertEquals("tolong kerjakan\nFile terlampir di \"/x/a.txt\"", shown)
        assertTrue(stripAttachmentBlocks(text, ATT_PLACEHOLDER).endsWith(ATT_PLACEHOLDER))
    }

    @Test
    fun teksTanpaBlokTidakBerubah() {
        assertEquals("halo", stripAttachmentBlocks("halo"))
    }

    @Test
    fun penandaAkhirDiIsiFileDinetralkan() {
        val block = buildAttachmentBlock(listOf(AttDigest("x.txt", "teks", "awal $ATT_TAG_END akhir")), 5000)
        assertEquals(1, Regex(Regex.escape(ATT_TAG_END)).findAll(block).count())
    }

    @Test
    fun jatahDibagiAdilDanYangPendekUtuh() {
        val out = fitDigests(listOf("pendek", "x".repeat(5000), "y".repeat(5000)), 3000)
        assertEquals("pendek", out[0])
        assertTrue(out[1].length < 5000 && out[1].endsWith(ATT_CUT_MARK))
        assertTrue(out[2].length < 5000 && out[2].endsWith(ATT_CUT_MARK))
        assertTrue(out[1].length + out[2].length <= 3000 + 2 * ATT_CUT_MARK.length + 10)
    }

    @Test
    fun blokKosongBilaTidakAdaIsi() {
        assertEquals("", buildAttachmentBlock(listOf(AttDigest("a.png", "gambar", "")), 1000))
    }

    @Test
    fun entriBerbahayDikenali() {
        assertTrue(isUnsafeEntry("../evil.txt"))
        assertTrue(isUnsafeEntry("a/../../evil.txt"))
        assertTrue(isUnsafeEntry("/etc/passwd"))
        assertTrue(isUnsafeEntry("C:\\Windows\\x"))
        assertFalse(isUnsafeEntry("app/src/Main.kt"))
        assertFalse(isUnsafeEntry("a/b..c/d.txt"))
    }

    @Test
    fun folderNoiseDikenaliTetapiFolderBuildBiasaTidak() {
        assertTrue(isNoiseEntry(".git/config"))
        assertTrue(isNoiseEntry("app/node_modules/x/index.js"))
        assertFalse(isNoiseEntry("app/src/main/java/com/build/mythos/A.kt"))
    }

    @Test
    fun pindaiZipAmanMembacaFileKunci() {
        val bytes = zipOf(
            "proj/README.md" to "# Judul\nisi readme",
            "proj/build.gradle.kts" to "plugins { }",
            "proj/app/src/Main.kt" to "fun main() {}",
            "proj/.git/config" to "[core]"
        )
        val scan = scanZip(ByteArrayInputStream(bytes))
        assertNull(scan.error)
        assertEquals(3, scan.files.size)
        assertEquals(1, scan.noiseSkipped)
        assertEquals(0, scan.unsafe)
        assertTrue(scan.safeToExtract)
        assertEquals(setOf("proj/README.md", "proj/build.gradle.kts"), scan.keyFiles.map { it.first }.toSet())
        val manifest = renderZipManifest("proj.zip", scan, "/tmp/aktif/proj")
        assertTrue(manifest.contains("3 file"))
        assertTrue(manifest.contains("Sudah diekstrak ke: /tmp/aktif/proj"))
        assertTrue(manifest.contains("isi readme"))
        assertTrue(manifest.contains("proj/app/src/Main.kt"))
        assertFalse(manifest.contains(".git/config"))
    }

    @Test
    fun zipDenganPathBerbahayTidakDiekstrakOtomatis() {
        val bytes = zipOf("ok.txt" to "a", "../evil.txt" to "b")
        val scan = scanZip(ByteArrayInputStream(bytes))
        assertEquals(1, scan.unsafe)
        assertEquals(1, scan.files.size)
        assertFalse(scan.safeToExtract)
        assertTrue(renderZipManifest("z.zip", scan, null).contains("path berbahaya"))
    }

    @Test
    fun zipRusakDilaporkanBukanCrash() {
        val scan = scanZip(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))
        // Bukan zip: tanpa entri dan tidak melempar galat.
        assertEquals(0, scan.files.size)
        assertTrue(renderZipManifest("x.zip", scan, null).isNotBlank())
    }

    @Test
    fun daftarFileDipotongUntukZipBesar() {
        val many = (1..300).map { "d/f$it.txt" to "x" }.toTypedArray()
        val scan = scanZip(ByteArrayInputStream(zipOf(*many)))
        val manifest = renderZipManifest("big.zip", scan, null)
        assertTrue(manifest.contains("300 file"))
        assertTrue(manifest.contains("dan ${300 - ZIP_LIST_MAX} file lain"))
    }

    @Test
    fun namaFolderAman() {
        assertEquals("myth0s-patched", safeFolderName("Myth0s_patched.zip"))
        assertEquals("lampiran", safeFolderName("???.zip"))
        assertEquals("a-b", safeFolderName("A  B.zip"))
    }

    @Test
    fun perintahEkstrakDanParsingHasil() {
        val cmd = buildExtractCommand("/h/a b.zip", "/h/MYTHOS/projects/aktif", "proj") { "'" + it + "'" }
        assertTrue(cmd.contains("'/h/a b.zip'"))
        assertTrue(cmd.contains("MYTHOS_UNZIP_DIR="))
        assertTrue(cmd.contains("unzip -o -q -d"))
        val guarded = buildExtractCommand("/h/a.zip", "/h/MYTHOS/projects/aktif", "proj", "/h/MYTHOS/reference") { "'" + it + "'" }
        assertTrue(guarded.contains("realpath -m"))
        assertTrue(guarded.contains("[ -L \"\$d\" ]"))
        assertTrue(guarded.contains("MYTHOS_REFERENCE_BLOCKED"))
        assertTrue(guarded.indexOf("parent_real=\$(realpath") < guarded.indexOf("mkdir -p \"\$P\""))
        assertEquals("/h/MYTHOS/projects/aktif/proj-2", parseExtractDir("exit=0\nMYTHOS_UNZIP_DIR=/h/MYTHOS/projects/aktif/proj-2\n"))
        assertNull(parseExtractDir("exit=3\nMYTHOS_UNZIP=missing"))
    }

    @Test
    fun docxDiekstrakJadiTeks() {
        val xml = "<w:document><w:body><w:p><w:r><w:t>Halo</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Dunia &amp; teman</w:t></w:r></w:p></w:body></w:document>"
        val text = docxToText(zipOf("word/document.xml" to xml), 1000)
        assertNotNull(text)
        assertEquals("Halo\nDunia & teman", text)
        assertNull(docxToText(zipOf("lain.txt" to "x"), 1000))
    }

    @Test
    fun bacaTeksTerbatasDanDeteksiBiner() {
        val r = readTextLimited(ByteArrayInputStream("abc".repeat(1000).toByteArray()), 100)
        assertNotNull(r)
        assertEquals(100, r!!.first.length)
        assertTrue(r.second)
        val small = readTextLimited(ByteArrayInputStream("halo".toByteArray()), 100)!!
        assertEquals("halo", small.first)
        assertFalse(small.second)
        assertNull(readTextLimited(ByteArrayInputStream(byteArrayOf(0x50, 0x4B, 0, 0, 1)), 100))
    }

    @Test
    fun jenisLampiran() {
        assertEquals("gambar", attachmentKind("a.png"))
        assertEquals("zip", attachmentKind("a.ZIP"))
        assertEquals("pdf", attachmentKind("a.pdf"))
        assertEquals("docx", attachmentKind("a.docx"))
        assertEquals("teks", attachmentKind("Main.kt"))
        assertEquals("file", attachmentKind("a.bin"))
    }

    @Test
    fun jatahIsiLampiranMengikutiAnggaran() {
        assertEquals(24_000, digestTotalLimit(100_000, false))
        assertEquals(800, digestTotalLimit(2_000, false))
        assertEquals(1_000, digestTotalLimit(4_000, false))
        assertEquals(2_500, digestTotalLimit(100_000, true))
        assertEquals(2_000, digestTotalLimit(8_000, true))
    }

    @Test
    fun catatanLampiranAktifMemuatSemuaDanDibatasi() {
        val list = listOf(
            ActiveAtt("a.zip", "zip", "/p/a.zip", "5 file", "/p/aktif/a"),
            ActiveAtt("b.png", "gambar", "/p/b.png", "gambar")
        )
        val note = activeAttNote(list, 4000)
        assertTrue(note.contains("LAMPIRAN AKTIF"))
        assertTrue(note.contains("a.zip") && note.contains("folder=/p/aktif/a"))
        assertTrue(note.contains("b.png"))
        val tight = activeAttNote(list, 330, tools = false)
        assertTrue(tight.contains("b.png"))
        assertFalse(tight.contains("periksa"))
        assertEquals("", activeAttNote(emptyList(), 1000))
    }

    @Test
    fun gabungLampiranGantiNamaSamaDanBatasJumlah() {
        val old = (1..ACTIVE_ATT_MAX).map { ActiveAtt("f$it", "teks", "/p/f$it", "") }
        val merged = mergeActive(old, listOf(ActiveAtt("f2", "teks", "/p/baru", "baru"), ActiveAtt("g", "teks", "/p/g", "")))
        assertEquals(ACTIVE_ATT_MAX, merged.size)
        assertEquals("g", merged.last().name)
        assertEquals(1, merged.count { it.name == "f2" })
        assertEquals("/p/baru", merged.first { it.name == "f2" }.path)
    }

    @Test
    fun lampiranAktifBolakBalikJson() {
        val list = listOf(ActiveAtt("a b.zip", "zip", "/p/a b.zip", "ringkas \"x\"", "/d"))
        val back = activeAttsFromJson(activeAttsToJson(list))
        assertEquals(1, back.size)
        assertEquals("ringkas \"x\"", back[0].summary)
        assertEquals("/d", back[0].dir)
        assertTrue(activeAttsFromJson("").isEmpty())
    }

    @Test
    fun gambarLamaDilepasDariKonteksHanyaTigaTerakhirDipakai() {
        val list = (1..5).flatMap { i -> listOf(Hist(0, "m$i", images = listOf(HistImage("/p/$i.jpg"))), Hist(1, "ok$i")) }
        val out = limitLiveImages(list)
        val live = out.indices.filter { out[it].images.isNotEmpty() }
        assertEquals(listOf(4, 6, 8), live)
        assertTrue(out[0].text.contains("Gambar lama tidak dikirim ulang"))
        assertEquals("m1", list[0].text) // riwayat asli tidak diubah
        assertEquals(1, list[0].images.size)
    }

    @Test
    fun gambarYangSudahReleasedTidakDihitung() {
        val img = HistImage("/p/x.jpg").also { it.released = true }
        val list = listOf(Hist(0, "a", images = listOf(img)), Hist(0, "b", images = listOf(HistImage("/p/y.jpg"))))
        assertEquals(list, limitLiveImages(list, keep = 1))
    }

    @Test
    fun pemangkasanMembuangBlokLampiranLamaBukanMemotongSembarang() {
        val big = buildAttachmentBlock(listOf(AttDigest("a.txt", "teks", "z".repeat(5000))), 6000)
        val list = listOf(
            Hist(0, "req1" + big), Hist(1, "ok"),
            Hist(0, "req2"), Hist(1, "ok"),
            Hist(0, "req3")
        )
        val out = trimContext(list, 1000)
        assertTrue(out[0].text.contains(ATT_PLACEHOLDER))
        assertFalse(out[0].text.contains("zzzz"))
        assertTrue(out[0].text.startsWith("req1"))
    }

    @Test
    fun anggaranBesarMempertahankanLimaPesanPenggunaKecilTiga() {
        fun chat() = (1..6).flatMap { listOf(Hist(0, "u$it" + "x".repeat(20_000)), Hist(1, "a$it")) }
        val big = trimContext(chat(), 45_000)
        assertEquals(KEEP_USER_TURNS_BIG, big.count { it.role == 0 })
        val small = trimContext(chat(), 39_000)
        assertEquals(KEEP_USER_TURNS, small.count { it.role == 0 })
    }
}
