package com.bitchat.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.R
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import com.bitchat.watch.ui.theme.colorForPeer

/**
 * Internal debug screen (M2): raw peer list with RSSI. Kept for troubleshooting; the real
 * people screen arrives in M4.
 */
@Composable
fun PeerDebugScreen() {
    val peers by AppStateStore.peers.collectAsState()
    val mesh = WearMeshService.peek()
    val listState = rememberScalingLazyListState()
    val palette = LocalBitchatPalette.current
    val nicknames = mesh?.getPeerNicknames() ?: emptyMap()
    val rssi = mesh?.getPeerRSSI() ?: emptyMap()
    val identityRevision by WearPeerIdentityState.revision.collectAsState()

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                ListHeader {
                    Text(
                        text = "Peers (${peers.size})",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (peers.isEmpty()) {
                item {
                    Text(
                        text = "Scanning for bitchat devices…",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    )
                }
            }
            items(peers) { peerID ->
                val nick = nicknames[peerID] ?: peerID.take(8)
                val encrypted = mesh?.hasEstablishedSession(peerID) == true
                val identity = androidx.compose.runtime.remember(peerID, identityRevision) {
                    WearPeerIdentityState.snapshot(peerID, mesh)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = nick,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorForPeer(nick + peerID, palette),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (encrypted) {
                        NoiseLockIcon(
                            state = NoiseSessionUiState.Established,
                            size = 11.dp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (identity.isFavorite) {
                        Icon(
                            painter = painterResource(R.drawable.ic_spec_star_filled),
                            contentDescription = "Favorite",
                            tint = palette.accentOrange,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(11.dp)
                        )
                    }
                    if (identity.isVerified) {
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = "Verified",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(11.dp)
                        )
                    }
                    rssi[peerID]?.let {
                        Text(
                            text = "${it}dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textTertiary,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
