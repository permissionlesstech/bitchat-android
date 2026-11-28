package com.bitchat.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.FilledTonalButton
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
    val peers by AppStateStore.peers.collectAsState()
    val unread by WearChatState.unreadDms.collectAsState()
    val mesh = WearMeshService.peek()
    val listState = rememberScalingLazyListState()
    val palette = LocalBitchatPalette.current
    val nicknames = mesh?.getPeerNicknames() ?: emptyMap()
    val identityRevision by WearPeerIdentityState.revision.collectAsState()

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

    ScreenScaffold(scrollState = listState) { scaffoldPadding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = scaffoldPadding
        ) {
            item {
                ListHeader {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "People",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        if (peers.isNotEmpty()) {
                            Text(
                                text = " ${peers.size}",
                                color = palette.textTertiary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            item(key = "self") {
                SelfRow(
                    nickname = mesh?.nickname ?: "me",
                    onClick = onEditNickname
                )
            }
            if (peers.isEmpty()) {
                item {
                    Text(
                        text = "No one nearby yet\nScanning…",
                        style = ChatVisualTokens.SystemActionStyle,
                        color = palette.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
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
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        secondaryLabel = {
            Text(
                text = "You · rename",
                style = ChatVisualTokens.SystemActionStyle,
                color = palette.textTertiary,
                maxLines = 1
            )
        },
        icon = {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = null,
                tint = palette.accentOrange,
                modifier = Modifier.size(18.dp)
            )
        }
    ) {
        Text(
            text = nickname,
            style = ChatVisualTokens.SenderStyle,
            color = palette.accentOrange,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * One line per person: name in their stable color, trust marks right after it, and unread
 * messages as an orange count pill at the end. Every row has the same height, so the list
 * reads as a list instead of a stack of differently sized cards.
 */
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
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Name and trust marks take the free width, which pins the pill to the row's end.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                        size = 12.dp,
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
                            .size(12.dp)
                    )
                }
                if (isVerified) {
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = "Verified",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(12.dp)
                    )
                }
            }
            if (unreadCount > 0) {
                UnreadPill(count = unreadCount, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
internal fun UnreadPill(count: Int, modifier: Modifier = Modifier) {
    val description = unreadDescription(count)
    Text(
        text = if (count > 99) "99+" else "$count",
        style = ChatVisualTokens.SenderStyle.copy(fontSize = 11.sp, lineHeight = 13.sp),
        color = MaterialTheme.colorScheme.onTertiary,
        maxLines = 1,
        modifier = modifier
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .background(LocalBitchatPalette.current.accentOrange)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}
