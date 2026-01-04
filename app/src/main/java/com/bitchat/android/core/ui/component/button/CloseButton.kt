package com.bitchat.android.core.ui.component.button

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier.Companion
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(32.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
        )
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close",
            modifier = Modifier.Companion.size(18.dp)
        )
    }
}