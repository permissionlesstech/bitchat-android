package com.bitchat.android.geohash

import org.junit.Assert.assertEquals
import org.junit.Test

class GeohashNostrPrivacyPolicyTest {
    @Test
    fun `disabled live location retains only user-selected sampling targets`() {
        val targets = GeohashNostrPrivacyPolicy.samplingTargets(
            liveLocationGeohashes = listOf("live-city", "shared"),
            userSelectedGeohashes = listOf("bookmark", "shared"),
            liveLocationEnabled = false
        )

        assertEquals(setOf("bookmark", "shared"), targets)
    }

    @Test
    fun `enabled live location combines and deduplicates sampling targets`() {
        val targets = GeohashNostrPrivacyPolicy.samplingTargets(
            liveLocationGeohashes = listOf("live-city", "shared"),
            userSelectedGeohashes = listOf("bookmark", "shared"),
            liveLocationEnabled = true
        )

        assertEquals(setOf("live-city", "bookmark", "shared"), targets)
    }

    @Test
    fun `presence excludes live channels when disabled`() {
        val channels = listOf(
            GeohashChannel(GeohashChannelLevel.REGION, "region"),
            GeohashChannel(GeohashChannelLevel.CITY, "city"),
            GeohashChannel(GeohashChannelLevel.NEIGHBORHOOD, "neighborhood")
        )

        assertEquals(
            emptySet<String>(),
            GeohashNostrPrivacyPolicy.livePresenceTargets(channels, false)
        )
        assertEquals(
            setOf("region", "city"),
            GeohashNostrPrivacyPolicy.livePresenceTargets(channels, true)
        )
    }
}
