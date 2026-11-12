package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserActionGuardTest {
    @Test
    fun finalActionsFromRequirementListAreGuarded() {
        listOf(
            "Setujui syarat dan ketentuan", "Kirim tanggapan", "Tutup kasus", "Terima penyelesaian",
            "Ajukan keberatan", "Unggah final", "Hapus dokumen", "Submit", "Kirim", "Bayar sekarang",
            "Saya setuju", "Tarik pengaduan", "Close case", "Delete file",
        ).forEach { assertTrue("harus dijaga: $it", BrowserActionGuard.isFinal(it)) }
    }

    @Test
    fun harmlessActionsAreNotGuarded() {
        listOf("Lanjut", "Berikutnya", "Simpan draf", "Kembali", "Cari", "Lihat detail", "Pilih berkas", "Next")
            .forEach { assertFalse("tidak perlu konfirmasi: $it", BrowserActionGuard.isFinal(it)) }
    }

    @Test
    fun actualButtonTextIsCheckedNotJustInstruction() {
        // Instruksi tampak aman, tetapi tombol sebenarnya "Kirim Tanggapan".
        assertTrue(BrowserActionGuard.isFinal("klik lanjut", "Kirim Tanggapan"))
        assertNull(BrowserActionGuard.reasonFor("klik lanjut", "Lanjut"))
    }

    @Test
    fun wordsInsideOtherWordsDoNotTrigger() {
        assertFalse(BrowserActionGuard.isFinal("Pengirim surat"))   // bukan "kirim" utuh
        assertFalse(BrowserActionGuard.isFinal("Terhapus otomatis")) // bukan "hapus" utuh
    }

    @Test
    fun reasonIsHumanReadable() {
        assertEquals("menutup kasus", BrowserActionGuard.reasonFor("tutup kasus"))
        assertEquals("menghapus data", BrowserActionGuard.reasonFor("hapus dokumen"))
    }

    @Test
    fun wizardAndGenericConfirmButtonsAreGuarded() {
        listOf("Selesai", "Simpan", "Laporkan", "Buat pengaduan", "Proses sekarang")
            .forEach { assertTrue("harus dijaga: $it", BrowserActionGuard.isFinal(it)) }
        listOf("OK", "Ya", "Oke", "Lanjutkan", "Yes").forEach {
            assertNotNull("tombol konfirmasi: $it", BrowserActionGuard.reasonForButton("klik $it", it))
        }
    }

    @Test
    fun draftSavingStaysHarmless() {
        assertFalse(BrowserActionGuard.isFinal("Simpan draf"))
        assertFalse(BrowserActionGuard.isFinal("Simpan sementara"))
        assertNull(BrowserActionGuard.reasonForButton("klik simpan draf", "Simpan Draf"))
    }

    @Test
    fun submitTypeButtonIsGuardedEvenWithInnocentText() {
        assertNull(BrowserActionGuard.reasonForButton("klik Lanjut", "Lanjut", isSubmit = false))
        assertEquals("mengirim formulir", BrowserActionGuard.reasonForButton("klik Lanjut", "Lanjut", isSubmit = true))
        // kata final tetap menang atas sinyal submit (alasan lebih spesifik)
        assertEquals("mengirim data", BrowserActionGuard.reasonForButton("klik kirim", "Kirim", isSubmit = true))
    }

    @Test
    fun yesAsRadioValueIsNotAButtonClick() {
        // "Ya" sebagai nilai radio kuesioner tidak boleh memicu dialog (hanya reasonForButton yang memeriksa teks polos)
        assertNull(BrowserActionGuard.reasonFor("pilih radio Ya pada Pernah mengadu", "Ya"))
    }

    @Test
    fun consentWordingWithPrefixIsGuarded() {
        // "menyetujui" tidak mengandung kata "setuju" utuh, jadi harus didaftar sendiri.
        listOf("Saya menyetujui syarat dan ketentuan", "menyetujui syarat", "Persetujuan", "I agree")
            .forEach { assertEquals("harus dijaga: $it", "memberi persetujuan", BrowserActionGuard.reasonFor(it)) }
        // Tautan untuk MEMBACA syarat tidak perlu konfirmasi.
        assertNull(BrowserActionGuard.reasonFor("Syarat dan Ketentuan"))
        assertNull(BrowserActionGuard.reasonFor("Berlangganan newsletter"))
    }
}
