package com.bitchat.watch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.R
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.notification.WearNotificationCoordinator
import com.bitchat.watch.ui.media.FullScreenImageViewer
import com.bitchat.watch.ui.theme.BitchatMotion
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import com.bitchat.watch.ui.theme.colorForPeer

@Composable
fun DmScreen(
    peerID: String,
    onOpenUserDetail: () -> Unit,
    onOpenTextInput: () -> Unit
) {
    val context = LocalContext.current
    val privateMessages by AppStateStore.privateMessages.collectAsState()
    val messages = privateMessages[peerID] ?: emptyList()
    val mesh = WearMeshService.peek()
    val myPeerID = mesh?.myPeerID ?: ""
    val palette = LocalBitchatPalette.current
    var viewerPath by remember { mutableStateOf<String?>(null) }
    val liveVoiceManager = remember(context) {
        com.bitchat.android.features.voice.LiveVoiceManager.getInstance(context)
    }
    val voice = rememberVoiceNoteController(
        recorderFactory = {
            val target = if (
                mesh != null && mesh.hasEstablishedSession(peerID) &&
                com.bitchat.android.features.voice.LiveVoicePreferences.isEnabled(context)
            ) com.bitchat.android.features.voice.LiveVoiceTarget { payload ->
                mesh.sendVoiceFrame(peerID, payload)
            } else null
            com.bitchat.android.features.voice.VoiceRecorder(context, target)
        }
    ) { path -> mesh?.let { sendVoiceNote(it, peerID, path) } }

    val nickname = mesh?.getPeerNickname(peerID) ?: peerID.take(8)
    val identityRevision by WearPeerIdentityState.revision.collectAsState()
    val identity = remember(peerID, identityRevision) {
        WearPeerIdentityState.snapshot(peerID, mesh)
    }
    var sessionEstablished by remember {
        mutableStateOf(mesh?.hasEstablishedSession(peerID) == true)
    }

    DisposableEffect(peerID) {
        liveVoiceManager.showDirectMessage(peerID)
        WearChatState.openDm(peerID)
        WearNotificationCoordinator.getInstance(context).clearConversation(peerID)
        onDispose {
            WearChatState.closeDm()
            liveVoiceManager.clearVisibleConversation()
        }
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
        emptyText = if (sessionEstablished) "Encrypted channel ready\nSay hi"
        else "Setting up encryption…",
        voice = voice,
        onOpenImage = { viewerPath = it },
        header = { expanded ->
            DmHeader(
                nickname = nickname,
                peerID = peerID,
                sessionEstablished = sessionEstablished,
                expanded = expanded,
                isFavorite = identity.isFavorite,
                isVerified = identity.isVerified,
                onClick = onOpenUserDetail
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
    expanded: Boolean,
    isFavorite: Boolean,
    isVerified: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalBitchatPalette.current
    // Floating title row: full-size at the newest messages, shrinks to its dense form
    // while scrolling up into history. Rendered as an overlay, so the animation only
    // relayouts this row, never the message list.
    val spec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.Dp>(
        BitchatMotion.STANDARD_MS
    )
    val headerIconSize by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (expanded) 16.dp else 11.dp, animationSpec = spec, label = "dmHdrIcon"
    )
    val headerTitleSize by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (expanded) 15.dp else 11.dp, animationSpec = spec, label = "dmHdrTitle"
    )
    val headerVPadding by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (expanded) 6.dp else 1.dp, animationSpec = spec, label = "dmHdrPad"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Open user details",
                onClick = onClick
            )
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
        if (isFavorite) {
            Icon(
                painter = painterResource(R.drawable.ic_spec_star_filled),
                contentDescription = "Favorite",
                tint = palette.accentOrange,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(headerIconSize)
            )
        }
        if (isVerified) {
            Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = "Verified",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(headerIconSize)
            )
        }
    }
}
