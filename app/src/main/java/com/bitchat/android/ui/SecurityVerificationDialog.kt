package com.bitchat.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Security Verification Dialog - Android equivalent of iOS FingerprintView
 * 
 * Shows fingerprint information for verifying the identity of the person you're chatting with
 */
@Composable
fun SecurityVerificationDialog(
    peerID: String,
    peerNicknames: Map<String, String>,
    peerFingerprints: Map<String, String>,
    verifiedFingerprints: Set<String>,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onVerify: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val clipboardManager = LocalClipboardManager.current
    
    val peerNickname = peerNicknames[peerID] ?: peerID
    val myFingerprint = viewModel.getMyFingerprint()
    val theirFingerprint = peerFingerprints[peerID]
    val sessionState = viewModel.peerSessionStates.value?.get(peerID) ?: "unknown"
    
    // Check if this peer's fingerprint has been verified
    val isVerified = theirFingerprint?.let { verifiedFingerprints.contains(it) } ?: false
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = if (isSystemInDarkTheme()) Color.Black else Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SECURITY VERIFICATION",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0)
                        )
                    )
                    
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "DONE",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                color = if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0)
                            )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Peer info
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Gray.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (sessionState == "established") Icons.Filled.Lock else Icons.Outlined.Lock,
                                contentDescription = if (sessionState == "established") "Verified" else "Not verified",
                                tint = if (sessionState == "established") Color.Green else if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0)
                            )
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            Column {
                                Text(
                                    text = peerNickname,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0)
                                    )
                                )
                                
                                Text(
                                    text = when (sessionState) {
                                        "established" -> "End-to-end encrypted"
                                        "handshaking" -> "Handshake in progress"
                                        "uninitialized" -> "Ready for handshake"
                                        else -> "Unsecured connection"
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0).copy(alpha = 0.7f)
                                    )
                                )
                            }
                            
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    
                    // Their fingerprint
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "THEIR FINGERPRINT:",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0).copy(alpha = 0.7f)
                            )
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (theirFingerprint != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Gray.copy(alpha = 0.1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = formatFingerprint(theirFingerprint),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0),
                                            textAlign = TextAlign.Center
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        TextButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(theirFingerprint))
                                            }
                                        ) {
                                            Text(
                                                text = "COPY",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace
                                                ),
                                                color = if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Gray.copy(alpha = 0.1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "not available - handshake in progress",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFFFA500) // Orange color
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                )
                            }
                        }
                    }
                    
                    // My fingerprint
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "YOUR FINGERPRINT:",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0).copy(alpha = 0.7f)
                            )
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Gray.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = formatFingerprint(myFingerprint),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0),
                                        textAlign = TextAlign.Center
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    TextButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(myFingerprint))
                                        }
                                    ) {
                                        Text(
                                            text = "COPY",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // Verification status
                    if (sessionState == "established" || sessionState == "handshaking") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isVerified) "✓ VERIFIED" else "⚠️ NOT VERIFIED",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isVerified) Color.Green else Color(0xFFFFA500) // Orange color
                                )
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = if (isVerified) {
                                    "you have verified this person's identity."
                                } else {
                                    "compare these fingerprints with $peerNickname using a secure channel."
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isSystemInDarkTheme()) Color.Green else Color(0, 128, 0).copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            if (!isVerified) {
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Button(
                                    onClick = {
                                        viewModel.verifyFingerprint(peerID)
                                        // Don't close the dialog immediately, let the user see the updated state
                                        // onVerify() will be called by the parent component when needed
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.Green,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "MARK AS VERIFIED",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Format fingerprint string with spaces and line breaks for better readability
 */
private fun formatFingerprint(fingerprint: String): String {
    val uppercased = fingerprint.uppercase()
    val formatted = StringBuilder()
    
    for (i in uppercased.indices) {
        // Add space every 4 characters (but not at the start)
        if (i > 0 && i % 4 == 0) {
            // Add newline after every 16 characters (4 groups of 4)
            if (i % 16 == 0) {
                formatted.append("\n")
            } else {
                formatted.append(" ")
            }
        }
        formatted.append(uppercased[i])
    }
    
    return formatted.toString()
}
