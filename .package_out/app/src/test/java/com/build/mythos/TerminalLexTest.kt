package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLexTest {
    @Test fun lexBashDanLexCodeMewarnaiTokenUtama() {
        val bashSpans = lexBash("git commit -m 'perbaikan' && echo \$HOME # komentar")
        assertTrue(bashSpans.any { it.kind == TermKind.CMD })
        assertTrue(bashSpans.any { it.kind == TermKind.FLAG })
        assertTrue(bashSpans.any { it.kind == TermKind.STR })
        assertTrue(bashSpans.any { it.kind == TermKind.VAR })

        val codeSpans = lexCode("internal fun hitung(): Int = 42 // hasil")
        assertTrue(codeSpans.any { it.kind == TermKind.KEYWORD })
        assertTrue(codeSpans.any { it.kind == TermKind.NUM })
        assertTrue(codeSpans.any { it.kind == TermKind.DIM })
    }

    @Test fun kindOfOutputMengenaliDiffDanStatusBuild() {
        assertEquals(TermKind.ADD, kindOfOutput("+baris baru", diff = true))
        assertEquals(TermKind.DEL, kindOfOutput("-baris lama", diff = true))
        assertEquals(TermKind.OK, kindOfOutput("BUILD SUCCESSFUL in 12s"))
        assertEquals(TermKind.ERR, kindOfOutput("FAILURE: Build failed with an exception."))
    }
}
