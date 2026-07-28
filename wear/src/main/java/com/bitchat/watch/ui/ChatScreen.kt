package com.bitchat.watch.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import com.bitchat.watch.ui.theme.colorForPeer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(onOpenPeople: () -> Unit) {
    val messages by AppStateStore.publicMessages.collectAsState()
    val peers by AppStateStore.peers.collectAsState()
    val unreadDms by WearChatState.unreadDms.collectAsState()
    val mesh = WearMeshService.peek()
    val myPeerID = mesh?.myPeerID ?: ""
    val listState = rememberScalingLazyListState()
    val palette = LocalBitchatPalette.current
    val haptics = LocalHapticFeedback.current

    var previousCount by remember { mutableStateOf(messages.size) }
    LaunchedEffect(messages.size) {
        if (messages.size > previousCount) {
            val last = messages.lastOrNull()
            if (last != null && last.senderPeerID != myPeerID) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            // Keep the newest message visible (header + messages + composer indices)
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size + 1)
            }
        }
        previousCount = messages.size
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                ChatHeader(
                    peerCount = peers.size,
                    unreadDms = unreadDms.values.sum(),
                    onOpenPeople = onOpenPeople
                )
            }
            if (messages.isEmpty()) {
                item {
                    Text(
                        text = "no messages yet\nsay hi to the mesh",
                        style = ChatVisualTokens.SystemActionStyle,
                        color = palette.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageItem(message = message, myPeerID = myPeerID)
            }
            item {
                ChatComposer(
                    onSend = { text ->
                        mesh?.let { sendPublicMessage(it, text) }
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatHeader(peerCount: Int, unreadDms: Int, onOpenPeople: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "bitchat",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "  ·  $peerCount online >",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onOpenPeople() }
        )
        if (unreadDms > 0) {
            Text(
                text = "  ·  $unreadDms new",
                style = MaterialTheme.typography.bodySmall,
                color = LocalBitchatPalette.current.accentOrange,
                modifier = Modifier.clickable { onOpenPeople() }
            )
        }
    }
}

@Composable
fun MessageItem(message: BitchatMessage, myPeerID: String) {
    val palette = LocalBitchatPalette.current
    val isSelf = message.senderPeerID == myPeerID
    val senderColor = when {
        isSelf -> palette.accentOrange
        else -> colorForPeer(message.sender + (message.senderPeerID ?: ""), palette)
    }

    // Snappy appear animation for incoming messages (BitchatMotion.EMPHASIZED_MS)
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(message.id) { appeared = true }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            com.bitchat.watch.ui.theme.BitchatMotion.EMPHASIZED_MS
        ),
        label = "msgAlpha"
    )
    val offset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (appeared) 0.dp else 6.dp,
        animationSpec = androidx.compose.animation.core.tween(
            com.bitchat.watch.ui.theme.BitchatMotion.EMPHASIZED_MS
        ),
        label = "msgOffset"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .offset(y = offset)
            .alpha(alpha)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isSelf) "you" else message.sender,
                style = ChatVisualTokens.SenderStyle,
                color = senderColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = "  ${formatTime(message.timestamp)}",
                style = ChatVisualTokens.SystemActionStyle,
                fontSize = 9.sp,
                color = palette.textTertiary
            )
        }
        Text(
            text = message.content,
            style = ChatVisualTokens.MessageBodyStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

@Composable
fun ChatComposer(onSend: (String) -> Unit) {
    val palette = LocalBitchatPalette.current
    var text by remember { mutableStateOf("") }

    val dictationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                onSend(spoken.trim())
                text = ""
            }
        }
    }

    fun send() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSend(trimmed)
            text = ""
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = ChatVisualTokens.MessageBodyStyle.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(palette.inputSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = "message",
                            style = ChatVisualTokens.MessageBodyStyle,
                            color = palette.textTertiary
                        )
                    }
                    innerTextField()
                }
            }
        )
        IconButton(
            onClick = {
                dictationLauncher.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "speak your message")
                    }
                )
            },
            modifier = Modifier
                .padding(start = 4.dp)
                .size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "dictate",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(
            onClick = { send() },
            enabled = text.isNotBlank(),
            modifier = Modifier
                .padding(start = 2.dp)
                .size(34.dp)
                .clip(CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "send",
                tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary
                else palette.textTertiary
            )
        }
    }
}

private fun sendPublicMessage(mesh: WearMeshService, content: String) {
    mesh.sendMessage(content)
    AppStateStore.addPublicMessage(
        BitchatMessage(
            sender = mesh.nickname,
            content = content,
            timestamp = Date(),
            senderPeerID = mesh.myPeerID,
            deliveryStatus = DeliveryStatus.Sent
        )
    )
}

private fun formatTime(date: Date): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
