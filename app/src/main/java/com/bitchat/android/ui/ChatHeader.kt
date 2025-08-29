package com.bitchat.android.ui

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.core.ui.utils.singleOrTripleClickable

/**
 * Header components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

/**
 * Reactive helper to compute favorite state from fingerprint mapping
 * This eliminates the need for static isFavorite parameters and makes
 * the UI reactive to fingerprint manager changes
 */
@Composable
fun isFavoriteReactive(
    peerID: String,
    peerFingerprints: Map<String, String>,
    favoritePeers: Set<String>
): Boolean {
    return remember(peerID, peerFingerprints, favoritePeers) {
        val fingerprint = peerFingerprints[peerID]
        fingerprint != null && favoritePeers.contains(fingerprint)
    }
}

@Composable
fun TorStatusIcon(
    modifier: Modifier = Modifier
) {
    val torStatus by com.bitchat.android.net.TorManager.statusFlow.collectAsState()
    
    if (torStatus.mode != com.bitchat.android.net.TorMode.OFF) {
        val cableColor = when {
            torStatus.running && torStatus.bootstrapPercent < 100 -> Color(0xFFFF9500)
            torStatus.running && torStatus.bootstrapPercent >= 100 -> Color(0xFF00C851)
            else -> Color.Red
        }
        Icon(
            imageVector = Icons.Outlined.Cable,
            contentDescription = "Tor status",
            modifier = modifier,
            tint = cableColor
        )
    }
}

@Composable
fun NoiseSessionIcon(
    sessionState: String?,
    modifier: Modifier = Modifier
) {
    val (icon, color, contentDescription) = when (sessionState) {
        "uninitialized" -> Triple(
            Icons.Outlined.NoEncryption,
            Color(0x87878700), // Grey - ready to establish
            "Ready for handshake"
        )
        "handshaking" -> Triple(
            Icons.Outlined.Sync,
            Color(0x87878700), // Grey - in progress
            "Handshake in progress"
        )
        "established" -> Triple(
            Icons.Filled.Lock,
            Color(0xFFFF9500), // Orange - secure
            "End-to-end encrypted"
        )
        else -> { // "failed" or any other state
            Triple(
                Icons.Outlined.Warning,
                Color(0xFFFF4444), // Red - error
                "Handshake failed"
            )
        }
    }
    
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = color
    )
}

@Composable
fun NicknameEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    
    // Auto-scroll to end when text changes (simulates cursor following)
    LaunchedEffect(value) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = "@",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.primary.copy(alpha = 0.8f)
        )
        
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = colorScheme.primary,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { 
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier
                .widthIn(max = 120.dp)
                .horizontalScroll(scrollState)
        )
    }
}

@Composable
fun PeerCounter(
    connectedPeers: List<String>,
    joinedChannels: Set<String>,
    hasUnreadChannels: Map<String, Int>,
    hasUnreadPrivateMessages: Set<String>,
    isConnected: Boolean,
    selectedLocationChannel: com.bitchat.android.geohash.ChannelID?,
    geohashPeople: List<GeoPerson>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Compute channel-aware people count and color (matches iOS logic exactly)
    val (peopleCount, countColor) = when (selectedLocationChannel) {
        is com.bitchat.android.geohash.ChannelID.Location -> {
            // Geohash channel: show geohash participants
            val count = geohashPeople.size
            val green = Color(0xFF00C851) // Standard green
            Pair(count, if (count > 0) green else Color.Gray)
        }
        is com.bitchat.android.geohash.ChannelID.Mesh,
        null -> {
            // Mesh channel: show Bluetooth-connected peers (excluding self)
            val count = connectedPeers.size
            val meshBlue = Color(0xFF007AFF) // iOS-style blue for mesh
            Pair(count, if (isConnected && count > 0) meshBlue else Color.Gray)
        }
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onClick() }.padding(end = 8.dp) // Added right margin to match "bitchat" logo spacing
    ) {
        if (hasUnreadChannels.values.any { it > 0 }) {
            // Channel icon in a Box to ensure consistent size with other icons
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "#",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF0080FF),
                    fontSize = 16.sp
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
        
        if (hasUnreadPrivateMessages.isNotEmpty()) {
            // Filled mail icon to match sidebar style
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = "Unread private messages",
                modifier = Modifier.size(16.dp),
                tint = Color(0xFFFF9500) // Orange to match private message theme
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        
        Icon(
            imageVector = Icons.Default.Group,
            contentDescription = when (selectedLocationChannel) {
                is com.bitchat.android.geohash.ChannelID.Location -> "Geohash participants"
                else -> "Connected peers"
            },
            modifier = Modifier.size(16.dp),
            tint = countColor
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$peopleCount",
            style = MaterialTheme.typography.bodyMedium,
            color = countColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        
        if (joinedChannels.isNotEmpty()) {
            Text(
                text = " · ⧉ ${joinedChannels.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) Color(0xFF00C851) else Color.Red,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ChatHeaderContent(
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onSidebarClick: () -> Unit,
    onTripleClick: () -> Unit,
    onShowAppInfo: () -> Unit,
    onLocationChannelsClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    when {
        selectedPrivatePeer != null -> {
            // Private chat header - Fully reactive state tracking
            val favoritePeers by viewModel.favoritePeers.observeAsState(emptySet())
            val peerFingerprints by viewModel.peerFingerprints.observeAsState(emptyMap())
            val peerSessionStates by viewModel.peerSessionStates.observeAsState(emptyMap())
            val peerNicknames by viewModel.peerNicknames.observeAsState(emptyMap())
            
            // Reactive favorite computation - no more static lookups!
            val isFavorite = isFavoriteReactive(
                peerID = selectedPrivatePeer,
                peerFingerprints = peerFingerprints,
                favoritePeers = favoritePeers
            )
            val sessionState = peerSessionStates[selectedPrivatePeer]
            
            Log.d("ChatHeader", "Header recomposing: peer=$selectedPrivatePeer, isFav=$isFavorite, sessionState=$sessionState")
            
            // Pass geohash context and people for NIP-17 chat title formatting
            val selectedLocationChannel by viewModel.selectedLocationChannel.observeAsState()
            val geohashPeople by viewModel.geohashPeople.observeAsState(emptyList())

            PrivateChatHeader(
                peerID = selectedPrivatePeer,
                peerNicknames = peerNicknames,
                isFavorite = isFavorite,
                sessionState = sessionState,
                selectedLocationChannel = selectedLocationChannel,
                geohashPeople = geohashPeople,
                onBackClick = onBackClick,
                onToggleFavorite = { viewModel.toggleFavorite(selectedPrivatePeer) }
            )
        }
        currentChannel != null -> {
            // Channel header
            ChannelHeader(
                channel = currentChannel,
                onBackClick = onBackClick,
                onLeaveChannel = { viewModel.leaveChannel(currentChannel) },
                onSidebarClick = onSidebarClick
            )
        }
        else -> {
            // Main header
            MainHeader(
                nickname = nickname,
                onNicknameChange = viewModel::setNickname,
                onTitleClick = onShowAppInfo,
                onTripleTitleClick = onTripleClick,
                onSidebarClick = onSidebarClick,
                onLocationChannelsClick = onLocationChannelsClick,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun PrivateChatHeader(
    peerID: String,
    peerNicknames: Map<String, String>,
    isFavorite: Boolean,
    sessionState: String?,
    selectedLocationChannel: com.bitchat.android.geohash.ChannelID?,
    geohashPeople: List<GeoPerson>,
    onBackClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val isNostrDM = peerID.startsWith("nostr_") || peerID.startsWith("nostr:")
    // Determine mutual favorite state for this peer (supports mesh ephemeral 16-hex via favorites lookup)
    val isMutualFavorite = remember(peerID, peerNicknames) {
        try {
            if (isNostrDM) return@remember false
            if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val noiseKeyBytes = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKeyBytes)?.isMutual == true
            } else if (peerID.length == 16 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)?.isMutual == true
            } else false
        } catch (_: Exception) { false }
    }

    // Compute title text: for NIP-17 chats show "#geohash/@username" (iOS parity)
    val titleText: String = if (isNostrDM) {
        val geohash = (selectedLocationChannel as? com.bitchat.android.geohash.ChannelID.Location)?.channel?.geohash
        val shortId = peerID.removePrefix("nostr_").removePrefix("nostr:")
        val person = geohashPeople.firstOrNull { it.id.startsWith(shortId, ignoreCase = true) }
        val baseName = person?.displayName?.substringBefore('#') ?: peerNicknames[peerID] ?: "unknown"
        val geoPart = geohash?.let { "#$it" } ?: "#geohash"
        "$geoPart/@$baseName"
    } else {
        // Prefer live mesh nickname; fallback to favorites nickname (supports 16-hex), finally short key
        peerNicknames[peerID] ?: run {
            val titleFromFavorites = try {
                if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                    val noiseKeyBytes = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKeyBytes)?.peerNickname
                } else if (peerID.length == 16 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)?.peerNickname
                } else null
            } catch (_: Exception) { null }
            titleFromFavorites ?: peerID.take(12)
        }
    }
    
    Box(modifier = Modifier.fillMaxWidth()) {
        // Back button - positioned all the way to the left with minimal margin
        Button(
            onClick = onBackClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = colorScheme.primary
            ),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp), // Reduced horizontal padding
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-8).dp) // Move even further left to minimize margin
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "back",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.primary
                )
            }
        }
        
        // Title - perfectly centered regardless of other elements
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.Center)
        ) {
            
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFF9500) // Orange
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Show a globe when chatting via Nostr alias, or when mesh session not established but mutual favorite exists
            val showGlobe = isNostrDM || (sessionState != "established" && isMutualFavorite)
            if (showGlobe) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = "Nostr reachable",
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFF9B59B6) // Purple like iOS
                )
            } else {
                NoiseSessionIcon(
                    sessionState = sessionState,
                    modifier = Modifier.size(14.dp)
                )
            }

        }
        
        // Favorite button - positioned on the right
        IconButton(
            onClick = {
                Log.d("ChatHeader", "Header toggle favorite: peerID=$peerID, currentFavorite=$isFavorite")
                onToggleFavorite()
            },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                modifier = Modifier.size(18.dp), // Slightly larger than sidebar icon
                tint = if (isFavorite) Color(0xFFFFD700) else Color(0x87878700) // Yellow or grey
            )
        }
    }
}

@Composable
private fun ChannelHeader(
    channel: String,
    onBackClick: () -> Unit,
    onLeaveChannel: () -> Unit,
    onSidebarClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Box(modifier = Modifier.fillMaxWidth()) {
        // Back button - positioned all the way to the left with minimal margin
        Button(
            onClick = onBackClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = colorScheme.primary
            ),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp), // Reduced horizontal padding
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-8).dp) // Move even further left to minimize margin
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "back",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.primary
                )
            }
        }
        
        // Title - perfectly centered regardless of other elements
        Text(
            text = "channel: $channel",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFFF9500), // Orange to match input field
            modifier = Modifier
                .align(Alignment.Center)
                .clickable { onSidebarClick() }
        )
        
        // Leave button - positioned on the right
        TextButton(
            onClick = onLeaveChannel,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Text(
                text = "leave",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Red
            )
        }
    }
}

@Composable
private fun MainHeader(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    onTitleClick: () -> Unit,
    onTripleTitleClick: () -> Unit,
    onSidebarClick: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    viewModel: ChatViewModel
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptySet())
    val hasUnreadChannels by viewModel.unreadChannelMessages.observeAsState(emptyMap())
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
    val isConnected by viewModel.isConnected.observeAsState(false)
    val selectedLocationChannel by viewModel.selectedLocationChannel.observeAsState()
    val geohashPeople by viewModel.geohashPeople.observeAsState(emptyList())
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "bitchat/",
                style = MaterialTheme.typography.headlineSmall,
                color = colorScheme.primary,
                modifier = Modifier.singleOrTripleClickable(
                    onSingleClick = onTitleClick,
                    onTripleClick = onTripleTitleClick
                )
            )
            
            Spacer(modifier = Modifier.width(2.dp))
            
            NicknameEditor(
                value = nickname,
                onValueChange = onNicknameChange
            )
        }
        
        // Right section with location channels button and peer counter
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            
            // Tor status cable icon when Tor is enabled
            TorStatusIcon(modifier = Modifier.size(15.dp))

            // Location channels button (matching iOS implementation)
            LocationChannelsButton(
                viewModel = viewModel,
                onClick = onLocationChannelsClick
            )
            
            PeerCounter(
                connectedPeers = connectedPeers.filter { it != viewModel.meshService.myPeerID },
                joinedChannels = joinedChannels,
                hasUnreadChannels = hasUnreadChannels,
                hasUnreadPrivateMessages = hasUnreadPrivateMessages,
                isConnected = isConnected,
                selectedLocationChannel = selectedLocationChannel,
                geohashPeople = geohashPeople,
                onClick = onSidebarClick
            )
        }
    }
}

@Composable
private fun LocationChannelsButton(
    viewModel: ChatViewModel,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Get current channel selection from location manager
    val selectedChannel by viewModel.selectedLocationChannel.observeAsState()
    val teleported by viewModel.isTeleported.observeAsState(false)
    
    val (badgeText, badgeColor) = when (selectedChannel) {
        is com.bitchat.android.geohash.ChannelID.Mesh -> {
            "#mesh" to Color(0xFF007AFF) // iOS blue for mesh
        }
        is com.bitchat.android.geohash.ChannelID.Location -> {
            val geohash = (selectedChannel as com.bitchat.android.geohash.ChannelID.Location).channel.geohash
            "#$geohash" to Color(0xFF00C851) // Green for location
        }
        null -> "#mesh" to Color(0xFF007AFF) // Default to mesh
    }
    
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = badgeColor
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = badgeColor,
                maxLines = 1
            )
            
            // Teleportation indicator (like iOS)
            if (teleported) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.PinDrop,
                    contentDescription = "Teleported",
                    modifier = Modifier.size(12.dp),
                    tint = badgeColor
                )
            }
        }
    }
}
