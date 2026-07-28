package com.bitchat.watch.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
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
import java.util.Date

@Composable
fun DmScreen(peerID: String) {
    val privateMessages by AppStateStore.privateMessages.collectAsState()
    val messages = privateMessages[peerID] ?: emptyList()
    val mesh = WearMeshService.peek()
    val myPeerID = mesh?.myPeerID ?: ""
    val palette = LocalBitchatPalette.current
    val listState = rememberScalingLazyListState()
    val haptics = LocalHapticFeedback.current

    val nickname = mesh?.getPeerNickname(peerID) ?: peerID.take(8)
    var sessionEstablished by remember {
        mutableStateOf(mesh?.hasEstablishedSession(peerID) == true)
    }

    DisposableEffect(peerID) {
        WearChatState.openDm(peerID)
        onDispose { WearChatState.closeDm() }
    }

    LaunchedEffect(peerID) {
        if (mesh?.hasEstablishedSession(peerID) != true) {
            try { mesh?.initiateNoiseHandshake(peerID) } catch (_: Exception) { }
        }
        while (true) {
            sessionEstablished = mesh?.hasEstablishedSession(peerID) == true
            kotlinx.coroutines.delay(2_000)
        }
    }

    var previousCount by remember { mutableStateOf(messages.size) }
    LaunchedEffect(messages.size) {
        if (messages.size > previousCount) {
            val last = messages.lastOrNull()
            if (last != null && last.senderPeerID != myPeerID) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = nickname,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colorForPeer(nickname + peerID, palette)
                    )
                    Text(
                        text = if (sessionEstablished) "  ·  noise ✓" else "  ·  handshaking…",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sessionEstablished) MaterialTheme.colorScheme.primary
                        else palette.textTertiary
                    )
                }
            }
            if (messages.isEmpty()) {
                item {
                    Text(
                        text = if (sessionEstablished) "encrypted channel ready\nsay hi"
                        else "setting up encryption…",
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
                        mesh?.let { sendPrivateMessage(it, peerID, nickname, text) }
                    }
                )
            }
        }
    }
}

private fun sendPrivateMessage(
    mesh: WearMeshService,
    peerID: String,
    recipientNickname: String,
    content: String
) {
    mesh.sendPrivateMessage(content, peerID, recipientNickname)
    AppStateStore.addPrivateMessage(
        peerID,
        BitchatMessage(
            sender = mesh.nickname,
            content = content,
            timestamp = Date(),
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = mesh.myPeerID,
            deliveryStatus = DeliveryStatus.Sent
        )
    )
}
