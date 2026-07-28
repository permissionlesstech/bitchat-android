package com.bitchat.watch.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
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
import com.bitchat.watch.ui.theme.BitchatMotion
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

    ChatScaffold(
        messages = messages,
        myPeerID = myPeerID,
        emptyText = if (sessionEstablished) "encrypted channel ready\nsay hi"
        else "setting up encryption…",
        voice = voice,
        onOpenImage = { viewerPath = it },
        header = { expanded ->
            DmHeader(
                nickname = nickname,
                peerID = peerID,
                sessionEstablished = sessionEstablished,
                expanded = expanded
            )
        },
        actionBar = {
            ChatActionBar(onKeyboard = onOpenTextInput, voice = voice)
        }
    )

    viewerPath?.let { path ->
        FullScreenImageViewer(path = path, onClose = { viewerPath = null })
    }
}

@Composable
private fun DmHeader(
    nickname: String,
    peerID: String,
    sessionEstablished: Boolean,
    expanded: Boolean
) {
    val palette = LocalBitchatPalette.current
    val headerIconSize by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (expanded) 16.dp else 11.dp,
        animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS),
        label = "dmHdrIcon"
    )
    val headerTitleSize by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (expanded) 15.dp else 11.dp,
        animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS),
        label = "dmHdrTitle"
    )
    val headerVPadding by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (expanded) 6.dp else 1.dp,
        animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS),
        label = "dmHdrPad"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = headerVPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = nickname,
            style = MaterialTheme.typography.titleSmall,
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) {
                headerTitleSize.toSp()
            },
            fontWeight = FontWeight.Bold,
            color = colorForPeer(nickname + peerID, palette)
        )
        NoiseLockIcon(
            state = if (sessionEstablished) NoiseSessionUiState.Established
            else NoiseSessionUiState.Handshaking,
            size = headerIconSize,
            modifier = Modifier.padding(start = 5.dp)
        )
    }
}
