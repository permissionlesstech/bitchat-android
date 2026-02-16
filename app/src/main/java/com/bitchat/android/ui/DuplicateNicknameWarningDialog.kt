package com.bitchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

/**
 * Dialog warning users when they choose a nickname that matches existing peers.
 * Helps prevent confusion in nickname-based messaging.
 */
@Composable
fun DuplicateNicknameWarningDialog(
    nickname: String,
    existingPeerIDs: List<String>,
    peerFingerprints: Map<String, String>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val peerCount = existingPeerIDs.size
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Color(0xFFFF9500)
            )
        },
        title = {
            Text(stringResource(R.string.duplicate_nickname_warning))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.duplicate_nickname_used_by,
                        peerCount,
                        nickname,
                        peerCount
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )

                existingPeerIDs.take(3).forEach { peerID ->
                    val fp = peerFingerprints[peerID]?.take(8)
                        ?: stringResource(R.string.unknown)
                    Text(
                        text = stringResource(R.string.duplicate_nickname_peer_entry, fp, nickname),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                val remaining = peerCount - 3
                if (remaining > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.and_more_peers, remaining, remaining),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.duplicate_nickname_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9500)
                )
            ) {
                Text(stringResource(R.string.use_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.abort))
            }
        }
    )
}