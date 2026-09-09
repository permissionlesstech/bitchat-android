package com.bitchat.android.wifiaware

/** Resolves a packet to the exact still-active ingress link that delivered it. */
internal object IngressLinkPolicy {
    data class Link<T : Any>(
        val relayAddress: String,
        val transport: T
    )

    fun <T : Any> resolve(
        ingressLinkID: String?,
        relayAddress: String?,
        links: Map<String, Link<T>>,
        currentTransportForRelay: (String) -> T?
    ): Link<T>? {
        val linkID = ingressLinkID ?: return null
        val relayAddress = relayAddress ?: return null
        val link = links[linkID] ?: return null
        if (link.relayAddress != relayAddress) return null
        return link.takeIf { currentTransportForRelay(relayAddress) === it.transport }
    }
}
