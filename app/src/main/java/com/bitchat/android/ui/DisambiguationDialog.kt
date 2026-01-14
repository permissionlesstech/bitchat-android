package com.bitchat.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Dialog for disambiguating between multiple peers with the same nickname.
 * Displays a list of matching peers with their fingerprints, verification status,
 * favorite status, and connection status.
 */
@Composable
fun PeerDisambiguationDialog(
    nickname: String,
    candidatePeerIDs: List<String>,
    peerNicknames: Map<String, String>,
    peerFingerprints: Map<String, String>,
    verifiedFingerprints: Set<String>,
    favoritePeers: Set<String>,
    connectedPeers: List<String>,
    peerDirect: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Multiple users named '$nickname'")
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "Select the correct peer:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.padding(vertical = 4.dp))
                }

                items(candidatePeerIDs) { peerID ->
                    PeerDisambiguationItem(
                        peerID = peerID,
                        nickname = nickname,
                        fingerprint = peerFingerprints[peerID],
                        isVerified = peerFingerprints[peerID]?.let { verifiedFingerprints.contains(it) } ?: false,
                        isFavorite = peerFingerprints[peerID]?.let { favoritePeers.contains(it) } ?: false,
                        isConnected = connectedPeers.contains(peerID),
                        isDirect = peerDirect[peerID] ?: false,
                        onClick = { onSelect(peerID) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PeerDisambiguationItem(
    peerID: String,
    nickname: String,
    fingerprint: String?,
    isVerified: Boolean,
    isFavorite: Boolean,
    isConnected: Boolean,
    isDirect: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = nickname,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    if (isVerified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = "Verified",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF32D74B)
                        )
                    }
                    if (isFavorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Favorite",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFFFD700)
                        )
                    }
                }

                if (fingerprint != null) {
                    Text(
                        text = "ID: ${fingerprint.take(8)}...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                } else {
                    Text(
                        text = "ID: pending",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = Color(0xFFFF9500)
                    )
                }
            }

            // Connection status indicator
            Icon(
                imageVector = when {
                    !isConnected -> Icons.Outlined.Circle
                    isDirect -> Icons.Outlined.SettingsInputAntenna
                    else -> Icons.Filled.Route
                },
                contentDescription = when {
                    !isConnected -> "Offline"
                    isDirect -> "Direct connection"
                    else -> "Routed connection"
                },
                modifier = Modifier.size(16.dp),
                tint = if (isConnected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}
