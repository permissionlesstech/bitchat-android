package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.nostr.NostrRelayManager

@Composable
fun ClientSettingsSection() {
    val context = LocalContext.current
    val relayManager = remember { NostrRelayManager.getInstance(context) }
    val relays by relayManager.customRelays.collectAsStateWithLifecycle()
    val gateway by com.bitchat.android.services.bridge.MeshGatewayService.enabled.collectAsStateWithLifecycle()
    var previews by remember { mutableStateOf(ClientPrivacyPreferences.showNotificationPreviews(context)) }
    var relayInput by remember { mutableStateOf("") }
    var relayError by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.notification_previews), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.notification_previews_description), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = previews, onCheckedChange = {
                previews = it
                ClientPrivacyPreferences.setNotificationPreviews(context, it)
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.gateway_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.gateway_description), style = MaterialTheme.typography.bodySmall)
            }
            Switch(gateway, onCheckedChange = com.bitchat.android.services.bridge.MeshGatewayService::setEnabled)
        }
        Text(stringResource(R.string.custom_relays), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.custom_relays_description), style = MaterialTheme.typography.bodySmall)
        relays.forEach { url ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(url, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { relayManager.removeCustomRelay(url) }) { Text(stringResource(R.string.relay_remove)) }
            }
        }
        OutlinedTextField(
            value = relayInput,
            onValueChange = { relayInput = it; relayError = false },
            label = { Text(stringResource(R.string.relay_url)) },
            placeholder = { Text("wss://relay.example") },
            singleLine = true,
            isError = relayError,
            modifier = Modifier.fillMaxWidth()
        )
        if (relayError) Text(stringResource(R.string.relay_invalid), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = {
            relayError = !relayManager.addCustomRelay(relayInput)
            if (!relayError) relayInput = ""
        }, enabled = relayInput.isNotBlank()) { Text(stringResource(R.string.relay_add)) }
    }
}
