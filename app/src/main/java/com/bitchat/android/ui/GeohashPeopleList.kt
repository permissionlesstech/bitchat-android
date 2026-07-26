package com.bitchat.android.ui

import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import com.bitchat.android.ui.theme.LocalBitchatPalette
import java.util.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R

/**
 * GeohashPeopleList - iOS-compatible component for displaying geohash participants
 * Shows peers discovered through Nostr ephemeral events instead of Bluetooth peers
 */

/**
 * GeoPerson data class - matches iOS GeoPerson structure exactly
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
    
    // Observe geohash people from ChatViewModel
    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val isTeleported by viewModel.isTeleported.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val unreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    
    val palette = LocalBitchatPalette.current

    Column(modifier = modifier) {
        if (geohashPeople.isEmpty()) {
            SheetSectionLabel(text = stringResource(R.string.section_on_location))
            // Empty state - matches iOS "nobody around..."
            Text(
                text = stringResource(R.string.nobody_around),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.textTertiary,
                modifier = Modifier.padding(
                    horizontal = SheetHorizontalPadding + 14.dp,
                    vertical = 12.dp
                )
            )
        } else {
            // Get current geohash identity for "me" detection
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
            
            // Sort people: me first, then by lastSeen (matches iOS exactly)
            val orderedPeople = remember(geohashPeople, myHex) {
                geohashPeople.sortedWith { a, b ->
                    when {
                        myHex != null && a.id == myHex && b.id != myHex -> -1
                        myHex != null && b.id == myHex && a.id != myHex -> 1
                        else -> b.lastSeen.compareTo(a.lastSeen) // Most recent first
                    }
                }
            }

            // Compute base name collisions to decide whether to show hash suffix
            val baseNameCounts = remember(geohashPeople) {
                val counts = mutableMapOf<String, Int>()
                geohashPeople.forEach { person ->
                    val (b, _) = com.bitchat.android.ui.splitSuffix(person.displayName)
                    counts[b] = (counts[b] ?: 0) + 1
                }
                counts
            }
            
            // Split by presence: people physically in the geohash versus people who teleported
            // in. Mixing them hid the fact that a "nearby" channel can contain remote users.
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
                    showHashSuffix = (baseNameCounts[com.bitchat.android.ui.splitSuffix(person.displayName).first] ?: 0) > 1,
                    onTap = {
                        if (person.id != myHex) {
                            // TODO: Re-enable when NIP-17 geohash DM issues are fixed
                            // Start geohash DM (iOS-compatible)
                            viewModel.startGeohashDM(person.id)
                            onTapPerson()
                        }
                    }
                )
            }

            if (localPeople.isNotEmpty()) {
                SheetSectionLabel(text = stringResource(R.string.section_on_location))
                localPeople.forEach { personRow(it) }
            }

            if (teleportedPeople.isNotEmpty()) {
                SheetSectionLabel(text = stringResource(R.string.section_teleported_in))
                teleportedPeople.forEach { personRow(it) }
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

    Surface(
        onClick = onTap,
        color = palette.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SheetHorizontalPadding, vertical = 3.dp)
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon logic matching iOS exactly
        if (hasUnreadDM) {
            // Unread DM indicator (orange envelope)
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = stringResource(R.string.cd_unread_message),
                modifier = Modifier.size(16.dp),
                tint = palette.accentOrange
            )
        } else {
            // Face icon with teleportation state
            val (iconName, iconColor) = when {
                isMe && isMyTeleported -> "face.dashed" to palette.accentOrange
                isTeleported -> "face.dashed" to palette.textSecondary
                isMe -> "face.smiling" to palette.accentOrange
                else -> "face.smiling" to palette.textSecondary
            }
            
            // Use appropriate Material icon (closest match to iOS SF Symbols)
            val icon = when (iconName) {
                "face.dashed" -> Icons.Outlined.Explore
                else -> Icons.Outlined.LocationOn
            }
            
            Icon(
                imageVector = icon,
                contentDescription = if (isTeleported || isMyTeleported) "Teleported user" else "User",
                modifier = Modifier.size(16.dp),
                tint = iconColor.copy(alpha = if (iconName == "face.dashed") 0.6f else 1.0f) // Make dashed faces slightly transparent
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Display name with suffix handling
        val (baseNameRaw, suffixRaw) = com.bitchat.android.ui.splitSuffix(person.displayName)
        val baseName = truncateNickname(baseNameRaw)
        val suffix = if (showHashSuffix) suffixRaw else ""
        
        // Get consistent peer color (matches iOS color assignment exactly)
        val assignedColor = viewModel.colorForNostrPubkey(person.id, palette.isDark)
        val baseColor = if (isMe) palette.accentOrange else assignedColor
        
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Base name with peer-specific color
            Text(
                text = baseName,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Suffix (collision-resistant #abcd) in lighter shade
            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                    color = baseColor.copy(alpha = SUFFIX_ALPHA)
                )
            }

            // "You" indicator for current user
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
}


