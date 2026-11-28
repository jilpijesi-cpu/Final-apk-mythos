package com.build.mythos

// "Konteks kerja": blok yang SELALU ada di prompt sistem agar agen tidak kehilangan arah pada pesan pendek, ambigu, atau salah ketik.
// Isinya fakta yang diperiksa tiap giliran, bukan ingatan model: folder kerja chat ini, proyek yang benar-benar ada di sana, dan
// beberapa permintaan pengguna terakhir (tetap ada walau riwayat lama terpangkas oleh anggaran konteks).
// Logika murni ada di WorkContextRules (teruji JVM).

internal object WorkContextRules {
    /** Subfolder operasional workspace; bukan proyek. */
    private val KNOWN = setOf("projects", "artifacts", "attachments", "backups", "logs", "memory")
    const val MAX_PROJECTS = 8

    /**
     * Daftar proyek dari keluaran `ls -1dt` (terbaru dulu): folder di projects/aktif dan folder tingkat atas folder kerja
     * (mis. hasil ekstrak manual oleh agen) selain subfolder operasional. Hasil relatif terhadap [root].
     */
    fun projectsFrom(lsLines: List<String>, root: String): List<String> {
        val r = root.trimEnd('/')
        val out = LinkedHashSet<String>()
        for (raw in lsLines) {
            val line = raw.trim()
            if (!line.endsWith("/") || !line.startsWith("$r/")) continue
            val rel = line.removePrefix("$r/").trimEnd('/')
            if (rel.isEmpty() || rel.contains("..")) continue
            val ok = when {
                rel.startsWith("projects/aktif/") -> rel.removePrefix("projects/aktif/").let { it.isNotEmpty() && !it.contains('/') }
                else -> !rel.contains('/') && rel !in KNOWN && !rel.startsWith(".")
            }
            if (ok) out.add(rel)
            if (out.size >= MAX_PROJECTS) break
        }
        return out.toList()
    }

    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,64}$")

    /**
     * Folder chat kosong tetapi ada salinan source di reference/: beri agen path ABSOLUT dan cara menyalinnya,
     * agar tidak menyimpulkan "tidak ada proyek" atau menebak path relatif yang salah. Null bila tidak ada yang bisa ditunjuk.
     * reference/ boleh DIBACA langsung; hanya perubahan yang harus dilakukan pada salinan di projects/aktif.
     */
    fun emptyWorkspaceHint(root: String, refRoot: String?, refNames: Collection<String>, claimEmpty: Boolean = true): String? {
        val ref = refRoot?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val names = refNames.filter { SAFE_NAME.matches(it) && !it.startsWith(".") }.take(MAX_PROJECTS)
        if (names.isEmpty()) return null
        val first = names.first()
        return (if (claimEmpty) "Folder kerja chat ini KOSONG" else "Folder kerja chat ini belum punya proyek") + ", tetapi ada salinan source (hanya baca) di $ref: " + names.joinToString(", ") + ". " +
            "Chat lain tidak boleh disentuh, jadi path relatif seperti \"$first\" tidak akan ketemu di sini; pakai path absolut \"$ref/$first\". " +
            "Membaca/mengaudit langsung dari reference/ diperbolehkan. Untuk mengubah, salin dulu: " +
            "mkdir -p '$root/projects/aktif' && cp -r '$ref/$first' '$root/projects/aktif/$first', lalu kerjakan salinannya. " +
            "Jangan menyimpulkan bahwa tidak ada proyek, dan jangan memanggil alat dengan path kosong atau null."
    }

    /** Beberapa permintaan pengguna terakhir (lama -> baru): satu baris, dipotong, tanpa yang kosong. */
    fun recentAsks(userTexts: List<String>, n: Int = 4, maxChars: Int = 140): List<String> =
        userTexts.map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .takeLast(n)
            .map { if (it.length > maxChars) it.take(maxChars - 1) + "…" else it }

    /**
     * [installed] = versi aplikasi yang TERPASANG di HP; [versions] = versi proyek di ruang kerja (kunci: path relatif);
     * [refVersions] = versi salinan source di reference/ (kunci: nama subfolder). Ketiganya opsional agar pemanggil lama tetap jalan.
     */
    fun block(
        root: String,
        projects: List<String>,
        asks: List<String>,
        installed: AppVersion? = null,
        versions: Map<String, FolderVersion> = emptyMap(),
        refVersions: Map<String, FolderVersion> = emptyMap(),
        syncNote: String? = null,
        refRoot: String? = null,
        attachments: List<String> = emptyList(),
        projectsKnown: Boolean = true
    ): String {
        val sb = StringBuilder()
        sb.append("KONTEKS KERJA (fakta yang diperiksa aplikasi giliran ini; pakai ini sebelum menebak):\n")
        sb.append("- Ruang kerja khusus chat ini: ").append(root).append(". File yang diunggah di chat ini ada di ").append(root).append("/attachments/.\n")
        if (installed != null) {
            sb.append("- Aplikasi MYTHOS yang TERPASANG di HP ini: versi ").append(VersionCheck.label(installed.name, installed.code)).append(". ")
            sb.append("Hanya itu arti kata \"aplikasi\" atau \"MYTHOS\" dalam permintaan. Isi ruang kerja BUKAN otomatis aplikasi itu: ")
            sb.append("foldernya bisa versi lain, dan salinan source di reference/ dibuat sekali saat migrasi lalu tidak ikut diperbarui saat aplikasi di-upgrade.\n")
            val shown = projects.mapNotNull { p -> versions[p]?.let { v -> p to v } }
            if (shown.isNotEmpty()) {
                sb.append("- Versi proyek di ruang kerja: ")
                sb.append(shown.joinToString("; ") { (p, v) ->
                    val t = VersionCheck.tag(installed, v)
                    p + " = " + VersionCheck.label(v.name, v.code) + (if (t != null) " [" + t + "]" else " [sama dengan terpasang]")
                })
                sb.append(".\n")
            } else if (projects.isNotEmpty()) {
                sb.append("- Versi proyek di ruang kerja tidak terbaca (bukan proyek Android atau belum diperiksa); jangan anggap sama dengan aplikasi terpasang.\n")
            }
            if (refVersions.isNotEmpty()) {
                sb.append("- Salinan source di reference/: ")
                sb.append(refVersions.entries.joinToString("; ") { (n, v) ->
                    val t = VersionCheck.tag(installed, v)
                    n + " = " + VersionCheck.label(v.name, v.code) + (if (t != null) " [" + t + "]" else " [sama dengan terpasang]")
                })
                sb.append(".\n")
            }
            val sameAsInstalled = shown.filter { (_, v) -> VersionCheck.compare(installed, v) == VersionRel.SAME }.map { it.first }
            if (sameAsInstalled.isNotEmpty()) {
                sb.append("- SOURCE APLIKASI TERPASANG (versi sama): ").append(sameAsInstalled.joinToString(", "))
                sb.append(". Permintaan audit atau ubah \"aplikasi\"/\"MYTHOS\" dikerjakan di folder ini, BUKAN di folder lain, kecuali pengguna menyebut folder lain.\n")
            }
            if (syncNote != null) sb.append("- ").append(syncNote).append("\n")
            SourceSync.sourceGapNote(installed, versions.values + refVersions.values)?.let { sb.append("- ").append(it).append("\n") }
        }
        if (projects.isEmpty()) {
            // "Tidak ada proyek" tidak sama dengan "folder kosong": lampiran pengguna ada di attachments/, dan daftar proyek
            // yang belum dipindai berarti TIDAK DIKETAHUI, bukan kosong. Dulu keduanya dilaporkan sebagai kosong.
            if (projectsKnown) sb.append("- Belum ada proyek di ruang kerja ini.\n")
            else sb.append("- Daftar proyek belum dipindai pada giliran ini (BUKAN berarti kosong). Periksa isi folder dengan ls sebelum menyimpulkan apa pun.\n")
            if (attachments.isNotEmpty()) {
                sb.append("- Ruang kerja ini TIDAK kosong: ada ").append(attachments.size).append(" lampiran pengguna di ").append(root)
                    .append("/attachments/ (").append(attachments.takeLast(5).joinToString(", ") { it.take(60) })
                    .append("). Kerjakan file itu dari sana; jangan bilang file tidak ada sebelum memeriksa path-nya dengan ls.\n")
            }
            emptyWorkspaceHint(root, refRoot, refVersions.keys, claimEmpty = projectsKnown && attachments.isEmpty())
                ?.let { sb.append("- ").append(it).append("\n") }
        } else {
            sb.append("- Proyek di ruang kerja ini (terbaru dulu): ").append(projects.joinToString(", ")).append(".\n")
            sb.append("- Bila pengguna menyebut \"proyek\", \"ini\", atau \"-nya\" tanpa nama, yang dimaksud: ").append(projects.first()).append(".\n")
        }
        if (asks.isNotEmpty()) {
            sb.append("- Permintaan pengguna terakhir (lama ke baru): ")
            sb.append(asks.mapIndexed { i, a -> "${i + 1}) \"$a\"" }.joinToString(" "))
            sb.append("\n")
        }
        sb.append("ATURAN MENAFSIRKAN: pesan pendek, ambigu, atau tampak salah ketik (mis. \"Adit\" untuk \"Audit\", \"Proyek nya\") adalah lanjutan ")
        sb.append("dari permintaan di atas dan menyangkut proyek di ruang kerja ini. Tafsirkan lewat konteks ini LEBIH DULU: jangan mencarinya di web ")
        sb.append("dan jangan bertanya balik \"apa yang ingin dikerjakan\" bila tujuannya sudah tampak dari permintaan sebelumnya. ")
        sb.append("Aturan ini tidak berlaku bila pesan menyebut \"aplikasi\", \"MYTHOS\", atau \"upgrade\", atau bila versi folder berbeda dari yang terpasang: ")
        sb.append("pada kasus itu isi ruang kerja bukan jawaban otomatis. ")
        sb.append("Bertanya hanya bila setelah memakai konteks ini masih ada dua tafsiran yang hasilnya berbeda; sebutkan tafsiran terbaikmu dan lanjutkan dengan itu.\n")
        sb.append("ATURAN APLIKASI TERPASANG: sebelum mengaudit, mengubah, atau meng-upgrade \"aplikasi\", pastikan folder mana yang dimaksud dan cocokkan versinya ")
        sb.append("dengan versi terpasang (validate_project dan self_audit menampilkan keduanya). Bila versinya beda atau tidak terbaca, sebutkan bedanya lalu tanya ")
        sb.append("folder mana yang dimaksud lewat ask_user; jangan lanjut diam-diam dengan folder terbaru. Setiap laporan audit wajib menyebut folder dan versi yang ")
        sb.append("diperiksa, dan keadaan aplikasi terpasang tidak boleh disimpulkan dari folder yang versinya berbeda.")
        return sb.toString()
    }
}

/** Cache proyek per folder kerja; diisi di awal setiap tugas (thread latar), dibaca saat menyusun prompt. */
@Volatile private var cachedProjects: Pair<String, List<String>>? = null

/** Versi proyek di folder kerja dan di reference/ (hasil probe yang sama dengan daftar proyek). */
@Volatile private var cachedVersions: Triple<String, Map<String, FolderVersion>, Map<String, FolderVersion>>? = null

/** Catatan satu kali: berangkas baru disalin ulang karena aplikasi naik versi (hilang bila proses aplikasi mati). */
@Volatile private var cachedSyncNote: String? = null

internal fun MainActivity.refreshWorkContext() {
    val root = workspaceRoot().trimEnd('/')
    try {
        val notes = ArrayList<String>()
        try { syncBerangkasIfNeeded()?.let { notes.add(it) } } catch (e: Exception) { logQuiet("WorkContext.kt", e) }
        try { syncOwnSourceIfNeeded()?.let { notes.add(it) } } catch (e: Exception) { logQuiet("WorkContext.kt", e) }
        if (notes.isNotEmpty()) cachedSyncNote = notes.joinToString(" ")
    } catch (e: Exception) {
        logQuiet("WorkContext.kt", e)
    }
    try {
        val ref = referenceRoot().trimEnd('/')
        val targets = "${sh("$root/projects/aktif")}/*/ ${sh(root)}/*/ ${sh(ref)}/*/"
        val r = termux(VersionCheck.dirProbe(targets), limit = 8000, timeoutSec = 15, useWorkdir = false, killable = false)
        if (r.startsWith("exit=0")) {
            val lines = r.lines()
            cachedProjects = root to WorkContextRules.projectsFrom(lines, root)
            cachedVersions = Triple(root, VersionCheck.parseDirs(lines, root), VersionCheck.parseDirs(lines, ref))
        }
    } catch (e: Exception) {
        logQuiet("WorkContext.kt", e)
    }
}

/** Versi aplikasi yang terpasang (null bila PackageManager gagal); dipakai blok konteks dan validate_project. */
internal fun MainActivity.installedVersion(): AppVersion? = try {
    val info = packageManager.getPackageInfo(packageName, 0)
    AppVersion(info.versionName ?: "?", versionOf(info))
} catch (e: Exception) {
    null
}

internal fun MainActivity.workContextBlock(): String {
    val root = workspaceRoot().trimEnd('/')
    val projectsKnown = cachedProjects?.first == root
    val projects = cachedProjects?.takeIf { it.first == root }?.second.orEmpty()
    val cv = cachedVersions?.takeIf { it.first == root }
    val asks = synchronized(hist) { hist.filter { it.role == 0 }.map { it.text } }
    return "\n" + WorkContextRules.block(
        homeShort(root), projects, WorkContextRules.recentAsks(asks),
        installedVersion(), cv?.second.orEmpty(), cv?.third.orEmpty(), cachedSyncNote,
        homeShort(referenceRoot()),
        activeAtts().map { it.name }, projectsKnown
    )
}
