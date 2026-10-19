package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class NdrBootstrapTriggerCoordinatorTest {
    @Test
    fun `authenticated policy resolution retries the same peer immediately`() {
        val requested = mutableListOf<String>()
        val coordinator = NdrBootstrapTriggerCoordinator(
            connectedPeerIDs = { emptyList() },
            noiseKeyHexForPeer = { null },
            requestBootstrap = requested::add
        )

        coordinator.onAuthenticatedPolicyResolved("peer-a")

        assertEquals(listOf("peer-a"), requested)
    }

    @Test
    fun `mutual favorite change retries only the live peer with that noise key`() {
        val requested = mutableListOf<String>()
        val coordinator = NdrBootstrapTriggerCoordinator(
            connectedPeerIDs = { listOf("peer-a", "peer-b", "peer-a") },
            noiseKeyHexForPeer = { peerID ->
                when (peerID) {
                    "peer-a" -> "AABBCC"
                    "peer-b" -> "112233"
                    else -> null
                }
            },
            requestBootstrap = requested::add
        )

        coordinator.onFavoriteChanged("aabbcc")

        assertEquals(listOf("peer-a"), requested)
    }
}
