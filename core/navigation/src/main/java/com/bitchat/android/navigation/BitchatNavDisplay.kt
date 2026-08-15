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
 * Back handling belongs here and nowhere else. NavDisplay drives predictive back
 * through androidx.navigationevent; adding a second handler over the same
 * gesture (an OnBackPressedCallback, or PredictiveBackHandler) can detach the
 * navigationevent input mid-gesture and crash.
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
