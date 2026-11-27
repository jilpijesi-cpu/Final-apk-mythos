package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkContextTest {
    private val root = "/data/data/com.termux/files/home/MYTHOS/chats/c1730000000000"

    @Test fun proyekDariKeluaranLsTerbaruDuluTanpaFolderOperasional() {
        val ls = listOf(
            "$root/automatic-couscous-fix/",          // hasil ekstrak manual di akar chat
            "$root/projects/aktif/mythos-app/",
            "$root/attachments/", "$root/projects/", "$root/backups/", "$root/memory/",
            "$root/.git/", "$root/notes.txt", "", "exit=0"
        )
        assertEquals(listOf("automatic-couscous-fix", "projects/aktif/mythos-app"), WorkContextRules.projectsFrom(ls, root))
    }

    @Test fun pathAsingDanBertingkatDiabaikan() {
        val ls = listOf("/etc/passwd/", "$root/a/b/", "$root/projects/aktif/x/y/", "$root/projects/aktif//", "$root/../x/")
        assertEquals(emptyList<String>(), WorkContextRules.projectsFrom(ls, root))
    }

    @Test fun jumlahProyekDibatasi() {
        val ls = (1..20).map { "$root/p$it/" }
        assertEquals(WorkContextRules.MAX_PROJECTS, WorkContextRules.projectsFrom(ls, root).size)
    }

    @Test fun permintaanTerakhirDipotongDanDirapikan() {
        val asks = WorkContextRules.recentAsks(listOf("a", " ", "b\n  c", "d", "e", "f".repeat(300)), n = 3, maxChars = 10)
        assertEquals(3, asks.size)
        assertEquals("d", asks[0])
        assertEquals("e", asks[1])
        assertEquals(10, asks[2].length)
        assertTrue(asks[2].endsWith("…"))
    }

    @Test fun blokMemuatFolderProyekPermintaanDanAturan() {
        val b = WorkContextRules.block("~/MYTHOS/chats/c1", listOf("projects/aktif/mythos-app"), listOf("Audit secara mendalam", "Proyek nya"))
        assertTrue(b.contains("~/MYTHOS/chats/c1"))
        assertTrue(b.contains("projects/aktif/mythos-app"))
        assertTrue(b.contains("1) \"Audit secara mendalam\" 2) \"Proyek nya\""))
        assertTrue(b.contains("jangan mencarinya di web"))
    }

    @Test fun blokTanpaProyekTidakMengarangNama() {
        val b = WorkContextRules.block("~/x", emptyList(), emptyList())
        assertTrue(b.contains("Belum ada proyek"))
        assertFalse(b.contains("yang dimaksud:"))
        assertFalse(b.contains("Permintaan pengguna terakhir"))
    }

    // ---- fakta versi aplikasi terpasang vs isi ruang kerja ----

    private val terpasang = AppVersion("2.42", 57L)

    @Test fun versiProyekDanSalinanReferenceTampilDenganTandaBeda() {
        val b = WorkContextRules.block(
            "~/MYTHOS", listOf("projects/aktif/mythos-app", "tmp"), listOf("Audit aplikasi"),
            terpasang,
            mapOf("projects/aktif/mythos-app" to FolderVersion("2.40", 55L)),
            mapOf("automatic-couscous-2" to FolderVersion("2.30", 50L))
        )
        assertTrue(b.contains("TERPASANG di HP ini: versi 2.42 (kode 57)"))
        assertTrue(b.contains("projects/aktif/mythos-app = 2.40 (kode 55) [LEBIH LAMA dari terpasang]"))
        assertTrue(b.contains("automatic-couscous-2 = 2.30 (kode 50) [LEBIH LAMA dari terpasang]"))
        assertFalse(b.contains("tmp = ")) // proyek tanpa versi tidak dikarang
    }

    @Test fun versiSamaDitandaiSamaDanProyekTanpaVersiDiperingatkan() {
        val sama = WorkContextRules.block("~/x", listOf("p"), emptyList(), terpasang, mapOf("p" to FolderVersion("2.42", 57L)))
        assertTrue(sama.contains("p = 2.42 (kode 57) [sama dengan terpasang]"))
        val tanpa = WorkContextRules.block("~/x", listOf("p"), emptyList(), terpasang, emptyMap())
        assertTrue(tanpa.contains("Versi proyek di ruang kerja tidak terbaca"))
        assertFalse(tanpa.contains("[LEBIH LAMA"))
    }

    @Test fun tanpaVersiTerpasangBlokLamaTidakBerubahIsinya() {
        val b = WorkContextRules.block("~/x", emptyList(), emptyList())
        assertFalse(b.contains("TERPASANG di HP ini"))
        assertFalse(b.contains("yang dimaksud:"))
        assertTrue(b.contains("jangan mencarinya di web"))
    }

    @Test fun aturanMenafsirkanTidakBerlakuUntukKataAplikasiDanMewajibkanTanyaBilaBeda() {
        val b = WorkContextRules.block("~/x", listOf("p"), listOf("Audit aplikasi saya"), terpasang)
        assertTrue(b.contains("Aturan ini tidak berlaku bila pesan menyebut \"aplikasi\""))
        assertTrue(b.contains("ATURAN APLIKASI TERPASANG"))
        assertTrue(b.contains("ask_user"))
        assertTrue(b.contains("jangan lanjut diam-diam dengan folder terbaru"))
        assertTrue(b.contains("wajib menyebut folder dan versi yang diperiksa"))
    }
}
