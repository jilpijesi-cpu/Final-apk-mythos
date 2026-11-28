package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpgradeTest {
    private fun sampleDiffOutput(
        versionCode: Long = 59L,
        testsNow: Int = 400,
        testsBase: Int = 400,
        changedPath: String = "app/src/main/java/com/build/mythos/Ui.kt",
        apkStatus: String = "/tmp/app-debug.apk|fresh",
        testStatus: String = "fresh|400|0",
        extraAudit: String = ""
    ): String = """
        KITA_BASEHASH=abc123hash
        KITA_CHG|M|$changedPath
        KITA_META|app_id|com.build.mythos
        KITA_META|version_code|$versionCode
        KITA_META|packs_source|yes
        KITA_META|tests|$testsNow
        $extraAudit
        KITA_AUDIT_DONE
        KITA_BASE_META|app_id|com.build.mythos
        KITA_BASE_META|version_code|58
        KITA_BASE_META|tests|$testsBase
        KITA_BASE_AUDIT_DONE
        KITA_APK|$apkStatus
        KITA_TESTS|$testStatus
        KITA_DIFF=ok
    """.trimIndent()

    @Test fun evaluateUpgradeMeloloskanPerubahanAmanDenganVersiBaru() {
        val diff = parseDiff(sampleDiffOutput())
        val gate = evaluateUpgrade(UpgradeInput(58L, "com.build.mythos", diff, "abc123hash"))
        assertEquals(Verdict.PASS, gate.verdict)
        assertTrue(gate.blockers.isEmpty())
    }

    @Test fun evaluateUpgradeMemintaPersetujuanJikaMenyentuhProtectedCore() {
        val diff = parseDiff(sampleDiffOutput(changedPath = "app/src/main/java/com/build/mythos/CommandPolicy.kt"))
        assertTrue(ProtectedCore.isProtected("app/src/main/java/com/build/mythos/CommandPolicy.kt"))
        val gate = evaluateUpgrade(UpgradeInput(58L, "com.build.mythos", diff, "abc123hash"))
        assertEquals(Verdict.NEEDS_USER, gate.verdict)
        assertTrue(gate.reviews.any { it.contains("CommandPolicy.kt") })
    }

    @Test fun evaluateUpgradeMemblokirJikaVersionCodeBelumNaikAtauPengamanHilang() {
        val diffSameVer = parseDiff(sampleDiffOutput(versionCode = 58L))
        val gateSameVer = evaluateUpgrade(UpgradeInput(58L, "com.build.mythos", diffSameVer, "abc123hash"))
        assertEquals(Verdict.BLOCK, gateSameVer.verdict)

        val diffMissingGuard = parseDiff(
            sampleDiffOutput(extraAudit = "KITA_AUDIT|high|S08|CommandPolicy.kt|berkas pengaman hilang: CommandPolicy.kt")
        )
        val gateGuard = evaluateUpgrade(UpgradeInput(58L, "com.build.mythos", diffMissingGuard, "abc123hash"))
        assertEquals(Verdict.BLOCK, gateGuard.verdict)
    }

    @Test fun nextVersionCodeDanRollForwardNameSelaluMaju() {
        assertEquals(59L, nextVersionCode(58L, 58L))
        assertEquals(61L, nextVersionCode(58L, 60L))
        assertEquals("2.43-r59", rollForwardName("2.43-r58", 59L))
        assertFalse(ProtectedCore.isProtected("app/src/main/java/com/build/mythos/Ui.kt"))
    }
}
