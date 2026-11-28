package com.build.mythos

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTrimTest {
    @Test fun shrinkArgsDanTrimContextMemangkasKonteksBesarTanpaMenghapusPesanPenggunaTerakhir() {
        val longContent = "x".repeat(2000)
        val shrunk = shrinkArgs(JSONObject().put("isi", longContent), 300)
        assertTrue(shrunk.getString("isi").length < 400)

        val hist = listOf(
            Hist(0, "Permintaan pertama"),
            Hist(1, "b".repeat(8000)),
            Hist(2, "", results = listOf(Call("c1", "run_command", JSONObject()) to "c".repeat(8000))),
            Hist(0, "Permintaan kedua terbaru")
        )
        val trimmed = trimContext(hist, budget = 5000)
        assertTrue(trimmed.any { it.role == 0 && it.text == "Permintaan kedua terbaru" })
    }
}
