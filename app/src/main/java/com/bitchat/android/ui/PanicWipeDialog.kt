package com.bitchat.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R

enum class PanicWipeState { IDLE, CONFIRM, ERASING, COMPLETE, FAILED }

@Composable
fun PanicWipeDialog(state: PanicWipeState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (state == PanicWipeState.IDLE) return
    AlertDialog(
        onDismissRequest = { if (state != PanicWipeState.ERASING) onDismiss() },
        title = { Text(stringResource(when (state) {
            PanicWipeState.CONFIRM -> R.string.panic_confirm_title
            PanicWipeState.ERASING -> R.string.panic_erasing_title
            PanicWipeState.COMPLETE -> R.string.panic_complete_title
            else -> R.string.panic_failed_title
        })) },
        text = {
            if (state == PanicWipeState.ERASING) CircularProgressIndicator()
            else Text(stringResource(when (state) {
                PanicWipeState.CONFIRM -> R.string.panic_confirm_body
                PanicWipeState.COMPLETE -> R.string.panic_complete_body
                else -> R.string.panic_failed_body
            }))
        },
        confirmButton = {
            if (state != PanicWipeState.ERASING) TextButton(onClick = {
                if (state == PanicWipeState.CONFIRM || state == PanicWipeState.FAILED) onConfirm() else onDismiss()
            }) { Text(stringResource(when (state) {
                PanicWipeState.CONFIRM -> R.string.panic_erase
                PanicWipeState.FAILED -> R.string.panic_retry
                else -> android.R.string.ok
            })) }
        },
        dismissButton = {
            if (state == PanicWipeState.CONFIRM) TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
