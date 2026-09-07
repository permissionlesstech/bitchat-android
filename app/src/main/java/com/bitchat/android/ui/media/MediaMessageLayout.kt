package com.bitchat.android.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.text.AnnotatedClickableText
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.ui.formatMessageHeaderAnnotatedString
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette
import java.text.SimpleDateFormat

internal fun mediaTransferProgress(message: BitchatMessage, isSelf: Boolean): Float? {
    val status = message.deliveryStatus as? DeliveryStatus.PartiallyDelivered ?: return null
    if (!isSelf || status.total <= 0 || status.reached >= status.total) return null
    return (status.reached.toFloat() / status.total).coerceIn(0f, 1f)
}

/** One header, action surface and metadata policy for every attachment and conversation. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaMessageLayout(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
    timeFormatter: SimpleDateFormat,
    showSender: Boolean,
    bubbles: Boolean,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val onLongPress = onMessageLongPress?.let { action -> { action(message) } }
    if (bubbles) {
        MediaBubbleShell(
            message, currentUserNickname, myPeerID, showSender, timeFormatter,
            onNicknameClick, onLongPress, modifier, content,
        )
    } else {
        val haptic = LocalHapticFeedback.current
        Column(modifier.fillMaxWidth().combinedClickable(
            enabled = onLongPress != null,
            onClick = {},
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongPress?.invoke()
            },
        )) {
            AnnotatedClickableText(
                text = formatMessageHeaderAnnotatedString(
                    message, currentUserNickname, myPeerID, LocalBitchatPalette.current,
                    MaterialTheme.colorScheme.onSurface, timeFormatter, includeSender = showSender,
                ),
                annotationTags = listOf("nickname_click"),
                onAnnotationClick = { _, nickname ->
                    onNicknameClick?.let { it(nickname); true } ?: false
                },
                onLongPress = onLongPress,
                fontFamily = BitchatFontFamily,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
internal fun CancelMediaTransferButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_cancel))
    }
}
