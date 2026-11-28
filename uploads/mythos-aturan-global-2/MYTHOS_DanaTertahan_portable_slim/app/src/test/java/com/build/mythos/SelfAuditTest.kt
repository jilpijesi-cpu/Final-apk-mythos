package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfAuditTest {
    @Test fun parseSelfFactsMembacaMetaTemuanDanPenandaSelesai() {
        val raw = """
            KITA_META|app_id|com.build.mythos
            KITA_META|version_code|58
            KITA_META|version_name|2.43
            KITA_META|kt_main|172
            KITA_META|kt_test|58
            KITA_META|tests|410
            KITA_PERM|android.permission.INTERNET
            KITA_DEP|org.jetbrains.kotlin:kotlin-stdlib:1.9.24
            KITA_AUDIT|low|S04|-|2 berkas logika murni tanpa rujukan di tes
            KITA_AUDIT_DONE
        """.trimIndent()
        val facts = parseSelfFacts(raw)
        assertTrue(facts.done)
        assertEquals("com.build.mythos", facts.meta["app_id"])
        assertEquals(58L, facts.metaLong("version_code"))
        assertEquals(172, facts.metaInt("kt_main"))
        assertEquals(1, facts.findings.size)
        assertEquals(Sev.LOW, facts.findings.first().sev)
        assertEquals(99, auditScore(facts.findings))
        assertTrue(facts.perms.contains("android.permission.INTERNET"))
    }

    @Test fun renderAuditMenyusunRingkasanDanStatusAplikasi() {
        val facts = parseSelfFacts(
            "KITA_META|app_id|com.build.mythos\nKITA_META|version_code|58\nKITA_META|version_name|2.43\n" +
                "KITA_META|kt_main|172\nKITA_META|kt_test|58\nKITA_META|tests|410\nKITA_AUDIT_DONE"
        )
        val status = renderStatus(
            StatusFacts("2.43", 58L, true, true, true, false, false, false, true, BootState(58L, 0, 58L, 0L))
        )
        val report = renderAudit(facts, status)
        assertTrue(report.contains("AUDIT DIRI"))
        assertTrue(report.contains("Skor arah: 100/100"))
        assertTrue(report.contains("versi ini sudah terbukti stabil"))
    }

    @Test fun selfScriptMenolakModeTidakDikenal() {
        val cmd = SelfScript.command("echo ok", "audit", ".", "com.build.mythos")
        assertTrue(cmd.contains("bash \"\$S\" audit '.' 'com.build.mythos'"))
        var threw = false
        try {
            SelfScript.command("echo ok", "hapus_semua", ".")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
