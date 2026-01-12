package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning as OutlinedWarning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet

private data class SecurityStatusInfo(
    val text: String,
    val icon: ImageVector,
    val tint: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityVerificationSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    if (!isPresented) return

    val peerID by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
    val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()
    val peerSessionStates by viewModel.peerSessionStates.collectAsStateWithLifecycle()

    val isDark = isSystemInDarkTheme()
    val accent = if (isDark) Color.Green else Color(0xFF008000)
    val boxColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f)
    val peerHexRegex = remember { Regex("^[0-9a-fA-F]{16}$") }

    BitchatBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecurityVerificationHeader(
                accent = accent,
                onClose = onDismiss
            )

            if (peerID == null) {
                Text(
                    text = stringResource(R.string.fingerprint_no_peer),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            } else {
                val selectedPeerID = peerID!!
                val displayName = viewModel.resolvePeerDisplayNameForFingerprint(selectedPeerID)
                val fingerprint = viewModel.getPeerFingerprintForDisplay(selectedPeerID)
                val isVerified = fingerprint != null && verifiedFingerprints.contains(fingerprint)
                val sessionState = peerSessionStates[selectedPeerID]
                val statusInfo = buildStatusInfo(
                    isVerified = isVerified,
                    sessionState = sessionState,
                    accent = accent
                )

                SecurityStatusCard(
                    displayName = displayName,
                    accent = accent,
                    boxColor = boxColor,
                    statusInfo = statusInfo
                )

                FingerprintBlock(
                    title = stringResource(R.string.fingerprint_their),
                    fingerprint = fingerprint,
                    boxColor = boxColor,
                    accent = accent
                )

                FingerprintBlock(
                    title = stringResource(R.string.fingerprint_yours),
                    fingerprint = viewModel.getMyFingerprint(),
                    boxColor = boxColor,
                    accent = accent
                )

                SecurityVerificationActions(
                    isVerified = isVerified,
                    fingerprint = fingerprint,
                    displayName = displayName,
                    accent = accent,
                    canStartHandshake = fingerprint == null && selectedPeerID.matches(peerHexRegex),
                    onStartHandshake = { viewModel.meshService.initiateNoiseHandshake(selectedPeerID) },
                    onVerify = { fp -> viewModel.verifyFingerprintValue(fp) },
                    onUnverify = { fp -> viewModel.unverifyFingerprintValue(fp) }
                )
            }
        }
    }
}

@Composable
private fun SecurityVerificationHeader(
    accent: Color,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.security_verification_title),
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = accent
        )
        Spacer(modifier = Modifier.weight(1f))
        CloseButton(onClick = onClose)
    }
}

@Composable
private fun buildStatusInfo(
    isVerified: Boolean,
    sessionState: String?,
    accent: Color
): SecurityStatusInfo {
    val text = when {
        isVerified -> stringResource(R.string.fingerprint_status_verified)
        sessionState == "established" -> stringResource(R.string.fingerprint_status_encrypted)
        sessionState == "handshaking" -> stringResource(R.string.fingerprint_status_handshaking)
        sessionState == "failed" -> stringResource(R.string.fingerprint_status_failed)
        else -> stringResource(R.string.fingerprint_status_uninitialized)
    }
    val icon = when {
        isVerified -> Icons.Filled.Verified
        sessionState == "handshaking" -> Icons.Outlined.Sync
        sessionState == "failed" -> Icons.Outlined.OutlinedWarning
        sessionState == "established" -> Icons.Filled.Lock
        else -> Icons.Outlined.NoEncryption
    }
    val tint = when {
        isVerified -> Color(0xFF32D74B)
        sessionState == "failed" -> Color(0xFFFF3B30)
        sessionState == "handshaking" -> Color(0xFFFF9500)
        sessionState == "established" -> Color(0xFF32D74B)
        else -> accent.copy(alpha = 0.6f)
    }
    return SecurityStatusInfo(text, icon, tint)
}

@Composable
private fun SecurityStatusCard(
    displayName: String,
    accent: Color,
    boxColor: Color,
    statusInfo: SecurityStatusInfo
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(boxColor, shape = MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = statusInfo.icon,
            contentDescription = null,
            tint = statusInfo.tint
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = accent
            )
            Text(
                text = statusInfo.text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = accent.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun SecurityVerificationActions(
    isVerified: Boolean,
    fingerprint: String?,
    displayName: String,
    accent: Color,
    canStartHandshake: Boolean,
    onStartHandshake: () -> Unit,
    onVerify: (String) -> Unit,
    onUnverify: (String) -> Unit
) {
    if (canStartHandshake) {
        Button(
            onClick = onStartHandshake,
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.fingerprint_start_handshake),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }

    if (isVerified) {
        VerificationStatusRow(
            icon = Icons.Filled.Verified,
            iconTint = Color(0xFF32D74B),
            text = stringResource(R.string.fingerprint_verified_label),
            textTint = Color(0xFF32D74B)
        )
        Text(
            text = stringResource(R.string.fingerprint_verified_message),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = accent.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Button(
            onClick = { fingerprint?.let(onUnverify) },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF3B30),
                contentColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.verify_remove),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    } else {
        VerificationStatusRow(
            icon = Icons.Filled.Warning,
            iconTint = Color(0xFFFF9500),
            text = stringResource(R.string.fingerprint_not_verified_label),
            textTint = Color(0xFFFF9500)
        )
        Text(
            text = stringResource(R.string.fingerprint_not_verified_message_fmt, displayName),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = accent.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        if (fingerprint != null) {
            Button(
                onClick = { onVerify(fingerprint) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF34C759),
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.fingerprint_mark_verified),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun VerificationStatusRow(
    icon: ImageVector,
    iconTint: Color,
    text: String,
    textTint: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = textTint
        )
    }
}

@Composable
private fun FingerprintBlock(
    title: String,
    fingerprint: String?,
    boxColor: Color,
    accent: Color
) {
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember(fingerprint) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = accent.copy(alpha = 0.8f)
        )
        if (fingerprint != null) {
            Column {
                Text(
                    text = formatFingerprint(fingerprint),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    ),
                    color = accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {},
                            onLongClick = { showMenu = true }
                        )
                        .background(boxColor, shape = MaterialTheme.shapes.small)
                        .padding(16.dp),
                )
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.fingerprint_copy)) },
                        onClick = {
                            clipboardManager.setText(AnnotatedString(fingerprint))
                            showMenu = false
                        }
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.fingerprint_pending),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = Color(0xFFFF9500),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

private fun formatFingerprint(fingerprint: String): String {
    val upper = fingerprint.uppercase()
    val sb = StringBuilder()
    upper.forEachIndexed { index, c ->
        if (index > 0 && index % 4 == 0) {
            if (index % 16 == 0) sb.append('\n') else sb.append(' ')
        }
        sb.append(c)
    }
    return sb.toString()
}
