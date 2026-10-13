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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import com.bitchat.android.ui.theme.LocalBitchatPalette
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.bitchat.android.model.BitchatMessage

/**
 * User Action Sheet for selecting actions on a specific user (slap, hug, block)
 * Design language matches LocationChannelsSheet.kt for consistency
 *
 * The content of a sheet destination. [messageId] names the long-pressed
 * message; when it can no longer be found, only the user actions are offered.
 */
@Composable
fun ChatUserSheet(
    onDismiss: () -> Unit,
    targetNickname: String,
    messageId: String?,
    modifier: Modifier = Modifier
) {
    val viewModel = hiltViewModel<ChatUserViewModel, ChatUserViewModel.Factory>(
        creationCallback = { factory -> factory.create(targetNickname, messageId) }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedMessage = uiState.message
    // Every action closes the sheet. Opening a private chat has already taken
    // its place by then, and the close pops by key, so it does nothing.
    val act: (ChatUserAction) -> Unit = { action ->
        viewModel.onAction(action)
        onDismiss()
    }
    val clipboardManager = LocalClipboardManager.current

    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    val standardGreen = colorScheme.primary
    val standardBlue = colorScheme.secondary
    val standardPurple = palette.accentPurple
    val standardRed = colorScheme.error
    val standardGrey = colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
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
            selectedMessage?.let { message ->
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

            // Only show user actions for other users' messages or when no message is selected
            if (uiState.showUserActions) {
                // Send private message action
                item {
                    UserActionRow(
                        title = stringResource(R.string.action_private_message_title, targetNickname),
                        subtitle = stringResource(R.string.action_private_message_subtitle),
                        titleColor = standardPurple,
                        onClick = { act(ChatUserAction.PrivateMessage) }
                    )
                }

                // Slap action
                item {
                    UserActionRow(
                        title = stringResource(R.string.action_slap_title, targetNickname),
                        subtitle = stringResource(R.string.action_slap_subtitle),
                        titleColor = standardBlue,
                        onClick = { act(ChatUserAction.Slap) }
                    )
                }

                // Hug action
                item {
                    UserActionRow(
                        title = stringResource(R.string.action_hug_title, targetNickname),
                        subtitle = stringResource(R.string.action_hug_subtitle),
                        titleColor = standardGreen,
                        onClick = { act(ChatUserAction.Hug) }
                    )
                }

                // Block action
                item {
                    UserActionRow(
                        title = stringResource(R.string.action_block_title, targetNickname),
                        subtitle = stringResource(R.string.action_block_subtitle),
                        titleColor = standardRed,
                        onClick = { act(ChatUserAction.Block) }
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

/**
 * The long-pressed message, from whichever timeline chat was showing: the mesh
 * timeline or a channel, geohash channels included. Message ids are UUIDs, so
 * no timeline needs naming.
 */
internal fun timelineMessage(
    messageId: String?,
    messages: List<BitchatMessage>,
    channelMessages: Map<String, List<BitchatMessage>>,
): BitchatMessage? {
    if (messageId == null) return null
    return messages.firstOrNull { it.id == messageId }
        ?: channelMessages.values.firstNotNullOfOrNull { channel ->
            channel.firstOrNull { it.id == messageId }
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
