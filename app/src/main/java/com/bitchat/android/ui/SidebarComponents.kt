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
    
    // Get peer data from mesh service
    val peerNicknames = viewModel.meshService.getPeerNicknames()
    val peerRSSI = viewModel.meshService.getPeerRSSI()
    
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
                    
                    // People section
                    item {
                        PeopleSection(
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
                        contentDescription = "Leave channel",
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
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerRSSI: Map<String, Int>,
    nickname: String,
    colorScheme: ColorScheme,
    selectedPrivatePeer: String?,
    viewModel: ChatViewModel,
    onPrivateChatStart: (String) -> Unit
) {
    Column {
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
        } else {
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
            
            Log.d("SidebarComponents", "Recomposing with ${favoritePeers.size} favorites, peer states: $peerFavoriteStates")
 
            // Smart sorting: unread DMs first, then by most recent DM, then favorites, then alphabetical
            val sortedPeers = connectedPeers.sortedWith(
                compareBy<String> { !hasUnreadPrivateMessages.contains(it) } // Unread DM senders first
                .thenByDescending { privateChats[it]?.maxByOrNull { msg -> msg.timestamp }?.timestamp?.time ?: 0L } // Most recent DM (convert Date to Long)
                .thenBy { !(peerFavoriteStates[it] ?: false) } // Favorites first
                .thenBy { (if (it == nickname) "You" else (peerNicknames[it] ?: it)).lowercase() } // Alphabetical
            )
            
            sortedPeers.forEach { peerID ->
                val isFavorite = peerFavoriteStates[peerID] ?: false
                
                PeerItem(
                    peerID = peerID,
                    displayName = if (peerID == nickname) "You" else (peerNicknames[peerID] ?: peerID),
                    signalStrength = convertRSSIToSignalStrength(peerRSSI[peerID]),
                    isSelected = peerID == selectedPrivatePeer,
                    isFavorite = isFavorite,
                    hasUnreadDM = hasUnreadPrivateMessages.contains(peerID),
                    colorScheme = colorScheme,
                    onItemClick = { onPrivateChatStart(peerID) },
                    onToggleFavorite = { 
                        Log.d("SidebarComponents", "Sidebar toggle favorite: peerID=$peerID, currentFavorite=$isFavorite")
                        viewModel.toggleFavorite(peerID) 
                    },
                    unreadCount = privateChats[peerID]?.count { msg -> 
                        // Count unread messages from this peer (messages not from the current user)
                        msg.sender != nickname && hasUnreadPrivateMessages.contains(peerID)
                    } ?: if (hasUnreadPrivateMessages.contains(peerID)) 1 else 0
                )
            }
        }
    }
}

@Composable
private fun PeerItem(
    peerID: String,
    displayName: String,
    signalStrength: Int,
    isSelected: Boolean,
    isFavorite: Boolean,
    hasUnreadDM: Boolean,
    colorScheme: ColorScheme,
    onItemClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    unreadCount: Int = 0
) {
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
            UnreadBadge(
                count = unreadCount,
                colorScheme = colorScheme
            )
        } else {
            // Signal strength indicators
            SignalStrengthIndicator(
                signalStrength = signalStrength,
                colorScheme = colorScheme
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        
        // Favorite star with proper filled/outlined states
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                modifier = Modifier.size(16.dp),
                tint = if (isFavorite) Color(0xFFFFD700) else Color(0x87878700)
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
