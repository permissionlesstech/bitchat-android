package com.bitchat.android.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import java.text.SimpleDateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bitchat.android.ui.isFromSelf

@Composable
fun ImageMessageItem(
    message: BitchatMessage,
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    myPeerID: String,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?,
    modifier: Modifier = Modifier,
    showSender: Boolean = true,
    bubbles: Boolean = false
) {
    val path = message.content.trim()
    val bmp by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }
    val imagePaths = remember(messages, path) {
        messages.filter { it.type == BitchatMessageType.Image }
            .map { it.content.trim() }.ifEmpty { listOf(path) }
    }
    val haptic = LocalHapticFeedback.current
    MediaMessageLayout(
        message, currentUserNickname, myPeerID, timeFormatter,
        showSender, bubbles, onNicknameClick, onMessageLongPress, modifier,
    ) {
        ImageMessageCard(
            message = message,
            isSelf = message.isFromSelf(currentUserNickname, myPeerID),
            path = path,
            bmp = bmp,
            imagePaths = imagePaths,
            imageShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            onImageClick = onImageClick,
            onCancelTransfer = onCancelTransfer,
            onLongPress = onMessageLongPress?.let { action -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                action(message)
            } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageMessageCard(
    message: BitchatMessage,
    isSelf: Boolean,
    path: String,
    bmp: android.graphics.Bitmap?,
    imagePaths: List<String>,
    imageShape: androidx.compose.foundation.shape.RoundedCornerShape,
    onImageClick: ((String, List<String>, Int) -> Unit)?,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    if (bmp == null) {
        Text(text = stringResource(com.bitchat.android.R.string.image_unavailable), fontFamily = BitchatFontFamily, color = Color.Gray)
        return
    }
    val img = bmp.asImageBitmap()
    val aspect = (bmp.width.toFloat() / bmp.height.toFloat()).takeIf { it.isFinite() && it > 0 } ?: 1f
    val progressFraction = mediaTransferProgress(message, isSelf)
    Box {
        val imageModifier = Modifier
            .widthIn(max = 300.dp)
            .aspectRatio(aspect)
            .clip(imageShape)
            .combinedClickable(
                onClick = {
                    val currentIndex = imagePaths.indexOf(path)
                    onImageClick?.invoke(path, imagePaths, currentIndex)
                },
                onLongClick = { onLongPress?.invoke() },
            )
        if (progressFraction != null && progressFraction < 1f && isSelf) {
            // Cyberpunk block-reveal while sending
            BlockRevealImage(
                bitmap = img,
                progress = progressFraction,
                blocksX = 24,
                blocksY = 16,
                modifier = imageModifier
            )
        } else {
            // Fully revealed image
            Image(
                bitmap = img,
                contentDescription = stringResource(com.bitchat.android.R.string.cd_image),
                modifier = imageModifier,
                contentScale = ContentScale.Fit
            )
        }
        if (progressFraction != null && onCancelTransfer != null) {
            Box(Modifier.align(Alignment.TopEnd)) {
                CancelMediaTransferButton { onCancelTransfer(message) }
            }
        }
    }
}
