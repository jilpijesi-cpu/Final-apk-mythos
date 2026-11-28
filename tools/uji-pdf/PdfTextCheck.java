import com.build.mythos.cc.PdfText;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

/**
 * Uji mandiri PdfText dengan JDK saja (tanpa Gradle/Kotlin). Cermin dari PdfTextTest.kt.
 * Pemakaian: lihat tools/uji-pdf/run.sh
 */
public class PdfTextCheck {
    static int fails = 0, passes = 0;

    static void eq(String name, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) { passes++; }
        else { fails++; System.out.println("GAGAL " + name + "\n  diharapkan: [" + expected + "]\n  didapat:    [" + actual + "]"); }
    }

    static void ok(String name, boolean cond) { eq(name, true, cond); }

    // ---------------------------------------------------------------- pembangun PDF mini
    static final class B {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        B() { w("%PDF-1.4\n"); }
        void w(String s) { byte[] b = s.getBytes(StandardCharsets.ISO_8859_1); out.write(b, 0, b.length); }
        B obj(int n, String body) { w(n + " 0 obj\n" + body + "\nendobj\n"); return this; }
        B stream(int n, String dict, byte[] data) {
            w(n + " 0 obj\n<< " + dict + " /Length " + data.length + " >>\nstream\n");
            out.write(data, 0, data.length);
            w("\nendstream\nendobj\n");
            return this;
        }
        B stream(int n, String dict, String data) { return stream(n, dict, data.getBytes(StandardCharsets.ISO_8859_1)); }
        byte[] build(String extraTrailer) { w("trailer\n<< /Root 1 0 R " + extraTrailer + " >>\n%%EOF\n"); return out.toByteArray(); }
    }

    static final String HELV = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>";

    static byte[] deflate(byte[] in) {
        Deflater d = new Deflater();
        d.setInput(in); d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) { int n = d.deflate(buf); o.write(buf, 0, n); }
        d.end();
        return o.toByteArray();
    }

    /** Satu halaman, font /F1 = fontDict (objek 5), isi halaman = content (objek 4). */
    static B onePage(String content, String fontDict) {
        return new B().obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            .obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            .obj(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
            .stream(4, "", content)
            .obj(5, fontDict);
    }

    static PdfText.Result read(byte[] pdf) { return PdfText.extract(pdf, 24000, 60); }

    static String hex16(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) sb.append(String.format("%04X", (int) c));
        return sb.toString();
    }

    public static void main(String[] args) {
        // 1. teks sederhana
        PdfText.Result r = read(onePage("BT /F1 12 Tf 72 700 Td (Saldo ditahan platform) Tj ET", HELV).build(""));
        eq("sederhana status", PdfText.Status.OK, r.status);
        eq("sederhana teks", "Saldo ditahan platform", r.text);
        eq("sederhana halaman", 1, r.pageCount);

        // 2. baris baru lewat Td
        r = read(onePage("BT /F1 12 Tf 72 700 Td (Baris pertama) Tj 0 -14 Td (Baris kedua) Tj ET", HELV).build(""));
        eq("dua baris", "Baris pertama\nBaris kedua", r.text);

        // 3. TJ: kerning kecil menyatu, celah besar jadi spasi
        r = read(onePage("BT /F1 12 Tf 72 700 Td [(Sa) 20 (ldo) -300 (ditahan lama)] TJ ET", HELV).build(""));
        eq("TJ kerning dan celah", "Saldo ditahan lama", r.text);

        // 4. isi terkompresi Flate
        byte[] z = deflate("BT /F1 12 Tf 72 700 Td (Teks terkompresi Flate) Tj ET".getBytes(StandardCharsets.ISO_8859_1));
        byte[] pdf = new B().obj(1, "<< /Type /Catalog /Pages 2 0 R >>").obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            .obj(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
            .stream(4, "/Filter /FlateDecode", z).obj(5, HELV).build("");
        eq("flate", "Teks terkompresi Flate", read(pdf).text);

        // 5. escape string dan oktal
        r = read(onePage("BT /F1 12 Tf 72 700 Td (Rp 1.000 \\(ditahan\\) \\101\\102) Tj ET", HELV).build(""));
        eq("escape", "Rp 1.000 (ditahan) AB", r.text);

        // 6. string heksadesimal
        r = read(onePage("BT /F1 12 Tf 72 700 Td <5465787420686578206D656C616C7569> Tj ET", HELV).build(""));
        eq("hex", "Text hex melalui", r.text);

        // 7. NBSP WinAnsi menjadi spasi biasa
        r = read(onePage("BT /F1 12 Tf 72 700 Td (Rp\\240100.000\\240ditahan) Tj ET", HELV).build(""));
        eq("nbsp", "Rp 100.000 ditahan", r.text);

        // 8. Encoding /Differences dengan nama glyph
        String diff = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Type /Encoding /Differences [1 /eacute /uni00FC /fi /quoteright] >> >>";
        r = read(onePage("BT /F1 12 Tf 72 700 Td (caf\\001 m\\002ller \\003rst d\\004) Tj ET", diff).build(""));
        eq("differences", "café müller first d’", r.text);

        // 9. font CID Identity-H + ToUnicode (bfrange identitas)
        String type0 = "<< /Type /Font /Subtype /Type0 /BaseFont /ABCDEF+Uji /Encoding /Identity-H /DescendantFonts [<< /Type /Font /Subtype /CIDFontType2 /BaseFont /ABCDEF+Uji /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /DW 500 >>] /ToUnicode 6 0 R >>";
        String cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n1 begincodespacerange <0000> <FFFF> endcodespacerange\n1 beginbfrange <0000> <00FF> <0000> endbfrange\nendcmap end end";
        r = read(onePage("BT /F1 12 Tf 72 700 Td <" + hex16("Saldo ditahan sejak kemarin") + "> Tj ET", type0).stream(6, "", cmap).build(""));
        eq("cid tounicode", "Saldo ditahan sejak kemarin", r.text);

        // 10. font CID tanpa ToUnicode: gagal dengan jelas, bukan karakter acak
        String type0no = "<< /Type /Font /Subtype /Type0 /BaseFont /ABCDEF+Uji /Encoding /Identity-H /DescendantFonts [<< /Type /Font /Subtype /CIDFontType2 /BaseFont /ABCDEF+Uji /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> >>] >>";
        r = read(onePage("BT /F1 12 Tf 72 700 Td <" + hex16("Saldo ditahan sejak kemarin") + "> Tj ET", type0no).build(""));
        eq("cid tanpa tounicode status", PdfText.Status.UNMAPPED_FONT, r.status);
        eq("cid tanpa tounicode teks", "", r.text);

        // 11. font subset tanpa Encoding dan ToUnicode
        String subset = "<< /Type /Font /Subtype /TrueType /BaseFont /ABCDEF+Uji >>";
        r = read(onePage("BT /F1 12 Tf 72 700 Td (Saldo ditahan sejak kemarin) Tj ET", subset).build(""));
        eq("subset tanpa pemetaan", PdfText.Status.UNMAPPED_FONT, r.status);

        // 12. tanpa operator teks (scan)
        r = read(onePage("q 100 0 0 100 0 0 cm /Im1 Do Q", HELV).build(""));
        eq("scan status", PdfText.Status.NO_TEXT, r.status);

        // 13. terenkripsi
        r = read(onePage("BT /F1 12 Tf 72 700 Td (Saldo ditahan platform) Tj ET", HELV).obj(9, "<< /Filter /Standard /V 2 /R 3 >>").build("/Encrypt 9 0 R"));
        eq("terenkripsi", PdfText.Status.ENCRYPTED, r.status);

        // 14. bukan PDF / rusak
        eq("bukan pdf", PdfText.Status.CORRUPT, read("halo ini teks biasa, bukan PDF".getBytes(StandardCharsets.ISO_8859_1)).status);
        eq("header saja", PdfText.Status.CORRUPT, read("%PDF-1.4\nsampah tanpa objek apa pun di sini".getBytes(StandardCharsets.ISO_8859_1)).status);

        // 15. batas halaman
        B b = new B().obj(1, "<< /Type /Catalog /Pages 2 0 R >>").obj(2, "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R 6 0 R 7 0 R] /Count 5 >>");
        for (int i = 0; i < 5; i++) {
            b.obj(3 + i, "<< /Type /Page /Parent 2 0 R /Contents " + (10 + i) + " 0 R /Resources << /Font << /F1 9 0 R >> >> >>");
        }
        b.obj(9, HELV);
        for (int i = 0; i < 5; i++) b.stream(10 + i, "", "BT /F1 12 Tf 72 700 Td (Isi halaman nomor " + (i + 1) + ") Tj ET");
        r = PdfText.extract(b.build(""), 24000, 2);
        eq("batas halaman dibaca", 2, r.pagesRead);
        eq("batas halaman total", 5, r.pageCount);
        ok("batas halaman terpotong", r.truncated);
        ok("batas halaman penanda 1", r.text.contains("[Halaman 1]") && r.text.contains("Isi halaman nomor 1"));
        ok("batas halaman penanda 2", r.text.contains("[Halaman 2]"));
        ok("batas halaman tanpa 3", !r.text.contains("[Halaman 3]"));

        // 16. batas karakter
        StringBuilder many = new StringBuilder("BT /F1 12 Tf 72 700 Td 12 TL ");
        for (int i = 0; i < 300; i++) many.append("(Baris panjang nomor ").append(i).append(" untuk uji pemotongan) ' ");
        many.append("ET");
        r = PdfText.extract(onePage(many.toString(), HELV).build(""), 1000, 60);
        ok("batas karakter panjang", r.text.length() <= 1000);
        ok("batas karakter terpotong", r.truncated);
        eq("batas karakter status", PdfText.Status.OK, r.status);

        // 17. Form XObject
        pdf = new B().obj(1, "<< /Type /Catalog /Pages 2 0 R >>").obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            .obj(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /XObject << /Fm1 6 0 R >> >> >>")
            .stream(4, "", "q 1 0 0 1 50 50 cm /Fm1 Do Q")
            .obj(5, HELV)
            .stream(6, "/Type /XObject /Subtype /Form /BBox [0 0 500 500] /Resources << /Font << /F1 5 0 R >> >>", "BT /F1 12 Tf 0 0 Td (Teks di dalam form) Tj ET").build("");
        eq("form xobject", "Teks di dalam form", read(pdf).text);

        // 18. object stream: font hanya ada di dalam ObjStm
        String hdr = "5 0 ";
        pdf = new B().obj(1, "<< /Type /Catalog /Pages 2 0 R >>").obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            .obj(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
            .stream(4, "", "BT /F1 12 Tf 72 700 Td (Font dari object stream) Tj ET")
            .stream(6, "/Type /ObjStm /N 1 /First " + hdr.length(), hdr + HELV).build("");
        eq("object stream", "Font dari object stream", read(pdf).text);

        // 19. lebar glyph dipakai: Td kecil menyambung kata, Td besar memisah
        r = read(onePage("BT /F1 12 Tf 72 700 Td (Sal) Tj 17.5 0 Td (do ditahan) Tj ET", HELV).build(""));
        eq("td menyambung", "Saldo ditahan", r.text);
        r = read(onePage("BT /F1 12 Tf 72 700 Td (Saldo) Tj 50 0 Td (ditahan) Tj ET", HELV).build(""));
        eq("td memisah", "Saldo ditahan", r.text);

        // 20. Tm dan cm: baris diputuskan dari posisi akhir, bukan dari operator
        r = read(onePage("q 1 0 0 1 0 0 cm BT /F1 12 Tf 1 0 0 1 72 700 Tm (Baris atas dokumen) Tj ET Q q 1 0 0 1 0 -15 cm BT /F1 12 Tf 1 0 0 1 72 700 Tm (Baris bawah dokumen) Tj ET Q", HELV).build(""));
        eq("cm memisah baris", "Baris atas dokumen\nBaris bawah dokumen", r.text);

        // 21. gambar sebaris (BI ... EI) dilewati
        r = read(onePage("BT /F1 12 Tf 72 700 Td (Sebelum gambar) Tj ET q 10 0 0 10 0 0 cm BI /W 2 /H 2 /CS /G /BPC 8 ID \u0001\u0002\u0003\u0004 EI Q BT /F1 12 Tf 72 680 Td (Sesudah gambar) Tj ET", HELV).build(""));
        eq("inline image", "Sebelum gambar\nSesudah gambar", r.text);

        // 22. ASCIIHex dan ASCII85
        byte[] pdfHex = new B().obj(1, "<< /Type /Catalog /Pages 2 0 R >>").obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            .obj(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
            .stream(4, "/Filter /ASCIIHexDecode", hexOf("BT /F1 12 Tf 72 700 Td (Teks lewat ASCIIHex) Tj ET") + ">").obj(5, HELV).build("");
        eq("asciihex", "Teks lewat ASCIIHex", read(pdfHex).text);

        System.out.println(passes + " lulus, " + fails + " gagal");
        if (fails > 0) System.exit(1);
    }

    static String hexOf(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.ISO_8859_1)) sb.append(String.format("%02X", x & 0xff));
        return sb.toString();
    }
}
