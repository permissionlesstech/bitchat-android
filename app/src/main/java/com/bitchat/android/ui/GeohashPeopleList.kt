package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.colorForPeer
import com.bitchat.android.R
import com.bitchat.android.ui.theme.LocalBitchatPalette
import java.util.*

/**
 * Geohash people list — card groups matching location / settings sheet rows.
 */

data class GeoPerson(
    val id: String,           // pubkey hex (lowercased) - matches iOS
    val displayName: String,  // nickname with #suffix - matches iOS
    val lastSeen: Date        // activity timestamp - matches iOS
)

@Composable
fun GeohashPeopleList(
    viewModel: ChatViewModel,
    onTapPerson: () -> Unit,
    modifier: Modifier = Modifier,
    excludedIdentityAliases: Set<String> = emptySet()
) {
    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val isTeleported by viewModel.isTeleported.collectAsStateWithLifecycle()
    val teleportedGeo by viewModel.teleportedGeo.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val unreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()

    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme
    val myHex = remember(selectedLocationChannel) {
        when (val channel = selectedLocationChannel) {
            is com.bitchat.android.geohash.ChannelID.Location -> {
                try {
                    val identity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(
                        forGeohash = channel.channel.geohash,
                        context = viewModel.getApplication()
                    )
                    identity.publicKeyHex.lowercase(Locale.ROOT)
                } catch (e: Exception) {
                    Log.e("GeohashPeopleList", "Failed to derive identity: ${e.message}")
                    null
                }
            }
            else -> null
        }
    }
    val peopleIncludingSelf = remember(geohashPeople, myHex, nickname) {
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
    }
    val visiblePeople = remember(peopleIncludingSelf, excludedIdentityAliases) {
        peopleIncludingSelf.filterNot { person ->
            val alias = "nostr_${person.id.take(16)}".lowercase()
            alias in excludedIdentityAliases
        }
    }
    val sections = remember(visiblePeople, myHex, isTeleported, teleportedGeo) {
        sectionGeohashPeople(
            people = visiblePeople,
            myId = myHex,
            selfIsTeleported = isTeleported,
            teleportedIds = teleportedGeo
        )
    }
    val displayedPeople = remember(sections) {
        sections.onLocation + sections.teleportedIn
    }
    val teleportedPersonIds = remember(sections.teleportedIn) {
        sections.teleportedIn.mapTo(mutableSetOf()) { it.id.lowercase(Locale.ROOT) }
    }
    val duplicateBaseNames = remember(displayedPeople) {
        duplicateGeohashBaseNames(displayedPeople)
    }

    Column(modifier = modifier) {
        SheetIconSectionHeader(
            iconRes = R.drawable.ic_spec_people,
            title = stringResource(R.string.people_count_title, displayedPeople.size)
        )

        if (displayedPeople.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AboutHorizontalPadding)
                    .padding(top = 10.dp),
                color = colorScheme.surface,
                shape = AboutCardShape
            ) {
                Text(
                    text = stringResource(R.string.nobody_around),
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    color = palette.textTertiary,
                    modifier = Modifier.padding(
                        horizontal = SheetRowHorizontal,
                        vertical = SheetRowVertical
                    )
                )
            }
        } else {
            @Composable
            fun personRow(person: GeoPerson) {
                val isMe = myHex != null && person.id.equals(myHex, ignoreCase = true)
                val personIsTeleported = if (isMe) {
                    isTeleported
                } else {
                    person.id.lowercase(Locale.ROOT) in teleportedPersonIds
                }
                GeohashPersonItem(
                    person = person,
                    isMe = isMe,
                    hasUnreadDM = unreadPrivateMessages.contains("nostr_${person.id.take(16)}"),
                    isTeleported = personIsTeleported,
                    viewModel = viewModel,
                    showHashSuffix = splitSuffix(person.displayName)
                        .first
                        .lowercase(Locale.ROOT) in duplicateBaseNames,
                    onTap = {
                        if (!isMe) {
                            viewModel.startGeohashDM(person.id)
                            onTapPerson()
                        }
                    }
                )
            }

            if (sections.onLocation.isNotEmpty()) {
                AboutSectionLabel(text = stringResource(R.string.section_on_location))
                PeopleCard(
                    people = sections.onLocation,
                    row = { personRow(it) }
                )
            }

            if (sections.teleportedIn.isNotEmpty()) {
                AboutSectionLabel(text = stringResource(R.string.section_teleported_in))
                PeopleCard(
                    people = sections.teleportedIn,
                    row = { personRow(it) }
                )
            }
        }
    }
}

internal data class GeohashPeopleSections(
    val onLocation: List<GeoPerson>,
    val teleportedIn: List<GeoPerson>
)

/**
 * Names that require a short identity suffix, calculated across both people sections.
 *
 * Matching is case-insensitive to mirror geohash chat's nickname collision handling.
 */
internal fun duplicateGeohashBaseNames(people: List<GeoPerson>): Set<String> =
    people
        .groupingBy { splitSuffix(it.displayName).first.lowercase(Locale.ROOT) }
        .eachCount()
        .filterValues { it > 1 }
        .keys

/**
 * The same `#abcd` disambiguator used by geohash chat.
 *
 * Presence rows normally carry only a base nickname, so derive the suffix from the full Nostr
 * public key when a collision exists. Preserve an already-announced suffix for compatibility.
 */
internal fun geohashIdentitySuffix(person: GeoPerson, showHashSuffix: Boolean): String {
    if (!showHashSuffix) return ""
    val announcedSuffix = splitSuffix(person.displayName).second
    return announcedSuffix.ifEmpty { "#${person.id.takeLast(4)}" }
}

internal fun disambiguatedGeohashDisplayName(
    person: GeoPerson,
    duplicateBaseNames: Set<String>,
): String {
    val baseName = splitSuffix(person.displayName).first
    val showSuffix = baseName.lowercase(Locale.ROOT) in duplicateBaseNames
    return baseName + geohashIdentitySuffix(person, showSuffix)
}

/**
 * Split announced identities by how they entered this geohash. Bare `anon` heartbeat identities
 * are omitted, while announced names such as `anon1234` remain ordinary participants. Self is
 * retained even before a nickname announcement and is always first in the matching section.
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

/** One uncapped card of people. The enclosing sheet owns scrolling. */
@Composable
private fun PeopleCard(
    people: List<GeoPerson>,
    row: @Composable (GeoPerson) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding)
            .padding(top = 10.dp),
        color = colorScheme.surface,
        shape = AboutCardShape
    ) {
        AnimatedRowColumn(items = people, key = { it.id }) { index, person ->
            Column {
                if (index > 0) SheetCardDivider()
                row(person)
            }
        }
    }
}

@Composable
private fun GeohashPersonItem(
    person: GeoPerson,
    isMe: Boolean,
    hasUnreadDM: Boolean,
    isTeleported: Boolean,
    viewModel: ChatViewModel,
    showHashSuffix: Boolean,
    onTap: () -> Unit
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme

    val statusIconRes =
        if (isTeleported) R.drawable.ic_spec_teleport
        else R.drawable.ic_spec_on_location_person

    val (baseNameRaw, _) = splitSuffix(person.displayName)
    val baseName = truncateNickname(baseNameRaw)
    val suffix = geohashIdentitySuffix(person, showHashSuffix)
    val assignedColor = colorForPeer(
        viewModel.peerIdentityForNostrPubkey(person.id),
        palette
    )
    val baseColor = if (isMe) palette.accentOrange else assignedColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = SheetRowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PeerAvatar(
            name = baseNameRaw,
            color = baseColor,
            badge = {
                Icon(
                    painter = painterResource(statusIconRes),
                    contentDescription = if (isTeleported) {
                        stringResource(R.string.cd_teleported)
                    } else {
                        stringResource(R.string.section_on_location)
                    },
                    modifier = Modifier.size(13.dp),
                    tint = if (isTeleported) palette.accentPurple else colorScheme.primary
                )
            }
        )

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = baseName,
                fontFamily = BitchatFontFamily,
                fontSize = 14.sp,
                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = baseColor.copy(alpha = SUFFIX_ALPHA)
                )
            }

            if (isMe) {
                Text(
                    text = stringResource(R.string.you_suffix),
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    color = baseColor
                )
            }
        }

        if (hasUnreadDM) {
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = stringResource(R.string.cd_unread_message),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp),
                tint = palette.accentOrange
            )
        }
    }
}
