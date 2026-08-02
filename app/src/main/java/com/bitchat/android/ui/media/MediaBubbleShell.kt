package com.bitchat.android.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.core.ui.component.text.AnnotatedClickableText
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.DeliveryStatusIcon
import com.bitchat.android.ui.formatTextMessageMetadata
import com.bitchat.android.ui.formatTextMessageSender
import com.bitchat.android.ui.isFromSelf
import com.bitchat.android.ui.peerIdentityForMessage
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ChatVisualTokens
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.ui.theme.MessageSenderTextStyle
import com.bitchat.android.ui.theme.colorForPeer
import java.text.SimpleDateFormat

/**
 * Classic messenger shell for media messages (images, voice notes) in `ChatUiMode.Bubbles`,
 * mirroring the text-bubble treatment: the author's identity colour washes the fill and
 * hairline, the speaker-side corner tightens into a tail, the sender's name heads the first
 * bubble of a run, and the timestamp (plus delivery ticks for own private messages) rides
 * flush-right beneath the media. The shell itself is long-clickable so media bubbles always
 * open the message action sheet even when no sender label is shown and the media consumes
 * touches (voice-note player controls).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaBubbleShell(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
    showSender: Boolean,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalBitchatPalette.current
    val haptic = LocalHapticFeedback.current
    val isSelf = message.isFromSelf(currentUserNickname, myPeerID)

    val authorColor = remember(message, isSelf, palette) {
        if (isSelf) palette.accentOrange else colorForPeer(peerIdentityForMessage(message), palette)
    }

    val corner = ChatVisualTokens.BubbleCornerRadius
    val tail = ChatVisualTokens.BubbleTailRadius
    val bubbleShape = if (isSelf) {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomEnd = tail, bottomStart = corner)
    } else {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomEnd = corner, bottomStart = tail)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = authorColor.copy(alpha = ChatVisualTokens.BubbleBorderAlpha),
                    shape = bubbleShape
                )
                .background(
                    color = authorColor.copy(alpha = ChatVisualTokens.BubbleBackgroundAlpha),
                    shape = bubbleShape
                )
                .combinedClickable(
                    enabled = onLongPress != null,
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress?.invoke()
                    },
                )
                .padding(
                    horizontal = ChatVisualTokens.BubblePaddingHorizontal,
                    vertical = ChatVisualTokens.BubblePaddingVertical,
                )
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showSender && !isSelf) {
                    val senderText = remember(message, currentUserNickname, myPeerID, palette) {
                        formatTextMessageSender(
                            message = message,
                            currentUserNickname = currentUserNickname,
                            myPeerID = myPeerID,
                            palette = palette,
                        )
                    }
                    AnnotatedClickableText(
                        text = senderText,
                        annotationTags = listOf("nickname_click"),
                        onAnnotationClick = { tag, item ->
                            if (tag == "nickname_click" && onNicknameClick != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNicknameClick.invoke(item)
                                true
                            } else {
                                false
                            }
                        },
                        onLongPress = { onLongPress?.invoke() },
                        fontFamily = BitchatFontFamily,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        style = MessageSenderTextStyle,
                    )
                }

                content()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        text = formatTextMessageMetadata(message, timeFormatter),
                        fontFamily = BitchatFontFamily,
                    )
                    if (isSelf && message.isPrivate) {
                        message.deliveryStatus?.let { status ->
                            Spacer(Modifier.width(4.dp))
                            DeliveryStatusIcon(status = status)
                        }
                    }
                }
            }
        }
    }
}
