package com.bitchat.android.geohash

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VerifiedLocationPeersStoreTest {

    private lateinit var store: VerifiedLocationPeersStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = VerifiedLocationPeersStore.getInstance(context)
    }

    @Test
    fun testAddAndRemoveVerifiedPeer() {
        val peerID = "Peer1234ABC"
        assertFalse(store.isVerified(peerID))

        store.addVerifiedPeer(peerID)
        assertTrue(store.isVerified(peerID))
        assertTrue(store.isVerified("peer1234abc")) // Case-insensitive check

        store.removeVerifiedPeer(peerID)
        assertFalse(store.isVerified(peerID))
    }

    @Test
    fun testRejectionCooldown() {
        val peerID = "RejectionPeer"
        assertTrue(store.canSendRequest(peerID))

        // Record a rejection with a 5-second duration
        store.recordRejection(peerID, durationMs = 5000L)

        assertFalse(store.canSendRequest(peerID))
        assertTrue(store.getRemainingCooldownMs(peerID) > 0L)

        // Clear cooldown manually
        store.clearCooldown(peerID)
        assertTrue(store.canSendRequest(peerID))
    }

    @Test
    fun testVerifiedPeerCannotSendRequest() {
        val peerID = "VerifiedPeer"
        store.addVerifiedPeer(peerID)

        // Once verified, canSendRequest returns false (already verified)
        assertFalse(store.canSendRequest(peerID))
    }
}
