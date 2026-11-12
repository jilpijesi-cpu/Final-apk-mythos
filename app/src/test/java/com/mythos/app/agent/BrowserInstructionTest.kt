package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserInstructionTest {
    private fun p(s: String) = BrowserInstruction.parse(s)

    @Test fun readAndInspect() {
        assertEquals(BrowserCommand.Read, p("baca halaman"))
        assertEquals(BrowserCommand.Inspect, p("periksa semua kolom"))
    }

    @Test fun fillKeepsOriginalBehaviour() {
        assertEquals(BrowserCommand.Fill("Usulan Penyelesaian", "Kembalikan dana"), p("isi kolom Usulan Penyelesaian dengan Kembalikan dana"))
        assertEquals(BrowserCommand.Fill("Nama", "Budi"), p("isi kolom \"Nama\" dengan \"Budi\""))
    }

    @Test fun dropdownCheckboxRadio() {
        assertEquals(BrowserCommand.Choose("Jenis Pengaduan", "Asuransi"), p("pilih Asuransi pada Jenis Pengaduan"))
        assertEquals(BrowserCommand.Check("menyetujui syarat", true), p("centang menyetujui syarat"))
        assertEquals(BrowserCommand.Check("newsletter", false), p("hapus centang newsletter"))
        assertEquals(BrowserCommand.Radio("Status Pengadu", "Badan Hukum"), p("pilih radio Badan Hukum pada Status Pengadu"))
    }

    @Test fun dates() {
        assertEquals(BrowserCommand.DateField("tanggal Kejadian", "2026-11-08"), p("isi tanggal Kejadian dengan 8 November 2026"))
        assertEquals(BrowserCommand.DateField("tanggal Surat", "2026-11-08"), p("isi kolom Tanggal Surat dengan 08/11/2026"))
        assertEquals("2024-02-29", BrowserInstruction.toIsoDate("29-02-2024"))
        assertNull(BrowserInstruction.toIsoDate("31 April 2026"))
        assertNull(BrowserInstruction.toIsoDate("29/02/2026"))
    }

    @Test fun clickAndUnknown() {
        assertEquals(BrowserCommand.Click("Lanjut"), p("klik Lanjut"))
        // Instruksi majemuk tidak boleh diam-diam dianggap "baca saja": serahkan ke perencana LLM.
        assertNull(p("Baca jawaban perusahaan, cari bagian yang tidak menjawab, lalu tulis keberatan"))
        assertEquals(BrowserCommand.Read, p("baca halaman ini"))
        assertNull(p(""))
    }
}
