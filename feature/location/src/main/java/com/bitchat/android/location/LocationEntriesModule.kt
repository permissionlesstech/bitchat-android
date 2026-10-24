package com.bitchat.android.location

import com.bitchat.android.navigation.EntryProviderInstaller
import com.bitchat.android.navigation.LocationChannelsRoute
import com.bitchat.android.navigation.LocationNotesRoute
import com.bitchat.android.navigation.Navigator
import com.bitchat.android.ui.LocationChannelsScreen
import com.bitchat.android.ui.LocationNotesRoot
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet

/** Location channels and location notes, contributed to the host's navigation graph. */
@Module
@InstallIn(ActivityRetainedComponent::class)
object LocationEntriesModule {

    @Provides
    @IntoSet
    fun locationEntries(
        navigator: Navigator,
    ): EntryProviderInstaller = {
        entry<LocationChannelsRoute> {
            LocationChannelsScreen(
                onClose = { navigator.popTo(LocationChannelsRoute, inclusive = true) },
                // Pushed like any list → detail step: Back from notes
                // returns to channels, not to chat.
                onShowLocationNotes = { navigator.goTo(LocationNotesRoute) },
            )
        }
        entry<LocationNotesRoute> {
            LocationNotesRoot(
                onBack = { navigator.popTo(LocationNotesRoute, inclusive = true) },
            )
        }
    }
}
