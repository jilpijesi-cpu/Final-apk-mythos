package com.build.mythos

// Audit diri (logika murni, tanpa android.*, diuji di SelfAuditTest).
//
// Pembagian kerja:
//  - Fakta dikumpulkan oleh skrip tepercaya app/src/main/assets/kita-self.sh (grep/find/sha256sum, hanya membaca).
//    Skrip dikirim dari aplikasi setiap kali dipakai, bukan dibaca dari folder proyek, jadi agen tidak bisa mengubahnya diam-diam.
//  - File ini hanya membaca penanda KITA_* dari keluarannya dan menyusun laporan. Tidak ada penilaian dari tebakan model.
//  - Nilai rahasia tidak pernah ada di keluaran: temuan kunci hanya berisi berkas:baris.

internal enum class Sev(val label: String, val rank: Int) {
    HIGH("TINGGI", 0), MED("SEDANG", 1), LOW("RENDAH", 2), INFO("INFO", 3);

    companion object {
        fun parse(s: String): Sev = when (s.trim().lowercase()) {
            "high" -> HIGH
            "med" -> MED
            "low" -> LOW
            else -> INFO
        }
    }
}

internal class Finding(val sev: Sev, val id: String, val loc: String, val msg: String) {
    val file: String get() = loc.substringBefore(':')

    /** Identitas temuan lintas-run. Nomor baris dibuang karena bergeser setiap kali berkas diubah. */
    val key: String get() = id + "|" + file + "|" + msg.take(60)
}

/** Fakta hasil satu kali audit: temuan, metadata (versi, jumlah berkas/tes, git), izin manifest, dan dependensi. */
internal class SelfFacts(
    val findings: List<Finding>,
    val meta: Map<String, String>,
    val perms: Set<String>,
    val deps: Set<String>,
    val done: Boolean
) {
    fun metaInt(k: String): Int? = meta[k]?.trim()?.toIntOrNull()
    fun metaLong(k: String): Long? = meta[k]?.trim()?.toLongOrNull()
}

/**
 * Baca baris penanda dari keluaran skrip. [prefix] "KITA_" untuk kondisi sekarang, "KITA_BASE_" untuk salinan baseline
 * yang dicetak ulang oleh mode diff. Baris lain (exit=0, noise) diabaikan.
 */
internal fun parseSelfFacts(out: String, prefix: String = "KITA_"): SelfFacts {
    val findings = ArrayList<Finding>()
    val meta = LinkedHashMap<String, String>()
    val perms = LinkedHashSet<String>()
    val deps = LinkedHashSet<String>()
    var done = false
    val a = prefix + "AUDIT|"
    val m = prefix + "META|"
    val p = prefix + "PERM|"
    val d = prefix + "DEP|"
    for (raw in out.lineSequence()) {
        val line = raw.trim()
        when {
            line == prefix + "AUDIT_DONE" -> done = true
            line.startsWith(a) -> {
                val f = line.removePrefix(a).split('|', limit = 4)
                if (f.size == 4) findings.add(Finding(Sev.parse(f[0]), f[1], f[2], f[3]))
            }
            line.startsWith(m) -> {
                val f = line.removePrefix(m).split('|', limit = 2)
                if (f.size == 2) meta[f[0]] = f[1]
            }
            line.startsWith(p) -> perms.add(line.removePrefix(p))
            line.startsWith(d) -> deps.add(line.removePrefix(d))
        }
    }
    return SelfFacts(findings, meta, perms, deps, done)
}

internal fun countBySev(fs: List<Finding>): Map<Sev, Int> = Sev.values().associateWith { s -> fs.count { it.sev == s } }

/** Skor 0..100 hanya untuk melihat ARAH (naik/turun antar audit), bukan jaminan keamanan. */
internal fun auditScore(fs: List<Finding>): Int {
    val penalty = fs.fold(0) { acc, f ->
        acc + when (f.sev) {
            Sev.HIGH -> 15
            Sev.MED -> 5
            Sev.LOW -> 1
            Sev.INFO -> 0
        }
    }
    return (100 - penalty).coerceIn(0, 100)
}

/** Fakta runtime aplikasi sendiri (diisi sisi Android di SelfUpgradeIntegration.kt). */
internal class StatusFacts(
    val versionName: String,
    val versionCode: Long,
    val canInstall: Boolean,
    val termux: Boolean,
    val runCommand: Boolean,
    val sandbox: Boolean,
    val readOnly: Boolean,
    val autonomous: Boolean,
    val minimalConfirm: Boolean,
    val boot: BootState?
)

internal fun renderStatus(s: StatusFacts): List<String> {
    val out = ArrayList<String>()
    out.add("Aplikasi terpasang: versi ${s.versionName} (kode ${s.versionCode})")
    out.add("Izin memasang aplikasi: " + (if (s.canInstall) "ada" else "BELUM (pemasangan upgrade akan diminta mengizinkannya)"))
    out.add("Termux: " + (if (s.termux) "terpasang" else "TIDAK terpasang") + ", izin RUN_COMMAND: " + (if (s.runCommand) "ada" else "BELUM"))
    val modes = ArrayList<String>()
    if (s.sandbox) modes.add("sandbox")
    if (s.readOnly) modes.add("hanya baca")
    if (s.autonomous) modes.add("OTONOM")
    if (s.minimalConfirm) modes.add("konfirmasi minimal")
    out.add("Mode aktif: " + (if (modes.isEmpty()) "standar" else modes.joinToString(", ")))
    val b = s.boot
    if (b != null) {
        val proven = b.lastGoodCode == s.versionCode
        val lastGood = if (b.lastGoodCode > 0) "kode ${b.lastGoodCode}" else "belum tercatat"
        val stab = if (proven) "versi ini sudah terbukti stabil"
        else "versi ini BELUM terbukti stabil (dibuka ${b.unhealthyBoots} kali; versi stabil terakhir: $lastGood)"
        out.add("Kestabilan: $stab")
    }
    return out
}

/** Laporan audit ringkas untuk model dan layar HP: ringkasan, temuan terurut tingkat keparahan, lalu status runtime. */
internal fun renderAudit(f: SelfFacts, status: List<String> = emptyList(), maxItems: Int = 14): String {
    val m = f.meta
    val sb = StringBuilder()
    sb.append("AUDIT DIRI\n")
    sb.append("Proyek: ").append(m["app_id"] ?: "?").append(", versi ").append(m["version_name"] ?: "?")
        .append(" (kode ").append(m["version_code"] ?: "?").append(")\n")
    sb.append("Kode: ").append(m["kt_main"] ?: "?").append(" berkas Kotlin utama, ")
        .append(m["kt_test"] ?: "?").append(" berkas tes, ").append(m["tests"] ?: "?").append(" tes\n")
    if (m["git"] == "yes") {
        sb.append("Git: cabang ").append(m["git_branch"] ?: "?").append(", HEAD ").append(m["git_head"] ?: "?")
            .append(", ").append(m["git_dirty"] ?: "?").append(" berkas belum di-commit\n")
    } else {
        sb.append("Git: bukan repositori git (tidak ada riwayat untuk pulih)\n")
    }
    val c = countBySev(f.findings)
    sb.append("Temuan: ").append(c[Sev.HIGH] ?: 0).append(" tinggi, ").append(c[Sev.MED] ?: 0).append(" sedang, ")
        .append(c[Sev.LOW] ?: 0).append(" rendah, ").append(c[Sev.INFO] ?: 0).append(" info. Skor arah: ")
        .append(auditScore(f.findings)).append("/100\n")
    val sorted = f.findings.sortedWith(compareBy<Finding>({ it.sev.rank }, { it.id }, { it.loc }))
    for (x in sorted.take(maxItems)) {
        sb.append("[").append(x.sev.label).append("] ").append(x.id)
        if (x.loc != "-" && x.loc.isNotBlank()) sb.append(" ").append(x.loc)
        sb.append(": ").append(x.msg).append("\n")
    }
    if (sorted.size > maxItems) sb.append("... dan ").append(sorted.size - maxItems).append(" temuan lain (tingkat lebih rendah)\n")
    if (f.findings.isEmpty()) sb.append("Tidak ada temuan.\n")
    if (status.isNotEmpty()) {
        sb.append("\nSTATUS APLIKASI\n")
        for (s in status) sb.append(s).append("\n")
    }
    sb.append("\nAudit ini statis dan heuristik: hanya membaca berkas, belum membuktikan build atau tes lulus.")
    return sb.toString().trimEnd()
}


/** Pembangun perintah shell yang menulis skrip (base64, aman dari masalah kutip) ke berkas sementara lalu menjalankannya. */
internal object SelfScript {
    val MODES = setOf("audit", "baseline", "diff", "restore", "bump")

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** [args] menjadi argumen ke-3 dst. skrip: applicationId yang diharapkan (audit/baseline/diff) atau versionCode dan versionName (bump). */
    fun command(scriptText: String, mode: String, dir: String, vararg args: String): String {
        require(mode in MODES) { "mode skrip tidak dikenal: $mode" }
        val b64 = java.util.Base64.getEncoder().encodeToString(scriptText.toByteArray(Charsets.UTF_8))
        val a = args.joinToString("") { " " + shq(it) }
        return "S=\"\$(mktemp)\" || exit 1\n" +
            "printf %s '" + b64 + "' | base64 -d > \"\$S\"\n" +
            "bash \"\$S\" " + mode + " " + shq(dir) + a + "\n" +
            "rc=\$?\n" +
            "rm -f \"\$S\"\n" +
            "exit \$rc"
    }
}
