package com.bitchat.android.core.ui.component.button

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

@Composable
fun CloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        // 44.dp to match every other tap target in the app's chrome.
        modifier = modifier.size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = colorScheme.primary,
            containerColor = Color.Transparent
        )
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.close_plain),
            modifier = Modifier.size(18.dp)
        )
    }
}
