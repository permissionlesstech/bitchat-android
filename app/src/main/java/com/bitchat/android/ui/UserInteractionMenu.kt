package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.bitchat.android.R

/**
 * User interaction menu that appears when clicking on a username
 */
@Composable
fun UserInteractionMenu(
    username: String,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onPrivateMessage: (String) -> Unit,
    onSlap: (String) -> Unit,
    onHug: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (isVisible) {
        DropdownMenu(
            expanded = isVisible,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
            modifier = modifier
        ) {
            // Private Message
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Message,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(stringResource(R.string.user_menu_private_message))
                    }
                },
                onClick = {
                    onPrivateMessage(username)
                    onDismiss()
                }
            )
            
            // Slap
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PanTool,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(stringResource(R.string.user_menu_slap))
                    }
                },
                onClick = {
                    onSlap(username)
                    onDismiss()
                }
            )
            
            // Hug
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(stringResource(R.string.user_menu_hug))
                    }
                },
                onClick = {
                    onHug(username)
                    onDismiss()
                }
            )
        }
    }
}