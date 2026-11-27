package com.build.mythos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    // ---- isSimpleGreeting
    @Test fun sapaanSederhanaDikenali() {
        assertTrue(isSimpleGreeting("Halo"))
        assertTrue(isSimpleGreeting("  hai! "))
        assertTrue(isSimpleGreeting("Selamat pagi."))
    }

    @Test fun kalimatKerjaBukanSapaan() {
        assertFalse(isSimpleGreeting("halo bisa bantu buat aplikasi?"))
        assertFalse(isSimpleGreeting(""))
    }

    // ---- stripModelThinking
    @Test fun tagBerpikirLengkapDibuang() =
        assertEquals("Jawaban", stripModelThinking("<think>rencana</think>Jawaban"))

    @Test fun tagBerpikirMultibarisDibuang() =
        assertEquals("Halo", stripModelThinking("<thinking>baris1\nbaris2</thinking>\nHalo"))

    @Test fun tagBerpikirTanpaPenutupDipotongSampaiAkhir() =
        assertEquals("Jawab", stripModelThinking("Jawab<thinking>sisa pikiran"))

    @Test fun tagPenutupLiarDibuang() =
        assertEquals("Hasil", stripModelThinking("Hasil</think>"))

    @Test fun teksBiasaTidakBerubah() =
        assertEquals("Teks biasa", stripModelThinking("Teks biasa"))

    // ---- sanitizeAssistantText
    @Test fun muatanAlatDiganti() {
        val out = sanitizeAssistantText("{\"command\": \"ls -la\"}")
        assertTrue(out.startsWith("Agen belum menjalankan pemeriksaan"))
    }

    @Test fun rencanaInternalDenganProtokolDiganti() {
        val out = sanitizeAssistantText("Langkah selanjutnya: jalankan\n'command': 'ls'")
        assertTrue(out.startsWith("Agen belum menjalankan pemeriksaan"))
    }

    @Test fun penjelasanBiasaTidakDiganti() {
        val t = "Contoh JSON: \"description\": \"teks\""
        assertEquals(t, sanitizeAssistantText(t))
    }

    @Test fun protokolDiDalamTagBerpikirTidakMemicuPenggantian() =
        assertEquals("Selesai", sanitizeAssistantText("<think>{\"command\":\"x\"}</think>Selesai"))
}
