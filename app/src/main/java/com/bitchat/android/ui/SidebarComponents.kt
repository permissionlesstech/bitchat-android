package com.bitchat.android.ui

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

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
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptyList())
    val currentChannel by viewModel.currentChannel.observeAsState()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.observeAsState()
    val nickname by viewModel.nickname.observeAsState("")
    
    // Get peer data from mesh service
    val peerNicknames = viewModel.meshService.getPeerNicknames()
    val peerRSSI = viewModel.meshService.getPeerRSSI()
    
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() }
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
                    .background(colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.statusBars) // Add status bar padding
            ) {
                SidebarHeader()
                
                Divider()
                
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
                                }
                            )
                        }
                        
                        item {
                            Divider(modifier = Modifier.padding(vertical = 4.dp))
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
            .height(36.dp) // Match reduced main header height
            .fillMaxWidth()
            .background(colorScheme.surface.copy(alpha = 0.95f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "YOUR NETWORK",
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
    onLeaveChannel: (String) -> Unit
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
                text = "CHANNELS",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }
        
        channels.forEach { channel ->
            val isSelected = channel == currentChannel
            
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
                imageVector = Icons.Default.Person, // Using Person icon for people
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "PEOPLE",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }
        
        if (connectedPeers.isEmpty()) {
            Text(
                text = "No one connected",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        } else {
            // Get unread private messages and private chat history for sorting
            val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
            val privateChats by viewModel.privateChats.observeAsState(emptyMap())
            val favoritePeers by viewModel.favoritePeers.observeAsState(emptySet()) 
 
            // Pre-calculate all favorite states to ensure proper state synchronization
            val peerFavoriteStates = remember(favoritePeers, connectedPeers) {
                connectedPeers.associateWith { peerID ->
                    val fingerprint = viewModel.privateChatManager.getPeerFingerprint(peerID)
                    favoritePeers.contains(fingerprint)
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
                    signalStrength = peerRSSI[peerID] ?: 0,
                    isSelected = peerID == selectedPrivatePeer,
                    isFavorite = isFavorite,
                    hasUnreadDM = hasUnreadPrivateMessages.contains(peerID),
                    colorScheme = colorScheme,
                    onItemClick = { onPrivateChatStart(peerID) },
                    onToggleFavorite = { 
                        Log.d("SidebarComponents", "Sidebar toggle favorite: peerID=$peerID, currentFavorite=$isFavorite")
                        viewModel.toggleFavorite(peerID) 
                    }
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
    onToggleFavorite: () -> Unit
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
        // Show filled mail icon instead of signal strength when user has unread DMs
        if (hasUnreadDM) {
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = "Unread messages",
                modifier = Modifier.size(16.dp),
                tint = Color(0xFFFF9500) // Orange to match private message theme
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
                tint = if (isFavorite) Color(0xFFFFD700) else colorScheme.primary
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
