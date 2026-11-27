package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPolicyTest {
    @Test fun sensitiveReasonMendeteksiKredensialCliDanKunciRahasia() {
        assertNotNull(sensitiveReason("cat ~/.gemini/oauth_creds.json"))
        assertNotNull(sensitiveReason("cat ~/.codex/auth.json"))
        assertNotNull(sensitiveReason("cat ~/.claude/.credentials.json"))
        assertNotNull(sensitiveReason("cat ~/.kita-secrets/github_token"))
        assertNotNull(sensitiveReason("cat keystore.properties"))
        assertNull(sensitiveReason("cat app/src/main/java/com/build/mythos/MainActivity.kt"))
    }

    @Test fun needsExtraCareMendeteksiPembungkusJaringanDanInlineCode() {
        assertNotNull(needsExtraCare("curl https://example.com"))
        assertNotNull(needsExtraCare("busybox wget https://example.com/a.sh"))
        assertNotNull(needsExtraCare("timeout 10 curl -s https://example.com"))
        assertNotNull(needsExtraCare("nice -n 5 curl https://example.com"))
        assertNotNull(needsExtraCare("awk 'BEGIN { system(\"id\") }'"))
        assertNotNull(needsExtraCare("python3 -c 'import os'"))
        assertNull(needsExtraCare("grep -rn curl app/src/"))
    }

    @Test fun assessCommandMemblokirPerintahBerbahayaDanKeluarWorkdir() {
        assertEquals(Decision.SAFE, assessCommand("ls -la src").decision)
        assertEquals(Decision.BLOCKED, assessCommand("cd /etc").decision)
        assertEquals(Decision.BLOCKED, assessCommand("echo x > ../luar.txt").decision)
        assertEquals(Decision.BLOCKED, assessCommand("rm -rf /root/sandbox/work").decision)
    }

    @Test fun assessCommandMemblokirForkBombPerintahTerlarangIzinRekursifDanCdTanpaTujuan() {
        assertEquals(Decision.BLOCKED, assessCommand(":(){ :|:& };:").decision)
        assertEquals(Decision.BLOCKED, assessCommand("mkfs.ext4 /dev/sda1").decision)
        assertEquals(Decision.BLOCKED, assessCommand("termux-open https://example.com").decision)
        assertEquals(Decision.BLOCKED, assessCommand("bash -c 'reboot'").decision)
        assertEquals(Decision.BLOCKED, assessCommand("chmod -R 777 src").decision)
        assertEquals(Decision.BLOCKED, assessCommand("chown -R root:root src").decision)
        assertEquals(Decision.BLOCKED, assessCommand("cd").decision)
        assertEquals(Decision.BLOCKED, assessCommand("pushd").decision)
        assertEquals(Decision.BLOCKED, assessCommand("rm -rf .").decision)
    }

    @Test fun assessCommandMenandaiJaringanDanEkspansiShellSebagaiNeedsApproval() {
        val net = assessCommand("curl https://example.com")
        assertEquals(Decision.NEEDS_APPROVAL, net.decision)
        assertTrue(net.network)

        val gitPush = assessCommand("git push origin main")
        assertEquals(Decision.NEEDS_APPROVAL, gitPush.decision)
        assertTrue(gitPush.network)

        val devTcp = assessCommand("cat /dev/tcp/1.2.3.4/80")
        assertEquals(Decision.NEEDS_APPROVAL, devTcp.decision)
        assertTrue(devTcp.network)

        val varExp = assessCommand("ls \$HOME")
        assertEquals(Decision.NEEDS_APPROVAL, varExp.decision)
        assertFalse(varExp.network)

        val backtick = assessCommand("ls `pwd`")
        assertEquals(Decision.NEEDS_APPROVAL, backtick.decision)
    }
}
