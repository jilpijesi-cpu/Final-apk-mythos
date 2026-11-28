package com.build.mythos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVerifyTest {
    private val ownPkg = "com.build.mythos"
    private val installed = ApkFacts(ownPkg, 58L, setOf("aa11bb22"))

    @Test fun apkUpgradeDiriValidJikaPaketSamaVersiLebihTinggiDanSertifikatCocok() {
        val candidate = ApkFacts(ownPkg, 59L, setOf("aa11bb22"))
        val check = evaluateApk(ownPkg, installed, candidate, requireOwn = true)
        assertTrue(check.ok)
        assertTrue(check.problems.isEmpty())
    }

    @Test fun apkDitolakJikaVersionCodeTidakLebihTinggiAtauSertifikatBerbeda() {
        val sameVer = ApkFacts(ownPkg, 58L, setOf("aa11bb22"))
        assertFalse(evaluateApk(ownPkg, installed, sameVer, requireOwn = true).ok)

        val diffCert = ApkFacts(ownPkg, 59L, setOf("ff99ee88"))
        val resCert = evaluateApk(ownPkg, installed, diffCert, requireOwn = true)
        assertFalse(resCert.ok)
        assertTrue(resCert.problems.any { it.contains("Tanda tangan APK berbeda") })
    }

    @Test fun apkAplikasiLainLolosKecualiSaatUpgradeDiri() {
        val otherApp = ApkFacts("com.contoh.kalkulator", 1L, setOf("123456"))
        assertTrue(evaluateApk(ownPkg, installed, otherApp, requireOwn = false).ok)
        assertFalse(evaluateApk(ownPkg, installed, otherApp, requireOwn = true).ok)
    }

    @Test fun apkRusakDowngradeDanSertifikatKosongDitanganiBenar() {
        val unreadable = evaluateApk(ownPkg, installed, null, requireOwn = true)
        assertFalse(unreadable.ok)
        assertTrue(unreadable.problems.isNotEmpty())

        val downgrade = ApkFacts(ownPkg, 57L, setOf("aa11bb22"))
        assertFalse(evaluateApk(ownPkg, installed, downgrade, requireOwn = true).ok)

        val noCerts = ApkFacts(ownPkg, 59L, emptySet())
        val warnCheck = evaluateApk(ownPkg, installed, noCerts, requireOwn = true)
        assertTrue(warnCheck.ok)
        assertTrue(warnCheck.warnings.isNotEmpty())
    }
}
