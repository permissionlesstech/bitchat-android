package com.bitchat.android.nostr

import com.bitchat.android.mesh.NdrMeshRoute

internal data class NdrFavoriteRouteBinding(
    val isMutual: Boolean,
    val peerPubkeyHex: String?
)

/**
 * Rechecks both independent authorizations immediately before an OOB frame is encrypted:
 * the exact Noise generation must still be live, and that generation's static key must still
 * belong to the mutual favorite bound to the action's pairwise Nostr peer.
 */
internal object NdrOutOfBandRoutePolicy {
    fun isAuthorized(
        route: NdrMeshRoute,
        expectedPeerPubkeyHex: String,
        currentRoute: (peerID: String, transportId: String) -> NdrMeshRoute?,
        favoriteBinding: (noisePublicKey: ByteArray) -> NdrFavoriteRouteBinding?
    ): Boolean {
        if (!NdrInputPolicy.isPubkeyHex(expectedPeerPubkeyHex)) return false
        if (currentRoute(route.peerID, route.transportId) != route) return false
        val binding = favoriteBinding(route.authenticatedSession.remoteStaticKey) ?: return false
        return binding.isMutual &&
            binding.peerPubkeyHex?.equals(expectedPeerPubkeyHex, ignoreCase = true) == true
    }
}
