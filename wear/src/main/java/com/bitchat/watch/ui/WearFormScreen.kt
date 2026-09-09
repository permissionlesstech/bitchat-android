package com.bitchat.watch.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold

/** Independently scrollable form items remain reachable on small watches and at large fonts. */
@Composable
internal fun WearFormScreen(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val direction = LocalLayoutDirection.current
    ScreenScaffold(scrollState = state) { padding ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            autoCentering = null,
            contentPadding =
                padding
                    .withAdditionalPadding(direction, horizontal = 14.dp)
                    .withVerticalClearance(direction, top = 28.dp, bottom = 28.dp),
            content = content,
        )
    }
}
