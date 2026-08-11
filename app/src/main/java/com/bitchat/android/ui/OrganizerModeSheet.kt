package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.organizer.OrganizerIdentityManager
import com.bitchat.android.ui.theme.BitchatFontFamily
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizerModeSheet(
    onDismiss: () -> Unit,
    onSendAnnouncement: (BitchatMessage) -> Unit
) {
    var passcode by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var messageContent by remember { mutableStateOf("") }
    var isAuthenticated by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

    val handleDismiss = {
        passcode = ""
        privateKey = ""
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = handleDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetDefaults.properties(
            securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Organizer Broadcast Mode",
                fontFamily = BitchatFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (!isAuthenticated) {
                OutlinedTextField(
                    value = passcode,
                    onValueChange = { passcode = it; errorText = "" },
                    label = { Text("Passcode") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (OrganizerIdentityManager.isLockedOut()) {
                            errorText = "Too many failed attempts. Temporarily locked out."
                        } else if (OrganizerIdentityManager.validatePasscode(passcode)) {
                            isAuthenticated = true
                            errorText = ""
                        } else {
                            if (OrganizerIdentityManager.isLockedOut()) {
                                errorText = "Too many failed attempts. Locked out for 30 seconds."
                            } else {
                                errorText = "Invalid passcode"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Authenticate")
                }
            } else {
                if (!OrganizerIdentityManager.isOrganizerProvisioned()) {
                    Text("Provision Organizer Identity", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it; errorText = "" },
                        label = { Text("Private Key (Hex)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (OrganizerIdentityManager.provisionOrganizer(privateKey)) {
                                privateKey = ""
                                errorText = "Provisioned successfully."
                            } else {
                                errorText = "Invalid private key or does not match official public key."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Provision")
                    }
                } else {
                    Text("Broadcast Official Announcement", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = messageContent,
                        onValueChange = { messageContent = it },
                        label = { Text("Announcement Message") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (messageContent.isNotBlank()) {
                                val msg = BitchatMessage(
                                    id = UUID.randomUUID().toString().uppercase(),
                                    sender = "Organizer",
                                    content = messageContent,
                                    senderPeerID = "",
                                    timestamp = Date(),
                                    isOfficial = true
                                )
                                onSendAnnouncement(msg)
                                messageContent = ""
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    ) {
                        Text("Broadcast to Network")
                    }
                }
            }

            if (errorText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorText,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = BitchatFontFamily
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
