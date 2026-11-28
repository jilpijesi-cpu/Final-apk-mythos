package com.build.mythos

// Pencocokan versi aplikasi TERPASANG dengan versi proyek di sebuah folder (logika murni, tanpa android.*; diuji di VersionCheckTest).
//
// Latar belakang: agen bekerja di ruang kerja Termux. Isi ruang kerja (terutama salinan source di reference/) dibuat sekali
// dan tidak ikut diperbarui saat aplikasi di-upgrade, sehingga agen bisa mengaudit folder lama seolah-olah itu aplikasi yang
// sedang berjalan. Berkas ini memberi agen FAKTA versi (terpasang vs folder) supaya perbedaannya terlihat, bukan ditebak.

internal class AppVersion(val name: String, val code: Long)

internal class FolderVersion(val name: String?, val code: Long?)

internal enum class VersionRel { SAME, FOLDER_OLDER, FOLDER_NEWER, UNKNOWN }

internal object VersionCheck {
    /** Padanan `$` di templat skrip (menghindari escape di setiap baris), sama seperti ProjectCheck. */
    private const val D = '§'

    private const val PREFIX_ONE = "KITA_VERSION|"
    private const val PREFIX_DIR = "KITA_DIRVER|"

    /**
     * Skrip untuk SATU folder proyek; dijalankan dengan direktori kerja = folder itu (ditempel sesudah ProjectCheck.command).
     * Mencetak `KITA_VERSION|<versionCode>|<versionName>` bila app/build.gradle(.kts) ada.
     */
    private val ONE_PROBE = """
APPV=""
for g in app/build.gradle.kts app/build.gradle; do [ -f "§g" ] && APPV="§g" && break; done
if [ -n "§APPV" ]; then
  printf 'KITA_VERSION|%s|%s\n' "§(grep -vE '^[[:space:]]*(//|\*|/\*)' "§APPV" 2>/dev/null | grep -hoE '\bversionCode[[:space:]]*=?[[:space:]]*[0-9]+' | head -1 | grep -oE '[0-9]+§')" "§(grep -vE '^[[:space:]]*(//|\*|/\*)' "§APPV" 2>/dev/null | grep -hoE '\bversionName[[:space:]]*=?[[:space:]]*"[^"]*"' | head -1 | sed 's/^[^"]*"//; s/"§//')"
fi
"""

    /** Skrip daftar folder: cetak hasil `ls` (satu direktori per baris) lalu `KITA_DIRVER|<dir>|<code>|<name>` per folder proyek Android. */
    private val DIR_PROBE = """
L="§(ls -1dt -- %LS% 2>/dev/null)"
printf '%s\n' "§L"
printf '%s\n' "§L" | head -n 30 | while IFS= read -r d; do
  g="§{d}app/build.gradle.kts"; [ -f "§g" ] || g="§{d}app/build.gradle"
  [ -f "§g" ] || continue
  vc="§(grep -vE '^[[:space:]]*(//|\*|/\*)' "§g" 2>/dev/null | grep -hoE '\bversionCode[[:space:]]*=?[[:space:]]*[0-9]+' | head -1 | grep -oE '[0-9]+§')"
  vn="§(grep -vE '^[[:space:]]*(//|\*|/\*)' "§g" 2>/dev/null | grep -hoE '\bversionName[[:space:]]*=?[[:space:]]*"[^"]*"' | head -1 | sed 's/^[^"]*"//; s/"§//')"
  printf 'KITA_DIRVER|%s|%s|%s\n' "§d" "§vc" "§vn"
done
true
"""

    /** Potongan skrip yang ditempel setelah `cd <folder>`. */
    fun oneProbe(): String = ONE_PROBE.replace(D, '$')

    /** [lsTargets] = argumen `ls` yang sudah di-quote shell untuk target folder proyek. */
    fun dirProbe(lsTargets: String): String = DIR_PROBE.replace(D, '$').replace("%LS%", lsTargets)

    /** Baca `KITA_VERSION|code|name` dari keluaran skrip folder tunggal; null bila tidak ada barisnya. */
    fun parseOne(out: String): FolderVersion? {
        for (raw in out.lineSequence()) {
            val l = raw.trim()
            if (!l.startsWith(PREFIX_ONE)) continue
            val f = l.removePrefix(PREFIX_ONE).split('|', limit = 2)
            if (f.size != 2) continue
            return FolderVersion(f[1].trim().ifEmpty { null }, f[0].trim().toLongOrNull())
        }
        return null
    }

    /** Baca `KITA_DIRVER|dir|code|name`; kunci hasil = path relatif terhadap [root] tanpa garis miring akhir. */
    fun parseDirs(lines: List<String>, root: String): Map<String, FolderVersion> {
        val r = root.trimEnd('/')
        val out = LinkedHashMap<String, FolderVersion>()
        for (raw in lines) {
            val l = raw.trim()
            if (!l.startsWith(PREFIX_DIR)) continue
            val f = l.removePrefix(PREFIX_DIR).split('|', limit = 3)
            if (f.size != 3) continue
            val dir = f[0]
            if (!dir.startsWith("$r/")) continue
            val rel = dir.removePrefix("$r/").trimEnd('/')
            if (rel.isEmpty() || rel.contains("..")) continue
            out[rel] = FolderVersion(f[2].trim().ifEmpty { null }, f[1].trim().toLongOrNull())
        }
        return out
    }

    fun compare(installed: AppVersion, folder: FolderVersion?): VersionRel {
        val c = folder?.code ?: return VersionRel.UNKNOWN
        if (installed.code <= 0L) return VersionRel.UNKNOWN // -1 = versi terpasang tidak terbaca
        return when {
            c == installed.code -> VersionRel.SAME
            c < installed.code -> VersionRel.FOLDER_OLDER
            else -> VersionRel.FOLDER_NEWER
        }
    }

    fun label(name: String?, code: Long?): String = (name ?: "?") + " (kode " + (code?.toString() ?: "?") + ")"

    /** Tanda pendek per proyek untuk blok konteks; null bila sama atau tidak diketahui. */
    fun tag(installed: AppVersion, folder: FolderVersion?): String? = when (compare(installed, folder)) {
        VersionRel.FOLDER_OLDER -> "LEBIH LAMA dari terpasang"
        VersionRel.FOLDER_NEWER -> "lebih baru dari terpasang"
        else -> null
    }

    /** Peringatan satu kalimat untuk validate_project; null bila sama atau tidak diketahui. */
    fun message(installed: AppVersion, folder: FolderVersion?): String? {
        val f = folder ?: return null
        val inst = label(installed.name, installed.code)
        val fol = label(f.name, f.code)
        return when (compare(installed, folder)) {
            VersionRel.FOLDER_OLDER ->
                "VERSI BERBEDA: folder ini $fol LEBIH LAMA dari aplikasi terpasang $inst. " +
                    "Isinya bukan aplikasi yang berjalan di HP; jangan menyimpulkan keadaan aplikasi terpasang darinya. " +
                    "Tanyakan folder mana yang benar atau minta pengguna mengimpor source terbaru."
            VersionRel.FOLDER_NEWER ->
                "Versi folder ini $fol lebih baru dari aplikasi terpasang $inst (wajar bila ini hasil upgrade yang belum dipasang)."
            else -> null
        }
    }

    /** Temuan untuk self_audit dari metadata `version_code`/`version_name` hasil kita-self.sh; null bila sama atau tidak diketahui. */
    fun finding(meta: Map<String, String>, installed: AppVersion): Finding? {
        val f = FolderVersion(meta["version_name"]?.trim()?.ifEmpty { null }, meta["version_code"]?.trim()?.toLongOrNull())
        val msg = message(installed, f) ?: return null
        return when (compare(installed, f)) {
            VersionRel.FOLDER_OLDER -> Finding(Sev.HIGH, "V01", "app/build.gradle.kts", msg)
            else -> Finding(Sev.INFO, "V02", "app/build.gradle.kts", msg)
        }
    }
}
