package com.build.mythos

// Diagnosis otomatis: mengenali penyebab umum dari keluaran perintah yang gagal, lalu memberi AI hipotesis akar
// masalah dan langkah pemeriksaan/perbaikan yang berbeda dari sekadar mengulang perintah. Logika murni, bisa diuji unit.

internal class Diagnosis(val cause: String, val fix: String)

private class Rule(val pattern: Regex, val cause: String, val fix: String, val sandboxFix: String? = null)

private fun rule(p: String, cause: String, fix: String, sandboxFix: String? = null) =
    Rule(Regex(p, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)), cause, fix, sandboxFix)

private val rules = listOf(
    rule(
        """Illegal option -o pipefail|\[\[: not found|Bad substitution""",
        "skrip memakai fitur bash tetapi dijalankan oleh sh/dash",
        "Jalankan dengan bash: `bash skrip.sh` atau `bash -c '...'`. Jangan memakai [[ ]], array, atau pipefail di bawah sh."
    ),
    rule(
        """aapt2.*(daemon|startup failed|exec format|cannot execute|syntax error)|exec format error.*aapt2|AAPT2 .*Daemon startup failed""",
        "aapt2 bawaan Gradle berarsitektur x86_64 dan tidak bisa jalan di HP (aarch64)",
        "Cara paling mudah: bangun lewat tool build_apk (di GitHub Actions, tanpa aapt2 lokal). Manual di Termux: pkg install aapt2, lalu tambahkan -Pandroid.aapt2FromMavenOverride=\$(command -v aapt2) pada perintah gradle. Jangan jalankan ulang build sebelum itu.",
        "Cara paling mudah: bangun lewat tool build_apk (di GitHub Actions, tanpa aapt2 lokal). Manual di sandbox Debian: apt-get install -y aapt, lalu tambahkan -Pandroid.aapt2FromMavenOverride=\$(command -v aapt2) pada perintah gradle. pkg dan \$PREFIX tidak ada di sandbox."
    ),
    rule(
        """java\.lang\.OutOfMemoryError|Java heap space|GC overhead limit|Metaspace|Daemon .*(disappeared|was killed)|^Killed${'$'}""",
        "memori habis (JVM atau proses dibunuh Android)",
        "Cek memori kosong (free -m). Bangun lewat build_apk (di GitHub Actions, tidak memakai RAM HP); bila tetap lokal, tutup aplikasi lain, dan jalankan satu task saja. " +
            "Bila log hanya berakhir 'Killed' (signal 9) tanpa OutOfMemoryError, kemungkinan Android mematikan proses latar belakang Termux (Android 12+): minta pengguna menjaga layar menyala dan menutup aplikasi lain; pembatas proses hanya bisa dimatikan pengguna lewat opsi pengembang atau adb."
    ),
    rule(
        """Could not resolve|Could not GET|Could not download|UnknownHostException|Connection (timed out|reset|refused)|SSLHandshake|Read timed out|Network is unreachable|Temporary failure in name resolution""",
        "unduhan dependensi/Gradle gagal karena jaringan, bukan karena kode",
        "Cek koneksi (ping/curl -I https://repo.maven.apache.org). Jalankan ulang dengan --offline bila dependensi sudah ada di cache; kalau belum, tunggu koneksi stabil lalu ulangi sekali. Jangan ubah kode untuk error ini."
    ),
    rule(
        """HTTP 40[13]\b|Bad credentials|Resource not accessible|Token GitHub .*(tidak valid|belum diisi)|SAML enforcement|GitHub menolak""",
        "GitHub menolak kredensial (HTTP 401 = token salah atau kedaluwarsa; 403 = token valid tetapi izinnya kurang, kena batas laju, atau butuh otorisasi SSO), bukan masalah kode",
        "Jangan ubah kode atau workflow untuk error ini. Token di Pengaturan harus berupa token klasik dengan scope repo dan workflow, atau token fine-grained dengan akses ke repo itu: Contents (baca/tulis), Actions (baca/tulis), Workflows (baca/tulis), Metadata (baca). Untuk organisasi dengan SSO, token harus diotorisasi ke organisasi. Cek tanpa menampilkan token: gh auth status atau gh api -i user | head -n 20 (lihat header x-oauth-scopes). Lalu minta pengguna memperbarui token di Pengaturan dan panggil build_apk lagi."
    ),
    rule(
        """SDK location not found|ANDROID_HOME|sdk\.dir|Failed to install the following Android SDK packages|licen[cs]es? .*not accepted|platforms;android""",
        "Android SDK tidak ditemukan atau lisensinya belum diterima",
        "Perbaiki di tempat: cek ls ~/android-sdk; bila ada, jalankan echo \"sdk.dir=\$HOME/android-sdk\" > local.properties dan export ANDROID_HOME=\$HOME/android-sdk. Bila belum ada, ikuti build-termux.sh: pasang cmdline-tools, lalu yes | sdkmanager --licenses dan sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\". Alternatif: tool build_apk (SDK ada di GitHub Actions). Error ini bisa diperbaiki, bukan hambatan permanen."
    ),
    rule(
        """Timeout waiting to lock|already in use|lock .*held by|Gradle .*is busy""",
        "ada proses Gradle lain yang memegang kunci",
        "Jalankan ./gradlew --stop dan pkill -f gradle, tunggu beberapa detik, lalu ulangi sekali."
    ),
    rule(
        """No space left on device|ENOSPC""",
        "penyimpanan penuh",
        "Cek df -h dan hapus cache/artefak (rm -rf app/build .gradle/caches yang tidak perlu, ~/.gradle/caches/transforms-*) sebelum mencoba lagi."
    ),
    rule(
        """command not found|: not found${'$'}|No such file or directory.*(gradlew|bin/)""",
        "perintah atau file yang dipanggil tidak ada",
        "Periksa dengan which/ls apakah paketnya terpasang (pkg install ...) dan path-nya benar; untuk ./gradlew jalankan chmod +x gradlew dan pastikan berada di folder proyek.",
        "Periksa dengan which/ls di dalam sandbox. Pasang paket yang kurang dengan apt-get install -y <paket> (perlu persetujuan jaringan); pkg tidak ada di sandbox. Untuk ./gradlew jalankan chmod +x gradlew di folder proyek, atau pakai build_apk."
    ),
    rule(
        """No such file or directory|cannot access '[^']*'|can't cd to|cd: .*(not found|no such)|folder (proyek )?tidak ditemukan""",
        "path salah: file atau folder tidak ada di lokasi yang dituju (path relatif dihitung dari folder kerja, bukan dari lokasi sebelumnya)",
        "Jangan menebak path. Jalankan pwd && ls untuk melihat folder kerja aktif, lalu ls projects/aktif (proyek yang dikerjakan) dan baca path reference yang tercantum pada peta path di prompt. Path relatif dihitung dari folder kerja yang dipilih; proyek ada di projects/aktif/<nama-kebab-case>. Pakai path persis seperti yang dikembalikan create_android_project."
    ),
    rule(
        """Permission denied|EACCES|Operation not permitted""",
        "izin akses ditolak",
        "Periksa izin dengan ls -l; jangan menulis ke /sdcard atau folder aplikasi lain. Jalankan termux-setup-storage bila perlu akses penyimpanan, atau pindahkan kerja ke folder proyek di home Termux.",
        "Di sandbox tidak ada akses /sdcard maupun termux-*. Bekerja hanya di workdir; periksa izin dengan ls -l dan chmod +x pada file yang perlu dieksekusi (bukan -R)."
    ),
    rule(
        """Unresolved reference|Type mismatch|Overload resolution ambiguity|e: file:|error: (cannot find symbol|incompatible types)|Expecting (a )?top level declaration""",
        "error kompilasi di kode (bukan masalah lingkungan)",
        "Baca baris 'e: file:...' pertama (file dan nomor baris), buka file itu, perbaiki akar masalahnya (import, nama, tipe), lalu build ulang. Perbaiki error pertama dulu; error lain sering turunan."
    ),
    rule(
        """Execution failed for task ':app:test|There were failing tests|tests completed, \d+ failed|AssertionError|ComparisonFailure""",
        "ada unit test yang gagal",
        "Baca laporan di app/build/reports/tests atau keluaran 'FAILED' untuk nama test dan pesannya; tentukan apakah kode atau test yang salah, perbaiki, lalu jalankan HANYA test itu (--tests nama)."
    ),
    rule(
        """Unsupported (class file major version|Java version)|requires Java \d+|JAVA_HOME is not set|invalid source release""",
        "versi Java tidak cocok",
        "Cek java -version dan JAVA_HOME; proyek butuh JDK 17 (pkg install openjdk-17). Atau bangun lewat build_apk (di GitHub Actions, tanpa JDK lokal).",
        "Cek java -version; proyek butuh JDK 17 (apt-get install -y openjdk-17-jdk-headless). Atau bangun lewat build_apk (di GitHub Actions, tanpa JDK lokal)."
    )
)

/** [sandbox] true bila perintah berjalan di sandbox Debian: saran memakai apt dan path tamu, bukan pkg dan Termux. */
internal fun diagnoseFailure(command: String, output: String, sandbox: Boolean = false): Diagnosis? {
    val text = output.takeLast(6000)
    for (r in rules) {
        if (r.pattern.containsMatchIn(text)) return Diagnosis(r.cause, if (sandbox) r.sandboxFix ?: r.fix else r.fix)
    }
    if (Regex("""exit=124|timeout|dihentikan karena waktu""", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
        Regex("""gradle|build""", RegexOption.IGNORE_CASE).containsMatchIn(command)
    ) {
        return Diagnosis(
            "build melewati batas waktu (kemungkinan unduhan dependensi, memori kecil, atau build dingin tanpa daemon)",
            "Prosesnya sudah dimatikan oleh batas waktu, tetapi unduhan dan cache yang selesai tersimpan. Panggil build_apk lagi (melanjutkan run yang sama); jangan mengubah kode karena timeout. Bila berulang tanpa kemajuan, cek free -m dan koneksi, lalu periksa Actions di GitHub."
        )
    }
    return null
}

/** Ditambahkan ke hasil alat setelah beberapa kegagalan beruntun: paksa berhenti menebak dan diagnosis dulu. */
internal const val DEBUG_PROTOCOL =
    "\n\n[Protokol pemecahan masalah: sudah beberapa kali gagal berturut-turut. JANGAN mengulang perintah yang sama atau menebak perbaikan. " +
        "1) Kutip baris error pertama yang sebenarnya (bukan ringkasan). 2) Tulis satu hipotesis penyebab. " +
        "3) Buktikan dengan perintah diagnosis yang BERBEDA (baca log, cek versi/memori/jaringan/file). 4) Terapkan perbaikan yang menyasar penyebab itu. " +
        "5) Menyiapkan lingkungan build di HP wajar melewati beberapa kegagalan: lanjutkan selama tiap percobaan menyasar penyebab yang berbeda dan ada kemajuan. " +
        "6) Jika tiga perbaikan berbeda sudah gagal tanpa kemajuan, hentikan dan jelaskan penyebab, bukti, dan opsi ke pengguna (termasuk build lewat GitHub Actions) lewat jawaban akhir atau ask_user.]"

internal const val FAIL_STREAK_PROTOCOL_AT = 3
