package com.build.mythos

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildApkTest {
    @Test fun skripBuildApkIdentikDenganToolsBuildGithubSh() {
        val scriptFile = requireNotNull(
            listOf(File("../tools/build-github.sh"), File("tools/build-github.sh")).firstOrNull { it.isFile }
        ) { "tools/build-github.sh tidak ditemukan dari direktori kerja tes" }
        assertEquals(scriptFile.readText().trim(), BuildApk.SCRIPT.trim())
    }

    @Test fun setupCommandTidakPernahMemuatNilaiToken() {
        val token = "ghp_rahasia1234567890"
        val cmd = BuildApk.setupCommand("proyek", "owner", "repo", true, useStdinToken = true)
        // Token tidak boleh ada di string perintah sama sekali (bukan argumen, bukan printf, bukan pipe).
        assertFalse(cmd.contains(token))
        assertFalse(cmd.contains("ghp_"))
        assertFalse(cmd.contains("printf '%s\\n'"))
        // Skrip diberi argumen '-' agar membaca token dari stdin.
        assertTrue(cmd.endsWith("bash .kita-build.sh 'proyek' 'owner' 'repo' 'true' '-'"))
    }

    @Test fun stdinForHanyaMengirimTokenSatuBaris() {
        assertEquals("ghp_abc\n", BuildApk.stdinFor("  ghp_abc  "))
        assertNull(BuildApk.stdinFor(""))
        assertNull(BuildApk.stdinFor("   "))
    }

    @Test fun tanpaTokenPerintahTidakMemakaiStdin() {
        val cmd = BuildApk.setupCommand("proyek")
        assertFalse(cmd.contains("'-'"))
        assertTrue(cmd.endsWith("bash .kita-build.sh 'proyek'"))
    }

    @Test fun parseHanyaMenerimaPathApkYangAman() {
        val okRes = BuildApk.parse("KITA_APK=/data/data/com.termux/files/home/app/build/outputs/apk/debug/app-debug.apk\nKITA_BUILD=ok")
        assertTrue(okRes.ok)
        assertEquals("/data/data/com.termux/files/home/app/build/outputs/apk/debug/app-debug.apk", okRes.apk)

        val badRes = BuildApk.parse("KITA_APK=/tmp/jahat;rm -rf /\nKITA_BUILD=gagal")
        assertFalse(badRes.ok)
        assertNull(badRes.apk)
    }

    @Test fun artifactOnlyBukanSiap() {
        val out = "KITA_APK=/data/data/com.termux/files/home/app/build/outputs/apk/debug/app-debug.apk\n" +
            "KITA_ARTIFACT=available\nKITA_WORKFLOW=failure\nKITA_TESTS=failed\nKITA_BUILD=artifact-only"
        val r = BuildApk.parse(out)
        assertFalse(r.ok)
        assertTrue(r.artifactOnly)
        assertEquals("failure", r.workflow)
        assertEquals("failed", r.tests)
        assertNotNull(r.apk)
    }

    @Test fun penandaOkPalsuTidakMengalahkanArtifactOnly() {
        val r = BuildApk.parse("KITA_BUILD=ok\nKITA_BUILD=artifact-only")
        assertFalse(r.ok)
        assertTrue(r.artifactOnly)
    }

    @Test fun workflowSuksesDenganTesLulusAdalahSiap() {
        val r = BuildApk.parse("KITA_APK=/data/a.apk\nKITA_ARTIFACT=available\nKITA_WORKFLOW=success\nKITA_TESTS=passed\nKITA_BUILD=ok")
        assertTrue(r.ok)
        assertFalse(r.artifactOnly)
        assertEquals("passed", r.tests)
    }

    @Test fun statusDenganKarakterAneharDitolak() {
        val r = BuildApk.parse("KITA_WORKFLOW=success; rm -rf /\nKITA_TESTS=Passed!\nKITA_BUILD=ok")
        assertNull(r.workflow)
        assertNull(r.tests)
    }

    @Test fun skripMemisahkanStatusArtifactWorkflowDanTes() {
        val sh = BuildApk.SCRIPT
        assertTrue(sh.contains("KITA_ARTIFACT=available"))
        assertTrue(sh.contains("KITA_WORKFLOW="))
        assertTrue(sh.contains("KITA_TESTS="))
        assertTrue(sh.contains("KITA_BUILD=artifact-only"))
    }
}
