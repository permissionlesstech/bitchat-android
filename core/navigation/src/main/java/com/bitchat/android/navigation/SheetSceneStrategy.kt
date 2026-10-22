package com.bitchat.android.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.contains
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet

/**
 * Shows entries marked with [sheet] as a bottom sheet over the screen beneath
 * them, which stays composed.
 *
 * Invariant: never a sheet on top of a sheet. A sheet is a leaf — one list,
 * action set or fixed view, no sub-screens — and sits directly on a screen.
 * Anything a sheet opens replaces it rather than stacking on it. A destination
 * that would ever be opened over a sheet, has internal navigation, stacks
 * another destination, takes text input or launches an Activity for a result
 * is a full screen instead. NoStackedSheetsTest walks every opener.
 */
class SheetSceneStrategy : SceneStrategy<NavKey> {

    override fun SceneStrategyScope<NavKey>.calculateScene(
        entries: List<NavEntry<NavKey>>,
    ): Scene<NavKey>? {
        val top = entries.lastOrNull() ?: return null
        if (!top.metadata.contains(SheetKey)) return null
        val beneath = entries.dropLast(1)
        return SheetScene(
            key = top.contentKey,
            previousEntries = beneath,
            overlaidEntries = beneath,
            entry = top,
            onBack = onBack,
        )
    }

    companion object {
        /** Entry metadata that shows the entry as a sheet. */
        fun sheet(): Map<String, Any> = metadata { put(SheetKey, Unit) }

        private object SheetKey : NavMetadataKey<Unit>
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private data class SheetScene(
    override val key: Any,
    override val previousEntries: List<NavEntry<NavKey>>,
    override val overlaidEntries: List<NavEntry<NavKey>>,
    private val entry: NavEntry<NavKey>,
    private val onBack: () -> Unit,
) : OverlayScene<NavKey> {

    override val entries: List<NavEntry<NavKey>> = listOf(entry)

    // Outside the constructor, so neither is part of equality. NavDisplay keeps
    // the scene instance it first composed for a key and removes that one, so
    // what the content sets here is what onRemove sees, and the reverse.
    private var sheetState: SheetState? = null

    // Set once the entry is on its way off the stack, from either end. A swipe,
    // a scrim tap, Back and the sheet's own close button all end in
    // onDismissRequest, and the sheet's window keeps taking Back presses while
    // it slides down, whether the user dismissed it or code popped it. onBack
    // pops, and finishes the app when there is nothing left to pop, so no press
    // after the first dismissal may reach it.
    private var dismissed = false

    override val content: @Composable () -> Unit = {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        SideEffect { sheetState = state }
        // The sheet is its own window. Content that observes lifecycle, such as
        // a resume effect, follows the sheet rather than the Activity.
        val lifecycleOwner = rememberLifecycleOwner()
        BitchatBottomSheet(
            sheetState = state,
            onDismissRequest = {
                if (!dismissed) {
                    dismissed = true
                    onBack()
                }
            },
        ) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                entry.Content()
            }
        }
    }

    /**
     * Slides the sheet down when it is popped from outside, such as the stack
     * being reset, rather than letting it vanish mid-frame. A sheet the user
     * dismissed has already hidden, and hiding it again does nothing.
     */
    override suspend fun onRemove() {
        dismissed = true
        runCatching { sheetState?.hide() }
    }
}
