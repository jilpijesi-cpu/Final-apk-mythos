package com.build.mythos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGuardTest {
    @Test fun verificationNudgeMemperingatkanJikaKodeDiubahTanpaBuildSesudahnya() {
        val steps = listOf(
            StepRecord("write_file", "Menulis kode", "app/src/main/java/A.kt", ActivityKind.OTHER, StepState.OK, 0L)
        )
        val nudge = verificationNudge(steps)
        assertNotNull(nudge)
        assertTrue(nudge!!.startsWith(SYS_PREFIX))

        val stepsWithBuild = steps + StepRecord("build_apk", "Membangun APK", ".", ActivityKind.BUILD, StepState.OK, 0L)
        assertNull(verificationNudge(stepsWithBuild))
    }

    @Test fun verificationNudgeMemperingatkanJikaBuildTerakhirGagalDanMenghormatiBatasKesempatan() {
        val failedBuild = listOf(
            StepRecord("build_apk", "Membangun APK", ".", ActivityKind.BUILD, StepState.FAIL, 0L)
        )
        val first = verificationNudge(failedBuild, lastFailOut = "error: unresolved reference", nudgesUsed = 0)
        assertNotNull(first)
        assertTrue(first!!.startsWith(SYS_PREFIX))
        assertTrue(first.contains("GAGAL"))
        assertFalse(first.contains("kesempatan terakhir"))

        val finalNudge = verificationNudge(failedBuild, lastFailOut = "error: unresolved reference", nudgesUsed = MAX_VERIFY_NUDGES - 1)
        assertNotNull(finalNudge)
        assertTrue(finalNudge!!.contains("kesempatan terakhir"))

        val recovered = failedBuild + StepRecord("build_apk", "Membangun APK", ".", ActivityKind.BUILD, StepState.OK, 0L)
        assertNull(verificationNudge(recovered))
    }

    @Test fun fileNonKodeTidakMemicuVerificationNudge() {
        assertTrue(isCodeFile("Main.kt"))
        assertTrue(isCodeFile("build.gradle.kts"))
        assertFalse(isCodeFile("README.md"))
        assertFalse(isCodeFile("config.json"))
        assertFalse(isCodeFile("catatan.txt"))
        val steps = listOf(
            StepRecord("write_file", "Menulis dokumen", "README.md", ActivityKind.OTHER, StepState.OK, 0L)
        )
        assertNull(verificationNudge(steps))
    }

    @Test fun timeoutResultDiawaliExit124AgarDikenaliPengaman() {
        val out = timeoutResult(15L)
        assertTrue(out.startsWith("exit=124 Timeout:"))
        assertTrue(out.contains("15 detik"))
    }
}
