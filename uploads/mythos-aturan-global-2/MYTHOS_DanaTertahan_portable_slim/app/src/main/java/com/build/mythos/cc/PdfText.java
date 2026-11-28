package com.build.mythos.cc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Pembaca teks PDF tanpa dependensi (JVM murni, tanpa android.*), hanya memakai API Java 8.
 * Ditulis dalam Java agar bisa dijalankan dan divalidasi di mesin mana pun yang punya JDK; Kotlin hanya memanggilnya.
 *
 * Yang dibaca: objek (dipindai berurutan, tidak bergantung tabel xref sehingga file rusak/terpotong tetap terbaca),
 * object stream (PDF 1.5+), aliran Flate/ASCIIHex/ASCII85 (+ prediktor PNG), pohon halaman, Form XObject,
 * font sederhana (Encoding, Differences, ToUnicode) dan font CID (ToUnicode, Identity, CMap UCS2).
 * Spasi dan baris baru dihitung dari posisi teks dan lebar glyph (Widths / W), bukan ditebak.
 *
 * Batas yang diketahui: tanpa OCR (PDF scan tidak terbaca), PDF terenkripsi tidak didekripsi, font CID/subset
 * tanpa ToUnicode dilaporkan gagal (bukan ditampilkan sebagai karakter acak), filter LZW/RunLength/CCITT/JPX tidak
 * didukung, tabel dan kolom hanya didekati per baris.
 */
@SuppressWarnings("unchecked")
public final class PdfText {
    private PdfText() {}

    public enum Status { OK, ENCRYPTED, NO_TEXT, UNMAPPED_FONT, CORRUPT, TOO_LARGE }

    public static final long MAX_BYTES = 30L * 1024L * 1024L;
    public static final int DEFAULT_MAX_PAGES = 60;
    public static final long DEFAULT_BUDGET_MS = 20_000L;
    /** Kurang dari ini karakter terlihat dianggap "tanpa teks". */
    public static final int MIN_VISIBLE = 10;

    private static final long MAX_STREAM_BYTES = 24L * 1024L * 1024L;
    private static final long DECODE_BUDGET_BYTES = 96L * 1024L * 1024L;

    public static final class Result {
        public final Status status;
        /** Teks hasil (kosong bila status bukan OK). Halaman diberi penanda [Halaman N] bila dokumen lebih dari satu halaman. */
        public final String text;
        /** Jumlah halaman di dokumen (0 bila tidak ketahuan). */
        public final int pageCount;
        public final int pagesRead;
        /** Ada halaman atau karakter yang tidak ikut karena batas halaman/karakter/waktu. */
        public final boolean truncated;
        /** Halaman yang dibaca tetapi tanpa teks (gambar/scan). */
        public final int emptyPages;
        /** Porsi glyph yang tidak bisa dipetakan ke Unicode (0..1). */
        public final double unmappedRatio;
        /** Penjelasan singkat (Indonesia). */
        public final String detail;

        Result(Status status, String text, int pageCount, int pagesRead, boolean truncated, int emptyPages,
               double unmappedRatio, String detail) {
            this.status = status;
            this.text = text;
            this.pageCount = pageCount;
            this.pagesRead = pagesRead;
            this.truncated = truncated;
            this.emptyPages = emptyPages;
            this.unmappedRatio = unmappedRatio;
            this.detail = detail;
        }

        static Result fail(Status s, String detail) {
            return new Result(s, "", 0, 0, false, 0, 0.0, detail);
        }
    }

    public static Result extract(byte[] data, int maxChars, int maxPages) {
        return extract(data, maxChars, maxPages, DEFAULT_BUDGET_MS);
    }

    public static Result extract(byte[] data, int maxChars, int maxPages, long budgetMs) {
        if (data == null || data.length < 8) return Result.fail(Status.CORRUPT, "berkas kosong atau terlalu kecil untuk PDF");
        if (data.length > MAX_BYTES) return Result.fail(Status.TOO_LARGE, "lebih dari 30 MB");
        if (maxChars < 1) maxChars = 1;
        if (maxPages < 1) maxPages = 1;
        int headEnd = Math.min(data.length, 1024);
        if (indexOf(data, "%PDF-", 0, headEnd) < 0) return Result.fail(Status.CORRUPT, "bukan berkas PDF (tanpa header %PDF-)");
        Doc doc = new Doc(data, budgetMs);
        try {
            return doc.run(maxChars, maxPages);
        } catch (Timeout t) {
            return Result.fail(Status.CORRUPT, "waktu baca habis");
        } catch (OutOfMemoryError e) {
            return Result.fail(Status.TOO_LARGE, "memori tidak cukup untuk membaca PDF ini");
        } catch (StackOverflowError e) {
            return Result.fail(Status.CORRUPT, "struktur PDF terlalu bersarang");
        } catch (RuntimeException e) {
            return Result.fail(Status.CORRUPT, "struktur PDF rusak (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ------------------------------------------------------------------ token dan leksikal

    private static final class Timeout extends RuntimeException {
        Timeout() { super("timeout", null, false, false); }
    }

    private static final Object EOF = new Object();
    private static final Object CLOSE_ARR = new Object();
    private static final Object CLOSE_DICT = new Object();
    private static final Object NULL = new Object();

    private static final class Name {
        final String s;
        Name(String s) { this.s = s; }
    }

    private static final class Ref {
        final int num;
        Ref(int num) { this.num = num; }
    }

    private static final class Kw {
        final String s;
        Kw(String s) { this.s = s; }
    }

    private static boolean isWs(int c) { return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32; }

    private static boolean isDelim(int c) {
        switch (c) {
            case '(': case ')': case '<': case '>': case '[': case ']': case '{': case '}': case '/': case '%':
                return true;
            default:
                return false;
        }
    }

    private static boolean isDigit(int c) { return c >= '0' && c <= '9'; }

    private static int hexVal(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    static int indexOf(byte[] h, String pat, int from) { return indexOf(h, pat, from, h.length); }

    static int indexOf(byte[] h, String pat, int from, int to) {
        int m = pat.length();
        if (m == 0) return from;
        byte f = (byte) pat.charAt(0);
        int last = Math.min(to, h.length) - m;
        for (int i = Math.max(0, from); i <= last; i++) {
            if (h[i] != f) continue;
            int k = 1;
            while (k < m && h[i + k] == (byte) pat.charAt(k)) k++;
            if (k == m) return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] h, int at, String pat) {
        if (at < 0 || at + pat.length() > h.length) return false;
        for (int k = 0; k < pat.length(); k++) if (h[at + k] != (byte) pat.charAt(k)) return false;
        return true;
    }

    private static final class Lexer {
        final byte[] b;
        int p;
        final int end;
        final boolean refs;
        int depth;

        Lexer(byte[] b, int p, int end, boolean refs) {
            this.b = b;
            this.p = p;
            this.end = end;
            this.refs = refs;
        }

        void skipWs() {
            while (p < end) {
                int c = b[p] & 0xff;
                if (isWs(c)) p++;
                else if (c == '%') { while (p < end && b[p] != 10 && b[p] != 13) p++; }
                else break;
            }
        }

        Object next() {
            while (true) {
                skipWs();
                if (p >= end) return EOF;
                int c = b[p] & 0xff;
                switch (c) {
                    case '/': p++; return readName();
                    case '(': p++; return readLiteral();
                    case '<':
                        if (p + 1 < end && b[p + 1] == '<') { p += 2; return readDict(); }
                        p++;
                        return readHex();
                    case '[': p++; return readArray();
                    case ']': p++; return CLOSE_ARR;
                    case '>':
                        if (p + 1 < end && b[p + 1] == '>') { p += 2; return CLOSE_DICT; }
                        p++;
                        continue;
                    case ')': case '{': case '}':
                        p++;
                        continue;
                    default:
                        return readToken();
                }
            }
        }

        Object readDict() {
            if (++depth > 64) throw new IllegalStateException("terlalu dalam");
            try {
                Map<String, Object> m = new HashMap<String, Object>();
                while (true) {
                    Object k = next();
                    if (k == EOF || k == CLOSE_DICT) break;
                    if (!(k instanceof Name)) continue;
                    Object v = next();
                    if (v == EOF || v == CLOSE_DICT) break;
                    if (v == CLOSE_ARR || v == NULL) continue;
                    m.put(((Name) k).s, v);
                }
                return m;
            } finally {
                depth--;
            }
        }

        Object readArray() {
            if (++depth > 64) throw new IllegalStateException("terlalu dalam");
            try {
                List<Object> l = new ArrayList<Object>();
                while (true) {
                    Object v = next();
                    if (v == EOF || v == CLOSE_ARR || v == CLOSE_DICT) break;
                    l.add(v);
                    if (l.size() > 2_000_000) break;
                }
                return l;
            } finally {
                depth--;
            }
        }

        Object readName() {
            StringBuilder sb = new StringBuilder();
            while (p < end) {
                int c = b[p] & 0xff;
                if (isWs(c) || isDelim(c)) break;
                if (c == '#' && p + 2 < end && hexVal(b[p + 1] & 0xff) >= 0 && hexVal(b[p + 2] & 0xff) >= 0) {
                    sb.append((char) (hexVal(b[p + 1] & 0xff) * 16 + hexVal(b[p + 2] & 0xff)));
                    p += 3;
                } else {
                    sb.append((char) c);
                    p++;
                }
            }
            return new Name(sb.toString());
        }

        Object readLiteral() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int nest = 1;
            while (p < end) {
                int c = b[p++] & 0xff;
                if (c == '\\') {
                    if (p >= end) break;
                    int d = b[p++] & 0xff;
                    switch (d) {
                        case 'n': o.write(10); break;
                        case 'r': o.write(13); break;
                        case 't': o.write(9); break;
                        case 'b': o.write(8); break;
                        case 'f': o.write(12); break;
                        case '\r': if (p < end && b[p] == '\n') p++; break;
                        case '\n': break;
                        default:
                            if (d >= '0' && d <= '7') {
                                int v = d - '0';
                                for (int k = 0; k < 2 && p < end && b[p] >= '0' && b[p] <= '7'; k++) { v = v * 8 + (b[p] - '0'); p++; }
                                o.write(v & 0xff);
                            } else {
                                o.write(d);
                            }
                    }
                } else if (c == '(') {
                    nest++;
                    o.write(c);
                } else if (c == ')') {
                    if (--nest == 0) break;
                    o.write(c);
                } else {
                    o.write(c);
                }
            }
            return o.toByteArray();
        }

        Object readHex() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int hi = -1;
            while (p < end) {
                int c = b[p++] & 0xff;
                if (c == '>') break;
                int v = hexVal(c);
                if (v < 0) continue;
                if (hi < 0) hi = v;
                else { o.write(hi * 16 + v); hi = -1; }
            }
            if (hi >= 0) o.write(hi * 16);
            return o.toByteArray();
        }

        Object readToken() {
            int s = p;
            while (p < end) {
                int c = b[p] & 0xff;
                if (isWs(c) || isDelim(c)) break;
                p++;
            }
            if (p == s) { p++; return new Kw(""); }
            int c0 = b[s] & 0xff;
            if (isDigit(c0) || c0 == '+' || c0 == '-' || c0 == '.') {
                Object n = parseNumber(s, p);
                if (n != null) {
                    if (refs && n instanceof Long) {
                        long v = (Long) n;
                        if (v >= 0 && v < Integer.MAX_VALUE) {
                            Ref r = tryRef((int) v);
                            if (r != null) return r;
                        }
                    }
                    return n;
                }
            }
            String t = new String(b, s, p - s, StandardCharsets.ISO_8859_1);
            if (t.equals("true")) return Boolean.TRUE;
            if (t.equals("false")) return Boolean.FALSE;
            if (t.equals("null")) return NULL;
            return new Kw(t);
        }

        Object parseNumber(int s, int e) {
            boolean dot = false, digit = false;
            for (int i = s; i < e; i++) {
                int c = b[i] & 0xff;
                if (isDigit(c)) digit = true;
                else if (c == '.') dot = true;
                else if (c == '+' || c == '-') { /* tanda */ }
                else return null;
            }
            if (!digit) return null;
            String t = new String(b, s, e - s, StandardCharsets.ISO_8859_1);
            try {
                if (!dot) return Long.valueOf(Long.parseLong(t));
                return Double.valueOf(Double.parseDouble(t));
            } catch (NumberFormatException ex) {
                return Long.valueOf(0L);
            }
        }

        Ref tryRef(int num) {
            int save = p;
            if (p >= end || !isWs(b[p] & 0xff)) return null;
            while (p < end && isWs(b[p] & 0xff)) p++;
            int s = p;
            while (p < end && isDigit(b[p] & 0xff)) p++;
            if (p == s || p - s > 5 || p >= end || !isWs(b[p] & 0xff)) { p = save; return null; }
            while (p < end && isWs(b[p] & 0xff)) p++;
            if (p < end && b[p] == 'R' && (p + 1 >= end || isWs(b[p + 1] & 0xff) || isDelim(b[p + 1] & 0xff))) {
                p++;
                return new Ref(num);
            }
            p = save;
            return null;
        }
    }

    // ------------------------------------------------------------------ objek dan dokumen

    private static final class Obj {
        Object val;
        int sStart = -1;
        int sLen;
        int order;
    }

    private static final class PageInfo {
        final Map<String, Object> dict;
        final Map<String, Object> res;
        PageInfo(Map<String, Object> dict, Map<String, Object> res) { this.dict = dict; this.res = res; }
    }

    private static String nameOf(Object o) { return o instanceof Name ? ((Name) o).s : null; }

    private static double num(Object o, double def) { return o instanceof Number ? ((Number) o).doubleValue() : def; }

    private static Map<String, Object> dictOf(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    private static final class Doc {
        final byte[] d;
        final long start = System.nanoTime();
        final long budgetNanos;
        final Map<Integer, Obj> objs = new HashMap<Integer, Obj>();
        boolean encrypted;
        Object rootRef;
        int rootOrder = -1;
        long budget = DECODE_BUDGET_BYTES;
        long ticks;
        final Map<Integer, Font> fontCache = new HashMap<Integer, Font>();
        final IdentityHashMap<Object, Font> fontCacheDirect = new IdentityHashMap<Object, Font>();
        long shown, unmapped;
        int skippedStreams, badStreams, pagesNoContent;

        Doc(byte[] d, long budgetMs) {
            this.d = d;
            this.budgetNanos = Math.max(1L, budgetMs) * 1_000_000L;
        }

        void tick() {
            if ((++ticks & 255) == 0 && System.nanoTime() - start > budgetNanos) throw new Timeout();
        }

        Object res(Object o) {
            int guard = 0;
            while (o instanceof Ref && guard++ < 16) {
                Obj ob = objs.get(((Ref) o).num);
                if (ob == null) return null;
                o = ob.val;
            }
            if (o instanceof Ref || o == NULL) return null;
            return o;
        }

        Obj objOf(Object o) { return o instanceof Ref ? objs.get(((Ref) o).num) : null; }

        // ---------------------------------------------------------- pemindaian objek

        void scan() {
            int n = d.length;
            int p = 0;
            while (true) {
                tick();
                int i = indexOf(d, "obj", p);
                if (i < 0) break;
                if (i >= 3 && d[i - 1] == 'd' && d[i - 2] == 'n' && d[i - 3] == 'e') { p = i + 3; continue; }
                int after = i + 3;
                if (after < n && !isWs(d[after] & 0xff) && !isDelim(d[after] & 0xff)) { p = after; continue; }
                int q = i - 1;
                while (q >= 0 && isWs(d[q] & 0xff)) q--;
                if (q < 0 || !isDigit(d[q] & 0xff)) { p = after; continue; }
                while (q >= 0 && isDigit(d[q] & 0xff)) q--;
                if (q < 0 || !isWs(d[q] & 0xff)) { p = after; continue; }
                while (q >= 0 && isWs(d[q] & 0xff)) q--;
                if (q < 0 || !isDigit(d[q] & 0xff)) { p = after; continue; }
                int ne = q;
                while (q >= 0 && isDigit(d[q] & 0xff)) q--;
                int ns = q + 1;
                if (ne - ns + 1 > 9) { p = after; continue; }
                int num = Integer.parseInt(new String(d, ns, ne - ns + 1, StandardCharsets.ISO_8859_1));

                Lexer lx = new Lexer(d, after, n, true);
                Object v = lx.next();
                if (v == EOF) break;
                Obj o = new Obj();
                o.val = v == NULL ? null : v;
                o.order = i;
                int nextP = lx.p;
                lx.skipWs();
                if (v instanceof Map && startsWith(d, lx.p, "stream")) {
                    int sp = lx.p + 6;
                    if (sp < n && d[sp] == '\r') { sp++; if (sp < n && d[sp] == '\n') sp++; }
                    else if (sp < n && d[sp] == '\n') sp++;
                    int endPos = -1;
                    Object len = ((Map<String, Object>) v).get("Length");
                    if (len instanceof Number) {
                        long l = ((Number) len).longValue();
                        if (l >= 0 && sp + l <= n) {
                            int t = (int) (sp + l);
                            int lim = Math.min(n, t + 4);
                            while (t < lim && isWs(d[t] & 0xff)) t++;
                            if (startsWith(d, t, "endstream")) endPos = (int) (sp + l);
                        }
                    }
                    if (endPos < 0) {
                        int t = sp;
                        int firstE = -1;
                        while (true) {
                            int e = indexOf(d, "endstream", t);
                            if (e < 0) break;
                            if (firstE < 0) firstE = e;
                            int a = e + 9;
                            while (a < n && isWs(d[a] & 0xff)) a++;
                            if (startsWith(d, a, "endobj") || a >= n) { firstE = e; break; }
                            t = e + 9;
                        }
                        endPos = firstE >= 0 ? firstE : n;
                        if (endPos > sp && d[endPos - 1] == '\n') endPos--;
                        if (endPos > sp && d[endPos - 1] == '\r') endPos--;
                    }
                    o.sStart = sp;
                    o.sLen = Math.max(0, endPos - sp);
                    nextP = endPos;
                }
                Obj ex = objs.get(num);
                if (ex == null || ex.order <= o.order) objs.put(num, o);
                p = Math.max(nextP, after);
            }
        }

        void noteTrailer(Map<String, Object> m, int order) {
            if (m.containsKey("Encrypt")) encrypted = true;
            Object r = m.get("Root");
            if (r instanceof Ref && order >= rootOrder) { rootRef = r; rootOrder = order; }
        }

        void scanTrailers() {
            int p = 0;
            while (true) {
                tick();
                int i = indexOf(d, "trailer", p);
                if (i < 0) break;
                Lexer lx = new Lexer(d, i + 7, d.length, true);
                Object v = lx.next();
                if (v instanceof Map) noteTrailer((Map<String, Object>) v, i);
                p = i + 7;
            }
            for (Obj o : objs.values()) {
                if (o.val instanceof Map && "XRef".equals(nameOf(((Map<String, Object>) o.val).get("Type")))) {
                    noteTrailer((Map<String, Object>) o.val, o.order);
                }
            }
        }

        void expandObjStms() {
            List<Obj> streams = new ArrayList<Obj>();
            for (Obj o : objs.values()) {
                if (o.sStart >= 0 && o.val instanceof Map
                        && "ObjStm".equals(nameOf(((Map<String, Object>) o.val).get("Type")))) streams.add(o);
            }
            for (Obj o : streams) {
                tick();
                Map<String, Object> m = (Map<String, Object>) o.val;
                int n = (int) Math.min(100000, num(res(m.get("N")), 0));
                int first = (int) num(res(m.get("First")), 0);
                byte[] data = decode(o);
                if (data == null || n <= 0 || first < 0 || first > data.length) continue;
                Lexer h = new Lexer(data, 0, first, false);
                for (int k = 0; k < n; k++) {
                    Object a = h.next();
                    Object b = h.next();
                    if (!(a instanceof Long) || !(b instanceof Long)) break;
                    int num = (int) (long) (Long) a;
                    long pos = first + (Long) b;
                    if (pos < 0 || pos >= data.length) continue;
                    Lexer lx = new Lexer(data, (int) pos, data.length, true);
                    Object v = lx.next();
                    if (v == EOF) continue;
                    Obj no = new Obj();
                    no.val = v == NULL ? null : v;
                    no.order = o.order;
                    Obj ex = objs.get(num);
                    if (ex == null || ex.order <= no.order) objs.put(num, no);
                }
            }
        }

        // ---------------------------------------------------------- dekode aliran

        byte[] decode(Obj o) {
            if (o == null || o.sStart < 0 || !(o.val instanceof Map)) return null;
            Map<String, Object> m = (Map<String, Object>) o.val;
            List<String> fl = new ArrayList<String>();
            Object f = res(m.get("Filter"));
            if (f instanceof Name) fl.add(((Name) f).s);
            else if (f instanceof List) {
                for (Object x : (List<Object>) f) {
                    Object r = res(x);
                    if (r instanceof Name) fl.add(((Name) r).s);
                }
            }
            Object dp = res(m.get("DecodeParms"));
            if (dp == null) dp = res(m.get("DP"));
            byte[] data = Arrays.copyOfRange(d, o.sStart, o.sStart + o.sLen);
            for (int i = 0; i < fl.size(); i++) {
                Map<String, Object> pm = null;
                if (dp instanceof Map) pm = (Map<String, Object>) dp;
                else if (dp instanceof List && i < ((List<Object>) dp).size()) pm = dictOf(res(((List<Object>) dp).get(i)));
                String fn = fl.get(i);
                if (fn.equals("FlateDecode") || fn.equals("Fl")) data = predictor(inflate(data), pm);
                else if (fn.equals("ASCIIHexDecode") || fn.equals("AHx")) data = asciiHex(data);
                else if (fn.equals("ASCII85Decode") || fn.equals("A85")) data = ascii85(data);
                else return null;
                if (data == null) return null;
            }
            return data;
        }

        byte[] inflate(byte[] in) {
            Inflater inf = new Inflater();
            try {
                inf.setInput(in);
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, Math.min(in.length * 3, 1 << 22)));
                byte[] buf = new byte[16384];
                long cap = Math.min(MAX_STREAM_BYTES, budget);
                while (!inf.finished()) {
                    tick();
                    int n;
                    try {
                        n = inf.inflate(buf);
                    } catch (DataFormatException e) {
                        badStreams++;
                        break;
                    }
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    out.write(buf, 0, n);
                    if (out.size() > cap) break;
                }
                budget -= out.size();
                return out.toByteArray();
            } finally {
                inf.end();
            }
        }

        byte[] predictor(byte[] data, Map<String, Object> pm) {
            if (pm == null || data == null) return data;
            int pred = (int) num(res(pm.get("Predictor")), 1);
            if (pred < 10) return data;
            int colors = Math.max(1, (int) num(res(pm.get("Colors")), 1));
            int bpc = Math.max(1, (int) num(res(pm.get("BitsPerComponent")), 8));
            int cols = Math.max(1, (int) num(res(pm.get("Columns")), 1));
            int bpp = Math.max(1, (colors * bpc + 7) / 8);
            int rowLen = (colors * bpc * cols + 7) / 8;
            if (rowLen <= 0 || rowLen > (1 << 24)) return data;
            int stride = rowLen + 1;
            int rows = (data.length + stride - 1) / stride;
            byte[] out = new byte[rows * rowLen];
            for (int r = 0; r < rows; r++) {
                int ft = data[r * stride] & 0xff;
                for (int i = 0; i < rowLen; i++) {
                    int si = r * stride + 1 + i;
                    if (si >= data.length) break;
                    int x = data[si] & 0xff;
                    int a = i >= bpp ? out[r * rowLen + i - bpp] & 0xff : 0;
                    int b = r > 0 ? out[(r - 1) * rowLen + i] & 0xff : 0;
                    int c = (i >= bpp && r > 0) ? out[(r - 1) * rowLen + i - bpp] & 0xff : 0;
                    int v;
                    switch (ft) {
                        case 1: v = x + a; break;
                        case 2: v = x + b; break;
                        case 3: v = x + ((a + b) >> 1); break;
                        case 4: {
                            int pp = a + b - c;
                            int pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c);
                            v = x + ((pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c));
                            break;
                        }
                        default: v = x;
                    }
                    out[r * rowLen + i] = (byte) v;
                }
            }
            return out;
        }

        byte[] asciiHex(byte[] in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int hi = -1;
            for (byte x : in) {
                int c = x & 0xff;
                if (c == '>') break;
                int v = hexVal(c);
                if (v < 0) continue;
                if (hi < 0) hi = v;
                else { o.write(hi * 16 + v); hi = -1; }
            }
            if (hi >= 0) o.write(hi * 16);
            return o.toByteArray();
        }

        byte[] ascii85(byte[] in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            long acc = 0;
            int cnt = 0;
            int i = 0;
            if (in.length >= 2 && in[0] == '<' && in[1] == '~') i = 2;
            for (; i < in.length; i++) {
                int c = in[i] & 0xff;
                if (c == '~') break;
                if (isWs(c)) continue;
                if (c == 'z' && cnt == 0) { o.write(0); o.write(0); o.write(0); o.write(0); continue; }
                if (c < '!' || c > 'u') continue;
                acc = acc * 85 + (c - '!');
                if (++cnt == 5) {
                    o.write((int) (acc >> 24) & 0xff);
                    o.write((int) (acc >> 16) & 0xff);
                    o.write((int) (acc >> 8) & 0xff);
                    o.write((int) acc & 0xff);
                    acc = 0;
                    cnt = 0;
                }
            }
            if (cnt > 1) {
                for (int k = cnt; k < 5; k++) acc = acc * 85 + 84;
                for (int k = 0; k < cnt - 1; k++) o.write((int) (acc >> (24 - 8 * k)) & 0xff);
            }
            return o.toByteArray();
        }

        // ---------------------------------------------------------- pohon halaman

        void walk(Object node, Map<String, Object> inh, int depth, Set<Integer> seen, List<PageInfo> out, int max, int[] total) {
            tick();
            if (depth > 64 || total[0] > 200000) return;
            if (node instanceof Ref && !seen.add(((Ref) node).num)) return;
            Map<String, Object> n = dictOf(res(node));
            if (n == null) return;
            Map<String, Object> r = dictOf(res(n.get("Resources")));
            if (r == null) r = inh;
            Object kids = res(n.get("Kids"));
            if (kids instanceof List && !"Page".equals(nameOf(res(n.get("Type"))))) {
                for (Object k : (List<Object>) kids) walk(k, r, depth + 1, seen, out, max, total);
            } else {
                total[0]++;
                if (out.size() < max) out.add(new PageInfo(n, r));
            }
        }

        List<PageInfo> findPages(int max, int[] total) {
            List<PageInfo> out = new ArrayList<PageInfo>();
            Object root = rootRef;
            Map<String, Object> cat = null;
            if (root != null) cat = dictOf(res(root));
            if (cat == null || cat.get("Pages") == null) {
                int best = -1;
                cat = null;
                for (Obj o : objs.values()) {
                    if (o.val instanceof Map && "Catalog".equals(nameOf(((Map<String, Object>) o.val).get("Type"))) && o.order > best) {
                        best = o.order;
                        cat = (Map<String, Object>) o.val;
                    }
                }
            }
            if (cat != null && cat.get("Pages") != null) {
                walk(cat.get("Pages"), null, 0, new HashSet<Integer>(), out, max, total);
            }
            if (out.isEmpty()) {
                // Cadangan: pohon halaman rusak; ambil semua objek /Type /Page menurut urutan di berkas.
                total[0] = 0;
                List<Obj> pages = new ArrayList<Obj>();
                for (Obj o : objs.values()) {
                    if (o.val instanceof Map && "Page".equals(nameOf(((Map<String, Object>) o.val).get("Type")))) pages.add(o);
                }
                java.util.Collections.sort(pages, new java.util.Comparator<Obj>() {
                    @Override public int compare(Obj a, Obj b) { return a.order < b.order ? -1 : (a.order == b.order ? 0 : 1); }
                });
                for (Obj o : pages) {
                    total[0]++;
                    if (out.size() < max) {
                        Map<String, Object> pd = (Map<String, Object>) o.val;
                        out.add(new PageInfo(pd, dictOf(res(pd.get("Resources")))));
                    }
                }
            }
            return out;
        }

        void addContent(Object c, List<Obj> r, int depth) {
            if (depth > 4) return;
            if (c instanceof Ref) {
                Obj ob = objs.get(((Ref) c).num);
                if (ob == null) return;
                if (ob.sStart >= 0) r.add(ob);
                else addContent(ob.val, r, depth + 1);
            } else if (c instanceof List) {
                for (Object x : (List<Object>) c) addContent(x, r, depth + 1);
            }
        }

        String pageText(PageInfo pi) {
            List<Obj> cs = new ArrayList<Obj>();
            addContent(pi.dict.get("Contents"), cs, 0);
            if (cs.isEmpty()) pagesNoContent++;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (Obj c : cs) {
                byte[] b = decode(c);
                if (b == null) { skippedStreams++; continue; }
                bos.write(b, 0, b.length);
                bos.write('\n');
            }
            Interp ip = new Interp(this);
            ip.run(bos.toByteArray(), pi.res, 0);
            return ip.out.toString();
        }

        // ---------------------------------------------------------- font

        Font lookupFont(Map<String, Object> resources, String name) {
            if (resources == null) return null;
            Map<String, Object> fonts = dictOf(res(resources.get("Font")));
            if (fonts == null) return null;
            Object fo = fonts.get(name);
            if (fo == null) return null;
            if (fo instanceof Ref) {
                Integer key = ((Ref) fo).num;
                Font f = fontCache.get(key);
                if (f == null) {
                    Map<String, Object> fd = dictOf(res(fo));
                    if (fd == null) return null;
                    f = buildFont(fd);
                    fontCache.put(key, f);
                }
                return f;
            }
            Map<String, Object> fd = dictOf(fo);
            if (fd == null) return null;
            Font f = fontCacheDirect.get(fd);
            if (f == null) {
                f = buildFont(fd);
                fontCacheDirect.put(fd, f);
            }
            return f;
        }

        Font buildFont(Map<String, Object> fd) {
            Font f = new Font();
            String subtype = nameOf(res(fd.get("Subtype")));
            Obj tuo = objOf(fd.get("ToUnicode"));
            if (tuo != null && tuo.sStart >= 0) {
                byte[] data = decode(tuo);
                if (data != null) parseToUnicode(data, f);
            }
            boolean hasToUni = f.toUni != null && !f.toUni.isEmpty();
            if ("Type0".equals(subtype)) {
                f.cid = true;
                Object enc = res(fd.get("Encoding"));
                String en = nameOf(enc);
                if (en != null && ((en.startsWith("Uni") && en.contains("UCS2")) || en.contains("UTF16"))) f.identityUnicode = true;
                if (f.codeLen == 0) f.codeLen = 2;
                Object dfs = res(fd.get("DescendantFonts"));
                Map<String, Object> desc = null;
                if (dfs instanceof List && !((List<Object>) dfs).isEmpty()) desc = dictOf(res(((List<Object>) dfs).get(0)));
                else if (dfs instanceof Map) desc = (Map<String, Object>) dfs;
                if (desc != null) {
                    f.dw = num(res(desc.get("DW")), 1000);
                    parseW(res(desc.get("W")), f);
                }
                if (!hasToUni && !f.identityUnicode) f.unmappable = true;
                return f;
            }
            f.codeLen = 1;
            Object e = res(fd.get("Encoding"));
            String base = null;
            List<Object> diffs = null;
            if (e instanceof Name) {
                base = ((Name) e).s;
            } else if (e instanceof Map) {
                Map<String, Object> em = (Map<String, Object>) e;
                Object b = res(em.get("BaseEncoding"));
                if (b instanceof Name) base = ((Name) b).s;
                Object dd = res(em.get("Differences"));
                if (dd instanceof List) diffs = (List<Object>) dd;
            }
            String[] src = "MacRomanEncoding".equals(base) ? MAC : ("StandardEncoding".equals(base) ? STD : WIN);
            f.enc = Arrays.copyOf(src, 256);
            if (diffs != null) {
                int code = 0;
                for (Object x : diffs) {
                    Object r = res(x);
                    if (r instanceof Number) code = ((Number) r).intValue();
                    else if (r instanceof Name) {
                        if (code >= 0 && code < 256) f.enc[code] = glyph(((Name) r).s);
                        code++;
                    }
                }
            }
            String bf = nameOf(res(fd.get("BaseFont")));
            boolean subset = false;
            if (bf != null && bf.length() > 7 && bf.charAt(6) == '+') {
                subset = true;
                for (int i = 0; i < 6; i++) if (bf.charAt(i) < 'A' || bf.charAt(i) > 'Z') subset = false;
            }
            if (!hasToUni && subset && e == null) f.unmappable = true;
            f.first = (int) num(res(fd.get("FirstChar")), 0);
            Object w = res(fd.get("Widths"));
            if (w instanceof List) {
                List<Object> wl = (List<Object>) w;
                f.widths = new double[wl.size()];
                for (int i = 0; i < wl.size(); i++) f.widths[i] = num(res(wl.get(i)), 0);
            }
            Map<String, Object> fdesc = dictOf(res(fd.get("FontDescriptor")));
            if (fdesc != null) f.missing = num(res(fdesc.get("MissingWidth")), 0);
            return f;
        }

        void parseW(Object w, Font f) {
            if (!(w instanceof List)) return;
            List<Object> l = (List<Object>) w;
            f.cw = new HashMap<Integer, Double>();
            int i = 0;
            while (i < l.size()) {
                Object a = res(l.get(i));
                if (!(a instanceof Number) || i + 1 >= l.size()) break;
                int c1 = ((Number) a).intValue();
                Object b = res(l.get(i + 1));
                if (b instanceof List) {
                    List<Object> ws = (List<Object>) b;
                    for (int k = 0; k < ws.size(); k++) f.cw.put(c1 + k, num(res(ws.get(k)), f.dw));
                    i += 2;
                } else if (b instanceof Number && i + 2 < l.size()) {
                    int c2 = ((Number) b).intValue();
                    double wv = num(res(l.get(i + 2)), f.dw);
                    if (c2 >= c1 && c2 - c1 < 70000) for (int c = c1; c <= c2; c++) f.cw.put(c, wv);
                    i += 3;
                } else {
                    break;
                }
            }
        }

        // ---------------------------------------------------------- jalannya

        Result run(int maxChars, int maxPages) {
            scan();
            scanTrailers();
            if (encrypted) return Result.fail(Status.ENCRYPTED, "PDF terenkripsi/terkunci");
            expandObjStms();
            int[] total = new int[1];
            List<PageInfo> pages = findPages(maxPages, total);
            if (pages.isEmpty()) {
                return Result.fail(Status.CORRUPT, objs.isEmpty() ? "tidak ada objek PDF yang bisa dibaca" : "tidak ada halaman yang bisa ditemukan");
            }
            int totalPages = total[0];
            boolean multi = totalPages > 1;
            StringBuilder all = new StringBuilder();
            long visible = 0;
            int emptyPages = 0, pagesRead = 0;
            boolean truncated = totalPages > pages.size();
            boolean timedOut = false;
            for (int i = 0; i < pages.size(); i++) {
                if (all.length() >= maxChars) { truncated = true; break; }
                String t;
                try {
                    t = clean(pageText(pages.get(i)));
                } catch (Timeout to) {
                    truncated = true;
                    timedOut = true;
                    break;
                }
                pagesRead++;
                if (t.isEmpty()) { emptyPages++; continue; }
                for (int k = 0; k < t.length(); k++) if (!Character.isWhitespace(t.charAt(k))) visible++;
                if (all.length() > 0) all.append("\n\n");
                if (multi) all.append("[Halaman ").append(i + 1).append("]\n");
                all.append(t);
            }
            double ratio = shown == 0 ? 0.0 : (double) unmapped / (double) shown;
            if (visible < MIN_VISIBLE) {
                if (timedOut) return new Result(Status.CORRUPT, "", totalPages, pagesRead, true, emptyPages, ratio, "waktu baca habis sebelum ada teks");
                if (unmapped > 0) {
                    return new Result(Status.UNMAPPED_FONT, "", totalPages, pagesRead, truncated, emptyPages, ratio,
                            "font tidak punya pemetaan ke Unicode; teks tidak bisa dibaca dengan benar");
                }
                boolean noEof = indexOf(d, "%%EOF", Math.max(0, d.length - 2048)) < 0;
                if (badStreams > 0 || (noEof && pagesNoContent > 0)) {
                    return new Result(Status.CORRUPT, "", totalPages, pagesRead, truncated, emptyPages, ratio,
                            noEof ? "berkas terpotong atau rusak (tanpa penutup %%EOF), isi halaman tidak lengkap" : "aliran isi halaman rusak");
                }
                String why = skippedStreams > 0 ? "isi halaman memakai filter yang tidak didukung (LZW/RunLength/lainnya)"
                        : (pagesNoContent == pagesRead ? "halaman kosong, tanpa isi" : "tanpa lapisan teks (kemungkinan hasil scan atau gambar)");
                return new Result(Status.NO_TEXT, "", totalPages, pagesRead, truncated, emptyPages, ratio, why);
            }
            if (all.length() > maxChars) {
                int cut = maxChars;
                int nl = all.lastIndexOf("\n", cut);
                if (nl > maxChars * 0.8) cut = nl;
                if (cut > 0 && Character.isHighSurrogate(all.charAt(cut - 1))) cut--;
                all.setLength(cut);
                truncated = true;
            }
            return new Result(Status.OK, all.toString().trim(), totalPages, pagesRead, truncated, emptyPages, ratio, "");
        }
    }

    // ------------------------------------------------------------------ font dan pemetaan teks

    private static final class Font {
        boolean cid;
        boolean identityUnicode;
        boolean unmappable;
        int codeLen;
        Map<Integer, String> toUni;
        String[] enc;
        int first;
        double[] widths;
        double missing;
        double dw = 1000;
        Map<Integer, Double> cw;

        String map(int code) {
            if (toUni != null) {
                String s = toUni.get(code);
                if (s != null) return s;
            }
            if (cid) return identityUnicode ? String.valueOf((char) code) : null;
            if (unmappable) return null;
            return enc != null && code >= 0 && code < 256 ? enc[code] : null;
        }

        double width(int code) {
            if (cid) {
                Double w = cw == null ? null : cw.get(code);
                return w != null ? w : dw;
            }
            if (widths != null) {
                int i = code - first;
                return (i >= 0 && i < widths.length) ? widths[i] : missing;
            }
            if (code >= 32 && code <= 126) return HELV[code - 32];
            return 556;
        }
    }

    private static Font defaultFont() {
        Font f = new Font();
        f.enc = Arrays.copyOf(WIN, 256);
        f.codeLen = 1;
        return f;
    }

    /** Lebar Helvetica (per seribu em) untuk kode 32..126; dipakai bila font tidak membawa /Widths. */
    private static final int[] HELV = {
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584
    };

    private static String utf16(byte[] b) {
        if (b.length == 1) return String.valueOf((char) (b[0] & 0xff));
        return new String(b, StandardCharsets.UTF_16BE);
    }

    private static int toInt(byte[] b) {
        int v = 0;
        for (byte x : b) v = (v << 8) | (x & 0xff);
        return v;
    }

    private static void parseToUnicode(byte[] data, Font f) {
        Lexer lx = new Lexer(data, 0, data.length, false);
        Map<Integer, String> map = new HashMap<Integer, String>();
        int guard = 0;
        while (guard++ < 2_000_000) {
            Object t = lx.next();
            if (t == EOF) break;
            if (!(t instanceof Kw)) continue;
            String k = ((Kw) t).s;
            if (k.equals("begincodespacerange")) {
                while (true) {
                    Object a = lx.next();
                    if (a == EOF || a instanceof Kw) break;
                    lx.next();
                    if (a instanceof byte[] && f.codeLen == 0 && ((byte[]) a).length >= 1 && ((byte[]) a).length <= 2) f.codeLen = ((byte[]) a).length;
                }
            } else if (k.equals("beginbfchar")) {
                while (true) {
                    Object a = lx.next();
                    if (a == EOF || a instanceof Kw) break;
                    Object b = lx.next();
                    if (a instanceof byte[] && b instanceof byte[]) map.put(toInt((byte[]) a), utf16((byte[]) b));
                }
            } else if (k.equals("beginbfrange")) {
                while (true) {
                    Object a = lx.next();
                    if (a == EOF || a instanceof Kw) break;
                    Object b = lx.next();
                    Object c = lx.next();
                    if (!(a instanceof byte[]) || !(b instanceof byte[])) continue;
                    int lo = toInt((byte[]) a), hi = toInt((byte[]) b);
                    if (hi < lo || hi - lo > 65535) continue;
                    if (c instanceof byte[]) {
                        String s = utf16((byte[]) c);
                        for (int code = lo; code <= hi; code++) {
                            if (s.isEmpty()) { map.put(code, s); continue; }
                            char[] ch = s.toCharArray();
                            ch[ch.length - 1] += (char) (code - lo);
                            map.put(code, new String(ch));
                        }
                    } else if (c instanceof List) {
                        List<Object> l = (List<Object>) c;
                        for (int i = 0; i < l.size() && lo + i <= hi; i++) {
                            if (l.get(i) instanceof byte[]) map.put(lo + i, utf16((byte[]) l.get(i)));
                        }
                    }
                }
            }
        }
        f.toUni = map;
    }

    // ------------------------------------------------------------------ penerjemah isi halaman

    private static final class GS {
        Font font;
        double fs, tc, tw, th, tl;
        double[] ctm;
    }

    private static double[] mul(double[] a, double[] b) {
        return new double[] {
            a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
            a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
            a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5]
        };
    }

    private static final class Interp {
        final Doc doc;
        final StringBuilder out = new StringBuilder();
        Map<String, Object> res;
        Deque<GS> stack = new ArrayDeque<GS>();
        Font font;
        double fs, tc, tw, tl;
        double th = 1;
        double[] ctm = {1, 0, 0, 1, 0, 0};
        double[] tm = {1, 0, 0, 1, 0, 0};
        double[] tlm = {1, 0, 0, 1, 0, 0};
        boolean haveEnd;
        double endX, endY, endFs;
        final Set<Integer> chain = new HashSet<Integer>();

        final Font defaultFont = defaultFont();

        Interp(Doc doc) { this.doc = doc; }

        GS snapshot() {
            GS g = new GS();
            g.font = font; g.fs = fs; g.tc = tc; g.tw = tw; g.th = th; g.tl = tl; g.ctm = ctm.clone();
            return g;
        }

        void restore(GS g) {
            font = g.font; fs = g.fs; tc = g.tc; tw = g.tw; th = g.th; tl = g.tl; ctm = g.ctm;
        }

        void run(byte[] content, Map<String, Object> resources, int depth) {
            Lexer lx = new Lexer(content, 0, content.length, false);
            Map<String, Object> savedRes = res;
            Deque<GS> savedStack = stack;
            res = resources;
            stack = new ArrayDeque<GS>();
            List<Object> ops = new ArrayList<Object>();
            try {
                while (true) {
                    doc.tick();
                    Object t = lx.next();
                    if (t == EOF) break;
                    if (t instanceof Kw) {
                        String k = ((Kw) t).s;
                        if (k.equals("BI")) skipInline(lx);
                        else exec(k, ops, depth);
                        ops.clear();
                    } else if (t != CLOSE_ARR && t != CLOSE_DICT) {
                        ops.add(t == NULL ? Boolean.FALSE : t);
                        if (ops.size() > 4096) ops.clear();
                    }
                }
            } finally {
                res = savedRes;
                stack = savedStack;
            }
        }

        void skipInline(Lexer lx) {
            while (true) {
                Object t = lx.next();
                if (t == EOF) return;
                if (t instanceof Kw && ((Kw) t).s.equals("ID")) break;
            }
            int p = lx.p + 1;
            byte[] b = lx.b;
            while (p + 1 < lx.end) {
                if (b[p] == 'E' && b[p + 1] == 'I' && p > 0 && isWs(b[p - 1] & 0xff)
                        && (p + 2 >= lx.end || isWs(b[p + 2] & 0xff) || isDelim(b[p + 2] & 0xff))) {
                    lx.p = p + 2;
                    return;
                }
                p++;
            }
            lx.p = lx.end;
        }

        double n(List<Object> o, int i) {
            return i >= 0 && i < o.size() && o.get(i) instanceof Number ? ((Number) o.get(i)).doubleValue() : 0.0;
        }

        void exec(String k, List<Object> o, int depth) {
            int n = o.size();
            switch (k) {
                case "q": stack.push(snapshot()); break;
                case "Q": if (!stack.isEmpty()) restore(stack.pop()); break;
                case "cm":
                    if (n >= 6) ctm = mul(new double[] {n(o, n - 6), n(o, n - 5), n(o, n - 4), n(o, n - 3), n(o, n - 2), n(o, n - 1)}, ctm);
                    break;
                case "BT": tm = new double[] {1, 0, 0, 1, 0, 0}; tlm = tm.clone(); break;
                case "Tf":
                    if (n >= 2 && o.get(n - 2) instanceof Name) {
                        font = doc.lookupFont(res, ((Name) o.get(n - 2)).s);
                        fs = n(o, n - 1);
                    }
                    break;
                case "Tc": tc = n(o, n - 1); break;
                case "Tw": tw = n(o, n - 1); break;
                case "Tz": th = n(o, n - 1) / 100.0; break;
                case "TL": tl = n(o, n - 1); break;
                case "Td": if (n >= 2) td(n(o, n - 2), n(o, n - 1)); break;
                case "TD": if (n >= 2) { tl = -n(o, n - 1); td(n(o, n - 2), n(o, n - 1)); } break;
                case "Tm":
                    if (n >= 6) {
                        tm = new double[] {n(o, n - 6), n(o, n - 5), n(o, n - 4), n(o, n - 3), n(o, n - 2), n(o, n - 1)};
                        tlm = tm.clone();
                    }
                    break;
                case "T*": td(0, -tl); break;
                case "Tj": if (n >= 1 && o.get(n - 1) instanceof byte[]) show((byte[]) o.get(n - 1)); break;
                case "'":
                    td(0, -tl);
                    if (n >= 1 && o.get(n - 1) instanceof byte[]) show((byte[]) o.get(n - 1));
                    break;
                case "\"":
                    if (n >= 3) {
                        tw = n(o, n - 3);
                        tc = n(o, n - 2);
                        td(0, -tl);
                        if (o.get(n - 1) instanceof byte[]) show((byte[]) o.get(n - 1));
                    }
                    break;
                case "TJ":
                    if (n >= 1 && o.get(n - 1) instanceof List) {
                        for (Object e : (List<Object>) o.get(n - 1)) {
                            if (e instanceof byte[]) show((byte[]) e);
                            else if (e instanceof Number) advance(-((Number) e).doubleValue() / 1000.0 * fs * th);
                        }
                    }
                    break;
                case "Do": if (n >= 1 && o.get(n - 1) instanceof Name) doForm(((Name) o.get(n - 1)).s, depth); break;
                default: break;
            }
        }

        void doForm(String name, int depth) {
            if (res == null || depth >= 6) return;
            Map<String, Object> xo = dictOf(doc.res(res.get("XObject")));
            if (xo == null) return;
            Object ref = xo.get(name);
            if (!(ref instanceof Ref)) return;
            int num = ((Ref) ref).num;
            Obj ob = doc.objs.get(num);
            if (ob == null || ob.sStart < 0 || !(ob.val instanceof Map)) return;
            Map<String, Object> fm = (Map<String, Object>) ob.val;
            if (!"Form".equals(nameOf(doc.res(fm.get("Subtype"))))) return;
            if (!chain.add(num)) return;
            try {
                byte[] data = doc.decode(ob);
                if (data == null) { doc.skippedStreams++; return; }
                GS saved = snapshot();
                Object mo = doc.res(fm.get("Matrix"));
                if (mo instanceof List && ((List<Object>) mo).size() >= 6) {
                    List<Object> l = (List<Object>) mo;
                    ctm = mul(new double[] {num(doc.res(l.get(0)), 1), num(doc.res(l.get(1)), 0), num(doc.res(l.get(2)), 0),
                        num(doc.res(l.get(3)), 1), num(doc.res(l.get(4)), 0), num(doc.res(l.get(5)), 0)}, ctm);
                }
                Map<String, Object> fr = dictOf(doc.res(fm.get("Resources")));
                run(data, fr != null ? fr : res, depth + 1);
                restore(saved);
            } finally {
                chain.remove(num);
            }
        }

        void td(double tx, double ty) {
            tlm = new double[] {tlm[0], tlm[1], tlm[2], tlm[3], tx * tlm[0] + ty * tlm[2] + tlm[4], tx * tlm[1] + ty * tlm[3] + tlm[5]};
            tm = tlm.clone();
        }

        void advance(double tx) {
            tm[4] += tx * tm[0];
            tm[5] += tx * tm[1];
        }

        double[] trm() { return mul(tm, ctm); }

        void breakLine(boolean para) {
            int len = out.length();
            if (len == 0) return;
            while (len > 0 && out.charAt(len - 1) == ' ') len--;
            out.setLength(len);
            int nl = 0;
            for (int i = len - 1; i >= 0 && out.charAt(i) == '\n'; i--) nl++;
            for (int want = para ? 2 : 1; nl < want; nl++) out.append('\n');
        }

        void space(int count) {
            int len = out.length();
            if (len == 0) return;
            char c = out.charAt(len - 1);
            if (c == ' ' || c == '\n') return;
            for (int i = 0; i < count; i++) out.append(' ');
        }

        /** Putuskan baris baru atau spasi dari jarak antara ujung teks sebelumnya dan awal teks ini. */
        void beforeShow() {
            double[] t = trm();
            double sx = t[4], sy = t[5];
            double fsDev = Math.abs(fs) * Math.hypot(t[2], t[3]);
            if (fsDev < 1e-6) fsDev = 1;
            if (haveEnd && out.length() > 0) {
                double ux = t[0], uy = t[1];
                double ul = Math.hypot(ux, uy);
                if (ul < 1e-9) { ux = 1; uy = 0; ul = 1; }
                ux /= ul;
                uy /= ul;
                double dx = sx - endX, dy = sy - endY;
                double along = dx * ux + dy * uy;
                double perp = -dx * uy + dy * ux;
                double ref = Math.max(fsDev, endFs);
                double ap = Math.abs(perp);
                if (ap > 1.8 * ref) breakLine(true);
                else if (ap > 0.5 * ref) breakLine(false);
                else if (along > 2.0 * ref) space(2);
                else if (along > 0.15 * ref) space(1);
                else if (along < -ref) space(1);
            }
        }

        void emit(String u) {
            for (int i = 0; i < u.length(); i++) {
                char ch = u.charAt(i);
                if (ch == 0xA0) out.append(' ');
                else if (ch == 0xAD) continue;
                else if (ch >= 0xFB00 && ch <= 0xFB04) {
                    switch (ch) {
                        case 0xFB00: out.append("ff"); break;
                        case 0xFB01: out.append("fi"); break;
                        case 0xFB02: out.append("fl"); break;
                        case 0xFB03: out.append("ffi"); break;
                        default: out.append("ffl");
                    }
                } else if (ch < 32) {
                    if (ch == '\t') out.append(' ');
                } else out.append(ch);
            }
        }

        void show(byte[] s) {
            Font f = font != null ? font : defaultFont;
            beforeShow();
            int i = 0;
            int step = f.cid ? Math.max(1, f.codeLen) : 1;
            while (i < s.length) {
                doc.tick();
                int len = Math.min(step, s.length - i);
                int code = 0;
                for (int k = 0; k < len; k++) code = (code << 8) | (s[i + k] & 0xff);
                i += len;
                String u = f.map(code);
                doc.shown++;
                if (u == null) doc.unmapped++;
                else emit(u);
                double w = f.width(code);
                double tx = (w / 1000.0 * fs + tc + ((len == 1 && code == 32) ? tw : 0)) * th;
                advance(tx);
            }
            double[] t = trm();
            endX = t[4];
            endY = t[5];
            endFs = Math.abs(fs) * Math.hypot(t[2], t[3]);
            if (endFs < 1e-6) endFs = 1;
            haveEnd = true;
        }
    }

    private static String clean(String t) {
        String[] lines = t.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String ln : lines) {
            String s = ln.trim().replaceAll(" {3,}", "  ");
            if (s.isEmpty()) { blank++; continue; }
            if (sb.length() > 0) sb.append(blank > 0 ? "\n\n" : "\n");
            blank = 0;
            sb.append(s);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ tabel encoding dan nama glyph

    private static final String[] WIN = decodeTable("windows-1252");
    private static final String[] MAC = macTable();
    private static final String[] STD = stdTable();

    private static String[] decodeTable(String csName) {
        Charset cs;
        try {
            cs = Charset.forName(csName);
        } catch (Exception e) {
            cs = StandardCharsets.ISO_8859_1;
        }
        String[] t = new String[256];
        for (int i = 32; i < 256; i++) {
            String s = new String(new byte[] {(byte) i}, cs);
            if (s.length() == 1 && s.charAt(0) != '\uFFFD' && i != 127) t[i] = s;
        }
        return t;
    }

    private static String[] macTable() {
        String[] names = {"x-MacRoman", "macintosh", "MacRoman"};
        for (String n : names) {
            try {
                Charset.forName(n);
                return decodeTable(n);
            } catch (Exception e) {
                // coba nama berikutnya
            }
        }
        return Arrays.copyOf(WIN, 256);
    }

    private static String[] stdTable() {
        String[] t = Arrays.copyOf(WIN, 256);
        for (int i = 128; i < 256; i++) t[i] = null;
        t[0x27] = "\u2019";
        t[0x60] = "\u2018";
        String hi = "\u00A1\u00A2\u00A3\u2044\u00A5\u0192\u00A7\u00A4'\u201C\u00AB\u2039\u203A\uFB01\uFB02";
        for (int i = 0; i < hi.length(); i++) t[0xA1 + i] = String.valueOf(hi.charAt(i));
        t[0xB1] = "\u2013"; t[0xB2] = "\u2020"; t[0xB3] = "\u2021"; t[0xB4] = "\u00B7"; t[0xB6] = "\u00B6";
        t[0xB7] = "\u2022"; t[0xB8] = "\u201A"; t[0xB9] = "\u201E"; t[0xBA] = "\u201D"; t[0xBB] = "\u00BB";
        t[0xBC] = "\u2026"; t[0xBD] = "\u2030"; t[0xBF] = "\u00BF";
        t[0xD0] = "\u2014"; t[0xE1] = "\u00C6"; t[0xE3] = "\u00AA"; t[0xE8] = "\u0141"; t[0xE9] = "\u00D8";
        t[0xEA] = "\u0152"; t[0xEB] = "\u00BA"; t[0xF1] = "\u00E6"; t[0xF5] = "\u0131"; t[0xF8] = "\u0142";
        t[0xF9] = "\u00F8"; t[0xFA] = "\u0153"; t[0xFB] = "\u00DF";
        return t;
    }

    private static final Map<String, String> GLYPHS = new HashMap<String, String>();
    private static final Map<String, Character> ACCENTS = new LinkedHashMap<String, Character>();

    private static void g(String name, String value) { GLYPHS.put(name, value); }

    static {
        g("space", " "); g("exclam", "!"); g("quotedbl", "\""); g("numbersign", "#"); g("dollar", "$");
        g("percent", "%"); g("ampersand", "&"); g("quotesingle", "'"); g("quoteright", "\u2019");
        g("quoteleft", "\u2018"); g("parenleft", "("); g("parenright", ")"); g("asterisk", "*"); g("plus", "+");
        g("comma", ","); g("hyphen", "-"); g("minus", "\u2212"); g("period", "."); g("slash", "/");
        g("zero", "0"); g("one", "1"); g("two", "2"); g("three", "3"); g("four", "4"); g("five", "5");
        g("six", "6"); g("seven", "7"); g("eight", "8"); g("nine", "9"); g("colon", ":"); g("semicolon", ";");
        g("less", "<"); g("equal", "="); g("greater", ">"); g("question", "?"); g("at", "@");
        g("bracketleft", "["); g("backslash", "\\"); g("bracketright", "]"); g("asciicircum", "^");
        g("underscore", "_"); g("grave", "`"); g("braceleft", "{"); g("bar", "|"); g("braceright", "}");
        g("asciitilde", "~"); g("endash", "\u2013"); g("emdash", "\u2014"); g("bullet", "\u2022");
        g("ellipsis", "\u2026"); g("fi", "fi"); g("fl", "fl"); g("ff", "ff"); g("ffi", "ffi"); g("ffl", "ffl");
        g("quotedblleft", "\u201C"); g("quotedblright", "\u201D"); g("quotesinglbase", "\u201A");
        g("quotedblbase", "\u201E"); g("guillemotleft", "\u00AB"); g("guillemotright", "\u00BB");
        g("guilsinglleft", "\u2039"); g("guilsinglright", "\u203A"); g("dagger", "\u2020");
        g("daggerdbl", "\u2021"); g("trademark", "\u2122"); g("copyright", "\u00A9"); g("registered", "\u00AE");
        g("degree", "\u00B0"); g("plusminus", "\u00B1"); g("section", "\u00A7"); g("paragraph", "\u00B6");
        g("Euro", "\u20AC"); g("sterling", "\u00A3"); g("yen", "\u00A5"); g("cent", "\u00A2");
        g("currency", "\u00A4"); g("multiply", "\u00D7"); g("divide", "\u00F7"); g("nbspace", " ");
        g("nonbreakingspace", " "); g("sfthyphen", ""); g("softhyphen", ""); g("periodcentered", "\u00B7");
        g("mu", "\u00B5"); g("ae", "\u00E6"); g("AE", "\u00C6"); g("oe", "\u0153"); g("OE", "\u0152");
        g("oslash", "\u00F8"); g("Oslash", "\u00D8"); g("germandbls", "\u00DF"); g("dotlessi", "\u0131");
        g("lslash", "\u0142"); g("Lslash", "\u0141"); g("ordfeminine", "\u00AA"); g("ordmasculine", "\u00BA");
        g("onesuperior", "\u00B9"); g("twosuperior", "\u00B2"); g("threesuperior", "\u00B3");
        g("onequarter", "\u00BC"); g("onehalf", "\u00BD"); g("threequarters", "\u00BE");
        g("exclamdown", "\u00A1"); g("questiondown", "\u00BF"); g("brokenbar", "\u00A6");
        g("logicalnot", "\u00AC"); g("fraction", "\u2044"); g("florin", "\u0192"); g("perthousand", "\u2030");
        g("tilde", "\u02DC"); g("circumflex", "\u02C6");

        ACCENTS.put("acute", '\u0301'); ACCENTS.put("grave", '\u0300'); ACCENTS.put("circumflex", '\u0302');
        ACCENTS.put("dieresis", '\u0308'); ACCENTS.put("tilde", '\u0303'); ACCENTS.put("ring", '\u030A');
        ACCENTS.put("cedilla", '\u0327'); ACCENTS.put("caron", '\u030C'); ACCENTS.put("macron", '\u0304');
        ACCENTS.put("breve", '\u0306'); ACCENTS.put("dotaccent", '\u0307'); ACCENTS.put("ogonek", '\u0328');
        ACCENTS.put("hungarumlaut", '\u030B');
    }

    /** Nama glyph Adobe -> teks Unicode; null bila tak dikenal (mis. "g23", "cid00045"). */
    static String glyph(String g) {
        if (g == null || g.isEmpty()) return null;
        int dot = g.indexOf('.');
        if (dot > 0) g = g.substring(0, dot);
        if (g.length() == 1) return g;
        String m = GLYPHS.get(g);
        if (m != null) return m;
        try {
            if (g.startsWith("uni") && g.length() >= 7 && (g.length() - 3) % 4 == 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 3; i + 4 <= g.length(); i += 4) sb.append((char) Integer.parseInt(g.substring(i, i + 4), 16));
                return sb.toString();
            }
            if (g.charAt(0) == 'u' && g.length() >= 5 && g.length() <= 7) {
                return new String(Character.toChars(Integer.parseInt(g.substring(1), 16)));
            }
        } catch (RuntimeException e) {
            return null;
        }
        for (Map.Entry<String, Character> e : ACCENTS.entrySet()) {
            String suf = e.getKey();
            if (g.length() == suf.length() + 1 && g.endsWith(suf) && Character.isLetter(g.charAt(0))) {
                return Normalizer.normalize(g.charAt(0) + String.valueOf(e.getValue()), Normalizer.Form.NFC);
            }
        }
        return null;
    }
}
