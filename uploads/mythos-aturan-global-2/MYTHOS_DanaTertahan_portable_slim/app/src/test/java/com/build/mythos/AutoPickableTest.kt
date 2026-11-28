package com.build.mythos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPickableTest {
    @Test
    fun penyediaLokalBukanUtamaTidakDipilihOtomatis() {
        assertFalse(autoPickable("Ollama", "Gemini", true))
    }

    @Test
    fun penyediaLokalYangMenjadiUtamaTetapDipakai() {
        assertTrue(autoPickable("Ollama", "Ollama", true))
    }

    @Test
    fun penyediaInternetTetapBolehJadiCadangan() {
        assertTrue(autoPickable("Gemini", "OpenRouter", false))
    }
}
