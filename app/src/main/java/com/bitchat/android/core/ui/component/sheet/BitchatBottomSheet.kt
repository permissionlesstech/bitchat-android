package com.bitchat.android.core.ui.component.sheet

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Dismisses the enclosing [BitchatBottomSheet], playing the slide-down first.
 *
 * `ModalBottomSheet` only animates itself out when *it* initiates the dismissal — a swipe or a tap
 * on the scrim. Anything that closes a sheet programmatically (a close button, picking an item from
 * a list) previously flipped the caller's `isPresented` flag straight to false, which yanks the
 * composable out of the tree and makes the sheet vanish instantly.
 *
 * Anything inside a sheet that wants to close it should prefer this over calling its own
 * `onDismiss` directly.
 */
val LocalSheetDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitchatBottomSheet(
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    onDismissRequest: () -> Unit,
    content: @Composable (ColumnScope.() -> Unit),
) {
    val scope = rememberCoroutineScope()

    // Runs the hide animation to completion, then tells the caller to drop the sheet. `hide()`
    // throws if the sheet is already on its way out (two rapid taps on a close button), which is
    // benign — the dismissal still has to go through.
    val animatedDismiss: () -> Unit = remember(sheetState, onDismissRequest) {
        {
            scope.launch {
                runCatching { sheetState.hide() }
                onDismissRequest()
            }
        }
    }

    ModalBottomSheet(
        modifier = modifier.statusBarsPadding(),
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        CompositionLocalProvider(LocalSheetDismiss provides animatedDismiss) {
            content()
        }
    }
}
