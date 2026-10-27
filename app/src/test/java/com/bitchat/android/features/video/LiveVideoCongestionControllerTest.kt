package com.bitchat.android.features.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVideoCongestionControllerTest {
    @Test
    fun growingQueueBacksOffThenRecoversGradually() {
        val controller = LiveVideoCongestionController(maxBitrate = 60_000)
        controller.onBacklog(20, 0)
        assertEquals(48_000, controller.bitrate)
        // Rate-limited: a second high sample inside a second does not compound.
        controller.onBacklog(20, 500)
        assertEquals(48_000, controller.bitrate)
        controller.onBacklog(20, 1_000)
        assertEquals(38_400, controller.bitrate)

        // Calm queue: nothing for two seconds, then one step per second, capped at the preset.
        var now = 2_000L
        controller.onBacklog(0, now)
        assertEquals(38_400, controller.bitrate)
        repeat(20) {
            now += 1_000
            controller.onBacklog(0, now)
        }
        assertEquals(60_000, controller.bitrate)
    }

    @Test
    fun deepQueueSkipsVideoAndResumesWithAKeyframe() {
        val controller = LiveVideoCongestionController(maxBitrate = 60_000)
        val deep = controller.onBacklog(80, 0)
        assertTrue(deep.skipVideo)
        assertFalse(deep.requestKeyframe)
        assertTrue(controller.onBacklog(30, 250).skipVideo)
        val drained = controller.onBacklog(2, 500)
        assertFalse(drained.skipVideo)
        assertTrue(drained.requestKeyframe)
        assertFalse(controller.onBacklog(2, 750).requestKeyframe)
        assertEquals(1, controller.skipEpisodes)
    }

    @Test
    fun bitrateNeverDropsBelowTheFloor() {
        val controller = LiveVideoCongestionController(maxBitrate = 20_000)
        var now = 0L
        repeat(30) {
            controller.onBacklog(100, now)
            now += 250
        }
        assertEquals(6_000, controller.bitrate)
    }
}
