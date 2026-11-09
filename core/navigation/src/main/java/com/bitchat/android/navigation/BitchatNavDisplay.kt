package com.bitchat.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.defaultViewModelCreationExtras
import androidx.lifecycle.defaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.ui.NavDisplay

/**
 * Hosts every feature's destinations.
 *
 * [entryInstallers] arrives as a Hilt multibinding, so adding a destination
 * means adding an @IntoSet provider in the owning feature — this function never
 * changes.
 *
 * NavDisplay claims Back only while something sits beneath the current scene. A
 * screen that owns overlays the back stack does not model has to claim the press
 * itself with androidx.activity.compose's BackHandler, which registers into the
 * same dispatcher; among enabled handlers the last one composed wins.
 *
 * [sceneStrategies] are tried in order before the single-pane default, so an
 * entry can ask to be shown some other way, such as a sheet over the screen
 * beneath it.
 *
 * Each entry's ViewModels live in [viewModelStores], and the whole display is keyed on its
 * [GenerationViewModelStoreOwner.screensKey], so advancing the owner starts the screens over: every
 * ViewModel and all saved screen state go, and the Activity is untouched. The factory and creation
 * extras stay the host's, which is what makes the entries' Hilt ViewModels work; the owner is
 * retained, so it cannot hold them itself.
 */
@Composable
fun BitchatNavDisplay(
    navigator: AppNavigator,
    viewModelStores: GenerationViewModelStoreOwner,
    entryInstallers: Set<EntryProviderInstaller>,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    sceneStrategies: List<SceneStrategy<NavKey>> = emptyList(),
) {
    // Read here, in composition: the transition specs are not composable.
    val layoutDirection = LocalLayoutDirection.current
    val host = LocalViewModelStoreOwner.current
    key(viewModelStores.screensKey) {
        val viewModelStoreProvider = rememberViewModelStoreProvider(
            parent = viewModelStores,
            defaultCreationExtras = host.defaultViewModelCreationExtras,
            defaultFactory = host.defaultViewModelProviderFactory,
        )
        NavDisplay(
            backStack = navigator.backStack,
            modifier = modifier,
            onBack = { if (!navigator.goBack()) onExit() },
            sceneStrategies = sceneStrategies,
            transitionSpec = { ScreenTransitions.push(layoutDirection) },
            popTransitionSpec = { ScreenTransitions.pop(layoutDirection) },
            predictivePopTransitionSpec = { ScreenTransitions.pop(layoutDirection) },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(viewModelStoreProvider),
            ),
            entryProvider = entryProvider {
                entryInstallers.forEach { install -> install() }
            },
        )
    }
}
