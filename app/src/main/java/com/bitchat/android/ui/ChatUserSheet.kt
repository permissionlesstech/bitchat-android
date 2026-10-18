package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import com.bitchat.android.ui.theme.LocalBitchatPalette
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.ui.media.FileAttachment
import com.bitchat.android.ui.media.FileViewerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    viewModel: ChatViewModel,
    conversationContext: ConversationUiContext,
    modifier: Modifier = Modifier,
    selectedMessage: BitchatMessage? = null,
) {
    val attachment by produceState<FileAttachment?>(null, selectedMessage?.content, selectedMessage?.type) {
        value = null
        val message = selectedMessage
        if (message != null && message.type != BitchatMessageType.Message) {
            value = withContext(Dispatchers.IO) {
                runCatching { FileAttachment.fromPath(message.content.trim()) }.getOrNull()
            }
        }
    }
    var showAttachmentDialog by remember(selectedMessage?.id) { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    val standardGreen = colorScheme.primary
    val standardBlue = colorScheme.secondary
    val standardPurple = palette.accentPurple
    val standardRed = colorScheme.error
    val standardGrey = colorScheme.onSurfaceVariant
    
    if (isPresented) {
        BitchatBottomSheet(
            onDismissRequest = onDismiss,
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
                    text = stringResource(R.string.at_nickname, targetNickname),
                    fontSize = 18.sp,
                    fontFamily = BitchatFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = if (selectedMessage != null) stringResource(R.string.choose_action_message_or_user) else stringResource(R.string.choose_action_user),
                    fontSize = 12.sp,
                    fontFamily = BitchatFontFamily,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                
                // Action list (iOS-style plain list)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Copy message action (only show if we have a message)
                    selectedMessage?.takeIf { it.type == BitchatMessageType.Message }?.let { message ->
                        item {
                            UserActionRow(
                                title = stringResource(R.string.action_copy_message_title),
                                subtitle = stringResource(R.string.action_copy_message_subtitle),
                                titleColor = standardGrey,
                                onClick = {
                                    // Copy the message content to clipboard
                                    clipboardManager.setText(AnnotatedString(message.content))
                                    onDismiss()
                                }
                            )
                        }
                    }
                    
                    attachment?.let { file ->
                        item {
                            UserActionRow(
                                title = stringResource(R.string.file_viewer_open_save),
                                subtitle = file.fileName,
                                titleColor = standardGrey,
                                onClick = { showAttachmentDialog = true },
                            )
                        }
                    }

                    // Only show user actions for other users' messages or when no message is selected
                    if (selectedMessage?.isFromSelf(viewModel.nickname.value, viewModel.meshServiceFacade.myPeerID) != true && selectedMessage?.sender != "system") {
                        // Already in this person's private conversation: do not offer opening it again.
                        if (conversationContext.privatePeerID == null) item {
                            UserActionRow(
                                title = stringResource(R.string.action_private_message_title, targetNickname),
                                subtitle = stringResource(R.string.action_private_message_subtitle),
                                titleColor = standardPurple,
                                onClick = {
                                    if (conversationContext.isNostr) {
                                        if (selectedMessage?.senderNostrPubkey != null) {
                                            viewModel.startGeohashDM(selectedMessage.senderNostrPubkey)
                                        } else if (selectedMessage?.senderPeerID?.startsWith("nostr:") == true) {
                                            val shortId = selectedMessage.senderPeerID!!.substring(6)
                                            viewModel.startGeohashDMByShortId(shortId)
                                        } else {
                                            viewModel.startGeohashDMByNickname(targetNickname)
                                        }
                                    } else {
                                        // Mesh chat
                                        val peerID = selectedMessage?.senderPeerID ?: viewModel.getPeerIDForNickname(targetNickname)
                                        if (peerID != null) {
                                            viewModel.showPrivateChatSheet(peerID)
                                        }
                                    }
                                    onDismiss()
                                }
                            )
                        }

                        // Slap action
                        item {
                            UserActionRow(
                                title = stringResource(R.string.action_slap_title, targetNickname),
                                subtitle = stringResource(R.string.action_slap_subtitle),
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
                                title = stringResource(R.string.action_hug_title, targetNickname),
                                subtitle = stringResource(R.string.action_hug_subtitle),
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
                                title = stringResource(R.string.action_block_title, targetNickname),
                                subtitle = stringResource(R.string.action_block_subtitle),
                                titleColor = standardRed,
                                onClick = {
                                    // Check if we're in a geohash channel
                                    if (conversationContext.isNostr) {
                                        viewModel.blockUserInGeohash(targetNickname, selectedMessage?.senderNostrPubkey)
                                    } else {
                                        val senderID = selectedMessage?.senderPeerID ?: conversationContext.privatePeerID
                                        val meshPeerID = senderID?.let { ContactDirectory.resolve(it).meshPeerID ?: it }
                                        if (meshPeerID != null) {
                                            viewModel.privateChatManager.blockPeer(meshPeerID, viewModel.meshServiceFacade)
                                        }
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
                        text = stringResource(R.string.cancel_lower),
                        fontSize = BASE_FONT_SIZE.sp,
                        fontFamily = BitchatFontFamily
                    )
                }
            }
        }
    }
    if (showAttachmentDialog) {
        attachment?.let { file ->
            FileViewerDialog(file) {
                showAttachmentDialog = false
                onDismiss()
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
                fontFamily = BitchatFontFamily,
                fontWeight = FontWeight.Medium,
                color = titleColor
            )
            
            Text(
                text = subtitle,
                fontSize = 12.sp,
                fontFamily = BitchatFontFamily,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
