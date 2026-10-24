package com.bitchat.android.nostr

import com.bitchat.android.mesh.NdrMeshRoute
import com.bitchat.android.mesh.NdrTransportTarget
import com.bitchat.android.noise.AuthenticatedNoiseSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrOutOfBandRoutePolicyTest {
    private val peerPubkey = "ab".repeat(32)
    private val noiseKey = ByteArray(32) { 1 }
    private val route = NdrMeshRoute(
        transportId = "BLE",
        peerID = "peer",
        authenticatedSession = AuthenticatedNoiseSession(
            remoteStaticKey = noiseKey,
            sessionToken = ByteArray(32) { 2 }
        ),
        transportTarget = NdrTransportTarget(
            endpointId = "endpoint",
            generationToken = "generation"
        )
    )

    @Test
    fun acceptsOnlyLiveGenerationWithExactMutualFavoriteBinding() {
        assertTrue(
            NdrOutOfBandRoutePolicy.isAuthorized(
                route = route,
                expectedPeerPubkeyHex = peerPubkey,
                currentRoute = { _, _ -> route },
                favoriteBinding = {
                    NdrFavoriteRouteBinding(
                        isMutual = true,
                        peerPubkeyHex = peerPubkey
                    )
                }
            )
        )
    }

    @Test
    fun rejectsReplacementNoiseGeneration() {
        val replacement = route.copy(
            authenticatedSession = AuthenticatedNoiseSession(
                remoteStaticKey = noiseKey,
                sessionToken = ByteArray(32) { 3 }
            )
        )

        assertFalse(
            NdrOutOfBandRoutePolicy.isAuthorized(
                route = route,
                expectedPeerPubkeyHex = peerPubkey,
                currentRoute = { _, _ -> replacement },
                favoriteBinding = {
                    NdrFavoriteRouteBinding(true, peerPubkey)
                }
            )
        )
    }

    @Test
    fun rejectsFavoriteRevocationOrNostrRebinding() {
        assertFalse(
            NdrOutOfBandRoutePolicy.isAuthorized(
                route = route,
                expectedPeerPubkeyHex = peerPubkey,
                currentRoute = { _, _ -> route },
                favoriteBinding = {
                    NdrFavoriteRouteBinding(false, peerPubkey)
                }
            )
        )
        assertFalse(
            NdrOutOfBandRoutePolicy.isAuthorized(
                route = route,
                expectedPeerPubkeyHex = peerPubkey,
                currentRoute = { _, _ -> route },
                favoriteBinding = {
                    NdrFavoriteRouteBinding(true, "cd".repeat(32))
                }
            )
        )
    }

    @Test
    fun validatesTheExactTransportWhenMultipleRoutesCoexist() {
        val wifiRoute = route.copy(
            transportId = "WIFI_AWARE",
            transportTarget = NdrTransportTarget(
                endpointId = "wifi-endpoint",
                generationToken = "wifi-generation"
            )
        )

        assertTrue(
            NdrOutOfBandRoutePolicy.isAuthorized(
                route = wifiRoute,
                expectedPeerPubkeyHex = peerPubkey,
                currentRoute = { _, transportId ->
                    when (transportId) {
                        "BLE" -> route
                        "WIFI_AWARE" -> wifiRoute
                        else -> null
                    }
                },
                favoriteBinding = {
                    NdrFavoriteRouteBinding(true, peerPubkey)
                }
            )
        )
        assertFalse(
            NdrOutOfBandRoutePolicy.isAuthorized(
                route = wifiRoute,
                expectedPeerPubkeyHex = peerPubkey,
                currentRoute = { _, _ -> route },
                favoriteBinding = {
                    NdrFavoriteRouteBinding(true, peerPubkey)
                }
            )
        )
    }
}
