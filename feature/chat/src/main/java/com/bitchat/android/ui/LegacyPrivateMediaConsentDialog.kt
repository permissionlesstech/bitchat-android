package com.bitchat.android.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bitchat.android.feature.chat.R

/**
 * Asks once whether [request]'s media may go to an older peer without the
 * private-media encryption. Dismissing it cancels the send.
 */
@Composable
internal fun LegacyPrivateMediaConsentDialog(
    request: LegacyPrivateMediaConsentRequest,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.private_media_legacy_title)) },
        text = {
            Text(
                stringResource(
                    R.string.private_media_legacy_body,
                    request.fileName,
                    request.recipientNickname,
                    request.warning
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onApprove) {
                Text(stringResource(R.string.private_media_legacy_send_once))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
