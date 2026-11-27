package com.build.mythos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralAssistTest {
    @Test fun identityIsNotCodingOnly() {
        assertTrue(GeneralAssist.IDENTITY.contains("serba bisa"))
        assertFalse(GeneralAssist.IDENTITY.contains("asisten pengembang yang"))
    }

    @Test fun rulesCoverSafetyAndHonesty() {
        for (r in listOf(GeneralAssist.RULE, GeneralAssist.RULE_COMPACT)) {
            assertTrue(r.contains("OTP"))
            assertTrue(r.contains("menyembunyikan"))
            assertTrue(r.contains("frame"))
            assertTrue(r.contains("cari_web"))
        }
    }

    @Test fun compactStaysSmall() {
        assertTrue("RULE_COMPACT terlalu panjang: ${GeneralAssist.RULE_COMPACT.length}", GeneralAssist.RULE_COMPACT.length < 900)
    }

    @Test fun noKotlinTemplateLeaks() {
        // Tanda dolar di konstanta akan jadi interpolasi di tempat lain; jaga agar tidak ada.
        assertFalse(GeneralAssist.RULE.contains("$"))
        assertFalse(GeneralAssist.RULE_COMPACT.contains("$"))
    }
}
