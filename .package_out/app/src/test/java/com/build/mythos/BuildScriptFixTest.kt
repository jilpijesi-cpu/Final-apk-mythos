package com.build.mythos

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildScriptFixTest {
    private fun repoFile(rel: String): File =
        listOf(File("../$rel"), File(rel)).first { it.exists() }

    @Test fun skripMengaturIdentitasGitLokalBilaKosong() {
        val s = BuildApk.SCRIPT
        assertTrue(s.contains("git config user.name \"MYTHOS Agent\""))
        assertTrue(s.contains("git config user.email"))
        assertFalse(s.contains("git config --global"))
    }

    @Test fun kegagalanCommitTidakLagiDisembunyikan() {
        val s = BuildApk.SCRIPT
        assertFalse(s.contains("git commit -qm \"build dari agen\" >/dev/null 2>&1 || true"))
        assertTrue(s.contains("git commit gagal:"))
        assertTrue(s.contains("repo belum punya commit"))
    }

    @Test fun pushMemakaiUpstreamDanMenampilkanGalatAsli() {
        val s = BuildApk.SCRIPT
        assertTrue(s.contains("do_push git push -q -u origin HEAD"))
        assertFalse(s.contains("git push -q 2>&1 ||"))
        assertTrue(s.contains("scope workflow"))
    }

    @Test fun peringatanScopeWorkflowAda() {
        assertTrue(BuildApk.SCRIPT.contains("PERINGATAN: token klasik tidak punya scope workflow"))
    }

    @Test fun ketigaSalinanSkripIdentik() {
        val a = repoFile("tools/build-github.sh").readText().trim()
        val b = repoFile("app/src/main/assets/berangkas/skrip/build-github.sh").readText().trim()
        assertEquals(a, b)
        assertEquals(a, BuildApk.SCRIPT.trim())
    }

    @Test fun skripBuildMengunduhArtifactMythosDebug() {
        assertTrue(BuildApk.SCRIPT.contains("-n mythos-debug"))
    }

    @Test fun templateWorkflowBerangkasMemakaiAllowUnsignedTanpaRahasia() {
        val y = repoFile("app/src/main/assets/berangkas/templat/build-apk.yml").readText()
        assertTrue(y.contains("GRADLE_EXTRA=-PallowUnsigned=true"))
        assertTrue(y.contains("./gradlew assembleDebug --no-daemon \${GRADLE_EXTRA:-}"))
    }
}
