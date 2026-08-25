package com.bitchat.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

/**
 * Hosts every feature's destinations.
 *
 * [entryInstallers] arrives as a Hilt multibinding, so adding a destination
 * means adding an @IntoSet provider in the owning feature — this function never
 * changes.
 *
 * This handles Back only between destinations, and only while there is one to
 * go back to: NavDisplay enables its handler on scene.previousEntries being
 * non-empty, so at the root destination it takes no press at all. A screen with
 * state the back stack does not model has to claim Back itself, with a handler
 * registered into the same dispatcher — androidx.activity.compose's BackHandler
 * is, since activity-compose prefers the NavigationEventDispatcher when one is
 * present. Among enabled handlers the last one composed wins.
 */
@Composable
fun BitchatNavDisplay(
    navigator: AppNavigator,
    entryInstallers: Set<EntryProviderInstaller>,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavDisplay(
        backStack = navigator.backStack,
        modifier = modifier,
        onBack = { if (!navigator.goBack()) onExit() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entryInstallers.forEach { install -> install() }
        },
    )
}
