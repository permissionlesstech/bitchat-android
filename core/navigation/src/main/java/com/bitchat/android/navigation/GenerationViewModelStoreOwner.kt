package com.bitchat.android.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import java.util.UUID
import javax.inject.Inject

/**
 * Holds the navigation entries' ViewModels, so a new session can discard them all while the
 * Activity stays. Retained like the back stack, so a configuration change keeps them.
 *
 * A ViewModel still on screen when [advance] is called lives until its screen leaves the
 * composition, which [BitchatNavDisplay] does as soon as it sees [screensKey] change.
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

    /**
     * What the screens are keyed on. The first is the same in every process, so saved screens come
     * back after process death. The saved state keeps what no screen has claimed and a screen finds
     * its own by this key, so a later one is never reused: the same number in a later process would
     * hand an old session's saved screens to a new one.
     */
    var screensKey: Any by mutableStateOf(FIRST_SCREENS_KEY)
        private set

    init {
        lifecycle.addOnClearedListener { viewModelStore.clear() }
    }

    /** Clears the current store and starts an empty one. */
    fun advance() {
        viewModelStore.clear()
        viewModelStore = ViewModelStore()
        generation += 1
        screensKey = UUID.randomUUID()
    }

    private companion object {
        const val FIRST_SCREENS_KEY = 0
    }
}
