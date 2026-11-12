package com.mythos.app

import com.mythos.app.agent.TermuxLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/** Menjaga agar nomor versi tidak melenceng lagi dari changelog (audit v83, temuan 6). Tes dijalankan dari folder `app/`. */
class VersionConsistencyTest {
    private fun props(): Properties = Properties().apply { File("../gradle.properties").inputStream().use { load(it) } }

    @Test fun versiBerasalDariSatuSumberDanCocokDenganChangelogTerbaru() {
        val p = props()
        val code = p.getProperty("mythosVersionCode").trim().toInt()
        assertEquals("1.0.$code", p.getProperty("mythosVersionName").trim())
        val latest = File("../docs/changelog").listFiles { f -> f.name.startsWith("CHANGELOG-v") }!!
            .mapNotNull { Regex("""CHANGELOG-v(\d+)""").find(it.name)?.groupValues?.get(1)?.toInt() }.maxOrNull()!!
        assertEquals("versionCode harus sama dengan nomor changelog terbaru", latest, code)
    }

    @Test fun buildGradleTidakMenuliskanVersiKeras() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = mythosVersionCode"))
        assertTrue(gradle.contains("versionName = mythosVersionName"))
        assertFalse(Regex("""versionCode\s*=\s*\d+""").containsMatchIn(gradle))
        assertFalse(Regex("""versionName\s*=\s*"""").containsMatchIn(gradle))
    }

    @Test fun runnerCodexMemakaiPlaceholderVersiBukanAngkaKeras() {
        val src = File("src/main/assets/codex_app_server.js").readText()
        assertTrue(src.contains(TermuxLauncher.VERSION_PLACEHOLDER))
        assertFalse(Regex("""version:\s*'\d+\.\d+""").containsMatchIn(src))
        val out = TermuxLauncher.withAppVersion(src, "1.0.83")
        assertFalse(out.contains(TermuxLauncher.VERSION_PLACEHOLDER))
        assertTrue(out.contains("version: '1.0.83'"))
        // versionName aneh tidak boleh menyusup ke kode JS
        assertTrue(TermuxLauncher.withAppVersion(src, "1'); process.exit(); ('").contains("version: '0'"))
    }

    @Test fun ciMemvalidasiWrapperGradle() {
        val ci = File("../.github/workflows/ci.yml").readText()
        assertTrue(ci.contains("gradle/actions/wrapper-validation@"))
    }
}
