package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.nostr.NostrRelayManager
import kotlinx.coroutines.launch

@Composable
fun VouchingDialog(
    targetPubkey: String,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel
) {
    var reason by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vouch for User", fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Building a decentralized web of trust. Why do you trust this user?", fontSize = 12.sp)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason", fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        val identity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                        if (identity != null) {
                            val event = NostrProtocol.createVouchEvent(targetPubkey, reason, identity)
                            NostrRelayManager.shared.sendEvent(event)
                        }
                        onDismiss()
                    }
                },
                enabled = reason.isNotEmpty()
            ) {
                Text("VOUCH", fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace)
            }
        }
    )
}
