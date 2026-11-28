package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFramesTest {
    @Test fun detectsVideoNames() {
        assertTrue(VideoFrames.isVideoName("VID-20261030-WA0013.mp4"))
        assertTrue(VideoFrames.isVideoName("a.MOV"))
        assertFalse(VideoFrames.isVideoName("foto.jpg"))
        assertFalse(VideoFrames.isVideoName("tanpa_ekstensi"))
    }

    @Test fun twelveSecondVideoGivesSixFramesInsideTheVideo() {
        val t = VideoFrames.timesUs(11_919L)
        assertEquals(6, t.size)
        assertEquals(t.sorted(), t)
        assertTrue(t.first() > 0L)
        assertTrue(t.last() < 11_919L * 1000L)
    }

    @Test fun shortVideoGivesOneFrameAndEmptyInputGivesNone() {
        assertEquals(1, VideoFrames.timesUs(900L).size)
        assertTrue(VideoFrames.timesUs(0L).isEmpty())
        assertTrue(VideoFrames.timesUs(5_000L, 0).isEmpty())
    }

    @Test fun neverMoreThanMax() {
        assertEquals(VideoFrames.MAX_FRAMES, VideoFrames.timesUs(10 * 60 * 1000L).size)
    }

    @Test fun frameNamesAreSafeImages() {
        val n = VideoFrames.frameName("Rekaman layar (1).mp4", 2)
        assertTrue(n.endsWith("_frame2.jpg"))
        assertTrue(isImageName(n))
        assertFalse(n.contains(" "))
    }
}
