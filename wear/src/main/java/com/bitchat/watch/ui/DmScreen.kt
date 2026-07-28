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
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val rotaryFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { rotaryFocus.requestFocus() }
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
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
        previousCount = messages.size
    }

    val buttonsVisible = rememberBottomBarVisibility(scrollState)
    val headerExpanded = scrollState.maxValue - scrollState.value > 60
    val atNewest = scrollState.maxValue - scrollState.value < 40
    val listBottomPadding by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (atNewest) 56.dp else 8.dp,
        animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS),
        label = "listBottomPad"
    )
    val headerIconSize by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (headerExpanded) 16.dp else 11.dp,
        animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS),
        label = "dmHdrIcon"
    )
    val headerTitleSize by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (headerExpanded) 15.dp else 11.dp,
        animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS),
        label = "dmHdrTitle"
    )
    val headerVPadding by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (headerExpanded) 6.dp else 1.dp,
        animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS),
        label = "dmHdrPad"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Sticky collapsing header: nickname + Noise lock (grey open → orange pulse → green)
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
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            ScreenScaffold(scrollState = scrollState) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = listBottomPadding)
                            .rotaryScrollable(
                                RotaryScrollableDefaults.behavior(scrollState),
                                rotaryFocus
                            )
                            .focusRequester(rotaryFocus)
                            .focusable()
                            .verticalScroll(scrollState)
                    ) {
                        if (messages.isEmpty()) {
                            Text(
                                text = if (sessionEstablished) "encrypted channel ready\nsay hi"
                                else "setting up encryption…",
                                style = ChatVisualTokens.SystemActionStyle,
                                color = palette.textTertiary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 48.dp)
                            )
                        }
                        messages.forEach { message ->
                            MessageItem(
                                message = message,
                                myPeerID = myPeerID,
                                onOpenImage = { viewerPath = it }
                            )
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = buttonsVisible.value,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS)
                ) + androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS)
                ),
                exit = androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS)
                ) + androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS)
                )
            ) {
                ChatActionBar(
                    onKeyboard = onOpenTextInput,
                    voice = voice,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            VoiceRecordOverlay(voice)
        }
    }

    viewerPath?.let { path ->
        FullScreenImageViewer(path = path, onClose = { viewerPath = null })
    }
}
