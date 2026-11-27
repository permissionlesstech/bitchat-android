package com.bitchat.android.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject

/**
 * Holds the navigation entries' ViewModels, so a new session can discard them all while the
 * Activity stays. Retained like the back stack, so a configuration change keeps them.
 *
 * A ViewModel still on screen when [advance] is called lives until its screen leaves the
 * composition, which [BitchatNavDisplay] does as soon as it sees [generation] change.
 *
 * Confined to the main thread.
 */
@Stable
@ActivityRetainedScoped
class GenerationViewModelStoreOwner @Inject constructor(
    lifecycle: ActivityRetainedLifecycle,
) : ViewModelStoreOwner {

    override var viewModelStore = ViewModelStore()
        private set

    /** How many times this owner has advanced. */
    var generation by mutableIntStateOf(0)
        private set

    init {
        lifecycle.addOnClearedListener { viewModelStore.clear() }
    }

    /** Clears the current store and starts an empty one. */
    fun advance() {
        viewModelStore.clear()
        viewModelStore = ViewModelStore()
        generation += 1
    }
}
