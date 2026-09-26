package com.bitchat.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette

@Composable
fun VerificationCodeScreen(peerID: String) {
    val mesh = WearMeshService.peek()
    val revision by WearPeerIdentityState.revision.collectAsState()
    val identity = androidx.compose.runtime.remember(peerID, revision) {
        WearPeerIdentityState.snapshot(peerID, mesh)
    }
    val myFingerprint = WearPeerIdentityState.myFingerprint(mesh)
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
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = if (identity.isVerified) {
                                Icons.Filled.Verified
                            } else {
                                Icons.Outlined.Warning
                            },
                            contentDescription = null,
                            tint = if (identity.isVerified) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                palette.accentOrange
                            }
                        )
                        Text(
                            text = if (identity.isVerified) "Verified" else "Verify identity",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                FingerprintCard(
                    title = "Their code",
                    fingerprint = identity.fingerprint
                )
            }

            item {
                FingerprintCard(
                    title = "Your code",
                    fingerprint = myFingerprint
                )
            }

            item {
                Text(
                    text = "Compare both full codes in person or over a trusted channel.",
                    style = ChatVisualTokens.SystemActionStyle,
                    color = palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            item {
                Button(
                    onClick = {
                        WearPeerIdentityState.setVerified(
                            peerID = peerID,
                            verified = !identity.isVerified,
                            mesh = mesh
                        )
                    },
                    enabled = identity.fingerprint != null
                ) {
                    Text(
                        if (identity.isVerified) {
                            "Remove verification"
                        } else {
                            "Mark verified"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FingerprintCard(
    title: String,
    fingerprint: String?
) {
    val palette = LocalBitchatPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = ChatVisualTokens.SystemActionStyle,
            color = palette.textTertiary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = fingerprint?.let(::formatVerificationCode) ?: "Handshake pending",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 13.sp
            ),
            color = if (fingerprint == null) {
                palette.accentOrange
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

fun formatVerificationCode(fingerprint: String): String {
    return fingerprint
        .uppercase()
        .chunked(4)
        .chunked(4)
        .joinToString("\n") { line -> line.joinToString(" ") }
}
