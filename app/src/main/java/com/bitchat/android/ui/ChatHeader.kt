package com.bitchat.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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

/**
 * Header components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun NicknameEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    
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
            modifier = Modifier.widthIn(max = 100.dp)
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
        modifier = modifier.clickable { onClick() }
    ) {
        if (hasUnreadChannels.values.any { it > 0 }) {
            Text(
                text = "#",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF0080FF),
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        
        if (hasUnreadPrivateMessages.isNotEmpty()) {
            Text(
                text = "✉",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFF8C00),
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        
        Icon(
            imageVector = Icons.Default.Person,
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
    var tripleClickCount by remember { mutableStateOf(0) }
    
    when {
        selectedPrivatePeer != null -> {
            // Private chat header
            PrivateChatHeader(
                peerID = selectedPrivatePeer,
                peerNicknames = viewModel.meshService.getPeerNicknames(),
                isFavorite = viewModel.isFavorite(selectedPrivatePeer),
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
                onTitleClick = {
                    tripleClickCount++
                    if (tripleClickCount >= 3) {
                        tripleClickCount = 0
                        onTripleClick()
                    } else {
                        onShowAppInfo()
                    }
                },
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
    onBackClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val peerNickname = peerNicknames[peerID] ?: peerID
    
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).windowInsetsPadding(WindowInsets.statusBars),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Text(
                text = "← back",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔒", fontSize = 16.sp) // Slightly larger
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = peerNickname,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFF8C00) // Orange
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Favorite button
        IconButton(onClick = onToggleFavorite) {
            Text(
                text = if (isFavorite) "★" else "☆",
                color = if (isFavorite) Color.Yellow else colorScheme.primary,
                fontSize = 18.sp // Larger icon
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
    
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).windowInsetsPadding(WindowInsets.statusBars),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Text(
                text = "← back",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Text(
            text = "channel: $channel",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF0080FF), // Blue
            modifier = Modifier.clickable { onSidebarClick() }
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        TextButton(onClick = onLeaveChannel) {
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).windowInsetsPadding(WindowInsets.statusBars),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "bitchat*",
                style = MaterialTheme.typography.headlineSmall,
                color = colorScheme.primary,
                modifier = Modifier.clickable { onTitleClick() }
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
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
