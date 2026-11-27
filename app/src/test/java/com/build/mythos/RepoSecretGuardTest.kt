package com.build.mythos

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Penjaga rahasia untuk Git (F-02) dan source bundle APK (F-03), serta pengerasan supply chain (F-08).
 * Tes membaca berkas proyek yang SAMA yang dipakai build, jadi daftar yang tidak lengkap membuat tes ini merah.
 */
class RepoSecretGuardTest {
    private fun repoFile(vararg rel: String): File? =
        rel.flatMap { listOf(File("../$it"), File(it)) }.firstOrNull { it.isFile }

    private val bashAvailable: Boolean by lazy {
        try {
            val p = ProcessBuilder("bash", "-c", "exit 0").redirectErrorStream(true).start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /** Ambil fungsi is_secret_path dari tools/build-github.sh apa adanya, lalu jalankan di bash. */
    private fun isSecretPath(path: String): Boolean {
        val file = repoFile("tools/build-github.sh")
        assertNotNull("tools/build-github.sh wajib ditemukan", file)
        val script = file!!.readLines()
        val start = script.indexOfFirst { it.startsWith("is_secret_path() {") }
        assertTrue("Fungsi is_secret_path tidak ditemukan di tools/build-github.sh", start >= 0)
        val end = (start + 1 until script.size).firstOrNull { script[it] == "}" } ?: -1
        assertTrue("Penutup fungsi is_secret_path tidak ditemukan", end > start)
        val fn = script.subList(start, end + 1).joinToString("\n")
        val p = ProcessBuilder("bash", "-c", fn + "\nis_secret_path \"\$1\"", "_", path).redirectErrorStream(true).start()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        return p.exitValue() == 0
    }

    @Test fun berkasRahasiaDikenaliOlehSkripBuildGithub() {
        assumeTrue("bash tidak tersedia di lingkungan tes", bashAvailable)
        assertNotNull("tools/build-github.sh wajib ditemukan", repoFile("tools/build-github.sh"))
        val rahasia = listOf(
            "signing.p12", "app/signing-key.p12", ".env", "app/.env.local", "prod.env", "id_rsa", "id_ed25519", "deploy.pem",
            "server.key", "service-account.json", "config/service-account-prod.json", "credentials.json", "gcp-credentials.json",
            ".ssh/config", "home/.aws/credentials", ".netrc", ".npmrc", "keystore.properties", "release.jks", "my.keystore"
        )
        for (f in rahasia) assertTrue("harus dianggap rahasia: $f", isSecretPath(f))
    }

    @Test fun berkasBiasaTidakSalahDitandai() {
        assumeTrue("bash tidak tersedia di lingkungan tes", bashAvailable)
        assertNotNull("tools/build-github.sh wajib ditemukan", repoFile("tools/build-github.sh"))
        val biasa = listOf(
            "README.md", "app/src/main/java/com/build/mythos/Secret.kt", "keystore.properties.example",
            ".env.example", "app/src/main/res/values/strings.xml", "gradle/wrapper/gradle-wrapper.properties"
        )
        for (f in biasa) assertFalse("tidak boleh dianggap rahasia: $f", isSecretPath(f))
    }

    @Test fun packSourceMengecualikanSemuaPolaRahasia() {
        val gradle = repoFile("app/build.gradle.kts", "build.gradle.kts")
        assertNotNull("app/build.gradle.kts wajib ditemukan", gradle)
        val text = gradle!!.readText()
        val wajib = listOf(
            "**/*.p12", "**/*.jks", "**/*.keystore", "**/*.pem", "**/*.key", "**/*.pfx", "**/.env", "**/.env.*", "**/*.env",
            "**/.ssh/**", "**/.aws/**", "**/id_rsa*", "**/id_ed25519*", "**/.netrc", "**/credentials.json",
            "**/service-account*.json", "keystore.properties", "local.properties"
        )
        for (pola in wajib) assertTrue("packSource harus mengecualikan $pola", text.contains("\"$pola\""))
        // Pagar kedua yang gagal tertutup pada nama dan isi arsip.
        assertTrue(text.contains("Penjaga rahasia"))
        assertTrue(text.contains("PRIVATE KEY"))
    }

    @Test fun gitignoreMencakupRahasiaUmum() {
        val f = repoFile(".gitignore")
        assertNotNull(".gitignore wajib ditemukan", f)
        val lines = f!!.readLines().map { it.trim() }
        for (p in listOf("keystore.properties", "*.jks", "*.keystore", "*.p12", "*.pem", "*.key", ".env", ".env.*", ".ssh/", ".aws/")) {
            assertTrue(".gitignore harus memuat $p", p in lines)
        }
    }

    @Test fun skripBuildGithubMemakaiPenjagaRahasiaDanTidakAddAllButa() {
        val f = repoFile("tools/build-github.sh")
        assertNotNull("tools/build-github.sh wajib ditemukan", f)
        val sh = f!!.readText()
        assertTrue(sh.contains("is_secret_path"))
        assertTrue(sh.contains("git grep --cached"))
        assertTrue(sh.contains("PRIVATE KEY"))
        // fail tertutup: push dibatalkan lewat fail(), bukan hanya peringatan
        assertTrue(sh.contains("push dibatalkan"))
        // pemindaian harus terjadi SEBELUM commit/push
        assertTrue(sh.indexOf("git grep --cached") < sh.indexOf("git commit -qm"))
        assertTrue(sh.indexOf("git commit -qm") < sh.indexOf("do_push git push"))
    }

    @Test fun tokenTidakPernahDisisipkanKeSkripBuild() {
        assertFalse(BuildApk.SCRIPT.contains("printf '%s\\n' \"\$MYTHOS_GH_TOKEN\""))
        // Skrip hanya MEMBACA token dari stdin saat diberi argumen '-'.
        assertTrue(BuildApk.SCRIPT.contains("IFS= read -r MYTHOS_GH_TOKEN"))
    }

    @Test fun gradleWrapperMemakaiChecksumDistribusi() {
        val f = repoFile("gradle/wrapper/gradle-wrapper.properties")
        assertNotNull("gradle/wrapper/gradle-wrapper.properties wajib ditemukan", f)
        val props = f!!.readLines().filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val sha = props["distributionSha256Sum"]
        assertTrue("distributionSha256Sum wajib diisi", sha != null && Regex("^[0-9a-f]{64}$").matches(sha))
        assertEquals("true", props["validateDistributionUrl"])
    }

    @Test fun githubActionsDipinKeShaCommitPenuh() {
        val f = repoFile(".github/workflows/build-apk.yml")
        assertNotNull(".github/workflows/build-apk.yml wajib ditemukan", f)
        val uses = f!!.readLines().map { it.trim() }.filter { it.startsWith("uses:") || it.startsWith("- uses:") }
        assertTrue(uses.isNotEmpty())
        val pinned = Regex("^(- )?uses: [A-Za-z0-9_./-]+@[0-9a-f]{40}( #.*)?$")
        for (u in uses) assertTrue("action harus dipin ke SHA 40 karakter: $u", pinned.matches(u))
    }

    @Test fun workflowMenguji_FixtureRahasiaPalsuDanBundleChecker() {
        val f = repoFile(".github/workflows/build-apk.yml")
        assertNotNull(".github/workflows/build-apk.yml wajib ditemukan", f)
        val y = f!!.readText()
        assertTrue(y.contains("tools/cek-source-bundle.sh"))
        assertTrue(y.contains("Penjaga rahasia"))
        assertTrue(y.contains("fixture"))
    }
}
