package com.build.mythos

// Penanda versi untuk sinkronisasi berangkas dan catatan celah source (logika murni, tanpa android.*; diuji di SourceSyncTest).
//
// Latar belakang: APK hanya membawa berangkas (panduan, skrip, templat) dan kita-self.sh, BUKAN seluruh source Kotlin.
// Jadi saat aplikasi di-upgrade, yang bisa diperbarui otomatis hanya salinan berangkas di Termux. Source di reference/ dan
// proyek lama di ruang kerja harus diimpor dari luar (zip atau repo), dan agen harus diberi tahu bahwa itu belum dilakukan.
internal object SourceSync {
    /** Kunci pref: versionCode aplikasi pada saat berangkas terakhir BERHASIL disalin ke Termux (0 = belum pernah). */
    const val PREF_SYNCED = "berangkas_synced_code"

    /** Kunci pref: versionCode aplikasi saat source bawaan APK terakhir BERHASIL diekstrak ke Termux (0 = belum pernah). */
    const val PREF_IMPORTED = "own_source_imported_code"

    /** Nama berkas source di assets APK (dibuat oleh tugas Gradle packSource). */
    const val SOURCE_ASSET = "mythos-source.zip"

    private val SAFE_NAME = Regex("[0-9A-Za-z._-]+")

    /** Ekstrak source bawaan bila versionCode terpasang terbaca dan berbeda dari yang tercatat pada ekstrak terakhir. */
    fun needsImport(installed: AppVersion?, lastImported: Long): Boolean = needsCopy(installed, lastImported)

    /** Nama folder tujuan, mis. "mythos-2.43"; null bila versionName tak aman dipakai sebagai nama folder. */
    fun folderName(installed: AppVersion): String? =
        installed.name.takeIf { SAFE_NAME.matches(it) && !it.contains("..") }?.let { "mythos-$it" }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Perintah shell: ekstrak [zip] ke folder sementara di samping [dest], lalu menggantikan [dest] sekaligus (tidak setengah jadi
     * bila gagal). Hanya menyentuh [dest] dan folder sementaranya. Penanda sukses: SRC_IMPOR=ok.
     */
    fun importCommand(zip: String, dest: String): String {
        val z = q(zip)
        val d = q(dest)
        val t = q("$dest.kita-tmp")
        return "command -v unzip >/dev/null 2>&1 || { echo SRC_IMPOR=gagal-unzip-tidak-ada; exit 1; }\n" +
            "rm -rf $t && mkdir -p $t && unzip -q -o $z -d $t && { chmod +x $t/gradlew 2>/dev/null; true; } && " +
            "rm -rf $d && mv $t $d && echo SRC_IMPOR=ok\n"
    }

    fun importSucceeded(out: String): Boolean = out.startsWith("exit=0") && out.contains("SRC_IMPOR=ok")

    /** Catatan untuk agen setelah source versi terpasang diekstrak; [path] ditulis apa adanya. */
    fun importNote(installed: AppVersion, path: String): String =
        "Source aplikasi terpasang " + VersionCheck.label(installed.name, installed.code) + " baru diekstrak otomatis dari APK ke " + path +
            ". Itulah satu-satunya source yang sama dengan aplikasi di HP; audit \"aplikasi\" harus di folder itu."

    /** Salin ulang berangkas bila versionCode terpasang terbaca dan berbeda dari yang tercatat pada salinan terakhir. */
    fun needsCopy(installed: AppVersion?, lastSynced: Long): Boolean {
        val c = installed?.code ?: return false
        return c > 0L && c != lastSynced
    }

    /** Catatan untuk agen setelah salinan ulang karena naik versi; null bila ini salinan pertama atau versi tidak naik. */
    fun upgradeNote(installed: AppVersion?, lastSynced: Long): String? {
        val inst = installed ?: return null
        if (inst.code <= 0L || lastSynced <= 0L || inst.code <= lastSynced) return null
        return "Aplikasi baru naik dari kode " + lastSynced + " ke " + VersionCheck.label(inst.name, inst.code) + ". " +
            "Berangkas di ~/.mythos/berangkas sudah disalin ulang otomatis dari aplikasi. " +
            "Source Kotlin di reference/ dan proyek lama tetap versi lama."
    }

    /**
     * Peringatan bila tidak ada satu pun folder berversi sama atau lebih baru dari yang terpasang. Null bila ada folder yang cocok,
     * atau bila belum ada versi folder yang terbaca (probe belum jalan atau bukan proyek Android): jangan menuduh tanpa fakta.
     */
    fun sourceGapNote(installed: AppVersion?, folders: Collection<FolderVersion>): String? {
        val inst = installed ?: return null
        if (inst.code <= 0L) return null
        val known = folders.mapNotNull { it.code }
        if (known.isEmpty()) return null
        if (known.any { it >= inst.code }) return null
        return "Di Termux TIDAK ada salinan source berversi terpasang (" + VersionCheck.label(inst.name, inst.code) + "); " +
            "versi tertinggi yang ada kode " + (known.maxOrNull() ?: 0L) + ". Ekstrak otomatis dari APK belum terjadi atau gagal (mode hanya baca, unzip belum ada di Termux, atau build tanpa source); " +
            "bila perlu minta pengguna mengimpor zip source. Sampai ada, jangan menyimpulkan keadaan aplikasi terpasang dari reference/ atau proyek lama."
    }

    /**
     * Alasan pemblokir upgrade bila build folder ini tidak mengemas source ke APK (meta `packs_source` dari kita-self.sh bukan "yes").
     * Tanpa ini APK baru membawa source lama atau tidak ada, dan agen versi berikutnya bekerja di source yang tidak sama dengan aplikasi.
     */
    fun packBlocker(meta: Map<String, String>): String? =
        if (meta["packs_source"]?.trim() == "yes") null
        else "Build folder ini tidak mengemas source ke APK (tugas packSource dan mythos-source.zip tidak ada di app/build.gradle.kts). " +
            "APK barunya tidak akan membawa source terbaru, jadi agen versi berikutnya memakai source yang tidak sama dengan aplikasi. " +
            "Pulihkan blok packSource di app/build.gradle.kts lalu build ulang."
}
