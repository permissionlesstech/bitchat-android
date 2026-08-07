package com.bitchat.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.R
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import com.bitchat.watch.ui.theme.colorForPeer

@Composable
fun PeopleScreen(onOpenDm: (String) -> Unit, onEditNickname: () -> Unit) {
    val context = LocalContext.current
    val peers by AppStateStore.peers.collectAsState()
    val unread by WearChatState.unreadDms.collectAsState()
    val mesh = WearMeshService.peek()
    val listState = rememberScalingLazyListState()
    val palette = LocalBitchatPalette.current
    val nicknames = mesh?.getPeerNicknames() ?: emptyMap()
    val identityRevision by WearPeerIdentityState.revision.collectAsState()
    var liveVoiceEnabled by remember {
        mutableStateOf(com.bitchat.android.features.voice.LiveVoicePreferences.isEnabled(context))
    }

    // Peers with unread messages float to the top so they are easy to see and reach.
    val sortedPeers = androidx.compose.runtime.remember(
        peers,
        unread,
        nicknames,
        identityRevision
    ) {
        peers.sortedWith(
            compareByDescending<String> { (unread[it] ?: 0) > 0 }
                .thenBy { (nicknames[it] ?: it).lowercase() }
        )
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                ListHeader {
                    Text(
                        text = "People (${peers.size})",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (peers.isEmpty()) {
                item {
                    Text(
                        text = "No one nearby yet\nKeep the app open to mesh",
                        style = ChatVisualTokens.SystemActionStyle,
                        color = palette.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    )
                }
            }
            item(key = "self") {
                SelfRow(
                    nickname = mesh?.nickname ?: "me",
                    onClick = onEditNickname
                )
            }
            item(key = "live_voice") {
                Card(
                    onClick = {
                        liveVoiceEnabled = !liveVoiceEnabled
                        com.bitchat.android.features.voice.LiveVoicePreferences.setEnabled(
                            context,
                            liveVoiceEnabled
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (liveVoiceEnabled) "Live push-to-talk: on" else "Live push-to-talk: off",
                        style = ChatVisualTokens.SenderStyle,
                        color = if (liveVoiceEnabled) MaterialTheme.colorScheme.primary else palette.textTertiary
                    )
                    Text(
                        text = "Tap to toggle; a voice note is still sent on release",
                        style = ChatVisualTokens.SystemActionStyle,
                        color = palette.textTertiary
                    )
                }
            }
            items(sortedPeers, key = { it }) { peerID ->
                val nick = nicknames[peerID] ?: peerID.take(8)
                val identity = WearPeerIdentityState.snapshot(peerID, mesh)
                PersonRow(
                    nickname = nick,
                    peerID = peerID,
                    encrypted = mesh?.hasEstablishedSession(peerID) == true,
                    isFavorite = identity.isFavorite,
                    isVerified = identity.isVerified,
                    unreadCount = unread[peerID] ?: 0,
                    onClick = { onOpenDm(peerID) }
                )
            }
        }
    }
}

@Composable
private fun SelfRow(nickname: String, onClick: () -> Unit) {
    val palette = LocalBitchatPalette.current
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = nickname,
                    style = ChatVisualTokens.SenderStyle,
                    color = palette.accentOrange,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = " (you)",
                    style = ChatVisualTokens.SenderStyle,
                    color = palette.textTertiary
                )
            }
            Text(
                text = "Tap to rename",
                style = ChatVisualTokens.SystemActionStyle,
                color = palette.textTertiary
            )
        }
    }
}

@Composable
private fun PersonRow(
    nickname: String,
    peerID: String,
    encrypted: Boolean,
    isFavorite: Boolean,
    isVerified: Boolean,
    unreadCount: Int,
    onClick: () -> Unit
) {
    val palette = LocalBitchatPalette.current
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = nickname,
                        style = ChatVisualTokens.SenderStyle,
                        color = colorForPeer(nickname + peerID, palette),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (encrypted) {
                        NoiseLockIcon(
                            state = NoiseSessionUiState.Established,
                            size = 11.dp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (isFavorite) {
                        Icon(
                            painter = painterResource(R.drawable.ic_spec_star_filled),
                            contentDescription = "Favorite",
                            tint = palette.accentOrange,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(11.dp)
                        )
                    }
                    if (isVerified) {
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = "Verified",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(11.dp)
                        )
                    }
                }
                if (!encrypted) {
                    Text(
                        text = "Tap to chat",
                        style = ChatVisualTokens.SystemActionStyle,
                        color = palette.textTertiary
                    )
                }
            }
            if (unreadCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MailOutline,
                        contentDescription = "$unreadCount unread messages",
                        tint = palette.accentOrange,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "$unreadCount",
                        style = ChatVisualTokens.SystemActionStyle,
                        color = palette.accentOrange,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
    }
}
