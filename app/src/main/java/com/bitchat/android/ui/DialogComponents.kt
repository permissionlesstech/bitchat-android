package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

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
                    text = stringResource(R.string.enter_channel_password),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.channel_password_prompt, channelName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = onPasswordChange,
                        label = { Text(stringResource(R.string.channel_password_hint), style = MaterialTheme.typography.bodyMedium) },
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
                        text = stringResource(R.string.join),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.bodyMedium,
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
        val context = LocalContext.current
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(R.string.about_bitchat),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.about_description, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.ok),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}
