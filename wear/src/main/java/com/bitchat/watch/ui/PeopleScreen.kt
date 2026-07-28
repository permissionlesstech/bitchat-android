package com.bitchat.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import com.bitchat.watch.ui.theme.colorForPeer

@Composable
fun PeopleScreen(onOpenDm: (String) -> Unit) {
    val peers by AppStateStore.peers.collectAsState()
    val unread by WearChatState.unreadDms.collectAsState()
    val mesh = WearMeshService.peek()
    val listState = rememberScalingLazyListState()
    val palette = LocalBitchatPalette.current
    val nicknames = mesh?.getPeerNicknames() ?: emptyMap()
    val rssi = mesh?.getPeerRSSI() ?: emptyMap()

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                ListHeader {
                    Text(
                        text = "people (${peers.size})",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (peers.isEmpty()) {
                item {
                    Text(
                        text = "no one nearby yet\nkeep the app open to mesh",
                        style = ChatVisualTokens.SystemActionStyle,
                        color = palette.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    )
                }
            }
            items(peers, key = { it }) { peerID ->
                val nick = nicknames[peerID] ?: peerID.take(8)
                val unreadCount = unread[peerID] ?: 0
                val encrypted = mesh?.hasEstablishedSession(peerID) == true
                PersonRow(
                    nickname = nick,
                    peerID = peerID,
                    rssi = rssi[peerID],
                    encrypted = encrypted,
                    unreadCount = unreadCount,
                    onClick = { onOpenDm(peerID) }
                )
            }
        }
    }
}

@Composable
private fun PersonRow(
    nickname: String,
    peerID: String,
    rssi: Int?,
    encrypted: Boolean,
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
                Text(
                    text = nickname,
                    style = ChatVisualTokens.SenderStyle,
                    color = colorForPeer(nickname + peerID, palette),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(if (encrypted) "noise ✓" else "tap to chat")
                        rssi?.let { append(" · ${it}dBm") }
                    },
                    style = ChatVisualTokens.SystemActionStyle,
                    color = if (encrypted) MaterialTheme.colorScheme.primary else palette.textTertiary
                )
            }
            if (unreadCount > 0) {
                Text(
                    text = "$unreadCount new",
                    style = ChatVisualTokens.SystemActionStyle,
                    color = palette.accentOrange,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}
