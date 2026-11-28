package com.build.mythos

import java.security.MessageDigest

// Berangkas bawaan APK (hanya baca). Murni Kotlin/JVM tanpa android.* supaya bisa diuji (BerangkasTest).
// Isi berangkas ada di app/src/main/assets/berangkas/ dan berganti otomatis tiap upgrade aplikasi.
// Aturan: agen hanya boleh MEMBACA dan MENYALIN; tidak ada jalur tulis ke berangkas.
internal object Berangkas {
    const val ASSET_ROOT = "berangkas"
    const val SUMS_FILE = "SHA256SUMS"

    /** Tujuan salinan di Termux, relatif ke $HOME. Dibuat ulang dari APK tiap dipanggil, jadi tidak pernah basi. */
    const val DEST_DIR = ".mythos/berangkas"

    const val MAX_READ_CHARS = 20000
    const val MAX_COPY_BYTES = 300_000

    private val SAFE = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
    private val SUM_LINE = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")

    /**
     * Jalur relatif aman di dalam berangkas. "" berarti akar. Null bila tidak aman:
     * awalan "/", "..", segmen kosong, atau karakter di luar huruf, angka, titik, garis bawah, strip.
     */
    fun safePath(raw: String): String? {
        var p = raw.trim().replace('\\', '/')
        if (p == "." || p == "./") return ""
        while (p.startsWith("./")) p = p.removePrefix("./")
        p = p.trimEnd('/')
        if (p.isEmpty()) return ""
        if (!SAFE.matches(p)) return null
        if (p.split('/').any { it == "." || it == ".." }) return null
        return p
    }

    /** Baca isi SHA256SUMS menjadi peta jalur -> hash (huruf kecil). Baris tak sah dan jalur tak aman dilewati. */
    fun parseSums(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val m = SUM_LINE.matchEntire(line.trim()) ?: continue
            val path = safePath(m.groupValues[2]) ?: continue
            if (path.isEmpty() || path == SUMS_FILE) continue
            out[path] = m.groupValues[1].lowercase()
        }
        return out
    }

    fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Null bila [bytes] cocok dengan hash terdaftar; selain itu pesan alasan. Berkas tak terdaftar ditolak. */
    fun verify(path: String, bytes: ByteArray, sums: Map<String, String>): String? {
        val want = sums[path] ?: return "$path tidak terdaftar di $SUMS_FILE; berkas ini tidak dianggap bagian berangkas."
        return if (sha256Hex(bytes) == want) null
        else "Integritas gagal untuk $path: isi tidak cocok dengan $SUMS_FILE. Jangan dipakai; pasang ulang aplikasi."
    }

    /** Semua berkas terdaftar di bawah [path]: [path] sendiri bila berkas, atau semua di bawah folder; "" = semuanya. */
    fun filesUnder(all: Collection<String>, path: String): List<String> = when {
        path.isEmpty() -> all.sorted()
        path in all -> listOf(path)
        else -> all.filter { it.startsWith("$path/") }.sorted()
    }

    /** Isi satu folder: nama berkas apa adanya, subfolder berakhiran "/". Kosong bila folder tidak ada. */
    fun listDir(all: Collection<String>, dir: String): List<String> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val names = sortedSetOf<String>()
        for (f in all) {
            if (!f.startsWith(prefix)) continue
            val rest = f.removePrefix(prefix)
            val slash = rest.indexOf('/')
            names.add(if (slash < 0) rest else rest.substring(0, slash + 1))
        }
        return names.toList()
    }

    /**
     * Perintah shell yang menulis ulang [files] ke $HOME/[DEST_DIR] dari awal (bagian [target] saja; "" = seluruh berangkas).
     * Isi dikirim sebagai base64 (heredoc), jadi tidak ada isi berkas yang ditafsirkan shell. Skrip .sh diberi izin eksekusi.
     * Semua jalur sudah divalidasi [safePath], jadi aman ditaruh di dalam tanda kutip ganda.
     */
    fun copyCommand(files: List<Pair<String, ByteArray>>, target: String): String {
        require(safePath(target) == target) { "target tidak aman: $target" }
        val b64 = java.util.Base64.getMimeEncoder(76, "\n".toByteArray())
        val sb = StringBuilder()
        sb.append("D=\"\$HOME/").append(DEST_DIR).append("\"\n")
        val root = if (target.isEmpty()) "\$D" else "\$D/$target"
        sb.append("chmod -R u+w \"").append(root).append("\" 2>/dev/null\n")
        sb.append("rm -rf \"").append(root).append("\"\n")
        sb.append("mkdir -p \"\$D\" || exit 1\n")
        for ((path, bytes) in files) {
            require(safePath(path) == path && path.isNotEmpty()) { "jalur tidak aman: $path" }
            val dir = path.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) sb.append("mkdir -p \"\$D/").append(dir).append("\" || exit 1\n")
            sb.append("base64 -d > \"\$D/").append(path).append("\" <<'KITA_B64_EOF' || exit 1\n")
            sb.append(b64.encodeToString(bytes)).append("\nKITA_B64_EOF\n")
            if (path.endsWith(".sh")) sb.append("chmod +x \"\$D/").append(path).append("\"\n")
        }
        sb.append("echo BERANGKAS_SALIN=ok JUMLAH=").append(files.size).append(" TUJUAN=\"\$D\"\n")
        return sb.toString()
    }

    /** True bila keluaran perintah salinan menandakan sukses. */
    fun copySucceeded(out: String): Boolean = out.startsWith("exit=0") && out.contains("BERANGKAS_SALIN=ok")
}
