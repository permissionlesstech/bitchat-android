package com.bitchat.android.nostr

internal object NostrLiveSubscriptionPrivacy {
    fun closeTargets(
        liveSubscriptionIds: Set<String>,
        subscriptionsByRelay: Map<String, Set<String>>,
    ): Map<String, Set<String>> = buildMap {
        subscriptionsByRelay.forEach { (relayUrl, relaySubscriptionIds) ->
            val matchingIds = relaySubscriptionIds.intersect(liveSubscriptionIds)
            if (matchingIds.isNotEmpty()) put(relayUrl, matchingIds)
        }
    }
}
