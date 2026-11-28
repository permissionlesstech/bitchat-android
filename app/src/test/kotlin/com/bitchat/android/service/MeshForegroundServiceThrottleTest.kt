package com.bitchat.android.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshForegroundServiceThrottleTest {
    @Test
    fun `first notification always updates`() {
        assertTrue(shouldUpdateNotification(null, 1, 1_000L, 0L, 5_000L, force = false))
    }

    @Test
    fun `rapid peer count changes within throttle window are skipped unless forced`() {
        assertFalse(shouldUpdateNotification(1, 2, 3_000L, 1_000L, 5_000L, force = false))
        assertTrue(shouldUpdateNotification(1, 2, 3_000L, 1_000L, 5_000L, force = true))
    }

    @Test
    fun `peer count change after throttle window updates`() {
        assertTrue(shouldUpdateNotification(1, 2, 6_000L, 1_000L, 5_000L, force = false))
        assertFalse(shouldUpdateNotification(2, 2, 10_000L, 1_000L, 5_000L, force = false))
    }
}
