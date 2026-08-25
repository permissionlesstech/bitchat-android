package com.bitchat.watch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.watch.R
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import com.bitchat.watch.ui.theme.colorForPeer

@Composable
fun UserDetailScreen(
    peerID: String,
    onOpenVerification: () -> Unit
) {
    val mesh = WearMeshService.peek()
    val revision by WearPeerIdentityState.revision.collectAsState()
    val identity = androidx.compose.runtime.remember(peerID, revision) {
        WearPeerIdentityState.snapshot(peerID, mesh)
    }
    val nickname = mesh?.getPeerNickname(peerID) ?: peerID.take(8)
    val listState = rememberScalingLazyListState()
    val palette = LocalBitchatPalette.current

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        ) {
            item {
                ListHeader {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = nickname,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colorForPeer(nickname + peerID, palette),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "User details",
                            style = ChatVisualTokens.SystemActionStyle,
                            color = palette.textTertiary
                        )
                    }
                }
            }

            item {
                Card(
                    onClick = {
                        mesh?.let {
                            WearPeerIdentityState.setFavorite(
                                peerID = peerID,
                                isFavorite = !identity.isFavorite,
                                mesh = it
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(
                                if (identity.isFavorite) {
                                    R.drawable.ic_spec_star_filled
                                } else {
                                    R.drawable.ic_spec_star
                                }
                            ),
                            contentDescription = when {
                                identity.isFavorite -> "Favorite"
                                identity.theyFavoritedUs -> "They favorited you"
                                else -> "Not a favorite"
                            },
                            tint = if (
                                identity.isFavorite || identity.theyFavoritedUs
                            ) {
                                palette.accentOrange
                            } else {
                                palette.textTertiary
                            },
                            modifier = Modifier.size(24.dp)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        ) {
                            Text(
                                text = favoriteTitle(identity),
                                style = ChatVisualTokens.SenderStyle,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = favoriteSubtitle(identity),
                                style = ChatVisualTokens.SystemActionStyle,
                                color = palette.textTertiary
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    onClick = onOpenVerification,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (identity.isVerified) {
                                Icons.Filled.Verified
                            } else {
                                Icons.Filled.Lock
                            },
                            contentDescription = null,
                            tint = if (identity.isVerified) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                palette.textTertiary
                            },
                            modifier = Modifier.size(22.dp)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        ) {
                            Text(
                                text = if (identity.isVerified) {
                                    "Identity verified"
                                } else {
                                    "Verification code"
                                },
                                style = ChatVisualTokens.SenderStyle,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Compare cryptographic fingerprints",
                                style = ChatVisualTokens.SystemActionStyle,
                                color = palette.textTertiary
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Peer ${peerID.take(8)}",
                    style = ChatVisualTokens.SystemActionStyle,
                    color = palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

private fun favoriteTitle(identity: WearPeerIdentitySnapshot): String = when {
    identity.isFavorite && identity.theyFavoritedUs -> "Mutual favorite"
    identity.isFavorite -> "Favorited"
    identity.theyFavoritedUs -> "Favorite back"
    else -> "Add favorite"
}

private fun favoriteSubtitle(identity: WearPeerIdentitySnapshot): String = when {
    identity.isFavorite && identity.theyFavoritedUs -> "You favorited each other"
    identity.isFavorite -> "Remove from favorites"
    identity.theyFavoritedUs -> "They favorited you"
    else -> "Keep this person easy to find"
}
