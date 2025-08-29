package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import com.bitchat.android.model.BitchatMessage

/**
 * User Action Sheet for selecting actions on a specific user (slap, hug, block)
 * Design language matches LocationChannelsSheet.kt for consistency
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatUserSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    targetNickname: String,
    selectedMessage: BitchatMessage? = null,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    
    // Bottom sheet state
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    
    // iOS system colors (matches LocationChannelsSheet exactly)
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val standardGreen = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D) // iOS green
    val standardBlue = Color(0xFF007AFF) // iOS blue
    val standardRed = Color(0xFFFF3B30) // iOS red
    val standardGrey = if (isDark) Color(0xFF8E8E93) else Color(0xFF6D6D70) // iOS grey
    
    if (isPresented) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Text(
                    text = "@$targetNickname",
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = if (selectedMessage != null) "choose an action for this message or user" else "choose an action for this user",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                
                // Action list (iOS-style plain list)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Copy message action (only show if we have a message)
                    selectedMessage?.let { message ->
                        item {
                            UserActionRow(
                                title = "copy message",
                                subtitle = "copy this message to clipboard",
                                titleColor = standardGrey,
                                onClick = {
                                    // Copy the message content to clipboard
                                    clipboardManager.setText(AnnotatedString(message.content))
                                    onDismiss()
                                }
                            )
                        }
                    }
                    
                    // Only show user actions for other users' messages or when no message is selected
                    if (selectedMessage?.sender != viewModel.nickname.value) {
                        // Slap action
                        item {
                            UserActionRow(
                                title = "slap $targetNickname",
                                subtitle = "send a playful slap message",
                                titleColor = standardBlue,
                                onClick = {
                                    // Send slap command
                                    viewModel.sendMessage("/slap $targetNickname")
                                    onDismiss()
                                }
                            )
                        }
                        
                        // Hug action  
                        item {
                            UserActionRow(
                                title = "hug $targetNickname",
                                subtitle = "send a friendly hug message",
                                titleColor = standardGreen,
                                onClick = {
                                    // Send hug command
                                    viewModel.sendMessage("/hug $targetNickname")
                                    onDismiss()
                                }
                            )
                        }
                        
                        // Block action
                        item {
                            UserActionRow(
                                title = "block $targetNickname",
                                subtitle = "block all messages from this user",
                                titleColor = standardRed,
                                onClick = {
                                    // Check if we're in a geohash channel
                                    val selectedLocationChannel = viewModel.selectedLocationChannel.value
                                    if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                                        // Get user's nostr public key and add to geohash block list
                                        viewModel.blockUserInGeohash(targetNickname)
                                    } else {
                                        // Regular mesh blocking
                                        viewModel.sendMessage("/block $targetNickname")
                                    }
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
                
                // Cancel button (iOS-style)
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "cancel",
                        fontSize = BASE_FONT_SIZE.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun UserActionRow(
    title: String,
    subtitle: String,
    titleColor: Color,
    onClick: () -> Unit
) {
    // iOS-style list row (plain button, no card background)
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                fontSize = BASE_FONT_SIZE.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = titleColor
            )
            
            Text(
                text = subtitle,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
