package com.bitchat.android.nostr

import android.os.Looper
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NostrRelayManagerEoseTest {
    private val manager = NostrRelayManager.shared
    private val relayUrl = "wss://relay.invalid"
    private val filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE))
    private val endOfStoredEvents = mutableListOf<String>()

    @Before
    fun setUp() {
        manager.disconnect()
        manager.clearAllSubscriptions()
    }

    @After
    fun tearDown() {
        manager.clearAllSubscriptions()
        LiveLocationPrivacyGate.update(false)
    }

    private fun subscribe(id: String, liveLocationToken: Long? = null) {
        manager.subscribe(
            filter = filter,
            id = id,
            handler = {},
            targetRelayUrls = emptyList(),
            liveLocationToken = liveLocationToken,
            onEndOfStoredEvents = { endOfStoredEvents += it },
        )
    }

    private fun receiveEose(subscriptionId: String) {
        manager.handleMessage("""["EOSE","$subscriptionId"]""", relayUrl)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `eose invokes the matching subscription's callback with the relay url`() {
        subscribe("notes-a")

        receiveEose("notes-a")

        assertEquals(listOf(relayUrl), endOfStoredEvents)
    }

    @Test
    fun `eose for another subscription id does not invoke the callback`() {
        subscribe("notes-a")

        receiveEose("notes-b")

        assertTrue(endOfStoredEvents.isEmpty())
    }

    @Test
    fun `subscription without a callback ignores eose`() {
        manager.subscribe(
            filter = filter,
            id = "plain",
            handler = {},
            targetRelayUrls = emptyList(),
        )

        receiveEose("plain")

        assertTrue(endOfStoredEvents.isEmpty())
    }

    @Test
    fun `eose is dropped when the subscription's live-location token is no longer accepted`() {
        LiveLocationPrivacyGate.update(true)
        subscribe("notes-a", liveLocationToken = LiveLocationPrivacyGate.captureToken())
        LiveLocationPrivacyGate.update(false)

        receiveEose("notes-a")

        assertTrue(endOfStoredEvents.isEmpty())
    }
}
