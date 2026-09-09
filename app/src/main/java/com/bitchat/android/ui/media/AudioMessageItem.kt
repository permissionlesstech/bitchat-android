package com.bitchat.android.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.text.AnnotatedClickableText
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import androidx.compose.material3.ColorScheme
import com.bitchat.android.ui.theme.LocalBitchatPalette
import java.text.SimpleDateFormat
import androidx.compose.ui.platform.LocalContext

@Composable
fun AudioMessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: MeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier,
    showSender: Boolean = true,
    bubbles: Boolean = false
) {
    val palette = LocalBitchatPalette.current
    val context = LocalContext.current
    val liveMessageIDs by com.bitchat.android.features.voice.LiveVoiceManager
        .getInstance(context).liveMessageIDs.collectAsState()
    val isLive = message.id in liveMessageIDs
    val path = message.content.trim()
    // Derive sending progress if applicable
    val (overrideProgress, overrideColor) = when (val st = message.deliveryStatus) {
        is com.bitchat.android.model.DeliveryStatus.PartiallyDelivered -> {
            if (st.total > 0 && st.reached < st.total) {
                (st.reached.toFloat() / st.total.toFloat()) to Color(0xFF1E88E5) // blue while sending
            } else null to null
        }
        else -> null to null
    }
    // Bubble mode wraps the voice note in the same tinted shell as text bubbles; Matrix mode
    // keeps the flat header-plus-player layout.
    if (bubbles) {
        MediaBubbleShell(
            message = message,
            currentUserNickname = currentUserNickname,
            myPeerID = meshService.myPeerID,
            showSender = showSender,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onLongPress = { onMessageLongPress?.invoke(message) },
            modifier = modifier,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLive) {
                    androidx.compose.material3.Text(
                        text = "LIVE",
                        color = Color(0xFFFFB300),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                VoiceNotePlayer(
                    path = path,
                    progressOverride = overrideProgress,
                    progressColor = overrideColor,
                    modifier = Modifier.widthIn(max = 260.dp)
                )
                val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is com.bitchat.android.model.DeliveryStatus.PartiallyDelivered)
                if (showCancel) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                            .clickable { onCancelTransfer?.invoke(message) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.cd_cancel), tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        // Header: nickname + timestamp line above the audio note, identical styling to text messages
        val headerText = com.bitchat.android.ui.formatMessageHeaderAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            myPeerID = meshService.myPeerID,
            palette = palette,
            contentColor = colorScheme.onSurface,
            timeFormatter = timeFormatter,
            includeSender = showSender
        )
        val haptic = LocalHapticFeedback.current
        AnnotatedClickableText(
            text = headerText,
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
            onLongPress = { onMessageLongPress?.invoke(message) },
            fontFamily = BitchatFontFamily,
            color = colorScheme.onSurface,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLive) {
                androidx.compose.material3.Text(
                    text = "LIVE",
                    color = Color(0xFFFFB300),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            VoiceNotePlayer(
                path = path,
                progressOverride = overrideProgress,
                progressColor = overrideColor,
            )
            val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is com.bitchat.android.model.DeliveryStatus.PartiallyDelivered)
            if (showCancel) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                        .clickable { onCancelTransfer?.invoke(message) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.cd_cancel), tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
