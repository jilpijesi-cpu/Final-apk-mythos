package com.build.mythos

import com.build.mythos.cc.PdfText
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uji pembaca PDF di aplikasi (PdfText). PDF dibuat di memori; cermin dari tools/uji-pdf/PdfTextCheck.java yang bisa dijalankan dengan JDK saja. */
class PdfTextTest {
    private class B {
        val out = ByteArrayOutputStream()
        init { w("%PDF-1.4\n") }
        fun w(s: String) { out.write(s.toByteArray(Charsets.ISO_8859_1)) }
        fun obj(n: Int, body: String): B { w("$n 0 obj\n$body\nendobj\n"); return this }
        fun stream(n: Int, dict: String, data: ByteArray): B {
            w("$n 0 obj\n<< $dict /Length ${data.size} >>\nstream\n")
            out.write(data)
            w("\nendstream\nendobj\n")
            return this
        }
        fun stream(n: Int, dict: String, data: String): B = stream(n, dict, data.toByteArray(Charsets.ISO_8859_1))
        fun build(extraTrailer: String = ""): ByteArray { w("trailer\n<< /Root 1 0 R $extraTrailer >>\n%%EOF\n"); return out.toByteArray() }
    }

    private val helv = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"

    private fun onePage(content: String, fontDict: String = helv): B = B()
        .obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        .obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        .obj(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
        .stream(4, "", content)
        .obj(5, fontDict)

    private fun read(pdf: ByteArray, maxChars: Int = 24000, maxPages: Int = 60) = PdfText.extract(pdf, maxChars, maxPages)

    private fun deflate(input: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(input)
        d.finish()
        val o = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) o.write(buf, 0, d.deflate(buf))
        d.end()
        return o.toByteArray()
    }

    private fun hex16(s: String) = s.map { String.format("%04X", it.code) }.joinToString("")

    @Test fun teksSederhana() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td (Saldo ditahan platform) Tj ET").build())
        assertEquals(PdfText.Status.OK, r.status)
        assertEquals("Saldo ditahan platform", r.text)
        assertEquals(1, r.pageCount)
    }

    @Test fun duaBarisLewatTd() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td (Baris pertama) Tj 0 -14 Td (Baris kedua) Tj ET").build())
        assertEquals("Baris pertama\nBaris kedua", r.text)
    }

    @Test fun tjKerningMenyatuDanCelahBesarJadiSpasi() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td [(Sa) 20 (ldo) -300 (ditahan lama)] TJ ET").build())
        assertEquals("Saldo ditahan lama", r.text)
    }

    @Test fun isiTerkompresiFlate() {
        val z = deflate("BT /F1 12 Tf 72 700 Td (Teks terkompresi Flate) Tj ET".toByteArray(Charsets.ISO_8859_1))
        val pdf = B().obj(1, "<< /Type /Catalog /Pages 2 0 R >>").obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            .obj(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
            .stream(4, "/Filter /FlateDecode", z).obj(5, helv).build()
        assertEquals("Teks terkompresi Flate", read(pdf).text)
    }

    @Test fun escapeDanOktal() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td (Rp 1.000 \\(ditahan\\) \\101\\102) Tj ET").build())
        assertEquals("Rp 1.000 (ditahan) AB", r.text)
    }

    @Test fun stringHeksadesimal() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td <5465787420686578206D656C616C7569> Tj ET").build())
        assertEquals("Text hex melalui", r.text)
    }

    @Test fun nbspMenjadiSpasiBiasa() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td (Rp\\240100.000\\240ditahan) Tj ET").build())
        assertEquals("Rp 100.000 ditahan", r.text)
    }

    @Test fun encodingDifferencesDenganNamaGlyph() {
        val diff = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Type /Encoding /Differences [1 /eacute /uni00FC /fi /quoteright] >> >>"
        val r = read(onePage("BT /F1 12 Tf 72 700 Td (caf\\001 m\\002ller \\003rst d\\004) Tj ET", diff).build())
        assertEquals("café müller first d\u2019", r.text)
    }

    private val type0 = "<< /Type /Font /Subtype /Type0 /BaseFont /ABCDEF+Uji /Encoding /Identity-H " +
        "/DescendantFonts [<< /Type /Font /Subtype /CIDFontType2 /BaseFont /ABCDEF+Uji " +
        "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /DW 500 >>] /ToUnicode 6 0 R >>"
    private val type0NoMap = "<< /Type /Font /Subtype /Type0 /BaseFont /ABCDEF+Uji /Encoding /Identity-H " +
        "/DescendantFonts [<< /Type /Font /Subtype /CIDFontType2 /BaseFont /ABCDEF+Uji " +
        "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> >>] >>"
    private val cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n" +
        "1 begincodespacerange <0000> <FFFF> endcodespacerange\n" +
        "1 beginbfrange <0000> <00FF> <0000> endbfrange\nendcmap end end"

    @Test fun fontCidDenganToUnicode() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td <${hex16("Saldo ditahan sejak kemarin")}> Tj ET", type0).stream(6, "", cmap).build())
        assertEquals("Saldo ditahan sejak kemarin", r.text)
    }

    @Test fun fontCidTanpaToUnicodeGagalDenganJelasBukanKarakterAcak() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td <${hex16("Saldo ditahan sejak kemarin")}> Tj ET", type0NoMap).build())
        assertEquals(PdfText.Status.UNMAPPED_FONT, r.status)
        assertEquals("", r.text)
        assertTrue(pdfSummary(r).contains("font"))
    }

    @Test fun fontSubsetTanpaEncodingDanToUnicodeDilaporkanGagal() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td (Saldo ditahan sejak kemarin) Tj ET", "<< /Type /Font /Subtype /TrueType /BaseFont /ABCDEF+Uji >>").build())
        assertEquals(PdfText.Status.UNMAPPED_FONT, r.status)
    }

    @Test fun halamanTanpaOperatorTeksDianggapScan() {
        val r = read(onePage("q 100 0 0 100 0 0 cm /Im1 Do Q").build())
        assertEquals(PdfText.Status.NO_TEXT, r.status)
        assertTrue(pdfSummary(r).contains("screenshot"))
    }

    @Test fun pdfTerenkripsiDilaporkan() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td (Saldo ditahan platform) Tj ET").obj(9, "<< /Filter /Standard /V 2 /R 3 >>").build("/Encrypt 9 0 R"))
        assertEquals(PdfText.Status.ENCRYPTED, r.status)
        assertEquals("", r.text)
        assertTrue(pdfSummary(r).contains("terkunci"))
    }

    @Test fun bukanPdfAtauRusakDilaporkanCorrupt() {
        assertEquals(PdfText.Status.CORRUPT, read("halo ini teks biasa, bukan PDF".toByteArray()).status)
        assertEquals(PdfText.Status.CORRUPT, read("%PDF-1.4\nsampah tanpa objek apa pun di sini".toByteArray()).status)
    }

    @Test fun batasHalaman() {
        val b = B().obj(1, "<< /Type /Catalog /Pages 2 0 R >>").obj(2, "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R 6 0 R 7 0 R] /Count 5 >>")
        for (i in 0 until 5) b.obj(3 + i, "<< /Type /Page /Parent 2 0 R /Contents ${10 + i} 0 R /Resources << /Font << /F1 9 0 R >> >> >>")
        b.obj(9, helv)
        for (i in 0 until 5) b.stream(10 + i, "", "BT /F1 12 Tf 72 700 Td (Isi halaman nomor ${i + 1}) Tj ET")
        val r = read(b.build(), maxPages = 2)
        assertEquals(2, r.pagesRead)
        assertEquals(5, r.pageCount)
        assertTrue(r.truncated)
        assertTrue(r.text.contains("[Halaman 1]") && r.text.contains("Isi halaman nomor 1"))
        assertTrue(r.text.contains("[Halaman 2]"))
        assertFalse(r.text.contains("[Halaman 3]"))
        assertTrue(pdfSummary(r).contains("terpotong"))
    }

    @Test fun batasKarakter() {
        val many = StringBuilder("BT /F1 12 Tf 72 700 Td 12 TL ")
        for (i in 0 until 300) many.append("(Baris panjang nomor $i untuk uji pemotongan) ' ")
        many.append("ET")
        val r = read(onePage(many.toString()).build(), maxChars = 1000)
        assertTrue(r.text.length <= 1000)
        assertTrue(r.truncated)
        assertEquals(PdfText.Status.OK, r.status)
    }

    @Test fun formXObjectDibaca() {
        val pdf = B().obj(1, "<< /Type /Catalog /Pages 2 0 R >>").obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            .obj(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /XObject << /Fm1 6 0 R >> >> >>")
            .stream(4, "", "q 1 0 0 1 50 50 cm /Fm1 Do Q")
            .obj(5, helv)
            .stream(6, "/Type /XObject /Subtype /Form /BBox [0 0 500 500] /Resources << /Font << /F1 5 0 R >> >>", "BT /F1 12 Tf 0 0 Td (Teks di dalam form) Tj ET").build()
        assertEquals("Teks di dalam form", read(pdf).text)
    }

    @Test fun fontDariObjectStream() {
        val hdr = "5 0 "
        val pdf = B().obj(1, "<< /Type /Catalog /Pages 2 0 R >>").obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            .obj(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
            .stream(4, "", "BT /F1 12 Tf 72 700 Td (Font dari object stream) Tj ET")
            .stream(6, "/Type /ObjStm /N 1 /First ${hdr.length}", hdr + helv).build()
        assertEquals("Font dari object stream", read(pdf).text)
    }

    @Test fun lebarGlyphMenentukanSpasi() {
        assertEquals("Saldo ditahan", read(onePage("BT /F1 12 Tf 72 700 Td (Sal) Tj 17.5 0 Td (do ditahan) Tj ET").build()).text)
        assertEquals("Saldo ditahan", read(onePage("BT /F1 12 Tf 72 700 Td (Saldo) Tj 50 0 Td (ditahan) Tj ET").build()).text)
    }

    @Test fun cmMemisahBarisDariPosisiBukanOperator() {
        val r = read(onePage("q 1 0 0 1 0 0 cm BT /F1 12 Tf 1 0 0 1 72 700 Tm (Baris atas dokumen) Tj ET Q " +
            "q 1 0 0 1 0 -15 cm BT /F1 12 Tf 1 0 0 1 72 700 Tm (Baris bawah dokumen) Tj ET Q").build())
        assertEquals("Baris atas dokumen\nBaris bawah dokumen", r.text)
    }

    @Test fun gambarSebarisDilewati() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td (Sebelum gambar) Tj ET q 10 0 0 10 0 0 cm BI /W 2 /H 2 /CS /G /BPC 8 ID \u0001\u0002\u0003\u0004 EI Q " +
            "BT /F1 12 Tf 72 680 Td (Sesudah gambar) Tj ET").build())
        assertEquals("Sebelum gambar\nSesudah gambar", r.text)
    }

    @Test fun asciiHexDecode() {
        val hex = "BT /F1 12 Tf 72 700 Td (Teks lewat ASCIIHex) Tj ET".toByteArray(Charsets.ISO_8859_1)
            .joinToString("") { String.format("%02X", it.toInt() and 0xff) } + ">"
        val pdf = B().obj(1, "<< /Type /Catalog /Pages 2 0 R >>").obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            .obj(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
            .stream(4, "/Filter /ASCIIHexDecode", hex).obj(5, helv).build()
        assertEquals("Teks lewat ASCIIHex", read(pdf).text)
    }

    @Test fun ringkasanOkMenyebutHalamanDanKarakter() {
        val r = read(onePage("BT /F1 12 Tf 72 700 Td (Saldo ditahan platform) Tj ET").build())
        val s = pdfSummary(r)
        assertTrue(s.contains("1 dari 1 halaman"))
        assertTrue(s.contains("22 karakter"))
    }
}
