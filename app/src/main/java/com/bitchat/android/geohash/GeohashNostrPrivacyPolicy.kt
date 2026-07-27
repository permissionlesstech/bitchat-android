package com.bitchat.android.geohash

internal object GeohashNostrPrivacyPolicy {
    fun livePresenceTargets(
        availableChannels: Collection<GeohashChannel>,
        liveLocationEnabled: Boolean,
    ): Set<String> {
        if (!liveLocationEnabled) return emptySet()
        return availableChannels
            .asSequence()
            .filter { it.level.precision <= GeohashChannelLevel.CITY.precision }
            .map { it.geohash }
            .toSet()
    }

    fun samplingTargets(
        liveLocationGeohashes: Collection<String>,
        userSelectedGeohashes: Collection<String>,
        liveLocationEnabled: Boolean,
    ): Set<String> = buildSet {
        addAll(userSelectedGeohashes)
        if (liveLocationEnabled) addAll(liveLocationGeohashes)
    }
}
