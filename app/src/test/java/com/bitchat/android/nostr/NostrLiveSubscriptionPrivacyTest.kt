package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class NostrLiveSubscriptionPrivacyTest {
    @Test
    fun `teardown closes live subscriptions on shared relays`() {
        val targets = NostrLiveSubscriptionPrivacy.closeTargets(
            liveSubscriptionIds = setOf("live-a", "live-b"),
            subscriptionsByRelay = mapOf(
                "shared-relay" to setOf("dm", "live-a"),
                "live-relay" to setOf("live-a", "live-b"),
                "dm-relay" to setOf("dm"),
            ),
        )

        assertEquals(
            mapOf(
                "shared-relay" to setOf("live-a"),
                "live-relay" to setOf("live-a", "live-b"),
            ),
            targets,
        )
    }

    @Test
    fun `teardown ignores relays without live subscriptions`() {
        assertEquals(
            emptyMap<String, Set<String>>(),
            NostrLiveSubscriptionPrivacy.closeTargets(
                liveSubscriptionIds = emptySet(),
                subscriptionsByRelay = mapOf("default-relay" to setOf("dm")),
            ),
        )
    }
}
