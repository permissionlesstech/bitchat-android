package com.bitchat.android.wifiaware

/**
 * Resolves an authenticated callback to the exact still-active ingress link that completed Noise.
 * A relay/discovery ID alone is not sufficient because a replacement socket may reuse it.
 */
internal object AuthenticatedIngressLinkPolicy {
    data class Claim(
        val relayAddress: String,
        val linkID: String
    )

    data class Link<T : Any>(
        val relayAddress: String,
        val transport: T
    )

    fun matches(
        expected: Claim?,
        authenticatedRelayAddress: String?,
        authenticatedLinkID: String?
    ): Boolean =
        expected != null &&
            expected.relayAddress == authenticatedRelayAddress &&
            expected.linkID == authenticatedLinkID

    fun <T : Any> resolve(
        authenticatedLinkID: String?,
        authenticatedRelayAddress: String?,
        links: Map<String, Link<T>>,
        currentTransportForRelay: (String) -> T?
    ): Link<T>? {
        val linkID = authenticatedLinkID ?: return null
        val relayAddress = authenticatedRelayAddress ?: return null
        val link = links[linkID] ?: return null
        if (link.relayAddress != relayAddress) return null
        return link.takeIf { currentTransportForRelay(relayAddress) === it.transport }
    }
}
