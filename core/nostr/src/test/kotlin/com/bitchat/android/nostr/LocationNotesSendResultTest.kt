package com.bitchat.android.nostr

import com.bitchat.android.geohash.LiveLocationPrivacyGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * F3: [LocationNotesManager.send] reports why a note was not sent, with the same side
 * effects as before. Real manager, never initialized: no relay directory, no subscribe or
 * send functions, so nothing here can reach the network. Synthetic geohash only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationNotesSendResultTest {

    private val manager = LocationNotesManager(
        LiveLocationPrivacyGate,
        RelayDirectory,
        UnconfinedTestDispatcher(),
    )

    @Before
    fun setUp() {
        LiveLocationPrivacyGate.update(true)
    }

    @After
    fun tearDown() {
        LiveLocationPrivacyGate.update(false)
    }

    @Test
    fun gateClosed_returnsFailedPrivacyGate_andStops() = runTest {
        manager.setGeohash("s0000000")
        LiveLocationPrivacyGate.update(false)

        val result = manager.send("synthetic note", "alice")

        assertEquals(NoteSendResult.Failed(NoteSendResult.Reason.PRIVACY_GATE_CLOSED), result)
        assertNull(manager.geohash.value)
        assertEquals(LocationNotesManager.State.IDLE, manager.state.value)
    }

    @Test
    fun noGeohash_returnsFailedNoLocation_andSetsError() = runTest {
        manager.stop()

        val result = manager.send("synthetic note", "alice")

        assertEquals(NoteSendResult.Failed(NoteSendResult.Reason.NO_LOCATION), result)
        assertEquals("No location set", manager.errorMessage.value)
    }

    @Test
    fun uninitializedRelays_returnsFailedNoRelays_andSetsNoRelaysState() = runTest {
        manager.setGeohash("s0000000")

        val result = manager.send("synthetic note", "alice")

        assertEquals(NoteSendResult.Failed(NoteSendResult.Reason.NO_RELAYS), result)
        assertEquals(LocationNotesManager.State.NO_RELAYS, manager.state.value)
        assertEquals("No relays available", manager.errorMessage.value)
    }
}
