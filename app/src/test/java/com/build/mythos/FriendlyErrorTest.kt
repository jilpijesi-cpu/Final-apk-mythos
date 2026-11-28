package com.build.mythos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendlyErrorTest {
    private val openRouter429 = "Gagal memanggil model: HTTP 429: {\"error\":{\"message\":\"Rate limit exceeded: free-models-per-day. " +
        "Add 10 credits to unlock 1000 free model requests per day\",\"code\":429}}"

    @Test fun jatahHarianHabisJadiKalimatSederhana() {
        val t = friendlyErrorText(openRouter429)
        assertTrue(t.contains("habis"))
        assertFalse(t.contains("{"))
        assertFalse(t.contains("http", ignoreCase = true))
    }

    @Test fun kunciDitolak() {
        assertTrue(friendlyErrorText("HTTP 401: invalid api key").contains("Kunci API"))
    }

    @Test fun teksTidakDikenalTidakMemuatKode() {
        val t = friendlyErrorText("{\"x\":1} aneh sekali")
        assertFalse(t.contains("{"))
    }

    @Test fun riwayatGagalDikenali() {
        assertTrue(isFailedHistText("Tugas tidak selesai: Koneksi internet bermasalah."))
        assertTrue(isFailedHistText("Berhenti: sudah 40 putaran tanpa selesai."))
        assertTrue(isFailedHistText("(respons model kosong)"))
        assertFalse(isFailedHistText("Halo, ini jawaban biasa."))
    }

    @Test fun kuotaHarianOpenRouterDikenaliKeyPool() {
        val e = com.build.mythos.cc.LlmException("HTTP 429: Rate limit exceeded: free-models-per-day", false, 429)
        assertTrue(com.build.mythos.cc.classify(e).reason.contains("harian"))
    }
}
