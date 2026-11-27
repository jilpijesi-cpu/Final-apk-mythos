package com.build.mythos

// Upgrade diri (logika murni, tanpa android.*, diuji di SelfUpgradeTest).
//
// Prinsip utama: VERSI YANG BERJALAN MENILAI VERSI BERIKUTNYA. Agen boleh mengubah kode aplikasi ini, tetapi
// gerbang di bawah (kode versi yang sedang terpasang) memutuskan apakah hasilnya boleh dipasang. Berkas gerbang itu
// sendiri termasuk area terlindungi, jadi melemahkannya selalu berujung pada persetujuan eksplisit pengguna,
// dinilai oleh versi lama yang masih utuh.
//
// Tiga hasil: PASS (boleh lanjut), NEEDS_USER (pengguna harus setuju; mode otonom dan "Ya, semua" tidak bisa
// menggantikan), BLOCK (tidak boleh dipasang sebelum alasannya diperbaiki).
//
// Batas jujur: gerbang melindungi dari kesalahan dan kemunduran, bukan dari agen yang sengaja memusuhi dan punya akses
// shell penuh. Batas sesungguhnya adalah ketukan pengguna di pemasang Android dan kesinambungan tanda tangan APK.
// Hash baseline disimpan di penyimpanan privat aplikasi (tidak terjangkau Termux) supaya perubahan baseline terdeteksi.

internal enum class UpgradeStage {
    MULAI, PERIKSA, PASANG, PULIHKAN;

    companion object {
        fun parse(s: String): UpgradeStage? = when (s.trim().lowercase()) {
            "mulai" -> MULAI
            "periksa" -> PERIKSA
            "pasang" -> PASANG
            "pulihkan" -> PULIHKAN
            else -> null
        }
    }
}

internal enum class Verdict { PASS, NEEDS_USER, BLOCK }

internal class FileChange(val kind: Char, val path: String)

/** [state]: "none" (tidak ada hasil), "fresh" (lebih baru dari semua kode), atau "stale". */
internal class TestState(val state: String, val ran: Int, val bad: Int)

internal class ApkState(val path: String?, val fresh: Boolean)

/** Hasil mode diff skrip: perubahan sejak baseline, fakta sekarang, salinan fakta baseline, dan status build/tes. */
internal class DiffFacts(
    val ok: Boolean,
    val error: String?,
    val baseHash: String?,
    val changes: List<FileChange>,
    val now: SelfFacts,
    val base: SelfFacts,
    val apk: ApkState,
    val tests: TestState
)

private fun errorOf(out: String): String? =
    out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("KITA_ERR|") }?.removePrefix("KITA_ERR|")

internal fun parseDiff(out: String): DiffFacts {
    val err = errorOf(out)
    var baseHash: String? = null
    var diffOk = false
    val changes = ArrayList<FileChange>()
    var apk = ApkState(null, false)
    var tests = TestState("none", 0, 0)
    for (raw in out.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("KITA_BASEHASH=") -> baseHash = line.removePrefix("KITA_BASEHASH=").trim().ifEmpty { null }
            line == "KITA_DIFF=ok" -> diffOk = true
            line.startsWith("KITA_CHG|") -> {
                val f = line.removePrefix("KITA_CHG|").split('|', limit = 2)
                if (f.size == 2 && f[0].length == 1 && f[0][0] in "AMD") changes.add(FileChange(f[0][0], f[1]))
            }
            line.startsWith("KITA_APK|") -> {
                val body = line.removePrefix("KITA_APK|")
                val sep = body.lastIndexOf('|')
                if (sep > 0) {
                    val path = body.substring(0, sep)
                    val st = body.substring(sep + 1)
                    apk = if (path == "none" || path.isEmpty()) ApkState(null, false) else ApkState(path, st == "fresh")
                }
            }
            line.startsWith("KITA_TESTS|") -> {
                val f = line.removePrefix("KITA_TESTS|").split('|')
                if (f.size >= 3) tests = TestState(f[0], f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0)
            }
        }
    }
    return DiffFacts(
        ok = err == null && diffOk,
        error = err ?: if (diffOk) null else "pemeriksaan perubahan tidak selesai",
        baseHash = baseHash,
        changes = changes,
        now = parseSelfFacts(out, "KITA_"),
        base = parseSelfFacts(out, "KITA_BASE_"),
        apk = apk,
        tests = tests
    )
}

internal class BaselineResult(val ok: Boolean, val error: String?, val snapshot: String?, val hash: String?, val facts: SelfFacts)


/** Area yang menentukan keselamatan aplikasi atau cara membangunnya. Perubahan di sini selalu butuh persetujuan pengguna. */
internal object ProtectedCore {
    private val RULES = listOf(
        // persetujuan, klasifikasi risiko, pengaman agen, penyimpanan kunci, pemasangan APK, sandbox, dan gerbang upgrade ini
        Regex("""(^|/)(CommandPolicy|Classify|ApprovalDialog|AgentGuard|ToolExecution|Secret|CryptoProvider|ApkInstall|ApkProvider|CommandRunner|SandboxRunner|SandboxIntegration|BuildApk|SelfAudit|SelfUpgrade|SelfUpgradeIntegration|ApkVerify|StartupHealth|SourceSync|TermuxFiles|TermuxShell|TermuxWrap|TermuxSettingsSync|WorkspacePaths|WorkspaceMigration|WebFetch|WebTools|ShareProvider|WaListenerService)\.kt$"""),
        Regex("""(^|/)AndroidManifest\.xml$"""),
        Regex("""(^|/)network_security_config\.xml$"""),
        Regex("""(^|/)proguard-rules\.pro$"""),
        Regex("""(^|/)(build|settings)\.gradle(\.kts)?$"""),
        Regex("""(^|/)gradle/wrapper/"""),
        Regex("""(^|/)gradlew(\.bat)?$"""),
        Regex("""(^|/)\.github/workflows/"""),
        Regex("""(^|/)assets/kita-self\.sh$"""),
        Regex("""(^|/)tools/[^/]+\.sh$""")
    )

    private fun norm(path: String): String = path.replace('\\', '/').removePrefix("./")

    fun isProtected(path: String): Boolean {
        val p = norm(path)
        return RULES.any { it.containsMatchIn(p) }
    }

    fun isTestPath(path: String): Boolean = Regex("""(^|/)src/(test|androidTest)/""").containsMatchIn(norm(path))
}

internal class UpgradeInput(
    val installedVersionCode: Long,
    val ownAppId: String,
    val diff: DiffFacts,
    /** Hash baseline yang dicatat aplikasi saat tahap mulai (penyimpanan privat). null bila tidak ada catatan. */
    val expectedBaseHash: String?
)

internal class GateResult(
    val verdict: Verdict,
    val blockers: List<String>,
    val reviews: List<String>,
    val notes: List<String>
)

private fun names(items: List<String>, max: Int): String =
    items.take(max).joinToString(", ") + (if (items.size > max) " (+${items.size - max} lagi)" else "")

/**
 * Gerbang upgrade. Semua keputusan berasal dari fakta di [UpgradeInput.diff] (skrip tepercaya) dan catatan privat aplikasi,
 * bukan dari klaim agen. Aturan BLOCK dan NEEDS_USER dijelaskan di SELF_UPGRADE.md.
 */
internal fun evaluateUpgrade(i: UpgradeInput): GateResult {
    val blockers = ArrayList<String>()
    val reviews = ArrayList<String>()
    val notes = ArrayList<String>()
    val d = i.diff
    if (!d.ok) {
        blockers.add(d.error ?: "pemeriksaan perubahan gagal dijalankan")
        return GateResult(Verdict.BLOCK, blockers, reviews, notes)
    }

    // Identitas dan keutuhan baseline
    val appId = d.now.meta["app_id"].orEmpty()
    if (appId != i.ownAppId) {
        blockers.add("Folder ini bukan kode sumber aplikasi ini (applicationId \"$appId\", seharusnya \"${i.ownAppId}\").")
    }
    val expected = i.expectedBaseHash
    if (expected.isNullOrBlank()) {
        blockers.add("Aplikasi tidak punya catatan baseline. Jalankan self_upgrade tahap=mulai dulu (sebelum mengubah kode).")
    } else if (d.baseHash != expected) {
        blockers.add("Baseline di folder proyek berubah di luar aplikasi (hash tidak cocok), jadi perbandingan tidak bisa dipercaya. Mulai ulang dengan tahap=mulai.")
    }
    if (d.changes.isEmpty()) blockers.add("Tidak ada perubahan dibanding baseline.")

    // Build
    if (d.apk.path == null) {
        blockers.add("Belum ada APK hasil build. Jalankan build_apk.")
    } else if (!d.apk.fresh) {
        blockers.add("APK lebih tua daripada perubahan kode terakhir, jadi belum membuktikan kode sekarang bisa dibangun. Jalankan build_apk lagi.")
    }

    // Versi
    val vNow = d.now.metaLong("version_code")
    if (vNow == null) {
        blockers.add("versionCode tidak terbaca di build.gradle.kts.")
    } else if (vNow <= i.installedVersionCode) {
        blockers.add("versionCode ($vNow) belum lebih tinggi dari versi terpasang (${i.installedVersionCode}). Android menolak pembaruan seperti ini: naikkan lalu build ulang.")
    }

    // Source bawaan APK harus ikut terbarui (dikemas Gradle dari folder ini tiap build)
    SourceSync.packBlocker(d.now.meta)?.let { blockers.add(it) }

    // Tes tidak boleh berkurang atau gagal
    val tNow = d.now.metaInt("tests")
    val tBase = d.base.metaInt("tests")
    if (tNow != null && tBase != null && tNow < tBase) {
        blockers.add("Jumlah @Test turun dari $tBase ke $tNow. Tes tidak boleh dihapus untuk meloloskan upgrade.")
    }
    val deletedTests = d.changes.filter { it.kind == 'D' && ProtectedCore.isTestPath(it.path) }.map { it.path.substringAfterLast('/') }
    if (deletedTests.isNotEmpty()) blockers.add("Berkas tes dihapus: " + names(deletedTests, 5) + ".")
    if (d.tests.state == "fresh" && d.tests.bad > 0) {
        blockers.add("Tes unit gagal: ${d.tests.bad} dari ${d.tests.ran}. Perbaiki sebelum upgrade.")
    } else if (d.tests.state == "fresh") {
        notes.add("Tes unit lulus: ${d.tests.ran} tes.")
    } else {
        reviews.add("Tes unit belum dijalankan sejak perubahan kode terakhir (build_apk hanya merakit APK). Jalankan ./gradlew testDebugUnitTest bila bisa.")
    }

    // Temuan audit
    val baseKeys = d.base.findings.map { it.key }.toSet()
    val fresh = d.now.findings.filter { it.key !in baseKeys }
    val guardMissing = d.now.findings.filter { it.id == "S08" && it.sev == Sev.HIGH }
    if (guardMissing.isNotEmpty()) {
        blockers.add("Fungsi atau berkas pengaman hilang atau dilemahkan: " + names(guardMissing.map { it.msg }, 4) + ".")
    }
    val newHigh = fresh.filter { it.sev == Sev.HIGH && it.id != "S08" }
    if (newHigh.isNotEmpty()) {
        blockers.add("Temuan audit TINGGI baru: " + names(newHigh.map { it.id + " " + it.loc + " (" + it.msg + ")" }, 4) + ".")
    }
    val newMed = fresh.filter { it.sev == Sev.MED }
    if (newMed.isNotEmpty()) reviews.add("Temuan audit SEDANG baru: " + names(newMed.map { it.id + " " + it.loc }, 4) + ".")

    // Perubahan yang harus dilihat manusia
    val prot = d.changes.filter { ProtectedCore.isProtected(it.path) }
    if (prot.isNotEmpty()) {
        reviews.add("Mengubah area pengaman atau pembangun (${prot.size} berkas): " + names(prot.map { "${it.kind} ${it.path}" }, 8) + ".")
    }
    val newPerms = d.now.perms - d.base.perms
    if (newPerms.isNotEmpty()) reviews.add("Izin baru di manifest: " + names(newPerms.toList(), 6) + ".")
    val newDeps = d.now.deps - d.base.deps
    if (newDeps.isNotEmpty()) reviews.add("Dependensi atau plugin baru/berubah versi: " + names(newDeps.toList(), 6) + ".")

    val verdict = when {
        blockers.isNotEmpty() -> Verdict.BLOCK
        reviews.isNotEmpty() -> Verdict.NEEDS_USER
        else -> Verdict.PASS
    }
    return GateResult(verdict, blockers, reviews, notes)
}



/** versionCode berikutnya: selalu di atas yang terpasang DAN yang ada di berkas (maju terus, tidak pernah turun). */
internal fun nextVersionCode(installed: Long, inFile: Long?): Long = maxOf(installed, inFile ?: 0L) + 1L

/** Nama versi untuk pemulihan: "2.5-agen-build" -> "2.5-agen-build-r17". Karakter di luar [A-Za-z0-9._-] dibuang (skrip menolaknya). */
internal fun rollForwardName(current: String, code: Long): String {
    val base = current.replace(Regex("[^A-Za-z0-9._-]"), "").replace(Regex("-r\\d+$"), "").ifEmpty { "v" }
    return base + "-r" + code
}

/** Resep untuk model. Ditambahkan ke prompt sistem oleh sysText(). */
internal const val SELF_UPGRADE_PLAYBOOK =
    "\nAUDIT DAN UPGRADE DIRI: bila pengguna meminta memeriksa atau memperbarui aplikasi ini sendiri, pakai self_audit (hanya membaca) dan self_upgrade; " +
        "folder = folder kode sumber aplikasi ini relatif terhadap folder kerja (tool menolak folder yang applicationId-nya bukan aplikasi ini). " +
        "Urutan upgrade: self_upgrade tahap=mulai (cadangan dan baseline) -> ubah kode -> naikkan versionCode di build.gradle.kts -> tambah atau perbarui tes -> " +
        "build_apk -> bila memungkinkan jalankan tes unit (./gradlew testDebugUnitTest --no-daemon) -> self_upgrade tahap=periksa; perbaiki semua alasan DIBLOKIR lalu periksa lagi -> " +
        "tahap=pasang (menampilkan kartu Pasang APK; pengguna sendiri yang mengetuknya). " +
        "Source aplikasi ikut terbarui otomatis: Gradle (packSource) mengemas folder kode ini ke dalam APK tiap build, lalu aplikasi mengekstraknya ke projects/aktif/mythos-<versi> saat versi baru berjalan. " +
        "Karena itu build_apk wajib dari folder yang diubah, dan blok packSource di app/build.gradle.kts tidak boleh dihapus (gerbang memblokir). " +
        "Jangan melemahkan pengaman (persetujuan perintah, klasifikasi risiko, enkripsi kunci, gerbang upgrade ini), jangan menghapus tes, " +
        "jangan menambah izin atau dependensi tanpa diminta: perubahan semacam itu memerlukan persetujuan pengguna yang tidak bisa kamu berikan sendiri, termasuk di mode otonom. " +
        "Versi yang berjalan menilai versi berikutnya. Bila versi baru bermasalah: self_upgrade tahap=pulihkan, lalu build_apk, periksa, pasang."
