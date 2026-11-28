package com.build.mythos.cc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Pembuat berkas dokumen tanpa dependensi (JVM murni, tanpa android.*): DOCX, XLSX, PPTX, dan PDF.
 * Teks masukan berupa Markdown ringan (judul #, daftar - dan 1., tabel |, **tebal**). Ditulis dalam Java agar bisa
 * dijalankan dan divalidasi di mesin mana pun yang punya JDK; Kotlin hanya membungkusnya sebagai alat agen.
 */
public final class OfficeKit {
    private OfficeKit() {}

    // ------------------------------------------------------------------ blok Markdown

    public static final int H = 1, P = 2, BUL = 3, NUM = 4, TBL = 5;

    public static final class Block {
        public final int type;
        public final int level;
        public final String text;
        public final List<List<String>> rows;
        Block(int type, int level, String text, List<List<String>> rows) {
            this.type = type; this.level = level; this.text = text; this.rows = rows;
        }
    }

    private static final Pattern NUM_ITEM = Pattern.compile("^(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$");

    public static List<Block> parse(String md) {
        List<Block> out = new ArrayList<Block>();
        String[] lines = (md == null ? "" : md).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder para = new StringBuilder();
        List<List<String>> table = null;
        for (String raw : lines) {
            String line = raw.replace("\t", "    ");
            String t = line.trim();
            if (t.startsWith("|")) {
                if (para.length() > 0) { out.add(new Block(P, 0, para.toString(), null)); para.setLength(0); }
                if (table == null) table = new ArrayList<List<String>>();
                if (!TABLE_SEP.matcher(t).matches()) table.add(cells(t));
                continue;
            }
            if (table != null) { out.add(new Block(TBL, 0, "", table)); table = null; }
            if (t.isEmpty()) {
                if (para.length() > 0) { out.add(new Block(P, 0, para.toString(), null)); para.setLength(0); }
                continue;
            }
            if (t.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                if (para.length() > 0) { out.add(new Block(P, 0, para.toString(), null)); para.setLength(0); }
                continue;
            }
            int hashes = 0;
            while (hashes < t.length() && t.charAt(hashes) == '#') hashes++;
            if (hashes >= 1 && hashes <= 6 && t.length() > hashes && t.charAt(hashes) == ' ') {
                if (para.length() > 0) { out.add(new Block(P, 0, para.toString(), null)); para.setLength(0); }
                out.add(new Block(H, Math.min(hashes, 3), t.substring(hashes + 1).trim(), null));
                continue;
            }
            if ((t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ "))) {
                if (para.length() > 0) { out.add(new Block(P, 0, para.toString(), null)); para.setLength(0); }
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                out.add(new Block(BUL, indent >= 2 ? 1 : 0, t.substring(2).trim(), null));
                continue;
            }
            java.util.regex.Matcher m = NUM_ITEM.matcher(t);
            if (m.matches()) {
                if (para.length() > 0) { out.add(new Block(P, 0, para.toString(), null)); para.setLength(0); }
                out.add(new Block(NUM, 0, m.group(1) + ". " + m.group(2).trim(), null));
                continue;
            }
            if (para.length() > 0) para.append(' ');
            para.append(t);
        }
        if (table != null) out.add(new Block(TBL, 0, "", table));
        if (para.length() > 0) out.add(new Block(P, 0, para.toString(), null));
        return out;
    }

    private static List<String> cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        List<String> c = new ArrayList<String>();
        for (String s : r.split("\\|", -1)) c.add(s.trim());
        return c;
    }

    /** Hapus penanda Markdown inline (**tebal**, `kode`) untuk keluaran teks polos. */
    static String plain(String s) {
        return s.replace("**", "").replace("`", "");
    }

    // ------------------------------------------------------------------ XML & ZIP

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default:
                    // karakter kontrol XML 1.0 dan unpaired UTF-16 surrogate yang tidak sah dibuang
                    if (Character.isHighSurrogate(c)) {
                        if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                            b.append(c).append(s.charAt(++i));
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        // buang low surrogate tanpa pasangan
                    } else if (c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c != 0xFFFE && c != 0xFFFF)) {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    private static final class Zip {
        private final ZipOutputStream z;
        Zip(OutputStream os) { z = new ZipOutputStream(os); }
        void put(String name, String content) throws IOException {
            ZipEntry e = new ZipEntry(name);
            z.putNextEntry(e);
            z.write(content.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        void close() throws IOException { z.close(); }
    }

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_PR = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types";

    private static String rel(String id, String type, String target) {
        return "<Relationship Id=\"" + id + "\" Type=\"" + NS_R + "/" + type + "\" Target=\"" + target + "\"/>";
    }

    private static String rels(String... items) {
        StringBuilder b = new StringBuilder(XML).append("<Relationships xmlns=\"").append(NS_PR).append("\">");
        for (String i : items) b.append(i);
        return b.append("</Relationships>").toString();
    }

    // ------------------------------------------------------------------ DOCX

    private static String runs(String text, boolean boldAll, int sizeHalfPt) {
        StringBuilder b = new StringBuilder();
        String[] parts = text.replace("`", "").split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            boolean bold = boldAll || (i % 2 == 1);
            b.append("<w:r>");
            if (bold || sizeHalfPt > 0) {
                b.append("<w:rPr>");
                if (bold) b.append("<w:b/>");
                if (sizeHalfPt > 0) b.append("<w:sz w:val=\"").append(sizeHalfPt).append("\"/>");
                b.append("</w:rPr>");
            }
            String[] segs = parts[i].split("\t", -1);
            for (int k = 0; k < segs.length; k++) {
                if (k > 0) b.append("<w:tab/>");
                b.append("<w:t xml:space=\"preserve\">").append(esc(segs[k])).append("</w:t>");
            }
            b.append("</w:r>");
        }
        if (b.length() == 0) b.append("<w:r><w:t></w:t></w:r>");
        return b.toString();
    }

    private static String para(String style, String indentLeft, String hanging, String body) {
        StringBuilder b = new StringBuilder("<w:p><w:pPr>");
        if (style != null) b.append("<w:pStyle w:val=\"").append(style).append("\"/>");
        if (indentLeft != null) {
            b.append("<w:ind w:left=\"").append(indentLeft).append("\"");
            if (hanging != null) b.append(" w:hanging=\"").append(hanging).append("\"");
            b.append("/>");
        }
        return b.append("</w:pPr>").append(body).append("</w:p>").toString();
    }

    public static void docx(File out, String title, String markdown) throws IOException {
        StringBuilder body = new StringBuilder();
        if (title != null && !title.trim().isEmpty()) body.append(para("Title", null, null, runs(title.trim(), false, 0)));
        for (Block bl : parse(markdown)) {
            switch (bl.type) {
                case H:
                    body.append(para("Heading" + bl.level, null, null, runs(bl.text, false, 0)));
                    break;
                case BUL:
                    String left = bl.level > 0 ? "1080" : "720";
                    body.append(para("ListParagraph", left, "360", runs("\u2022\t" + bl.text, false, 0)));
                    break;
                case NUM:
                    body.append(para("ListParagraph", "720", "360", runs(bl.text.replaceFirst("^(\\d+\\.)\\s*", "$1\t"), false, 0)));
                    break;
                case TBL:
                    body.append(docxTable(bl.rows));
                    body.append("<w:p/>");
                    break;
                default:
                    body.append(para(null, null, null, runs(bl.text, false, 0)));
            }
        }
        if (body.length() == 0) body.append("<w:p/>");
        String doc = XML + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"" + NS_R + "\"><w:body>"
            + body
            + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>"
            + "</w:body></w:document>";
        Zip z = new Zip(new FileOutputStream(out));
        try {
            z.put("[Content_Types].xml", XML + "<Types xmlns=\"" + NS_CT + "\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "</Types>");
            z.put("_rels/.rels", rels(rel("rId1", "officeDocument", "word/document.xml")));
            z.put("word/_rels/document.xml.rels", rels(rel("rId1", "styles", "styles.xml")));
            z.put("word/document.xml", doc);
            z.put("word/styles.xml", DOCX_STYLES);
        } finally { z.close(); }
    }

    private static String docxTable(List<List<String>> rows) {
        int cols = 0;
        for (List<String> r : rows) cols = Math.max(cols, r.size());
        if (cols == 0) return "";
        int total = 9026; // lebar teks A4 dengan margin 1"
        int w = Math.max(1, total / cols);
        StringBuilder b = new StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"").append(total).append("\" w:type=\"dxa\"/>")
            .append("<w:tblBorders>");
        for (String side : new String[] {"top", "left", "bottom", "right", "insideH", "insideV"})
            b.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>");
        b.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:left w:w=\"100\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>");
        for (int i = 0; i < cols; i++) b.append("<w:gridCol w:w=\"").append(w).append("\"/>");
        b.append("</w:tblGrid>");
        for (int ri = 0; ri < rows.size(); ri++) {
            List<String> r = rows.get(ri);
            b.append("<w:tr>");
            for (int ci = 0; ci < cols; ci++) {
                String cell = ci < r.size() ? r.get(ci) : "";
                b.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(w).append("\" w:type=\"dxa\"/>");
                if (ri == 0) b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EEEEEE\"/>");
                b.append("</w:tcPr>").append(para(null, null, null, runs(cell, ri == 0, 0))).append("</w:tc>");
            }
            b.append("</w:tr>");
        }
        return b.append("</w:tbl>").toString();
    }

    private static String style(String id, String name, String basedOn, int sizeHalfPt, boolean bold, int spaceBefore, int spaceAfter, String color, boolean isDefault) {
        StringBuilder b = new StringBuilder("<w:style w:type=\"paragraph\"");
        if (isDefault) b.append(" w:default=\"1\"");
        b.append(" w:styleId=\"").append(id).append("\"><w:name w:val=\"").append(name).append("\"/>");
        if (basedOn != null) b.append("<w:basedOn w:val=\"").append(basedOn).append("\"/>");
        b.append("<w:qFormat/><w:pPr><w:spacing w:before=\"").append(spaceBefore).append("\" w:after=\"").append(spaceAfter).append("\"/></w:pPr><w:rPr>");
        if (bold) b.append("<w:b/>");
        if (color != null) b.append("<w:color w:val=\"").append(color).append("\"/>");
        b.append("<w:sz w:val=\"").append(sizeHalfPt).append("\"/></w:rPr></w:style>");
        return b.toString();
    }

    private static final String DOCX_STYLES = XML
        + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\" w:eastAsia=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"id-ID\"/></w:rPr></w:rPrDefault>"
        + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
        + style("Normal", "Normal", null, 22, false, 0, 120, null, true)
        + style("Title", "Title", "Normal", 44, true, 0, 240, "1F2937", false)
        + style("Heading1", "heading 1", "Normal", 32, true, 360, 120, "1F2937", false)
        + style("Heading2", "heading 2", "Normal", 28, true, 280, 100, "1F2937", false)
        + style("Heading3", "heading 3", "Normal", 24, true, 200, 80, "374151", false)
        + style("ListParagraph", "List Paragraph", "Normal", 22, false, 0, 60, null, false)
        + "</w:styles>";

    // ------------------------------------------------------------------ XLSX

    private static final Pattern NUMBER = Pattern.compile("^-?(0|[1-9]\\d{0,14})(\\.\\d{1,10})?$");
    private static final Pattern FORMULA_UNSAFE_FN = Pattern.compile(
        "(?i)(?:\\b(?:HYPERLINK|WEBSERVICE|IMPORTXML|IMPORTHTML|IMPORTDATA|IMPORTFEED|IMPORTRANGE|FILTERXML|ENCODEURL|RTD|CALL|REGISTER\\.ID|EXEC|CMD|SHELL|SYSTEM|DDE)\\s*\\(|\\b(?:cmd|powershell|wscript|cscript|mshta)\\b)"
    );
    private static final Pattern FORMULA_START = Pattern.compile("^[A-Za-z0-9($+\\-].*");

    static boolean isSafeFormula(String rawExpr) {
        if (rawExpr == null) return false;
        String expr = rawExpr.trim();
        if (expr.isEmpty() || expr.startsWith("=") || expr.indexOf('|') >= 0 || expr.indexOf('\n') >= 0 || expr.indexOf('\r') >= 0) return false;
        if (!FORMULA_START.matcher(expr).matches()) return false;
        if (!NUMBER.matcher(expr).matches() && !expr.matches(".*[+*/^=<>:(&-].*")) return false;
        if (FORMULA_UNSAFE_FN.matcher(expr).find()) return false;
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (!inQuote) {
                if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth < 0) return false; }
            }
        }
        return !inQuote && depth == 0;
    }

    static String colName(int i) {
        StringBuilder b = new StringBuilder();
        int n = i + 1;
        while (n > 0) { int r = (n - 1) % 26; b.insert(0, (char) ('A' + r)); n = (n - 1) / 26; }
        return b.toString();
    }

    private static String sheetName(String raw, int index, List<String> used) {
        String n = raw == null ? "" : raw.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        n = n.replaceAll("^'+|'+$", "").trim();
        if (n.isEmpty()) n = "Sheet" + (index + 1);
        if (n.length() > 31) n = n.substring(0, 31).replaceAll("'+$", "").trim();
        if (n.isEmpty()) n = "Sheet" + (index + 1);
        String base = n;
        int k = 2;
        while (containsIgnoreCase(used, n)) {
            String suffix = " " + k++;
            n = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()) : base) + suffix;
        }
        used.add(n);
        return n;
    }

    private static boolean containsIgnoreCase(List<String> l, String s) {
        for (String x : l) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    /** [sheets]: daftar lembar; tiap lembar = daftar baris; tiap baris = daftar sel (teks). Awalan "=" menjadi rumus; angka ditulis sebagai angka. Baris pertama = judul kolom (tebal). */
    public static void xlsx(File out, List<String> names, List<List<List<String>>> sheets) throws IOException {
        if (sheets == null || sheets.isEmpty()) {
            sheets = new ArrayList<List<List<String>>>();
            sheets.add(new ArrayList<List<String>>());
        }
        List<String> used = new ArrayList<String>();
        List<String> finalNames = new ArrayList<String>();
        for (int i = 0; i < sheets.size(); i++) finalNames.add(sheetName(names != null && i < names.size() ? names.get(i) : null, i, used));
        Zip z = new Zip(new FileOutputStream(out));
        try {
            StringBuilder ct = new StringBuilder(XML + "<Types xmlns=\"" + NS_CT + "\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            StringBuilder wb = new StringBuilder(XML + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"" + NS_R + "\"><sheets>");
            String[] wbRels = new String[sheets.size() + 1];
            for (int i = 0; i < sheets.size(); i++) {
                int n = i + 1;
                ct.append("<Override PartName=\"/xl/worksheets/sheet").append(n).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                wb.append("<sheet name=\"").append(esc(finalNames.get(i))).append("\" sheetId=\"").append(n).append("\" r:id=\"rId").append(n).append("\"/>");
                wbRels[i] = rel("rId" + n, "worksheet", "worksheets/sheet" + n + ".xml");
                z.put("xl/worksheets/sheet" + n + ".xml", sheetXml(sheets.get(i)));
            }
            wbRels[sheets.size()] = rel("rId" + (sheets.size() + 1), "styles", "styles.xml");
            wb.append("</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>");
            ct.append("</Types>");
            z.put("[Content_Types].xml", ct.toString());
            z.put("_rels/.rels", rels(rel("rId1", "officeDocument", "xl/workbook.xml")));
            z.put("xl/workbook.xml", wb.toString());
            z.put("xl/_rels/workbook.xml.rels", rels(wbRels));
            z.put("xl/styles.xml", XML + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEEEEEE\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
        } finally { z.close(); }
    }

    private static String sheetXml(List<List<String>> rows) {
        int maxCols = 0;
        int[] width = new int[200];
        for (List<String> r : rows) {
            maxCols = Math.min(Math.max(maxCols, r.size()), 200);
            for (int c = 0; c < r.size() && c < 200; c++) {
                String v = r.get(c) == null ? "" : r.get(c);
                int len = 0;
                for (String line : v.split("\n")) len = Math.max(len, line.length());
                width[c] = Math.max(width[c], len);
            }
        }
        StringBuilder b = new StringBuilder(XML + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        if (!rows.isEmpty()) {
            b.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        }
        b.append("<sheetFormatPr defaultRowHeight=\"15\"/>");
        if (maxCols > 0) {
            b.append("<cols>");
            for (int c = 0; c < maxCols; c++)
                b.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(Math.min(Math.max(width[c] + 2, 8), 60)).append("\" customWidth=\"1\"/>");
            b.append("</cols>");
        }
        b.append("<sheetData>");
        for (int ri = 0; ri < rows.size() && ri < 1048576; ri++) {
            List<String> r = rows.get(ri);
            b.append("<row r=\"").append(ri + 1).append("\">");
            for (int ci = 0; ci < r.size() && ci < 16384; ci++) {
                String v = r.get(ci) == null ? "" : r.get(ci);
                String ref = colName(ci) + (ri + 1);
                String s = ri == 0 ? " s=\"1\"" : "";
                if (v.isEmpty()) {
                    if (ri == 0) b.append("<c r=\"").append(ref).append("\"").append(s).append("/>");
                } else if (ri > 0 && v.startsWith("=") && isSafeFormula(v.substring(1))) {
                    b.append("<c r=\"").append(ref).append("\"><f>").append(esc(v.substring(1).trim())).append("</f></c>");
                } else if (ri > 0 && NUMBER.matcher(v).matches()) {
                    b.append("<c r=\"").append(ref).append("\"><v>").append(v).append("</v></c>");
                } else {
                    b.append("<c r=\"").append(ref).append("\"").append(s).append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(v)).append("</t></is></c>");
                }
            }
            b.append("</row>");
        }
        return b.append("</sheetData></worksheet>").toString();
    }

    // ------------------------------------------------------------------ PPTX

    private static final int MAX_BULLETS_PER_SLIDE = 6;
    private static final int MAX_CHARS_PER_SLIDE = 520;

    /** Satu slide = judul + butir. Slide yang terlalu padat dipecah otomatis menjadi "(lanjutan)". */
    public static void pptx(File out, List<String> titles, List<List<String>> bullets) throws IOException {
        List<String> st = new ArrayList<String>();
        List<List<String>> sb = new ArrayList<List<String>>();
        int count = titles == null ? 0 : titles.size();
        for (int i = 0; i < count; i++) {
            List<String> src = bullets != null && i < bullets.size() && bullets.get(i) != null ? bullets.get(i) : new ArrayList<String>();
            String title = titles.get(i) == null ? "" : titles.get(i);
            List<String> cur = new ArrayList<String>();
            int chars = 0;
            boolean first = true;
            for (String item : src) {
                if (!cur.isEmpty() && (cur.size() >= MAX_BULLETS_PER_SLIDE || chars + item.length() > MAX_CHARS_PER_SLIDE)) {
                    st.add(first ? title : title + " (lanjutan)"); sb.add(cur);
                    cur = new ArrayList<String>(); chars = 0; first = false;
                }
                cur.add(item); chars += item.length();
            }
            st.add(first ? title : title + " (lanjutan)"); sb.add(cur);
        }
        if (st.isEmpty()) { st.add(""); sb.add(new ArrayList<String>()); }
        int n = st.size();
        Zip z = new Zip(new FileOutputStream(out));
        try {
            StringBuilder ct = new StringBuilder(XML + "<Types xmlns=\"" + NS_CT + "\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>"
                + "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>"
                + "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>"
                + "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>");
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < n; i++) {
                int k = i + 1;
                ct.append("<Override PartName=\"/ppt/slides/slide").append(k).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
                ids.append("<p:sldId id=\"").append(256 + i).append("\" r:id=\"rId").append(k + 2).append("\"/>");
                z.put("ppt/slides/slide" + k + ".xml", slideXml(st.get(i), sb.get(i)));
                z.put("ppt/slides/_rels/slide" + k + ".xml.rels", rels(rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml")));
            }
            // rId1 = master, rId2 = tema, rId3.. = slide (sldId merujuk rId{k+2})
            String[] pr = new String[n + 2];
            pr[0] = rel("rId1", "slideMaster", "slideMasters/slideMaster1.xml");
            pr[1] = rel("rId2", "theme", "theme/theme1.xml");
            for (int i = 0; i < n; i++) pr[i + 2] = rel("rId" + (i + 3), "slide", "slides/slide" + (i + 1) + ".xml");
            ct.append("</Types>");
            z.put("[Content_Types].xml", ct.toString());
            z.put("_rels/.rels", rels(rel("rId1", "officeDocument", "ppt/presentation.xml")));
            z.put("ppt/presentation.xml", XML + "<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + NS_R + "\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                + "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>"
                + "<p:sldIdLst>" + ids + "</p:sldIdLst>"
                + "<p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>");
            z.put("ppt/_rels/presentation.xml.rels", rels(pr));
            z.put("ppt/slideMasters/slideMaster1.xml", SLIDE_MASTER);
            z.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", rels(rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"), rel("rId2", "theme", "../theme/theme1.xml")));
            z.put("ppt/slideLayouts/slideLayout1.xml", SLIDE_LAYOUT);
            z.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", rels(rel("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")));
            z.put("ppt/theme/theme1.xml", THEME);
        } finally { z.close(); }
    }

    private static String slideXml(String title, List<String> items) {
        StringBuilder b = new StringBuilder(XML + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + NS_R + "\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld><p:spTree>"
            + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>");
        b.append("<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Judul\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>"
            + "<p:spPr><a:xfrm><a:off x=\"609600\" y=\"365125\"/><a:ext cx=\"10972800\" cy=\"1143000\"/></a:xfrm></p:spPr>"
            + "<p:txBody><a:bodyPr anchor=\"ctr\"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:r><a:rPr lang=\"id-ID\" sz=\"3600\" b=\"1\"/><a:t>")
            .append(esc(plain(title))).append("</a:t></a:r></a:p></p:txBody></p:sp>");
        b.append("<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Isi\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr>"
            + "<p:spPr><a:xfrm><a:off x=\"609600\" y=\"1600200\"/><a:ext cx=\"10972800\" cy=\"4525963\"/></a:xfrm></p:spPr>"
            + "<p:txBody><a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle/>");
        if (items.isEmpty()) b.append("<a:p><a:endParaRPr lang=\"id-ID\"/></a:p>");
        for (String it : items) {
            boolean sub = it.startsWith("  ") || it.startsWith("\t");
            String text = plain(it.trim());
            b.append("<a:p><a:pPr marL=\"").append(sub ? 800100 : 342900).append("\" indent=\"-342900\"><a:buChar char=\"").append(sub ? "\u2013" : "\u2022").append("\"/></a:pPr>")
                .append("<a:r><a:rPr lang=\"id-ID\" sz=\"").append(sub ? 2000 : 2400).append("\"/><a:t>").append(esc(text)).append("</a:t></a:r></a:p>");
        }
        return b.append("</p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>").toString();
    }

    private static final String SLIDE_LAYOUT = XML
        + "<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + NS_R + "\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"obj\" preserve=\"1\">"
        + "<p:cSld name=\"Judul dan Isi\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
        + "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Judul\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"id-ID\"/><a:t>Judul</a:t></a:r></a:p></p:txBody></p:sp>"
        + "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Isi\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"id-ID\"/><a:t>Isi</a:t></a:r></a:p></p:txBody></p:sp>"
        + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>";

    private static final String SLIDE_MASTER = XML
        + "<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + NS_R + "\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
        + "<p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
        + "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Judul\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"609600\" y=\"365125\"/><a:ext cx=\"10972800\" cy=\"1143000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr vert=\"horz\" anchor=\"ctr\"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:r><a:rPr lang=\"id-ID\"/><a:t>Judul</a:t></a:r></a:p></p:txBody></p:sp>"
        + "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Isi\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"609600\" y=\"1600200\"/><a:ext cx=\"10972800\" cy=\"4525963\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr vert=\"horz\"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:pPr lvl=\"0\"/><a:r><a:rPr lang=\"id-ID\"/><a:t>Isi</a:t></a:r></a:p></p:txBody></p:sp>"
        + "</p:spTree></p:cSld>"
        + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
        + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>"
        + "<p:txStyles><p:titleStyle><a:lvl1pPr algn=\"l\"><a:defRPr sz=\"3600\" b=\"1\"><a:solidFill><a:schemeClr val=\"tx2\"/></a:solidFill><a:latin typeface=\"+mj-lt\"/></a:defRPr></a:lvl1pPr></p:titleStyle>"
        + "<p:bodyStyle><a:lvl1pPr marL=\"342900\" indent=\"-342900\"><a:defRPr sz=\"2400\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill><a:latin typeface=\"+mn-lt\"/></a:defRPr></a:lvl1pPr></p:bodyStyle>"
        + "<p:otherStyle><a:defPPr><a:defRPr lang=\"id-ID\"/></a:defPPr></p:otherStyle></p:txStyles></p:sldMaster>";

    private static String fills() {
        return "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>";
    }

    private static final String THEME = XML
        + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Mythos\"><a:themeElements>"
        + "<a:clrScheme name=\"Mythos\"><a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>"
        + "<a:dk2><a:srgbClr val=\"1F2937\"/></a:dk2><a:lt2><a:srgbClr val=\"F3F4F6\"/></a:lt2>"
        + "<a:accent1><a:srgbClr val=\"2563EB\"/></a:accent1><a:accent2><a:srgbClr val=\"DC2626\"/></a:accent2><a:accent3><a:srgbClr val=\"16A34A\"/></a:accent3>"
        + "<a:accent4><a:srgbClr val=\"D97706\"/></a:accent4><a:accent5><a:srgbClr val=\"7C3AED\"/></a:accent5><a:accent6><a:srgbClr val=\"0891B2\"/></a:accent6>"
        + "<a:hlink><a:srgbClr val=\"2563EB\"/></a:hlink><a:folHlink><a:srgbClr val=\"7C3AED\"/></a:folHlink></a:clrScheme>"
        + "<a:fontScheme name=\"Mythos\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>"
        + "<a:fmtScheme name=\"Mythos\"><a:fillStyleLst>" + fills() + fills() + fills() + "</a:fillStyleLst>"
        + "<a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>"
        + "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>"
        + "<a:bgFillStyleLst>" + fills() + fills() + fills() + "</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>";

    // ------------------------------------------------------------------ PDF

    private static final short[] HELV = new short[95]; // lebar Helvetica (per 1000 em) untuk 0x20..0x7E
    static {
        int[] w = {278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584};
        for (int i = 0; i < 95; i++) HELV[i] = (short) w[i];
    }

    private static double textWidth(String s, double size, boolean bold) {
        double t = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int w = (c >= 0x20 && c <= 0x7E) ? HELV[c - 0x20] : 556;
            t += w;
        }
        return t / 1000.0 * size * (bold ? 1.07 : 1.0) + 0.0;
    }

    /** Pecah [text] menjadi baris yang muat dalam [maxW] poin. */
    private static List<String> wrap(String text, double size, boolean bold, double maxW) {
        List<String> lines = new ArrayList<String>();
        for (String hard : text.split("\n", -1)) {
            StringBuilder cur = new StringBuilder();
            for (String word : hard.split(" ", -1)) {
                String candidate = cur.length() == 0 ? word : cur + " " + word;
                if (textWidth(candidate, size, bold) <= maxW) { cur = new StringBuilder(candidate); continue; }
                if (cur.length() > 0) { lines.add(cur.toString()); cur = new StringBuilder(); }
                // kata tunggal lebih lebar dari baris: potong per karakter
                String rest = word;
                while (textWidth(rest, size, bold) > maxW && rest.length() > 1) {
                    int k = rest.length();
                    while (k > 1 && textWidth(rest.substring(0, k), size, bold) > maxW) k--;
                    lines.add(rest.substring(0, k));
                    rest = rest.substring(k);
                }
                cur = new StringBuilder(rest);
            }
            lines.add(cur.toString());
        }
        return lines;
    }

    /** Teks -> byte WinAnsi (cp1252) untuk literal PDF; karakter di luar cakupan menjadi '?'. */
    private static String pdfLiteral(String s) {
        StringBuilder b = new StringBuilder("(");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int code;
            switch (c) {
                case '\u2013': code = 0x96; break;
                case '\u2014': code = 0x97; break;
                case '\u2018': code = 0x91; break;
                case '\u2019': code = 0x92; break;
                case '\u201C': code = 0x93; break;
                case '\u201D': code = 0x94; break;
                case '\u2022': code = 0x95; break;
                case '\u2026': code = 0x85; break;
                case '\u20AC': code = 0x80; break;
                default: code = (c >= 0x20 && c <= 0x7E) || (c >= 0xA0 && c <= 0xFF) ? c : (c == '\t' ? 0x20 : '?');
            }
            if (code == '(' || code == ')' || code == '\\') b.append('\\').append((char) code);
            else if (code >= 0x20 && code <= 0x7E) b.append((char) code);
            else b.append(String.format(java.util.Locale.US, "\\%03o", code));
        }
        return b.append(")").toString();
    }

    private static final class PdfWriter {
        final double pageW = 595.28, pageH = 841.89, margin = 56;
        final List<String> pages = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        double y;

        PdfWriter() { newPage(); }

        void newPage() {
            if (cur.length() > 0) pages.add(cur.toString());
            cur = new StringBuilder();
            y = pageH - margin;
        }

        void ensure(double need) { if (y - need < margin) newPage(); }

        void text(double x, double baseline, String s, double size, boolean bold) {
            cur.append("BT /").append(bold ? "F2" : "F1").append(' ').append(fmt(size)).append(" Tf ")
                .append(fmt(x)).append(' ').append(fmt(baseline)).append(" Td ").append(pdfLiteral(s)).append(" Tj ET\n");
        }

        void line(double x1, double y1, double x2, double y2) {
            cur.append("0.6 G 0.5 w ").append(fmt(x1)).append(' ').append(fmt(y1)).append(" m ").append(fmt(x2)).append(' ').append(fmt(y2)).append(" l S\n");
        }

        void finish() { pages.add(cur.toString()); cur = new StringBuilder(); }

        static String fmt(double d) { return String.format(java.util.Locale.US, "%.2f", d); }
    }

    public static void pdf(File out, String title, String markdown) throws IOException {
        PdfWriter w = new PdfWriter();
        double textW = w.pageW - 2 * w.margin;
        if (title != null && !title.trim().isEmpty()) {
            for (String l : wrap(plain(title.trim()), 22, true, textW)) {
                w.ensure(28); w.y -= 22; w.text(w.margin, w.y, l, 22, true); w.y -= 6;
            }
            w.y -= 8;
        }
        for (Block bl : parse(markdown)) {
            switch (bl.type) {
                case H: {
                    double size = bl.level == 1 ? 17 : bl.level == 2 ? 14 : 12;
                    w.y -= bl.level == 1 ? 10 : 6;
                    for (String l : wrap(plain(bl.text), size, true, textW)) { w.ensure(size * 1.4); w.y -= size * 1.25; w.text(w.margin, w.y, l, size, true); }
                    w.y -= 4;
                    break;
                }
                case BUL: case NUM: {
                    double indent = (bl.level > 0 ? 36 : 18);
                    String marker = bl.type == BUL ? "\u2022" : bl.text.replaceFirst("^(\\d+\\.).*$", "$1");
                    String body = bl.type == BUL ? plain(bl.text) : plain(bl.text.replaceFirst("^\\d+\\.\\s*", ""));
                    boolean firstLine = true;
                    for (String l : wrap(body, 11, false, textW - indent - 14)) {
                        w.ensure(15); w.y -= 14;
                        if (firstLine) w.text(w.margin + indent - 4, w.y, marker, 11, false);
                        w.text(w.margin + indent + 12, w.y, l, 11, false);
                        firstLine = false;
                    }
                    w.y -= 2;
                    break;
                }
                case TBL: {
                    pdfTable(w, bl.rows, textW);
                    w.y -= 6;
                    break;
                }
                default: {
                    for (String l : wrap(plain(bl.text), 11, false, textW)) { w.ensure(15); w.y -= 14; w.text(w.margin, w.y, l, 11, false); }
                    w.y -= 6;
                }
            }
        }
        w.finish();
        writePdf(out, w.pages);
    }

    private static void pdfTable(PdfWriter w, List<List<String>> rows, double textW) {
        int cols = 0;
        for (List<String> r : rows) cols = Math.max(cols, r.size());
        if (cols == 0) return;
        double colW = textW / cols;
        for (int ri = 0; ri < rows.size(); ri++) {
            List<String> r = rows.get(ri);
            List<List<String>> wrapped = new ArrayList<List<String>>();
            int maxLines = 1;
            for (int c = 0; c < cols; c++) {
                List<String> ls = wrap(plain(c < r.size() ? r.get(c) : ""), 10, ri == 0, colW - 8);
                wrapped.add(ls);
                maxLines = Math.max(maxLines, ls.size());
            }
            double h = maxLines * 12.5 + 6;
            w.ensure(h);
            double top = w.y;
            for (int c = 0; c < cols; c++) {
                double base = top - 12;
                for (String l : wrapped.get(c)) { w.text(w.margin + c * colW + 4, base, l, 10, ri == 0); base -= 12.5; }
            }
            w.y = top - h;
            w.line(w.margin, w.y, w.margin + textW, w.y);
            if (ri == 0) w.line(w.margin, top, w.margin + textW, top);
        }
    }

    private static void writePdf(File out, List<String> pages) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<Integer>();
        int nPages = pages.size();
        // objek: 1 katalog, 2 daftar halaman, 3 F1, 4 F2, lalu pasangan (halaman, isi) mulai dari 5
        wr(o, "%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n");
        obj(o, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < nPages; i++) kids.append(5 + 2 * i).append(" 0 R ");
        obj(o, offsets, 2, "<< /Type /Pages /Kids [" + kids + "] /Count " + nPages + " >>");
        obj(o, offsets, 3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        obj(o, offsets, 4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        for (int i = 0; i < nPages; i++) {
            int pageObj = 5 + 2 * i;
            byte[] content = pages.get(i).getBytes(StandardCharsets.ISO_8859_1);
            obj(o, offsets, pageObj, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595.28 841.89] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (pageObj + 1) + " 0 R >>");
            offsets.add(o.size());
            wr(o, (pageObj + 1) + " 0 obj\n<< /Length " + content.length + " >>\nstream\n");
            o.write(content);
            wr(o, "\nendstream\nendobj\n");
        }
        int xref = o.size();
        int total = 4 + 2 * nPages + 1;
        StringBuilder x = new StringBuilder("xref\n0 ").append(total).append("\n0000000000 65535 f \n");
        for (int off : offsets) x.append(String.format(java.util.Locale.US, "%010d 00000 n \n", off));
        x.append("trailer\n<< /Size ").append(total).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        wr(o, x.toString());
        FileOutputStream f = new FileOutputStream(out);
        try { f.write(o.toByteArray()); } finally { f.close(); }
    }

    private static void obj(ByteArrayOutputStream o, List<Integer> offsets, int n, String body) throws IOException {
        offsets.add(o.size());
        wr(o, n + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void wr(ByteArrayOutputStream o, String s) throws IOException {
        o.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
