package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LocationOn
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val isTeleported by viewModel.isTeleported.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val unreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()

    val palette = LocalBitchatPalette.current

    Column(modifier = modifier) {
        if (geohashPeople.isEmpty()) {
            SheetIconSectionHeader(
                icon = Icons.Outlined.LocationOn,
                title = stringResource(R.string.section_on_location)
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AboutHorizontalPadding)
                    .padding(top = 10.dp),
                color = palette.surface,
                shape = AboutCardShape
            ) {
                Text(
                    text = stringResource(R.string.nobody_around),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = palette.textTertiary,
                    modifier = Modifier.padding(
                        horizontal = SheetRowHorizontal,
                        vertical = SheetRowVertical
                    )
                )
            }
        } else {
            val myHex = remember(selectedLocationChannel) {
                when (val channel = selectedLocationChannel) {
                    is com.bitchat.android.geohash.ChannelID.Location -> {
                        try {
                            val identity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(
                                forGeohash = channel.channel.geohash,
                                context = viewModel.getApplication()
                            )
                            identity.publicKeyHex.lowercase()
                        } catch (e: Exception) {
                            Log.e("GeohashPeopleList", "Failed to derive identity: ${e.message}")
                            null
                        }
                    }
                    else -> null
                }
            }

            val orderedPeople = remember(geohashPeople, myHex) {
                geohashPeople.sortedWith { a, b ->
                    when {
                        myHex != null && a.id == myHex && b.id != myHex -> -1
                        myHex != null && b.id == myHex && a.id != myHex -> 1
                        else -> b.lastSeen.compareTo(a.lastSeen)
                    }
                }
            }

            val baseNameCounts = remember(geohashPeople) {
                val counts = mutableMapOf<String, Int>()
                geohashPeople.forEach { person ->
                    val (b, _) = splitSuffix(person.displayName)
                    counts[b] = (counts[b] ?: 0) + 1
                }
                counts
            }

            fun personTeleported(person: GeoPerson): Boolean = if (person.id == myHex) {
                isTeleported
            } else {
                viewModel.isPersonTeleported(person.id)
            }

            val (teleportedPeople, localPeople) = orderedPeople.partition { personTeleported(it) }

            @Composable
            fun personRow(person: GeoPerson) {
                GeohashPersonItem(
                    person = person,
                    isMe = myHex != null && person.id == myHex,
                    hasUnreadDM = unreadPrivateMessages.contains("nostr_${person.id.take(16)}"),
                    isTeleported = person.id != myHex && viewModel.isPersonTeleported(person.id),
                    isMyTeleported = person.id == myHex && isTeleported,
                    nickname = nickname,
                    colorScheme = colorScheme,
                    viewModel = viewModel,
                    showHashSuffix = (baseNameCounts[splitSuffix(person.displayName).first] ?: 0) > 1,
                    onTap = {
                        if (person.id != myHex) {
                            viewModel.startGeohashDM(person.id)
                            onTapPerson()
                        }
                    }
                )
            }

            if (localPeople.isNotEmpty()) {
                SheetIconSectionHeader(
                    icon = Icons.Outlined.LocationOn,
                    title = stringResource(R.string.section_on_location)
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AboutHorizontalPadding)
                        .padding(top = 10.dp),
                    color = palette.surface,
                    shape = AboutCardShape
                ) {
                    Column {
                        localPeople.forEachIndexed { index, person ->
                            if (index > 0) SheetCardDivider()
                            personRow(person)
                        }
                    }
                }
            }

            if (teleportedPeople.isNotEmpty()) {
                SheetIconSectionHeader(
                    icon = Icons.Outlined.Explore,
                    title = stringResource(R.string.section_teleported_in),
                    modifier = Modifier.padding(top = if (localPeople.isNotEmpty()) 20.dp else 0.dp)
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AboutHorizontalPadding)
                        .padding(top = 10.dp),
                    color = palette.surface,
                    shape = AboutCardShape
                ) {
                    Column {
                        teleportedPeople.forEachIndexed { index, person ->
                            if (index > 0) SheetCardDivider()
                            personRow(person)
                        }
                    }
                }
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
    isMyTeleported: Boolean,
    nickname: String,
    colorScheme: ColorScheme,
    viewModel: ChatViewModel,
    showHashSuffix: Boolean,
    onTap: () -> Unit
) {
    val palette = LocalBitchatPalette.current

    val (iconName, iconColor) = when {
        isMe && isMyTeleported -> "face.dashed" to palette.accentOrange
        isTeleported -> "face.dashed" to palette.textSecondary
        isMe -> "face.smiling" to palette.accentOrange
        else -> "face.smiling" to palette.textSecondary
    }
    val statusIcon = when (iconName) {
        "face.dashed" -> Icons.Outlined.Explore
        else -> Icons.Outlined.LocationOn
    }

    val (baseNameRaw, suffixRaw) = splitSuffix(person.displayName)
    val baseName = truncateNickname(baseNameRaw)
    val suffix = if (showHashSuffix) suffixRaw else ""
    val assignedColor = viewModel.colorForNostrPubkey(person.id, palette.isDark)
    val baseColor = if (isMe) palette.accentOrange else assignedColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = SheetRowHorizontal, vertical = SheetRowVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(SheetRowLeadingSlot),
            contentAlignment = Alignment.Center
        ) {
            if (hasUnreadDM) {
                Icon(
                    imageVector = Icons.Filled.Email,
                    contentDescription = stringResource(R.string.cd_unread_message),
                    modifier = Modifier.size(22.dp),
                    tint = palette.accentOrange
                )
            } else {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = if (isTeleported || isMyTeleported) "Teleported user" else "User",
                    modifier = Modifier.size(22.dp),
                    tint = iconColor.copy(alpha = if (iconName == "face.dashed") 0.6f else 1.0f)
                )
            }
        }

        Spacer(modifier = Modifier.width(SheetRowLeadingGutter))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = baseName,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                    color = baseColor.copy(alpha = SUFFIX_ALPHA)
                )
            }

            if (isMe) {
                Text(
                    text = stringResource(R.string.you_suffix),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = baseColor
                )
            }
        }
    }
}
