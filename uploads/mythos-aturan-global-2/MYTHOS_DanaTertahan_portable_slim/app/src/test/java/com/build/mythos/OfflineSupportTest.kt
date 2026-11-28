package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSupportTest {
    @Test
    fun antreanMenjagaUrutan() {
        var raw: String? = "[]"
        raw = offlineQueueAdd(raw, "satu")
        raw = offlineQueueAdd(raw, "dua")
        assertEquals(listOf("satu", "dua"), offlineQueueList(raw))
    }

    @Test
    fun antreanPenuhDitolak() {
        var raw: String? = "[]"
        repeat(OFFLINE_QUEUE_MAX) { raw = offlineQueueAdd(raw, "p$it") }
        assertNotNull(raw)
        assertNull(offlineQueueAdd(raw, "kelebihan"))
    }

    @Test
    fun dataRusakDianggapKosong() {
        assertTrue(offlineQueueList("bukan json").isEmpty())
        assertTrue(offlineQueueList(null).isEmpty())
    }

    @Test
    fun pesanDiantreHanyaSaatOfflineDanAiButuhInternet() {
        assertTrue(shouldQueueOffline(online = false, local = false, hasAttachments = false, typed = "halo"))
        assertFalse(shouldQueueOffline(online = true, local = false, hasAttachments = false, typed = "halo"))
        assertFalse(shouldQueueOffline(online = false, local = true, hasAttachments = false, typed = "halo"))
        assertFalse(shouldQueueOffline(online = false, local = false, hasAttachments = true, typed = "halo"))
        assertFalse(shouldQueueOffline(online = false, local = false, hasAttachments = false, typed = "  "))
    }

    @Test
    fun teksBanner() {
        assertNull(offlineBannerText(online = true, local = false, queued = 3))
        assertTrue(offlineBannerText(false, true, 0)!!.contains("lokal"))
        assertTrue(offlineBannerText(false, false, 2)!!.contains("2 pesan"))
        assertTrue(offlineBannerText(false, false, 0)!!.contains("otomatis"))
    }
}
