package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import java.util.Date
import java.util.Locale

/*
 * The geohash "People" card of the peer list as plain functions of snapshots plus
 * [PeerListLookups] (inventory P26-P33). Moved verbatim from `GeohashPeopleList`.
 */

/** One person row in the geohash people card (P33). */
internal data class GeoPersonRowUi(
    /** Nostr public key hex; the animated list key. */
    val id: String,
    val displayName: String,
    /** [displayName] before any `#abcd` suffix. */
    val baseName: String,
    /** `geohashIdentitySuffix`: "" unless the base name is shared. */
    val suffix: String,
    val isMe: Boolean,
    val isTeleported: Boolean,
    val hasUnreadDM: Boolean,
    /** `PeerIdentity.stableKey` for `colorForPeer`. */
    val colorKey: String,
)

internal data class GeohashPeopleUi(
    /** The header count: every person shown, self included (Q-P1). */
    val count: Int,
    val onLocation: List<GeoPersonRowUi>,
    val teleportedIn: List<GeoPersonRowUi>,
)

internal data class GeohashPeopleSections(
    val onLocation: List<GeoPerson>,
    val teleportedIn: List<GeoPerson>
)

/** P26: our own Nostr key for the selected geohash, lowercased; null when not on a geohash. */
internal fun myGeohashHex(
    selectedLocationChannel: ChannelID?,
    lookups: PeerListLookups,
): String? = when (val channel = selectedLocationChannel) {
    is ChannelID.Location ->
        lookups.geohashIdentityPublicKeyHex(channel.channel.geohash)?.lowercase(Locale.ROOT)
    else -> null
}

/** P27: self first, as `anon` until we announce a nickname, unless already present. */
internal fun geohashPeopleIncludingSelf(
    geohashPeople: List<GeoPerson>,
    myHex: String?,
    nickname: String,
): List<GeoPerson> =
    if (myHex != null && geohashPeople.none { it.id.equals(myHex, ignoreCase = true) }) {
        listOf(
            GeoPerson(
                id = myHex,
                displayName = nickname.ifBlank { "anon" },
                lastSeen = Date(0)
            )
        ) + geohashPeople
    } else {
        geohashPeople
    }

/** P28: drop people already shown as a conversation. */
internal fun visibleGeohashPeople(
    peopleIncludingSelf: List<GeoPerson>,
    excludedIdentityAliases: Set<String>,
): List<GeoPerson> = peopleIncludingSelf.filterNot { person ->
    val alias = "nostr_${person.id.take(16)}".lowercase()
    alias in excludedIdentityAliases
}

/**
 * P29: split announced identities by how they entered this geohash. Bare `anon` heartbeat
 * identities are omitted, while announced names such as `anon1234` remain ordinary participants.
 * Self is retained even before a nickname announcement and is always first in the matching
 * section.
 */
internal fun sectionGeohashPeople(
    people: List<GeoPerson>,
    myId: String?,
    selfIsTeleported: Boolean,
    teleportedIds: Set<String>
): GeohashPeopleSections {
    val normalizedMyId = myId?.lowercase(Locale.ROOT)
    val normalizedTeleportedIds = teleportedIds
        .mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
    fun isSelf(person: GeoPerson): Boolean =
        normalizedMyId != null && person.id.lowercase(Locale.ROOT) == normalizedMyId
    fun isTeleported(person: GeoPerson): Boolean =
        if (isSelf(person)) selfIsTeleported
        else person.id.lowercase(Locale.ROOT) in normalizedTeleportedIds

    val displayedPeople = people.filter { person ->
        isSelf(person) || !isUnannouncedNickname(person.displayName)
    }
    val ordered = displayedPeople.sortedWith(
        compareByDescending<GeoPerson>(::isSelf)
            .thenByDescending { it.lastSeen }
    )
    return GeohashPeopleSections(
        onLocation = ordered.filterNot(::isTeleported),
        teleportedIn = ordered.filter(::isTeleported)
    )
}

/** P27-P33: the whole geohash people card. */
internal fun buildGeohashPeopleRows(
    geohashPeople: List<GeoPerson>,
    myHex: String?,
    nickname: String,
    isTeleported: Boolean,
    teleportedGeo: Set<String>,
    unreadPrivateMessages: Set<String>,
    excludedIdentityAliases: Set<String>,
): GeohashPeopleUi {
    val peopleIncludingSelf = geohashPeopleIncludingSelf(geohashPeople, myHex, nickname)
    val visiblePeople = visibleGeohashPeople(peopleIncludingSelf, excludedIdentityAliases)
    val sections = sectionGeohashPeople(
        people = visiblePeople,
        myId = myHex,
        selfIsTeleported = isTeleported,
        teleportedIds = teleportedGeo
    )
    // P30
    val displayedPeople = sections.onLocation + sections.teleportedIn
    // P31
    val teleportedPersonIds =
        sections.teleportedIn.mapTo(mutableSetOf()) { it.id.lowercase(Locale.ROOT) }
    // P32
    val duplicateBaseNames = duplicateGeohashBaseNames(displayedPeople)

    // P33
    fun personRow(person: GeoPerson): GeoPersonRowUi {
        val isMe = myHex != null && person.id.equals(myHex, ignoreCase = true)
        val personIsTeleported = if (isMe) {
            isTeleported
        } else {
            person.id.lowercase(Locale.ROOT) in teleportedPersonIds
        }
        val (baseNameRaw, _) = splitSuffix(person.displayName)
        val showHashSuffix = splitSuffix(person.displayName)
            .first
            .lowercase(Locale.ROOT) in duplicateBaseNames
        return GeoPersonRowUi(
            id = person.id,
            displayName = person.displayName,
            baseName = baseNameRaw,
            suffix = geohashIdentitySuffix(person, showHashSuffix),
            isMe = isMe,
            isTeleported = personIsTeleported,
            // Not lowercased, unlike the P28 alias (Q-P17).
            hasUnreadDM = unreadPrivateMessages.contains("nostr_${person.id.take(16)}"),
            colorKey = PeerIdentity.nostr(person.id).stableKey,
        )
    }

    return GeohashPeopleUi(
        count = displayedPeople.size,
        onLocation = sections.onLocation.map(::personRow),
        teleportedIn = sections.teleportedIn.map(::personRow),
    )
}
