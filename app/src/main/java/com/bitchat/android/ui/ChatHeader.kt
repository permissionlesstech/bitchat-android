package com.bitchat.android.ui

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.core.ui.utils.singleOrTripleClickable
import com.bitchat.android.util.NicknameUtils

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
    val isFocused = remember { mutableStateOf(false) }

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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { 
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier
                .widthIn(max = 100.dp)
                .onFocusChanged { focusState ->
                    isFocused.value = focusState.isFocused
                    if (!focusState.isFocused && value.isBlank()) {
                        onValueChange(NicknameUtils.generateRandomNickname())
                    }
                }
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
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
            contentDescription = "Connected peers",
            modifier = Modifier.size(16.dp),
            tint = if (isConnected) Color(0xFF00C851) else Color.Red
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${connectedPeers.size}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isConnected) Color(0xFF00C851) else Color.Red,
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
    onShowAppInfo: () -> Unit
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
            
            PrivateChatHeader(
                peerID = selectedPrivatePeer,
                peerNicknames = peerNicknames,
                isFavorite = isFavorite,
                sessionState = sessionState,
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
    onBackClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val peerNickname = peerNicknames[peerID] ?: peerID
    
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
                text = peerNickname,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFF9500) // Orange
            )

            Spacer(modifier = Modifier.width(4.dp))

            NoiseSessionIcon(
                sessionState = sessionState,
                modifier = Modifier.size(14.dp)
            )

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
    viewModel: ChatViewModel
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptySet())
    val hasUnreadChannels by viewModel.unreadChannelMessages.observeAsState(emptyMap())
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
    val isConnected by viewModel.isConnected.observeAsState(false)
    
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
        
        PeerCounter(
            connectedPeers = connectedPeers.filter { it != viewModel.meshService.myPeerID },
            joinedChannels = joinedChannels,
            hasUnreadChannels = hasUnreadChannels,
            hasUnreadPrivateMessages = hasUnreadPrivateMessages,
            isConnected = isConnected,
            onClick = onSidebarClick
        )
    }
}
