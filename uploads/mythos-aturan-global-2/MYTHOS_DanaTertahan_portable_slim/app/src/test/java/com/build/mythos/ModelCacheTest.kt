package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCacheTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun cacheBaruMasihSegar() = assertTrue(probeFresh(1_000_000L, 1_000_000L + 23 * hour))

    @Test
    fun cacheLewat24JamBasi() = assertFalse(probeFresh(1_000_000L, 1_000_000L + 25 * hour))

    @Test
    fun cacheLamaTanpaWaktuBasi() = assertFalse(probeFresh(0L, 5_000_000L))

    @Test
    fun jamMundurDianggapBasi() = assertFalse(probeFresh(5_000_000L, 1_000_000L))

    @Test
    fun percobaanPertamaBolehDanDalamSatuJamTidak() {
        assertTrue(retryDue(0L, 10_000_000L))
        assertFalse(retryDue(10_000_000L, 10_000_000L + hour / 2))
        assertTrue(retryDue(10_000_000L, 10_000_000L + 2 * hour))
    }

    @Test
    fun awalanModelsDiabaikanSaatMembandingkan() {
        assertTrue(sameModel("models/gemini-x", "gemini-x"))
        assertFalse(sameModel("gemini-x", "gemini-y"))
    }

    @Test
    fun modelDibuangDariDuaDaftar() {
        val (g, b) = dropModel(listOf("a", "models/m", "c"), listOf("m", "d"), "m")
        assertEquals(listOf("a", "c"), g)
        assertEquals(listOf("d"), b)
    }

    @Test
    fun error404ModelTidakDitemukanDiklasifikasiHilang() {
        assertEquals(ApiErrorKind.GONE, classifyApiError(404, "", "The model `foo` does not exist"))
        assertEquals(
            ApiErrorKind.GONE,
            classifyApiError(404, "NOT_FOUND", "models/gemini-old is not found for API version v1beta, or is not supported for generateContent")
        )
        assertEquals(ApiErrorKind.GONE, classifyApiError(400, "", "The model has been decommissioned and is no longer supported"))
    }

    @Test
    fun error404TanpaKataModelBukanModelHilang() {
        assertEquals(ApiErrorKind.FATAL, classifyApiError(404, "", "404 page not found"))
    }

    @Test
    fun openRouterTanpaEndpointTetapDitolakBukanHilang() {
        assertEquals(ApiErrorKind.MODEL, classifyApiError(404, "", "No endpoints found for model x that support tool use"))
    }

    @Test
    fun errorKuotaDanSibukTidakBerubah() {
        assertEquals(ApiErrorKind.QUOTA, classifyApiError(429, "", "insufficient_quota"))
        assertEquals(ApiErrorKind.TRANSIENT, classifyApiError(503, "", "model overloaded"))
    }

    @Test
    fun modelHilangLayakDialihkan() = assertTrue(isFallbackWorthy(ModelGoneException("x")))
}
