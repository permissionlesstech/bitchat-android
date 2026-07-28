package com.bitchat.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.media.FullScreenImageViewer
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import com.bitchat.watch.ui.theme.colorForPeer

@Composable
fun DmScreen(peerID: String, onOpenTextInput: () -> Unit) {
    val privateMessages by AppStateStore.privateMessages.collectAsState()
    val messages = privateMessages[peerID] ?: emptyList()
    val mesh = WearMeshService.peek()
    val myPeerID = mesh?.myPeerID ?: ""
    val palette = LocalBitchatPalette.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    var viewerPath by remember { mutableStateOf<String?>(null) }
    val voice = rememberVoiceNoteController { path ->
        mesh?.let { sendVoiceNote(it, peerID, path) }
    }

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
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
        }
        previousCount = messages.size
    }

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        ScreenScaffold(scrollState = listState) {
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 40.dp,
                    bottom = 56.dp
                )
            ) {
                items(messages.asReversed(), key = { it.id }) { message ->
                    MessageItem(
                        message = message,
                        myPeerID = myPeerID,
                        onOpenImage = { viewerPath = it }
                    )
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
            }
        }

        ChatActionBar(
            onKeyboard = onOpenTextInput,
            voice = voice,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
        )

        VoiceRecordOverlay(voice)
    }

    viewerPath?.let { path ->
        FullScreenImageViewer(path = path, onClose = { viewerPath = null })
    }
}
