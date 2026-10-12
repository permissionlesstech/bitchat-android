package com.bitchat.android.core.ui.component.screen

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitchat.android.core.ui.component.button.BackButton

/**
 * Top bar for a full-screen destination: a leading back arrow and no close.
 *
 * Sheets keep BitchatSheetTopBar and its trailing close; a screen is left the
 * way it was entered, so it gets the platform's back affordance instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitchatScreenTopBar(
    onBack: () -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    backgroundAlpha: Float = 0.98f,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = title,
        navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = backgroundAlpha),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
    )
}
