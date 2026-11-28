package com.build.mythos.cc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Penghitung Calc: urutan operasi, fungsi, format, dan penolakan input buruk. */
class MathEvalTest {
    private fun calc(s: String) = MathEval.format(MathEval.eval(s))
    private fun fails(s: String): Boolean = try { calc(s); false } catch (e: MathEval.MathError) { true }

    @Test fun urutanOperasi() {
        assertEquals("7", calc("1+2*3"))
        assertEquals("9", calc("(1+2)*3"))
        assertEquals("512", calc("2^3^2"))   // pangkat menyambung ke kanan
        assertEquals("-4", calc("-2^2"))
        assertEquals("2", calc("10 % 4"))
        assertEquals("1024", calc("2**10"))
    }

    @Test fun fungsiDanKonstanta() {
        assertEquals("7", calc("sqrt(16)+abs(-3)"))
        assertEquals("5", calc("max(1,5,3)"))
        assertEquals("1", calc("min(2,1)"))
        assertEquals("1024", calc("pow(2,10)"))
        assertEquals("1001", calc("1e3+1"))
        assertEquals("3", calc("round(2.5)"))
        assertEquals("1", calc("sin(pi/2)"))
        assertTrue(calc("2*pi").startsWith("6.2831853"))
    }

    @Test fun formatPecahanTanpaNoiseDesimal() {
        assertEquals("0.3", calc("0.1+0.2"))
        assertEquals("2.5", calc("10/4"))
        assertEquals("0", calc("0*5"))
    }

    @Test fun inputBurukDitolakTanpaCrash() {
        assertTrue(fails("1/0"))              // tak hingga
        assertTrue(fails("foo(1)"))
        assertTrue(fails("(1+2"))
        assertTrue(fails("1+"))
        assertTrue(fails("1 2"))
        assertTrue(fails("x+1"))
        assertTrue(fails("1.2.3"))
        assertTrue(fails("sqrt()"))
        assertTrue(fails("1+".repeat(300)))   // lebih dari 500 karakter
        assertTrue(fails("(".repeat(150) + "1" + ")".repeat(150))) // terlalu dalam
    }
}
