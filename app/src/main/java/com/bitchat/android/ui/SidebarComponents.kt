package com.bitchat.android.ui

import com.bitchat.android.R
import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.bitchat.android.ui.theme.BASE_FONT_SIZE


/**
 * Sidebar components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun SidebarOverlay(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }

    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptyList())
    val currentChannel by viewModel.currentChannel.observeAsState()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.observeAsState()
    val nickname by viewModel.nickname.observeAsState("")
    val unreadChannelMessages by viewModel.unreadChannelMessages.observeAsState(emptyMap())
    val peerNicknames by viewModel.peerNicknames.observeAsState(emptyMap())
    val peerRSSI by viewModel.peerRSSI.observeAsState(emptyMap())

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(indication = null, interactionSource = interactionSource) { onDismiss() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .width(280.dp)
                .align(Alignment.CenterEnd)
                .clickable { /* Prevent dismissing when clicking sidebar */ }
        ) {
            // Grey vertical bar for visual continuity (matches iOS)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(Color.Gray.copy(alpha = 0.3f))
            )
            
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .background(colorScheme.background.copy(alpha = 0.95f))
                    .windowInsetsPadding(WindowInsets.statusBars) // Add status bar padding
            ) {
                SidebarHeader()

                HorizontalDivider()
                
                // Scrollable content
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Channels section
                    if (joinedChannels.isNotEmpty()) {
                        item {
                            ChannelsSection(
                                channels = joinedChannels.toList(), // Convert Set to List
                                currentChannel = currentChannel,
                                colorScheme = colorScheme,
                                onChannelClick = { channel ->
                                    viewModel.switchToChannel(channel)
                                    onDismiss()
                                },
                                onLeaveChannel = { channel ->
                                    viewModel.leaveChannel(channel)
                                },
                                unreadChannelMessages = unreadChannelMessages
                            )
                        }
                        
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                    
                    // People section - switch between mesh and geohash lists (iOS-compatible)
                    item {
                        val selectedLocationChannel by viewModel.selectedLocationChannel.observeAsState()
                        
                        when (selectedLocationChannel) {
                            is com.bitchat.android.geohash.ChannelID.Location -> {
                                // Show geohash people list when in location channel
                                GeohashPeopleList(
                                    viewModel = viewModel,
                                    onTapPerson = onDismiss
                                )
                            }
                            else -> {
                                // Show mesh peer list when in mesh channel (default)
                                PeopleSection(
                                    modifier = modifier.padding(bottom = 16.dp),
                                    connectedPeers = connectedPeers,
                                    peerNicknames = peerNicknames,
                                    peerRSSI = peerRSSI,
                                    nickname = nickname,
                                    colorScheme = colorScheme,
                                    selectedPrivatePeer = selectedPrivatePeer,
                                    viewModel = viewModel,
                                    onPrivateChatStart = { peerID ->
                                        viewModel.startPrivateChat(peerID)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarHeader() {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = Modifier
            .height(42.dp) // Match reduced main header height
            .fillMaxWidth()
            .background(colorScheme.background.copy(alpha = 0.95f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(id = R.string.your_network).uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            ),
            color = colorScheme.onSurface
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun ChannelsSection(
    channels: List<String>,
    currentChannel: String?,
    colorScheme: ColorScheme,
    onChannelClick: (String) -> Unit,
    onLeaveChannel: (String) -> Unit,
    unreadChannelMessages: Map<String, Int> = emptyMap()
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Person, // Using Person icon as placeholder
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(id = R.string.channels).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }
        
        channels.forEach { channel ->
            val isSelected = channel == currentChannel
            val unreadCount = unreadChannelMessages[channel] ?: 0
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChannelClick(channel) }
                    .background(
                        if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else Color.Transparent
                    )
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Unread badge for channels
                UnreadBadge(
                    count = unreadCount,
                    colorScheme = colorScheme,
                    modifier = Modifier.padding(end = 8.dp)
                )
                
                Text(
                    text = channel, // Channel already contains the # prefix
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                
                // Leave channel button
                IconButton(
                    onClick = { onLeaveChannel(channel) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(com.bitchat.android.R.string.cd_leave_channel),
                        modifier = Modifier.size(14.dp),
                        tint = colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun PeopleSection(
    modifier: Modifier  = Modifier,
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerRSSI: Map<String, Int>,
    nickname: String,
    colorScheme: ColorScheme,
    selectedPrivatePeer: String?,
    viewModel: ChatViewModel,
    onPrivateChatStart: (String) -> Unit
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Group, // Using Person icon for people
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(id = R.string.people).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }
        
        if (connectedPeers.isEmpty()) {
            Text(
                text = stringResource(id = R.string.no_one_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }

        // Observe reactive state for favorites and fingerprints
        val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
        val privateChats by viewModel.privateChats.observeAsState(emptyMap())
        val favoritePeers by viewModel.favoritePeers.observeAsState(emptySet())
        val peerFingerprints by viewModel.peerFingerprints.observeAsState(emptyMap())
        
        // Reactive favorite computation for all peers
        val peerFavoriteStates = remember(favoritePeers, peerFingerprints, connectedPeers) {
            connectedPeers.associateWith { peerID ->
                // Reactive favorite computation - same as ChatHeader
                val fingerprint = peerFingerprints[peerID]
                fingerprint != null && favoritePeers.contains(fingerprint)
            }
        }
        
        // Build mapping of connected peerID -> noise key hex to unify with offline favorites
        val noiseHexByPeerID: Map<String, String> = connectedPeers.associateWith { pid ->
            try {
                viewModel.meshService.getPeerInfo(pid)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) { null }
        }.filterValues { it != null }.mapValues { it.value!! }

        Log.d("SidebarComponents", "Recomposing with ${favoritePeers.size} favorites, peer states: $peerFavoriteStates")

        // Smart sorting: unread DMs first, then by most recent DM, then favorites, then alphabetical
        val sortedPeers = connectedPeers.sortedWith(
            compareBy<String> { !hasUnreadPrivateMessages.contains(it) } // Unread DM senders first
            .thenByDescending { privateChats[it]?.maxByOrNull { msg -> msg.timestamp }?.timestamp?.time ?: 0L } // Most recent DM (convert Date to Long)
            .thenBy { !(peerFavoriteStates[it] ?: false) } // Favorites first
            .thenBy { (if (it == nickname) "You" else (peerNicknames[it] ?: it)).lowercase() } // Alphabetical
        )
        
        // Build a map of base name counts across all people shown in the list (connected + offline + nostr)
        val hex64Regex = Regex("^[0-9a-fA-F]{64}$")

        // Helper to compute display name used for a given key
        fun computeDisplayNameForPeerId(key: String): String {
            return if (key == nickname) "You" else (peerNicknames[key] ?: (privateChats[key]?.lastOrNull()?.sender ?: key.take(12)))
        }

        val baseNameCounts = mutableMapOf<String, Int>()

        // Connected peers
        sortedPeers.forEach { pid ->
            val dn = computeDisplayNameForPeerId(pid)
            val (b, _) = com.bitchat.android.ui.splitSuffix(dn)
            if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
        }

        // Offline favorites (exclude ones mapped to connected)
        val offlineFavorites = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getOurFavorites()
        offlineFavorites.forEach { fav ->
            val favPeerID = fav.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
            val isMappedToConnected = noiseHexByPeerID.values.any { it.equals(favPeerID, ignoreCase = true) }
            if (!isMappedToConnected) {
                val dn = peerNicknames[favPeerID] ?: fav.peerNickname
                val (b, _) = com.bitchat.android.ui.splitSuffix(dn)
                if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
            }
        }

        // Nostr-only conversations
        val connectedIds = sortedPeers.toSet()
        val appendedOfflineIds = mutableSetOf<String>()
        privateChats.keys
            .filter { key ->
                (key.startsWith("nostr_") || hex64Regex.matches(key)) &&
                !connectedIds.contains(key) &&
                !noiseHexByPeerID.values.any { it.equals(key, ignoreCase = true) }
            }
            .forEach { convKey ->
                val dn = peerNicknames[convKey] ?: (privateChats[convKey]?.lastOrNull()?.sender ?: convKey.take(12))
                val (b, _) = com.bitchat.android.ui.splitSuffix(dn)
                if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
            }

        sortedPeers.forEach { peerID ->
            val isFavorite = peerFavoriteStates[peerID] ?: false
            // fingerprint and favorite relationship resolution not needed here; UI will show Nostr globe for appended offline favorites below
            
            val noiseHex = noiseHexByPeerID[peerID]
            val meshUnread = hasUnreadPrivateMessages.contains(peerID)
            val nostrUnread = if (noiseHex != null) hasUnreadPrivateMessages.contains(noiseHex) else false
            val combinedHasUnread = meshUnread || nostrUnread
            val combinedUnreadCount = (
                privateChats[peerID]?.count { msg -> msg.sender != nickname && meshUnread } ?: 0
            ) + (
                if (noiseHex != null) privateChats[noiseHex]?.count { msg -> msg.sender != nickname && nostrUnread } ?: 0 else 0
            )

            val displayName = if (peerID == nickname) "You" else (peerNicknames[peerID] ?: (privateChats[peerID]?.lastOrNull()?.sender ?: peerID.take(12)))
            val (bName, _) = com.bitchat.android.ui.splitSuffix(displayName)
            val showHash = (baseNameCounts[bName] ?: 0) > 1

            val directMap by viewModel.peerDirect.observeAsState(emptyMap())
            val isDirectLive = directMap[peerID] ?: try { viewModel.meshService.getPeerInfo(peerID)?.isDirectConnection == true } catch (_: Exception) { false }
            PeerItem(
                peerID = peerID,
                displayName = displayName,
                isDirect = isDirectLive,
                isSelected = peerID == selectedPrivatePeer,
                isFavorite = isFavorite,
                hasUnreadDM = combinedHasUnread,
                colorScheme = colorScheme,
                viewModel = viewModel,
                onItemClick = { onPrivateChatStart(peerID) },
                onToggleFavorite = { 
                    Log.d("SidebarComponents", "Sidebar toggle favorite: peerID=$peerID, currentFavorite=$isFavorite")
                    viewModel.toggleFavorite(peerID) 
                },
                unreadCount = if (combinedUnreadCount > 0) combinedUnreadCount else if (combinedHasUnread) 1 else 0,
                showNostrGlobe = false,
                showHashSuffix = showHash
            )
        }

        // Append offline favorites we actively favorite (and not currently connected)
        offlineFavorites.forEach { fav ->
            val favPeerID = fav.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
            // If any connected peer maps to this noise key, skip showing the offline entry
            val isMappedToConnected = noiseHexByPeerID.values.any { it.equals(favPeerID, ignoreCase = true) }
            if (isMappedToConnected) return@forEach

            // Resolve potential Nostr conversation key for this favorite (for unread detection)
            val nostrConvKey: String? = try {
                val npubOrHex = com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNostrPubkey(fav.peerNoisePublicKey)
                if (npubOrHex != null) {
                    val hex = if (npubOrHex.startsWith("npub")) {
                        val (hrp, data) = com.bitchat.android.nostr.Bech32.decode(npubOrHex)
                        if (hrp == "npub") data.joinToString("") { "%02x".format(it) } else null
                    } else {
                        npubOrHex.lowercase()
                    }
                    hex?.let { "nostr_${it.take(16)}" }
                } else null
            } catch (_: Exception) { null }

            val hasUnread = hasUnreadPrivateMessages.contains(favPeerID) || (nostrConvKey != null && hasUnreadPrivateMessages.contains(nostrConvKey))

            // If user clicks an offline favorite and the mapped peer is currently connected under a different ID,
            // open chat with the connected peerID instead of the noise hex for a seamless window
            val mappedConnectedPeerID = noiseHexByPeerID.entries.firstOrNull { it.value.equals(favPeerID, ignoreCase = true) }?.key
            val dn = peerNicknames[favPeerID] ?: fav.peerNickname
            val (bName, _) = com.bitchat.android.ui.splitSuffix(dn)
            val showHash = (baseNameCounts[bName] ?: 0) > 1

            // Compute unreadCount from either noise conversation or Nostr conversation
            val unreadCount = (
                privateChats[favPeerID]?.count { msg -> msg.sender != nickname && hasUnreadPrivateMessages.contains(favPeerID) } ?: 0
            ) + (
                if (nostrConvKey != null) privateChats[nostrConvKey]?.count { msg -> msg.sender != nickname && hasUnreadPrivateMessages.contains(nostrConvKey) } ?: 0 else 0
            )

            PeerItem(
                peerID = favPeerID,
                displayName = dn,
                isDirect = false,
                isSelected = (mappedConnectedPeerID ?: favPeerID) == selectedPrivatePeer,
                isFavorite = true,
                hasUnreadDM = hasUnread,
                colorScheme = colorScheme,
                viewModel = viewModel,
                onItemClick = { onPrivateChatStart(mappedConnectedPeerID ?: favPeerID) },
                onToggleFavorite = { 
                    Log.d("SidebarComponents", "Sidebar toggle favorite (offline): peerID=$favPeerID")
                    viewModel.toggleFavorite(favPeerID)
                },
                unreadCount = if (unreadCount > 0) unreadCount else if (hasUnread) 1 else 0,
                showNostrGlobe = (fav.isMutual && fav.peerNostrPublicKey != null),
                showHashSuffix = showHash
            )
            appendedOfflineIds.add(favPeerID)
        }

        // NOTE: Do NOT append Nostr-only (nostr_*) conversations to the mesh people list.
        // Geohash DMs should appear in the GeohashPeople list for the active geohash, not in mesh offline contacts.
        // We intentionally remove previously-added behavior that mixed geohash DMs into mesh sidebar.
        // If you need to surface non-geohash offline mesh conversations in the future, do it here for 64-hex noise IDs only.
        /*
        val alreadyShownIds = connectedIds + appendedOfflineIds
        privateChats.keys
            .filter { key ->
                // Only include 64-hex noise IDs (mesh identities); exclude any nostr_* aliases
                hex64Regex.matches(key) &&
                !alreadyShownIds.contains(key) &&
                // Skip if this key maps to a connected peer via noiseHex mapping
                !noiseHexByPeerID.values.any { it.equals(key, ignoreCase = true) }
            }
            .sortedBy { key -> privateChats[key]?.lastOrNull()?.timestamp }
            .forEach { convKey ->
                val lastSender = privateChats[convKey]?.lastOrNull()?.sender
                val dn = peerNicknames[convKey] ?: (lastSender ?: convKey.take(12))
                val (bName, _) = com.bitchat.android.ui.splitSuffix(dn)
                val showHash = (baseNameCounts[bName] ?: 0) > 1

                PeerItem(
                    peerID = convKey,
                    displayName = dn,
                    isDirect = false,
                    isSelected = convKey == selectedPrivatePeer,
                    isFavorite = false,
                    hasUnreadDM = hasUnreadPrivateMessages.contains(convKey),
                    colorScheme = colorScheme,
                    viewModel = viewModel,
                    onItemClick = { onPrivateChatStart(convKey) },
                    onToggleFavorite = { viewModel.toggleFavorite(convKey) },
                    unreadCount = privateChats[convKey]?.count { msg ->
                        msg.sender != nickname && hasUnreadPrivateMessages.contains(convKey)
                    } ?: if (hasUnreadPrivateMessages.contains(convKey)) 1 else 0,
                    showNostrGlobe = false,
                    showHashSuffix = showHash
                )
            }
        */
        // End intentional removal
        
    }
}

@Composable
private fun PeerItem(
    peerID: String,
    displayName: String,
    isDirect: Boolean,
    isSelected: Boolean,
    isFavorite: Boolean,
    hasUnreadDM: Boolean,
    colorScheme: ColorScheme,
    viewModel: ChatViewModel,
    onItemClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    unreadCount: Int = 0,
    showNostrGlobe: Boolean = false,
    showHashSuffix: Boolean = true
) {
    // Split display name for hashtag suffix support (iOS-compatible)
    val (baseNameRaw, suffixRaw) = com.bitchat.android.ui.splitSuffix(displayName)
    val baseName = truncateNickname(baseNameRaw)
    val suffix = if (showHashSuffix) suffixRaw else ""
    val isMe = displayName == "You" || peerID == viewModel.nickname.value
    
    // Get consistent peer color (iOS-compatible)
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val assignedColor = viewModel.colorForMeshPeer(peerID, isDark)
    val baseColor = if (isMe) Color(0xFFFF9500) else assignedColor
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick() }
            .background(
                if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Show unread badge or signal strength  
        if (hasUnreadDM) {
            // Show mail icon for unread DMs (iOS orange)
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = stringResource(com.bitchat.android.R.string.cd_unread_message),
                modifier = Modifier.size(16.dp),
                tint = Color(0xFFFF9500) // iOS orange
            )
        } else {
            // Connection indicator icons
            if (showNostrGlobe) {
                // Purple globe to indicate Nostr availability
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = stringResource(com.bitchat.android.R.string.cd_reachable_via_nostr),
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF9C27B0) // Purple
                )
            } else if (!isDirect && isFavorite) {
                // Offline favorited user: show outlined circle icon
                Icon(
                    //painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_offline_favorite),
                    imageVector = Icons.Outlined.Circle,
                    contentDescription = stringResource(com.bitchat.android.R.string.cd_offline_favorite),
                    modifier = Modifier.size(16.dp),
                    tint = Color.Gray
                )
            } else {
                Icon(
                    imageVector = if (isDirect) Icons.Outlined.SettingsInputAntenna else Icons.Filled.Route,
                    contentDescription = if (isDirect) "Direct Bluetooth" else "Routed",
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Display name with iOS-style color and hashtag suffix support
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Base name with peer-specific color
            Text(
                text = baseName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal
                ),
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            // Hashtag suffix in lighter shade (iOS-style)
            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = BASE_FONT_SIZE.sp
                    ),
                    color = baseColor.copy(alpha = 0.6f)
                )
            }
        }
        
        // Favorite star with proper filled/outlined states
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                modifier = Modifier.size(16.dp),
                tint = if (isFavorite) Color(0xFFFFD700) else Color(0xFF4CAF50)
            )
        }
    }
}



@Composable
private fun SignalStrengthIndicator(
    signalStrength: Int,
    colorScheme: ColorScheme
) {
    Row(modifier = Modifier.width(24.dp)) {
        repeat(3) { index ->
            val opacity = when {
                signalStrength >= (index + 1) * 33 -> 1f
                else -> 0.2f
            }
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = (4 + index * 2).dp)
                    .background(
                        colorScheme.onSurface.copy(alpha = opacity),
                        RoundedCornerShape(1.dp)
                    )
            )
            if (index < 2) Spacer(modifier = Modifier.width(2.dp))
        }
    }
}

/**
 * Reusable unread badge component for both channels and private messages
 */
@Composable
private fun UnreadBadge(
    count: Int,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Box(
            modifier = modifier
                .background(
                    color = Color(0xFFFFD700), // Yellow color
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 2.dp, vertical = 0.dp)
                .defaultMinSize(minWidth = 14.dp, minHeight = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.Black // Black text on yellow background
            )
        }
    }
}

/**
 * Convert RSSI value (dBm) to signal strength percentage (0-100)
 * RSSI typically ranges from -30 (excellent) to -100 (very poor)
 * Maps to 0-100 scale where:
 * - 0-32: No signal (0 bars)
 * - 33-65: Weak (1 bar) 
 * - 66-98: Good (2 bars)
 * - 99-100: Excellent (3 bars)
 */
private fun convertRSSIToSignalStrength(rssi: Int?): Int {
    if (rssi == null) return 0
    
    return when {
        rssi >= -40 -> 100  // Excellent signal
        rssi >= -55 -> 85   // Very good signal  
        rssi >= -70 -> 70   // Good signal
        rssi >= -85 -> 50   // Fair signal
        rssi >= -100 -> 25  // Poor signal
        else -> 0           // Very poor or no signal
    }
}
