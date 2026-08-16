package com.bitchat.android.nostr

/**
 * Bridges lifecycle events that can make an NDR bootstrap newly eligible.
 *
 * Authenticated capability resolution happens after the peer-list callback that
 * carries the announcement, while a favorite can become mutual without any peer
 * update. Both events therefore need an explicit bootstrap trigger.
 */
internal class NdrBootstrapTriggerCoordinator(
    private val connectedPeerIDs: () -> List<String>,
    private val noiseKeyHexForPeer: (String) -> String?,
    private val requestBootstrap: (String) -> Unit
) {
    fun onAuthenticatedPolicyResolved(peerID: String) {
        requestBootstrap(peerID)
    }

    fun onFavoriteChanged(noiseKeyHex: String) {
        val changedKey = noiseKeyHex.trim()
        if (changedKey.isEmpty()) return

        connectedPeerIDs()
            .distinct()
            .filter { peerID ->
                noiseKeyHexForPeer(peerID)?.equals(changedKey, ignoreCase = true) == true
            }
            .forEach(requestBootstrap)
    }
}
