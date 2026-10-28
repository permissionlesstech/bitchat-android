package com.bitchat.android

import android.content.Context
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.nostr.LocationNotesInitializer
import com.bitchat.android.nostr.PoWPreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider

/** The app's part of init (P6) that needs no Activity, run by [AppStartupGate]. */
interface AppInitializer {

    /** PoW preferences and location notes, before the services start. */
    fun initializeLibraries()

    /** Starts the enabled mesh transports. */
    fun startServices()
}

class DefaultAppInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    // Re-reads the holder each time, as MeshModule's bindings do.
    private val meshService: Provider<MeshService>,
    private val powPreferences: PoWPreferenceManager,
    private val locationNotesInitializer: LocationNotesInitializer,
) : AppInitializer {

    override fun initializeLibraries() {
        // Initialize PoW preferences early in the initialization process
        powPreferences.load()

        // Initialize Location Notes Manager (extracted to separate file)
        locationNotesInitializer.initialize(context)
    }

    override fun startServices() {
        meshService.get().startServices()
    }
}
