package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel

/*
 * Pure geohash helpers of the location channels screen (inventory L2, L12-L15, L18-L20 and
 * validation). Moved verbatim from LocationChannelsSheet.kt, quirks included; the quirk IDs mark
 * the lines tests pin.
 */

/** The characters a geohash may contain (base32 without a, i, l and o). */
private const val GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"

/**
 * The text a geohash field or picker result stands for: trimmed, lowercased, `#` removed. No
 * alphabet filter (Q-L2).
 */
internal fun stripGeohashInput(value: String): String = value.trim().lowercase().replace("#", "")

/** L12: what typing into the teleport field keeps. */
internal fun normalizeGeohashInput(value: String): String {
    val allowed = GEOHASH_ALPHABET.toSet()
    return value
        .lowercase()
        .replace("#", "")
        .filter { it in allowed }
        .take(12)
}

internal fun validateGeohash(geohash: String): Boolean {
    if (geohash.isEmpty() || geohash.length > 12) return false
    val allowed = GEOHASH_ALPHABET.toSet()
    return geohash.all { it in allowed }
}

/** L15: the teleport button is enabled iff the stripped field is a valid geohash. */
internal fun isTeleportEnabled(customGeohash: String): Boolean =
    validateGeohash(stripGeohashInput(customGeohash))

/** L13: the channel a typed or picked geohash teleports to, or null when it is invalid. */
internal fun channelForManualGeohash(value: String): GeohashChannel? {
    val normalized = stripGeohashInput(value)
    if (!validateGeohash(normalized)) return null
    return GeohashChannel(
        level = levelForLength(normalized.length),
        geohash = normalized
    )
}

/**
 * L13, invalid branch: what the field shows after a rejected teleport. Skips the alphabet filter
 * typing applies, so a picker result can put characters there that typing cannot (Q-L2).
 */
internal fun rejectedTeleportFieldText(value: String): String = stripGeohashInput(value)

/** L14: the map picker opens on the typed geohash, else the selected location channel, else "". */
internal fun mapPickerInitialGeohash(customGeohash: String, selectedChannel: ChannelID?): String {
    val normalized = stripGeohashInput(customGeohash)
    return when {
        normalized.isNotBlank() -> normalized
        selectedChannel is ChannelID.Location -> selectedChannel.channel.geohash
        else -> ""
    }
}

/** L18. A length-3 geohash maps to PROVINCE (precision 4), see Q-L8. */
internal fun levelForLength(length: Int): GeohashChannelLevel {
    return when (length) {
        in 0..2 -> GeohashChannelLevel.REGION
        in 3..4 -> GeohashChannelLevel.PROVINCE
        5 -> GeohashChannelLevel.CITY
        6 -> GeohashChannelLevel.NEIGHBORHOOD
        7 -> GeohashChannelLevel.BLOCK
        8 -> GeohashChannelLevel.BUILDING
        else -> if (length > 8) GeohashChannelLevel.BUILDING else GeohashChannelLevel.BLOCK
    }
}

/** L19: the cell size of a geohash of [precision] characters, e.g. `~4.9 km`. */
internal fun coverageString(precision: Int): String {
    val maxMeters = when (precision) {
        2 -> 1_250_000.0
        3 -> 156_000.0
        4 -> 39_100.0
        5 -> 4_890.0
        6 -> 1_220.0
        7 -> 153.0
        8 -> 38.2
        9 -> 4.77
        10 -> 1.19
        else -> if (precision <= 1) 5_000_000.0 else 1.19 * Math.pow(0.25, (precision - 10).toDouble())
    }
    return "~${formatDistance(maxMeters / 1000.0)} km"
}

/**
 * L19. Formats with the default locale and its `>= 10` and `else` branches are identical
 * (Q-L9).
 */
internal fun formatDistance(value: Double): String {
    return when {
        value >= 100 -> String.format("%.0f", value)
        value >= 10 -> String.format("%.1f", value)
        else -> String.format("%.1f", value)
    }
}

/** L5: the mesh row's range, a hard-coded literal (Q-L9). */
internal fun meshRangeString(): String = "~10–50m"

/** Prefix for a place name in a subtitle; ignores [level] (Q-L9). */
@Suppress("UNUSED_PARAMETER")
internal fun formattedNamePrefix(level: GeohashChannelLevel): String = "~"

/** L20: data-class equality, so level and exact-case geohash must both match (Q-L8). */
internal fun isChannelSelected(channel: GeohashChannel, selectedChannel: ChannelID?): Boolean {
    return when (selectedChannel) {
        is ChannelID.Location -> selectedChannel.channel == channel
        else -> false
    }
}

/**
 * L2: returns the active location channel when it has no row in the nearby-channel list.
 *
 * This commonly happens after teleporting to a remote geohash. Keeping the selected channel in the
 * main channel card makes its selection and bookmark action available even before it is bookmarked.
 * Unlike [isChannelSelected], the geohash match is case-insensitive (Q-L8).
 */
internal fun selectedLocationChannelOutsideNearby(
    selectedChannel: ChannelID?,
    nearbyChannels: List<GeohashChannel>
): GeohashChannel? {
    val selected = (selectedChannel as? ChannelID.Location)?.channel ?: return null
    return selected.takeUnless { active ->
        nearbyChannels.any { nearby ->
            nearby.geohash.equals(active.geohash, ignoreCase = true)
        }
    }
}
