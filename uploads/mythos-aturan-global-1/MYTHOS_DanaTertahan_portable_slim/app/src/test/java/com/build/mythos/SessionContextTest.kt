package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContextTest {

    @Test fun buildTaskWithHistory_preservesFirstTurnEvenAfterManyTurns() {
        val pairs = (1..12).map { i -> "Permintaan ke-$i: topik-$i" to "Jawaban ke-$i" }
        val built = SessionContext.buildTaskWithHistory(pairs, "Lanjutkan dari awal", maxRecent = 4)
        assertTrue("Giliran pertama wajib dipertahankan", built.contains("Permintaan ke-1: topik-1"))
        assertTrue("Ringkasan giliran tengah harus ada", built.contains("Ringkasan giliran tengah"))
        assertTrue("Giliran terbaru wajib ada", built.contains("Permintaan ke-12: topik-12"))
        assertTrue("Pesan baru harus ada di akhir", built.endsWith("Lanjutkan dari awal"))
    }

    @Test fun buildSystemSessionAnchor_returnsEmptyForSingleTurnAndIncludesOriginForMultiTurn() {
        val oneTurn = listOf(Hist(0, "Halo"))
        assertEquals("", SessionContext.buildSystemSessionAnchor(oneTurn))

        val multi = listOf(
            Hist(0, "Buat aplikasi kalkulator sederhana"),
            Hist(1, "Siap, saya buatkan struktur awalnya."),
            Hist(0, "Sekarang tambahkan mode gelap"),
            Hist(1, "Mode gelap sudah ditambahkan."),
            Hist(0, "Cek lagi tombol persennya")
        )
        val anchor = SessionContext.buildSystemSessionAnchor(multi)
        assertTrue(anchor.contains("KONTEKS SESI LINTAS-AI"))
        assertTrue(anchor.contains("Buat aplikasi kalkulator sederhana"))
    }

    @Test fun buildCliMessageWithHistory_wrapsPriorApiConversation() {
        val hist = listOf(
            Hist(0, "Kita sedang merapikan proyek catatan"),
            Hist(1, "Baik, file catatan.kt sudah saya baca.")
        )
        val wrapped = SessionContext.buildCliMessageWithHistory(hist, "Lanjutkan dengan tambah fitur cari")
        assertTrue(wrapped.contains("Kita sedang merapikan proyek catatan"))
        assertTrue(wrapped.contains("Lanjutkan dengan tambah fitur cari"))
    }

    @Test fun extractPairsAndTask_preservesInterruptedTaskAfterQuotaExhausted() {
        val hist = listOf(
            Hist(0, "Buat halaman profil pengguna"),
            Hist(1, "Siap, struktur awal dibuat."),
            Hist(0, "Tambahkan tombol edit foto dan simpan ke database"),
            Hist(1, "$FAILED_HIST_PREFIX: Kuota token habis (HTTP 429)")
        )
        val pairs = SessionContext.extractPairs(hist)
        assertEquals(2, pairs.size)
        val task = SessionContext.buildTaskWithHistory(pairs, "lanjutkan")
        assertTrue("Harus ada penanda tugas terhenti", task.contains("TUGAS SEBELUMNYA TERHENTI"))
        assertTrue("Permintaan yang tertunda wajib terbawa", task.contains("Tambahkan tombol edit foto"))

        // Juga saat tidak sempat ada balasan AI sama sekali sebelum ganti kunci
        val danglingHist = listOf(
            Hist(0, "Rapikan layout halaman utama")
        )
        val danglingPairs = SessionContext.extractPairs(danglingHist)
        assertEquals(1, danglingPairs.size)
        assertTrue(SessionContext.buildTaskWithHistory(danglingPairs, "lanjutkan").contains("Rapikan layout halaman utama"))
    }
}
