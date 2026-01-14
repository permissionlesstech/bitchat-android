package com.bitchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

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
            Text("Duplicate Nickname Warning")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "The nickname '$nickname' is already used by ${existingPeerIDs.size} other ${if (existingPeerIDs.size == 1) "peer" else "peers"}:",
                    style = MaterialTheme.typography.bodyMedium
                )

                existingPeerIDs.take(3).forEach { peerID ->
                    val fp = peerFingerprints[peerID]
                    Text(
                        text = "• ${fp?.take(8) ?: "unknown"} ($nickname)",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                if (existingPeerIDs.size > 3) {
                    Text(
                        text = "... and ${existingPeerIDs.size - 3} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "This may cause confusion when sending messages. Fingerprints will help identify the correct peer.",
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
                Text("Use Anyway")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Choose Different Name")
            }
        }
    )
}
