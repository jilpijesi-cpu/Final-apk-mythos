package com.build.mythos

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BerangkasTest {
    private val sums = Berangkas.parseSums(
        """
        ${Berangkas.sha256Hex("isi-a".toByteArray())}  ./ATURAN.md
        ${Berangkas.sha256Hex("isi-b".toByteArray())}  ./skrip/kita-cek.sh
        ${Berangkas.sha256Hex("isi-c".toByteArray())}  ./skrip/build-github.sh
        """.trimIndent()
    )

    @Test fun jalurAmanDiterima() {
        assertEquals("", Berangkas.safePath(""))
        assertEquals("", Berangkas.safePath("."))
        assertEquals("", Berangkas.safePath("./"))
        assertEquals("skrip/kita-cek.sh", Berangkas.safePath("skrip/kita-cek.sh"))
        assertEquals("skrip", Berangkas.safePath("./skrip/"))
        assertEquals("ATURAN.md", Berangkas.safePath("  ATURAN.md  "))
    }

    @Test fun jalurBerbahayaDitolak() {
        for (bad in listOf("../x", "skrip/../../x", "/etc/passwd", "a//b", "a b", "a;rm -rf", "\$HOME", "a`b`", "skrip/..", "~/x", "a\nb")) {
            assertNull("harus ditolak: $bad", Berangkas.safePath(bad))
        }
    }

    @Test fun sumsDibacaDanJalurTakAmanDilewati() {
        assertEquals(setOf("ATURAN.md", "skrip/kita-cek.sh", "skrip/build-github.sh"), sums.keys)
        val m = Berangkas.parseSums("${"a".repeat(64)}  ../../etc/passwd\nbukan-hash  x\n${"b".repeat(64)}  ./SHA256SUMS\n")
        assertTrue(m.isEmpty())
    }

    @Test fun verifikasiIntegritas() {
        assertNull(Berangkas.verify("ATURAN.md", "isi-a".toByteArray(), sums))
        assertNotNull(Berangkas.verify("ATURAN.md", "isi-DIUBAH".toByteArray(), sums))
        assertNotNull(Berangkas.verify("tidak-terdaftar.md", "isi-a".toByteArray(), sums))
    }

    @Test fun daftarIsiDanBerkasDibawahJalur() {
        val all = sums.keys
        assertEquals(listOf("ATURAN.md", "skrip/"), Berangkas.listDir(all, ""))
        assertEquals(listOf("build-github.sh", "kita-cek.sh"), Berangkas.listDir(all, "skrip"))
        assertEquals(listOf("ATURAN.md"), Berangkas.filesUnder(all, "ATURAN.md"))
        assertEquals(listOf("skrip/build-github.sh", "skrip/kita-cek.sh"), Berangkas.filesUnder(all, "skrip"))
        assertEquals(3, Berangkas.filesUnder(all, "").size)
        assertTrue(Berangkas.filesUnder(all, "tidak-ada").isEmpty())
        assertTrue(Berangkas.filesUnder(all, "skri").isEmpty()) // awalan nama bukan folder
    }

    @Test fun perintahSalinDitulisUlangDariAwalDanTanpaPenafsiranShell() {
        val isi = "echo \$HOME; rm -rf /; `x`\n".toByteArray()
        val cmd = Berangkas.copyCommand(listOf("skrip/kita-cek.sh" to isi), "skrip")
        assertTrue(cmd.contains("rm -rf \"\$D/skrip\""))
        assertTrue(cmd.contains("base64 -d > \"\$D/skrip/kita-cek.sh\" <<'KITA_B64_EOF'"))
        assertTrue(cmd.contains("chmod +x \"\$D/skrip/kita-cek.sh\""))
        assertTrue(cmd.contains("BERANGKAS_SALIN=ok"))
        assertFalse("isi berkas tidak boleh muncul mentah", cmd.contains("rm -rf /;"))
    }

    @Test fun salinSemuaMenghapusSeluruhTujuan() {
        val cmd = Berangkas.copyCommand(listOf("ATURAN.md" to ByteArray(0)), "")
        assertTrue(cmd.contains("rm -rf \"\$D\"\n"))
    }

    @Test(expected = IllegalArgumentException::class) fun salinMenolakJalurTakAman() {
        Berangkas.copyCommand(listOf("../x" to ByteArray(1)), "")
    }

    @Test fun keberhasilanSalinan() {
        assertTrue(Berangkas.copySucceeded("exit=0\nBERANGKAS_SALIN=ok JUMLAH=2 TUJUAN=/x"))
        assertFalse(Berangkas.copySucceeded("exit=1\nBERANGKAS_SALIN=ok"))
        assertFalse(Berangkas.copySucceeded("exit=0\nselesai"))
    }

    /** Penjaga CI: isi assets/berangkas harus persis cocok dengan SHA256SUMS (buat ulang bila isi berangkas diubah). */
    @Test fun isiBerangkasCocokDenganSha256sums() {
        val dir = listOf(File("src/main/assets/berangkas"), File("app/src/main/assets/berangkas"), File("../app/src/main/assets/berangkas"))
            .firstOrNull { it.isDirectory }
        assertNotNull("folder berangkas tidak ditemukan dari direktori kerja tes", dir)
        dir!!
        val listed = Berangkas.parseSums(File(dir, Berangkas.SUMS_FILE).readText())
        assertTrue(listed.isNotEmpty())
        val onDisk = dir.walkTopDown().filter { it.isFile && it.name != Berangkas.SUMS_FILE }
            .map { it.relativeTo(dir).path.replace('\\', '/') }.toSet()
        assertEquals("berkas di disk harus sama dengan yang terdaftar", listed.keys, onDisk)
        for ((path, _) in listed) assertNull(path, Berangkas.verify(path, File(dir, path).readBytes(), listed))
    }

    @Test fun alatBerangkasTerdaftarDiPanduan() {
        assertTrue("berangkas_baca" in ToolDocs.NAMES)
        assertTrue("berangkas_salin" in ToolDocs.NAMES)
        assertTrue(ToolDocs.REFERENCE.contains("kita-cek.sh"))
    }
}
