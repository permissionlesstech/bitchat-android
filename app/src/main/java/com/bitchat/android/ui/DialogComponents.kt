package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Dialog components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun PasswordPromptDialog(
    show: Boolean,
    channelName: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (show && channelName != null) {
        val colorScheme = MaterialTheme.colorScheme

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "Enter Channel Password",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = "Channel #$channelName is password protected. Enter the password to join.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = onPasswordChange,
                        label = { Text("Password", style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.outline
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = "Join",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurface
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}

@Composable
fun AppInfoDialog(
    show: Boolean,
    onDismiss: () -> Unit
) {
    if (show) {
        val colorScheme = MaterialTheme.colorScheme

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "About bitchat",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Decentralized mesh messaging over Bluetooth LE\n\n" +
                            "• No servers or internet required\n" +
                            "• End-to-end encrypted private messages\n" +
                            "• Password-protected channels\n" +
                            "• Store-and-forward for offline peers\n\n" +
                            "Triple-click title to emergency clear all data",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "OK",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.primary
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}


/**
 * A themed dialog to confirm if the user wants to shut down the service or background the app.
 * @param show Controls the visibility of the dialog.
 * @param onDismiss Called when the user taps outside the dialog.
 * @param onConfirmExit Called when the user confirms they want to shut down the service.
 * @param onConfirmBackground Called when the user chooses to background the app.
 */
@Composable
fun ExitConfirmationDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirmExit: () -> Unit,
    onConfirmBackground: () -> Unit
) {
    if (show) {
        val colorScheme = MaterialTheme.colorScheme
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "Shut down Bitchat?",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Do you want to shut down Bitchat or keep scanning in the background?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Start
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmExit) {
                    Text(
                        "Shut Down",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onConfirmBackground) {
                    Text(
                        "Background",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.primary
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}
