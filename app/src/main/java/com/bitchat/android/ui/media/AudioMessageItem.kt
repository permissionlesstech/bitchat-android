package com.bitchat.android.ui.media

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.isFromSelf
import java.text.SimpleDateFormat

@Composable
fun AudioMessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier,
    showSender: Boolean = true,
    bubbles: Boolean = false,
) {
    val liveMessageIDs by LiveVoiceManager.getInstance(LocalContext.current)
        .liveMessageIDs.collectAsState()
    val isLive = message.id in liveMessageIDs
    val isSelf = message.isFromSelf(currentUserNickname, myPeerID)
    val progress = mediaTransferProgress(message, isSelf)
    MediaMessageLayout(
        message, currentUserNickname, myPeerID, timeFormatter,
        showSender, bubbles, onNicknameClick, onMessageLongPress, modifier,
    ) {
        Column(modifier = if (bubbles) Modifier.widthIn(max = 300.dp) else Modifier) {
            if (isLive) {
                Text(
                    stringResource(R.string.voice_waveform_live),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            VoiceNotePlayer(
                path = message.content.trim(),
                isLive = isLive,
                progressOverride = progress,
                onCancelTransfer = onCancelTransfer?.let { cancel -> { cancel(message) } },
            )
        }
    }
}
