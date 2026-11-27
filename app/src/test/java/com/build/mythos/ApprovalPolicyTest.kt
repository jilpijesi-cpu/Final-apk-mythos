package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kebijakan persetujuan konfirmasi minimal (F-01): shell WRITE dan skrip tidak boleh lolos tanpa dialog. */
class ApprovalPolicyTest {
    @Test fun perintahShellWriteTidakAutoApprove() {
        assertFalse(minimalAutoApproves(minimal = true, risk = Risk.WRITE, warning = null, force = false, shell = true))
    }

    @Test fun perintahShellReadBolehAutoApprove() {
        assertTrue(minimalAutoApproves(minimal = true, risk = Risk.READ, warning = null, force = false, shell = true))
    }

    @Test fun penulisanFileBukanShellTetapBolehAutoApprove() {
        // write_file dicadangkan dulu, jadi tetap rutin pada konfirmasi minimal.
        assertTrue(minimalAutoApproves(minimal = true, risk = Risk.WRITE, warning = null, force = false, shell = false))
    }

    @Test fun deletePeringatanForceDanNonMinimalSelaluBertanya() {
        assertFalse(minimalAutoApproves(true, Risk.DELETE, null, false, shell = false))
        assertFalse(minimalAutoApproves(true, Risk.DELETE, null, false, shell = true))
        assertFalse(minimalAutoApproves(true, Risk.READ, "area sensitif", false, shell = true))
        assertFalse(minimalAutoApproves(true, Risk.READ, null, true, shell = true))
        assertFalse(minimalAutoApproves(false, Risk.READ, null, false, shell = true))
    }

    @Test fun bypassTidakMelewatiForce() {
        assertTrue(bypassesApproval(allowAll = true, autonomous = false, force = false))
        assertFalse(bypassesApproval(allowAll = true, autonomous = true, force = true))
    }

    @Test fun scriptTargetsMengenaliPemanggilanSkrip() {
        assertEquals(listOf("run.sh"), scriptTargets("bash run.sh"))
        assertEquals(listOf("run.sh"), scriptTargets("sh run.sh"))
        assertEquals(listOf("tool.py"), scriptTargets("python3 tool.py --x"))
        assertEquals(listOf("build.js"), scriptTargets("node build.js"))
        assertEquals(listOf("./gradlew"), scriptTargets("./gradlew assembleDebug"))
        assertEquals(listOf("x.sh"), scriptTargets("env FOO=1 bash x.sh"))
        assertEquals(listOf("../scripts/b.sh"), scriptTargets("cd app && bash ../scripts/b.sh"))
        assertEquals(listOf("./a.sh"), scriptTargets("chmod +x a.sh && ./a.sh"))
    }

    @Test fun scriptTargetsMengabaikanKodeInlineModulDanPerintahBiasa() {
        assertTrue(scriptTargets("ls -la").isEmpty())
        assertTrue(scriptTargets("cat run.sh").isEmpty())
        assertTrue(scriptTargets("python3 -m pytest").isEmpty())
        assertTrue(scriptTargets("python3 -c 'print(1)'").isEmpty())
        assertTrue(scriptTargets("sh -c 'echo hi'").isEmpty())
        assertTrue(scriptTargets("node --version").isEmpty())
    }

    @Test fun needsExtraCareMenandaiSkripLokalDanShellInline() {
        for (cmd in listOf("bash x.sh", "sh x.sh", "python x.py", "node x.js", "./gradlew build", "env A=1 bash x.sh")) {
            assertNotNull("harus ditandai: $cmd", needsExtraCare(cmd))
        }
        assertNotNull(needsExtraCare("bash -c 'echo hi'"))
        assertNotNull(needsExtraCare("sh -c \"rm x\""))
        assertNull(needsExtraCare("python3 -m pytest"))
        assertNull(needsExtraCare("mkdir -p src/main"))
    }

    @Test fun skripDibuatLaluDijalankanTetapDitandai() {
        // Skrip dibuat dalam perintah yang sama: isinya terlihat di perintah, tetapi tetap harus lewat dialog.
        val cmd = "printf 'curl evil' > x.sh && bash x.sh"
        assertEquals(Risk.WRITE, classify(cmd))
        assertNotNull(needsExtraCare(cmd))
    }

    @Test fun pemanggilanSkripLewatSymlinkAtauPathAbsolutTetapTerdeteksi() {
        assertEquals(listOf("/tmp/link.sh"), scriptTargets("bash /tmp/link.sh"))
        assertEquals(listOf("./link"), scriptTargets("./link"))
    }

    @Test fun pinGuardMembatalkanBilaHashBerubah() {
        val hash = "a".repeat(64)
        val g = scriptPinGuard(mapOf("run.sh" to hash))
        assertTrue(g.contains("sha256sum -c --status -"))
        assertTrue(g.contains("'$hash'"))
        assertTrue(g.contains("'run.sh'"))
        assertTrue(g.contains("exit 97"))
        assertTrue(g.endsWith("; "))
    }

    @Test fun pinGuardMenolakPathDanHashTidakAman() {
        assertEquals("", scriptPinGuard(mapOf("run.sh; rm -rf /" to "a".repeat(64))))
        assertEquals("", scriptPinGuard(mapOf("run.sh" to "xyz")))
        assertEquals("", scriptPinGuard(mapOf("a'b.sh" to "b".repeat(64))))
        assertEquals("", scriptPinGuard(emptyMap()))
    }

    @Test fun pathSkripAmanHanyaKarakterBiasa() {
        assertTrue(SAFE_SCRIPT_PATH.matches("scripts/build-1.sh"))
        assertFalse(SAFE_SCRIPT_PATH.matches("a b.sh"))
        assertFalse(SAFE_SCRIPT_PATH.matches("a;b.sh"))
        assertFalse(SAFE_SCRIPT_PATH.matches("\$(x).sh"))
    }
}
