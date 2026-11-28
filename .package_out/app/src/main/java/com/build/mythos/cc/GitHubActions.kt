package com.build.mythos.cc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/** Hasil satu siklus build di GitHub Actions. */
data class BuildOutcome(val ok: Boolean, val text: String)

/**
 * Satu-satunya jalur build/test agen: tidak ada shell lokal. Implementasi: [GitHubBuildService].
 */
interface BuildService {
    /** Catatan lingkungan untuk model (aturan workflow, tips). */
    val hint: String get() = ""

    suspend fun build(root: File, timeoutSec: Int): BuildOutcome
}

class GitHubException(message: String) : Exception(message)

/** token = fine-grained/classic PAT; repo = "owner/nama" (URL github.com/owner/nama juga diterima). */
data class GitHubConfig(val token: String, val owner: String, val repo: String) {
    val slug: String get() = "$owner/$repo"

    companion object {
        private val SLUG = Regex("""^([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9._-]{1,100})$""")

        fun parse(token: String, repo: String): GitHubConfig? {
            val t = token.trim()
            if (t.isEmpty()) return null
            val r = repo.trim()
                .removePrefix("https://github.com/").removePrefix("http://github.com/")
                .trimEnd('/').removeSuffix(".git")
            val m = SLUG.matchEntire(r) ?: return null
            return GitHubConfig(t, m.groupValues[1], m.groupValues[2])
        }

        fun isValidRepo(repo: String): Boolean = parse("x", repo) != null
    }
}

// ---------- transport (bisa diganti fake di uji) ----------

class HttpResult(val code: Int, val body: ByteArray, val headers: Map<String, String>) {
    val text: String get() = String(body, Charsets.UTF_8)
}

fun interface GitHubTransport {
    suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResult
}

/** Redirect TIDAK diikuti otomatis: unduhan log berpindah host dan tidak boleh membawa token. */
class UrlConnectionTransport(private val timeoutMs: Int = 60_000) : GitHubTransport {
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResult =
        withContext(Dispatchers.IO) {
            val conn = try {
                openConn(url)
            } catch (e: LlmException) {
                throw IOException(e.message ?: "Alamat tidak valid")
            }
            try {
                blockingIo(conn) {
                    conn.requestMethod = method
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 20_000
                    conn.readTimeout = timeoutMs
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    if (body != null) {
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.outputStream.use { it.write(body) }
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                    val bytes = stream?.use { s ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = s.read(buf)
                            if (n < 0) break
                            if (total < MAX_BODY) out.write(buf, 0, minOf(n, MAX_BODY - total))
                            total += n
                        }
                        out.toByteArray()
                    } ?: ByteArray(0)
                    val hdrs = HashMap<String, String>()
                    conn.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) hdrs[k.lowercase()] = v[0] }
                    HttpResult(code, bytes, hdrs)
                }
            } catch (e: IOException) {
                ensureActive()   // dibatalkan pengguna -> CancellationException, bukan galat jaringan yang dicoba ulang
                throw e
            } finally {
                conn.disconnect()
            }
        }

    private companion object {
        const val MAX_BODY = 8_000_000
    }
}

// ---------- util ----------

/** SHA-1 blob Git: sha1("blob <len>\0" + isi). Dipakai agar file yang tidak berubah tidak diunggah ulang. */
internal fun gitBlobSha(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-1")
    md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
    md.update(bytes)
    return md.digest().joinToString("") { "%02x".format(it) }
}

internal fun branchFor(projectName: String): String {
    var safe = projectName.replace(Regex("[^A-Za-z0-9._-]"), "-")
        .replace(Regex("\\.{2,}"), ".")   // ".." tidak sah di nama ref Git
        .trim('-', '.')
    while (safe.endsWith(".lock")) safe = safe.removeSuffix(".lock").trim('-', '.')
    if (safe.isEmpty()) safe = "proyek"
    return "mythos/$safe"
}

/** Teks UTF-8 yang bisa dikirim sebagai string tanpa berubah satu byte pun (tanpa NUL dan tanpa urutan byte tidak sah). */
internal fun isLosslessUtf8(bytes: ByteArray): Boolean {
    if (bytes.any { it == 0.toByte() }) return false
    return String(bytes, Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(bytes)
}

/**
 * Path + isi semua workflow proyek. Workflow dijalankan GitHub dengan secrets repo, jadi workflow yang baru/berubah
 * harus disetujui pengguna sebelum didorong (lihat Agent.runTool). Kosong bila proyek belum punya workflow.
 */
internal fun workflowDigest(root: File): String {
    val dir = File(root, ".github/workflows")
    val files = dir.listFiles { f -> f.isFile && (f.name.endsWith(".yml") || f.name.endsWith(".yaml")) }?.sortedBy { it.name } ?: return ""
    return files.joinToString("\n\n") { f ->
        ".github/workflows/${f.name}:\n" + (try { f.readText() } catch (e: IOException) { "" })
    }.trim()
}

/** SHA-256 hex dari isi workflow untuk mengikat persetujuan pengguna secara presisi (F-01/F-08). */
internal fun workflowSha256(digest: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(digest.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Ringkasan berkas proyek yang akan diunggah pada snapshot GitHub Actions (F-11). */
internal fun snapshotPreview(root: File, maxListed: Int = 30): String {
    val canonRoot = try { root.canonicalFile } catch (_: IOException) { return "" }
    val rels = ArrayList<String>()
    var totalBytes = 0L
    for (f in root.walkTopDown()
        .onEnter { it == root || (!java.nio.file.Files.isSymbolicLink(it.toPath()) && !isIgnoredDir(it)) }
        .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }) {
        val canon = try { f.canonicalFile } catch (_: IOException) { continue }
        if (!canon.path.startsWith(canonRoot.path + File.separator)) continue
        val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
        if (rel.startsWith("uploads/") && !rel.removePrefix("uploads/").contains('/')) continue
        rels.add(rel)
        totalBytes += f.length()
    }
    if (rels.isEmpty()) return ""
    rels.sort()
    val shown = rels.take(maxListed).joinToString("\n") { "  - $it" }
    val more = if (rels.size > maxListed) "\n  … (+${rels.size - maxListed} berkas lainnya)" else ""
    val kb = (totalBytes + 1023L) / 1024L
    return "Ringkasan snapshot yang akan diunggah (${rels.size} berkas, ~${kb} KB):\n$shown$more"
}

private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
private val LOG_TS = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z """)
private val LOG_INTERESTING = Regex("""(?i)(##\[error\]|\berror\b|\bfailed\b|\bfailure\b|exception|\be: |cannot find|unresolved|not found|what went wrong)""")

/** Ringkas log CI: baris bermakna error + ekor log, tanpa timestamp/warna. */
internal fun logExcerpt(raw: String, maxChars: Int = 3_500): String {
    val lines = raw.lineSequence()
        .map { LOG_TS.replaceFirst(ANSI.replace(it, "").trimEnd(), "") }
        .filter { it.isNotBlank() }
        .toList()
    if (lines.isEmpty()) return "(log kosong)"
    val picked = java.util.TreeSet<Int>()
    lines.indices.filter { LOG_INTERESTING.containsMatchIn(lines[it]) }.take(30).forEach { picked.add(it) }
    for (i in maxOf(0, lines.size - 40) until lines.size) picked.add(i)
    val sb = StringBuilder()
    var prev = -2
    for (i in picked) {
        if (i != prev + 1 && sb.isNotEmpty()) sb.append("…\n")
        sb.append(lines[i].take(300)).append('\n')
        prev = i
    }
    return clip(sb.toString().trimEnd(), maxChars, 0.5)
}

// ---------- layanan ----------

/**
 * Alur: kumpulkan file proyek -> unggah blob yang berubah -> satu commit ke branch `mythos/<proyek>`
 * (dibuat yatim bila belum ada, tidak menyentuh branch utama) -> tunggu run GitHub Actions untuk commit itu
 * (trigger `push`) -> laporkan hasil + potongan log job yang gagal.
 *
 * Hanya memakai REST API (HttpURLConnection): tidak butuh git/shell di perangkat.
 * Catatan: PATCH tidak didukung HttpURLConnection di Android. Memperbarui ref dicoba lewat POST + X-HTTP-Method-Override: PATCH
 * (atomik); bila ditolak, jatuh kembali ke DELETE + POST.
 * File teks yang baru/berubah dikirim inline di dalam permintaan tree (tanpa satu permintaan per file) agar proyek besar tidak
 * menabrak batas laju sekunder GitHub; hanya file biner yang diunggah sebagai blob.
 */
class GitHubBuildService(
    private val cfg: GitHubConfig,
    private val transport: GitHubTransport = UrlConnectionTransport(),
    private val pollMs: Long = 5_000L,
) : BuildService {

    override val hint: String
        get() = "Builds and tests are NOT run on the phone and there is no shell. The ONLY way to build/test is the GithubBuild tool: " +
            "it pushes the whole project to the user's GitHub repository (${cfg.slug}, branch mythos/<project>) and waits for the GitHub Actions run, " +
            "then returns the result with excerpts of the failing logs. The project MUST contain a workflow file .github/workflows/<name>.yml that runs on push, " +
            "e.g. `on: { push: { branches: ['mythos/**'] } }` (write it yourself with the Write tool). " +
            "Android/Gradle tip: you cannot create gradle-wrapper.jar, so in the workflow use actions/setup-java@v4 (temurin 17) + gradle/actions/setup-gradle@v4 with a fixed gradle-version " +
            "and run `gradle assembleDebug` (ubuntu-latest already has the Android SDK); publish outputs with actions/upload-artifact@v4. " +
            "Each build takes minutes: batch several edits first, then build once. Never write secrets into project files."

    private val api = "https://api.github.com/repos/${cfg.owner}/${cfg.repo}"

    /** Id run dari build terakhir yang SEMUA run-nya sukses; dasar [downloadApk]. Kosong bila belum ada build sukses. */
    @Volatile var lastGoodRunIds: List<Long> = emptyList()
        private set

    /**
     * Unduh APK dari artifact run sukses terakhir ke [dest]. Mengembalikan null bila berhasil, atau pesan galat.
     * Artifact GitHub berupa zip; APK pertama di dalamnya diekstrak. Unduhan ditulis langsung ke file
     * (transport biasa memotong respons di 8 MB, terlalu kecil untuk APK).
     */
    suspend fun downloadApk(dest: File): String? {
        return try {
        val artifact = pickArtifact() ?: return "Tidak ada artifact APK pada build terakhir (workflow harus memakai actions/upload-artifact@v4)."
        val zip = File(dest.parentFile, dest.name + ".zip")
        dest.parentFile?.mkdirs()
        val err = downloadTo("$api/actions/artifacts/${artifact}/zip", zip)
        if (err != null) err else withContext(Dispatchers.IO) {
            try {
                var found = false
                java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        if (!e.isDirectory && e.name.endsWith(".apk", ignoreCase = true)) {
                            val maxApk = 300L * 1024 * 1024
                            var written = 0L
                            val buf = ByteArray(65536)
                            dest.outputStream().use { out ->
                                while (true) {
                                    val n = zin.read(buf)
                                    if (n < 0) break
                                    written += n
                                    if (written > maxApk) {
                                        dest.delete()
                                        return@withContext "APK di artifact terlalu besar (lebih dari 300 MB), dibatalkan."
                                    }
                                    out.write(buf, 0, n)
                                }
                            }
                            found = true
                            break
                        }
                    }
                }
                if (found) null else "Artifact tidak berisi berkas .apk."
            } finally {
                zip.delete()
            }
        }
    } catch (e: GitHubException) {
        e.message ?: "Gagal mengunduh APK"
        } catch (e: IOException) {
            "Gagal mengunduh APK: ${e.message}"
        }
    }

    /** Id artifact APK terbaik dari run sukses terakhir: nama memuat \"apk\"/\"debug\" didahulukan, yang kedaluwarsa dilewati. */
    private suspend fun pickArtifact(): Long? {
        for (runId in lastGoodRunIds.reversed()) {
            val o = obj(call("GET", "$api/actions/runs/$runId/artifacts?per_page=30"), "membaca daftar artifact")
            val arr = o.optJSONArray("artifacts") ?: continue
            val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { !it.optBoolean("expired", false) }
            val best = list.firstOrNull { it.optString("name").contains("apk", true) }
                ?: list.firstOrNull { it.optString("name").contains("debug", true) }
                ?: list.firstOrNull()
            val id = best?.optLong("id", -1L) ?: -1L
            if (id > 0L) return id
        }
        return null
    }

    /** GET dengan token, ikuti satu pengalihan TANPA token (host unduhan berbeda), tulis aliran ke [dest]. Null = sukses. */
    private suspend fun downloadTo(url: String, dest: File): String? = withContext(Dispatchers.IO) {
        var target = url
        var withAuth = true
        repeat(3) {
            val conn = try { openConn(target) } catch (e: LlmException) { throw IOException(e.message) }
            try {
                val (code, redirectLoc) = blockingIo(conn) {
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 120_000
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    conn.setRequestProperty("User-Agent", "Mythos-Android")
                    if (withAuth) conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
                    val c = conn.responseCode
                    val location = if (c == 301 || c == 302 || c == 303 || c == 307 || c == 308) conn.getHeaderField("Location") else null
                    if (c in 200..299) {
                        val maxZip = 350L * 1024 * 1024
                        var total = 0L
                        val buf = ByteArray(65536)
                        conn.inputStream.use { ins ->
                            dest.outputStream().use { out ->
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > maxZip) {
                                        dest.delete()
                                        return@blockingIo -2 to null
                                    }
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                    c to location
                }
                when (code) {
                    -2 -> return@withContext "Artifact unduhan terlalu besar (lebih dari 350 MB), dibatalkan."
                    in 200..299 -> return@withContext null
                    301, 302, 303, 307, 308 -> {
                        val loc = redirectLoc ?: return@withContext "Pengalihan unduhan tanpa alamat tujuan."
                        val resolved = try { java.net.URL(java.net.URL(target), loc).toString() } catch (e: Exception) { return@withContext "Alamat pengalihan unduhan tidak valid." }
                        if (!resolved.startsWith("https://", ignoreCase = true)) return@withContext "Pengalihan unduhan bukan HTTPS; dibatalkan."
                        target = resolved
                        withAuth = false
                    }
                    401 -> return@withContext "Token GitHub ditolak atau kedaluwarsa; perbarui di Pengaturan."
                    403, 404 -> return@withContext "Artifact tidak bisa diunduh (HTTP $code): token perlu izin Actions: Read, atau artifact sudah kedaluwarsa."
                    else -> return@withContext "Unduh artifact gagal (HTTP $code)."
                }
            } catch (e: IOException) {
                ensureActive()
                throw e
            } finally {
                conn.disconnect()
            }
        }
        "Terlalu banyak pengalihan saat mengunduh artifact."
    }

    /** Menjadi false setelah sekali ditolak, supaya build berikutnya tidak membuang satu permintaan lagi. */
    @Volatile private var methodOverrideWorks = true

    private class LocalFile(val path: String, val bytes: ByteArray, val mode: String, val sha: String)
    private class Run(val id: Long, val name: String, val status: String, val conclusion: String, val url: String)

    override suspend fun build(root: File, timeoutSec: Int): BuildOutcome = try {
        withContext(Dispatchers.IO) { collect(root) }.let { files -> execute(files, branchFor(root.name), timeoutSec) }
    } catch (e: GitHubException) {
        BuildOutcome(false, e.message ?: "Gagal build via GitHub Actions")
    } catch (e: JSONException) {
        BuildOutcome(false, "Respons GitHub tidak bisa dibaca: ${e.message}")
    }

    // ----- 1. snapshot lokal -----

    private fun collect(root: File): List<LocalFile> {
        val out = ArrayList<LocalFile>()
        var total = 0L
        val canonRoot = root.canonicalFile
        for (f in root.walkTopDown()
            .onEnter { it == root || (!java.nio.file.Files.isSymbolicLink(it.toPath()) && !isIgnoredDir(it)) }
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }) {
            if (!f.canonicalFile.path.startsWith(canonRoot.path + File.separator)) continue
            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
            // Berkas asli lampiran (gambar/PDF/dokumen/zip) di uploads/ bersifat pribadi dan tak dibutuhkan build: jangan dikirim.
            // Folder hasil ekstrak zip tetap ikut karena bisa jadi bagian proyek.
            if (rel.startsWith("uploads/") && !rel.removePrefix("uploads/").contains('/')) continue
            if (f.length() > MAX_FILE) throw GitHubException("File terlalu besar untuk dikirim: $rel (${f.length()} byte, batas 5 MB). Hapus atau kecilkan.")
            total += f.length()
            if (total > MAX_TOTAL) throw GitHubException("Proyek lebih dari 30 MB; terlalu besar untuk dikirim ke GitHub Actions.")
            if (out.size >= MAX_FILES) throw GitHubException("Proyek memiliki lebih dari $MAX_FILES file.")
            if (SECRET_PATH_RE.containsMatchIn(rel)) {
                throw GitHubException("Push dibatalkan: berkas rahasia terdeteksi ($rel). Hapus atau keluarkan dari folder proyek sebelum build.")
            }
            val bytes = f.readBytes()
            // F-11: pindai teks MAUPUN biner (via ISO-8859-1) untuk token, kunci privat, dan field kredensial.
            val rawScan = String(bytes, Charsets.ISO_8859_1)
            if (SECRET_CONTENT_RE.containsMatchIn(rawScan) || SECRET_FIELD_RE.containsMatchIn(rawScan)) {
                throw GitHubException("Push dibatalkan: pola kunci privat, token, atau kredensial terdeteksi di $rel (isinya tidak dicetak). Keluarkan rahasia sebelum build.")
            }
            val mode = if (f.name == "gradlew" || f.name.endsWith(".sh")) "100755" else "100644"
            out.add(LocalFile(rel, bytes, mode, gitBlobSha(bytes)))
        }
        val hasWorkflow = out.any { it.path.startsWith(".github/workflows/") && (it.path.endsWith(".yml") || it.path.endsWith(".yaml")) }
        if (!hasWorkflow) throw GitHubException(NO_WORKFLOW)
        return out
    }

    // ----- 2. push + tunggu -----

    private suspend fun execute(files: List<LocalFile>, branch: String, timeoutSec: Int): BuildOutcome {
        // keadaan branch saat ini (bila ada)
        val refRes = call("GET", "$api/git/ref/heads/$branch")
        var parent: String? = null
        val known = HashMap<String, String>()
        if (refRes.code == 404) {
            // branch belum ada -> commit yatim
        } else {
            parent = obj(refRes, "membaca branch").getJSONObject("object").getString("sha")
            val commit = obj(call("GET", "$api/git/commits/$parent"), "membaca commit")
            val treeSha = commit.getJSONObject("tree").getString("sha")
            val tree = obj(call("GET", "$api/git/trees/$treeSha?recursive=1"), "membaca tree")
            if (!tree.optBoolean("truncated", false)) {
                val arr = tree.optJSONArray("tree") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    if (e.optString("type") == "blob") known[e.getString("path")] = e.getString("sha")
                }
            }
        }

        // Hanya file yang belum ada di repo yang dikirim: teks inline di dalam tree, biner sebagai blob.
        val entries = JSONArray()
        var uploaded = 0
        val existingShas = HashSet<String>(known.values)   // blob yang SUDAH ada di repo (boleh dirujuk lewat sha)
        var inlineBudget = INLINE_TOTAL
        for (f in files) {
            val entry = JSONObject().put("path", f.path).put("mode", f.mode).put("type", "blob")
            if (f.sha in existingShas) {
                entry.put("sha", f.sha)
            } else if (f.bytes.isNotEmpty() && f.bytes.size <= INLINE_FILE && f.bytes.size <= inlineBudget && isLosslessUtf8(f.bytes)) {
                entry.put("content", String(f.bytes, Charsets.UTF_8))
                inlineBudget -= f.bytes.size
                uploaded++
            } else {
                val body = JSONObject().put("content", Base64.getEncoder().encodeToString(f.bytes)).put("encoding", "base64")
                val sha = obj(call("POST", "$api/git/blobs", body), "mengunggah ${f.path}").getString("sha")
                existingShas.add(sha)
                entry.put("sha", sha)
                uploaded++
            }
            entries.put(entry)
        }

        // tree penuh tanpa base_tree: file yang dihapus agen ikut hilang dari branch
        val newTree = obj(call("POST", "$api/git/trees", JSONObject().put("tree", entries)), "membuat tree").getString("sha")
        val parents = JSONArray()
        parent?.let { parents.put(it) }
        val commitBody = JSONObject()
            .put("message", "Mythos build ${System.currentTimeMillis()}")
            .put("tree", newTree)
            .put("parents", parents)
        val sha = obj(call("POST", "$api/git/commits", commitBody), "membuat commit").getString("sha")

        if (parent != null) {
            if (methodOverrideWorks) {
                val upd = call(
                    "POST", "$api/git/refs/heads/$branch", JSONObject().put("sha", sha).put("force", true),
                    extra = mapOf("X-HTTP-Method-Override" to "PATCH")
                )
                if (upd.code in 200..299) return waitAndReport(branch, sha, uploaded, files.size, timeoutSec)
                methodOverrideWorks = false
            }
            val del = call("DELETE", "$api/git/refs/heads/$branch")
            if (del.code !in 200..299 && del.code != 404 && del.code != 422) fail(del, "memperbarui branch")
        }
        val create = call("POST", "$api/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
        if (create.code !in 200..299) fail(create, "membuat branch")

        return waitAndReport(branch, sha, uploaded, files.size, timeoutSec)
    }

    private suspend fun waitAndReport(branch: String, sha: String, uploaded: Int, total: Int, timeoutSec: Int): BuildOutcome {
        val step = maxOf(pollMs, 1L)
        val maxPolls = (timeoutSec * 1000L / step).coerceAtLeast(1)
        val appearPolls = minOf(maxPolls, 90_000L / step).coerceAtLeast(1)
        val head = "repo=${cfg.slug} branch=$branch commit=${sha.take(7)} (file: $total, blob baru: $uploaded)"

        var runs: List<Run> = emptyList()
        var i = 0L
        while (i < appearPolls) {
            runs = listRuns(sha)
            if (runs.isNotEmpty()) break
            delay(step)
            i++
        }
        if (runs.isEmpty()) {
            return BuildOutcome(
                false,
                "$head\nTidak ada run GitHub Actions untuk commit ini setelah ${appearPolls * step / 1000}s. Penyebab umum: " +
                    "workflow tidak memakai trigger `push` untuk branch `$branch` (pakai `on: { push: { branches: ['mythos/**'] } }`), " +
                    "Actions dinonaktifkan di repo, atau file YAML tidak valid (cek tab Actions di GitHub)."
            )
        }
        delay(step) // beri waktu workflow lain untuk commit yang sama muncul

        var polls = 0L
        while (true) {
            runs = listRuns(sha)
            if (runs.isNotEmpty() && runs.all { it.status == "completed" }) break
            if (++polls >= maxPolls) {
                val sb = StringBuilder("$head\n[TIMEOUT ${timeoutSec}s] Run belum selesai (masih berjalan di GitHub; naikkan timeout_s bila build memang lama):\n")
                runs.forEach { sb.append("- ${it.name}: ${it.status} ${it.url}\n") }
                return BuildOutcome(false, sb.toString().trimEnd())
            }
            delay(step)
        }

        val sb = StringBuilder(head).append('\n')
        var allOk = true
        for (r in runs) {
            val good = r.conclusion in OK_CONCLUSIONS
            if (!good) allOk = false
            sb.append(if (good) "✔ " else "✘ ").append(r.name).append(" → ").append(r.conclusion.ifEmpty { r.status }).append("  ").append(r.url).append('\n')
            if (!good) sb.append(failureDetails(r))
        }
        if (allOk) {
            lastGoodRunIds = runs.map { it.id }
            sb.append("Semua run sukses.")
        }
        return BuildOutcome(allOk, sb.toString().trimEnd())
    }

    private suspend fun listRuns(sha: String): List<Run> {
        val o = obj(call("GET", "$api/actions/runs?head_sha=$sha&per_page=20"), "membaca daftar run")
        val arr = o.optJSONArray("workflow_runs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Run(it.optLong("id"), it.str("name") ?: "workflow", it.str("status").orEmpty(), it.str("conclusion").orEmpty(), it.str("html_url").orEmpty())
        }
    }

    private suspend fun failureDetails(run: Run): String {
        val sb = StringBuilder()
        try {
            val jobs = obj(call("GET", "$api/actions/runs/${run.id}/jobs?per_page=50"), "membaca job").optJSONArray("jobs") ?: JSONArray()
            for (i in 0 until jobs.length()) {
                val j = jobs.getJSONObject(i)
                if ((j.str("conclusion").orEmpty()) in OK_CONCLUSIONS) continue
                val steps = j.optJSONArray("steps") ?: JSONArray()
                val failedSteps = (0 until steps.length()).map { steps.getJSONObject(it) }
                    .filter { it.str("conclusion") == "failure" }.map { it.str("name").orEmpty() }
                sb.append("  JOB '${j.str("name")}' ${j.str("conclusion")}")
                if (failedSteps.isNotEmpty()) sb.append(" — langkah gagal: ").append(failedSteps.joinToString())
                sb.append('\n')
                val log = try {
                    logExcerpt(fetchLog(j.getLong("id")))
                } catch (e: GitHubException) {
                    "(log tidak bisa diambil: ${e.message})"
                }
                sb.append(log.prependIndent("    ")).append('\n')
            }
        } catch (e: GitHubException) {
            sb.append("  (detail kegagalan tidak bisa diambil: ${e.message})\n")
        }
        return sb.toString()
    }

    private suspend fun fetchLog(jobId: Long): String {
        val r = call("GET", "$api/actions/jobs/$jobId/logs")
        return when (r.code) {
            in 200..299 -> r.text
            in 300..399 -> {
                val loc = r.headers["location"] ?: throw GitHubException("redirect log tanpa Location")
                val resolved = try { java.net.URL(java.net.URL("$api/actions/jobs/$jobId/logs"), loc).toString() }
                catch (e: Exception) { throw GitHubException("alamat redirect log tidak valid") }
                if (!resolved.startsWith("https://", ignoreCase = true)) {
                    throw GitHubException("redirect log bukan HTTPS; dibatalkan")
                }
                val r2 = call("GET", resolved, auth = false)
                if (r2.code !in 200..299) fail(r2, "mengunduh log")
                r2.text
            }
            else -> fail(r, "mengambil log")
        }
    }

    // ----- HTTP helper -----

    private suspend fun call(
        method: String,
        url: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        extra: Map<String, String> = emptyMap(),
    ): HttpResult {
        val headers = HashMap<String, String>()
        headers["Accept"] = "application/vnd.github+json"
        headers["X-GitHub-Api-Version"] = "2022-11-28"
        headers["User-Agent"] = "Mythos-Android"
        if (auth) headers["Authorization"] = "Bearer ${cfg.token}"
        headers.putAll(extra)
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        var attempt = 0
        while (true) {
            attempt++
            try {
                val r = transport.send(method, url, headers, bytes)
                if (method == "GET" && r.code >= 500 && attempt < 3) {
                    delay(1_000L * attempt)
                    continue
                }
                return r
            } catch (e: IOException) {
                if (method == "GET" && attempt < 3) {
                    delay(1_000L * attempt)
                    continue
                }
                throw GitHubException("Jaringan: ${e.message}")
            }
        }
    }

    private fun obj(r: HttpResult, what: String): JSONObject {
        if (r.code !in 200..299) fail(r, what)
        return JSONObject(r.text)
    }

    private fun fail(r: HttpResult, what: String): Nothing {
        val msg = try { JSONObject(r.text).optString("message") } catch (e: JSONException) { r.text.take(200) }
        val hint = when {
            r.code == 401 -> "Token GitHub ditolak atau kedaluwarsa; perbarui di Pengaturan."
            r.code == 403 && r.headers["x-ratelimit-remaining"] == "0" -> "Batas laju GitHub tercapai; coba lagi nanti."
            r.code == 403 && (r.headers["retry-after"] != null || msg.contains("rate limit", ignoreCase = true)) ->
                "GitHub membatasi laju permintaan (secondary rate limit). Tunggu sekitar satu menit lalu coba lagi."
            (r.code == 403 || r.code == 404 || r.code == 422) && msg.contains("workflow", ignoreCase = true) ->
                "Token belum boleh mengubah file workflow: beri izin Workflows → Read and write (fine-grained) atau scope `workflow` (classic)."
            r.code == 403 || r.code == 404 ->
                "Repo ${cfg.slug} tidak ditemukan atau token tidak punya izin (perlu Contents: Read and write, Workflows: Read and write, dan Actions: Read)."
            r.code == 409 -> "Repo masih kosong: buat satu commit awal (mis. centang 'Add a README') lalu coba lagi."
            else -> ""
        }
        throw GitHubException("GitHub: gagal $what (HTTP ${r.code}): $msg $hint".trim())
    }

    private companion object {
        const val MAX_FILE = 5_000_000L
        const val MAX_TOTAL = 30_000_000L
        const val MAX_FILES = 2_000
        const val INLINE_FILE = 500_000     // file teks di atas ini diunggah sebagai blob
        const val INLINE_TOTAL = 3_000_000  // total isi inline per permintaan tree
        val OK_CONCLUSIONS = setOf("success", "skipped", "neutral")
        const val NO_WORKFLOW = "Proyek belum punya workflow GitHub Actions. Buat .github/workflows/build.yml dengan Write " +
            "(trigger: `on: { push: { branches: ['mythos/**'] } }`), lalu panggil GithubBuild lagi."
        val SECRET_PATH_RE = Regex(
            """(^|/)(\.env(\..*)?|.*\.env|.*\.(pem|key|p12|pfx|ppk|gpg|jks|keystore|kdbx)|id_(rsa|dsa|ecdsa|ed25519)(\..*)?|""" +
                """\.netrc|_netrc|\.npmrc|\.pypirc|\.git-credentials|credentials|credentials\.json|.*[-_]credentials\.json|""" +
                """service[-_]account.*\.json|oauth_creds\.json|auth\.json|secrets?\.(json|ya?ml|properties)|keystore\.properties|local\.properties)$|(^|/)\.(ssh|aws|gnupg|kita-secrets|kita-upgrade|config/gh)/""",
            RegexOption.IGNORE_CASE
        )
        val SECRET_CONTENT_RE = Regex(
            "-----BEGIN ([A-Z0-9]+ )*PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{50,}|" +
                "(AKIA|ASIA)[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|xox[baprs]-[A-Za-z0-9-]{10,}|" +
                "sk-ant-[A-Za-z0-9_-]{20,}|sk-proj-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9]{40,}|" +
                "glpat-[A-Za-z0-9_-]{20,}|(sk|rk)_live_[A-Za-z0-9]{20,}|\\b\\d{8,10}:AA[A-Za-z0-9_-]{33}\\b"
        )
        val SECRET_FIELD_RE = Regex(
            "(?i)(?:\"(private_key|client_secret|refresh_token|access_token|api_key|keystore_password|storePassword|keyPassword)\"\\s*:\\s*\"[^\"\\s]{16,}\"" +
                "|(?:^|[\\r\\n])\\s*(storePassword|keyPassword|KEYSTORE_PASSWORD|KEYSTORE_B64|GITHUB_TOKEN|API_KEY|CLIENT_SECRET)\\s*=\\s*[^\\s#\"']{12,})"
        )
    }
}
