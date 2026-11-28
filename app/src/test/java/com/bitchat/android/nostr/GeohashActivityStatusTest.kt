package com.bitchat.android.nostr

import com.bitchat.android.nostr.GeohashActivityMonitor.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class GeohashActivityStatusTest {

    @Test
    fun `connecting until traffic or the grace period proves the sweep`() {
        assertEquals(Status.CONNECTING, activityStatus(connected = false, receivedTraffic = false, graceElapsed = false))
        assertEquals(Status.CONNECTING, activityStatus(connected = true, receivedTraffic = false, graceElapsed = false))
        assertEquals(Status.LIVE, activityStatus(connected = true, receivedTraffic = true, graceElapsed = false))
    }

    @Test
    fun `a quiet but connected world is live after the grace period`() {
        assertEquals(Status.LIVE, activityStatus(connected = true, receivedTraffic = false, graceElapsed = true))
    }

    @Test
    fun `losing every relay after going live reports offline`() {
        assertEquals(Status.OFFLINE, activityStatus(connected = false, receivedTraffic = true, graceElapsed = true))
    }

    @Test
    fun `a late connection recovers from offline without waiting for traffic`() {
        assertEquals(Status.OFFLINE, activityStatus(connected = false, receivedTraffic = false, graceElapsed = true))
        assertEquals(Status.LIVE, activityStatus(connected = true, receivedTraffic = false, graceElapsed = true))
    }
}
