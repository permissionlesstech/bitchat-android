package com.bitchat.android.ui

import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.geohash.LocationChannelManager.PermissionState
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NearbyNotesController
import com.bitchat.android.nostr.NoteSendResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** The part of [LocationChannelManager] the notes screen reads and drives. */
interface NotesLocationSource {
    val permissionState: StateFlow<PermissionState>

    /** The app's location toggle and the system's, both on. */
    val effectiveLocationEnabled: StateFlow<Boolean>
    val availableChannels: StateFlow<List<GeohashChannel>>
    val isLoadingLocation: StateFlow<Boolean>
    val locationNames: StateFlow<Map<GeohashChannelLevel, String>>

    /** A one-shot location request when authorized and enabled. */
    fun refreshChannels()

    /** Turns the persistent app location toggle on (Q-N6). */
    fun enableLocationServices()
    fun enableLocationChannels()
}

/** The part of [LocationNotesManager] the notes screen reads and drives. */
interface NotesStore {
    val notes: StateFlow<List<LocationNotesManager.Note>>
    val state: StateFlow<LocationNotesManager.State>
    val errorMessage: StateFlow<String?>
    val initialLoadComplete: StateFlow<Boolean>
    suspend fun send(content: String, nickname: String?): NoteSendResult
    fun refresh()
    fun clearError()
}

/** The part of [NearbyNotesController] the notes screen holds while it is shown. */
interface NearbyNotesGate {
    fun updateAvailability(enabled: Boolean, authorized: Boolean, geohash: String?)
    fun activate()
    fun deactivate()
    fun reveal()
}

/** The wall clock the note timestamps are measured against. */
fun interface NotesClock {
    fun nowEpochSeconds(): Long
}

internal class ManagerNotesLocationSource @Inject constructor(
    private val manager: LocationChannelManager,
) : NotesLocationSource {
    override val permissionState: StateFlow<PermissionState> get() = manager.permissionState
    override val effectiveLocationEnabled: StateFlow<Boolean> get() = manager.effectiveLocationEnabled
    override val availableChannels: StateFlow<List<GeohashChannel>> get() = manager.availableChannels
    override val isLoadingLocation: StateFlow<Boolean> get() = manager.isLoadingLocation
    override val locationNames: StateFlow<Map<GeohashChannelLevel, String>> get() = manager.locationNames

    override fun refreshChannels() = manager.refreshChannels()
    override fun enableLocationServices() = manager.enableLocationServices()
    override fun enableLocationChannels() = manager.enableLocationChannels()
}

internal class ManagerNotesStore @Inject constructor() : NotesStore {
    private val manager = LocationNotesManager.getInstance()

    override val notes: StateFlow<List<LocationNotesManager.Note>> get() = manager.notes
    override val state: StateFlow<LocationNotesManager.State> get() = manager.state
    override val errorMessage: StateFlow<String?> get() = manager.errorMessage
    override val initialLoadComplete: StateFlow<Boolean> get() = manager.initialLoadComplete

    override suspend fun send(content: String, nickname: String?): NoteSendResult =
        manager.send(content, nickname)
    override fun refresh() = manager.refresh()
    override fun clearError() = manager.clearError()
}

internal class ControllerNearbyNotesGate @Inject constructor() : NearbyNotesGate {
    private val controller get() = NearbyNotesController.shared

    override fun updateAvailability(enabled: Boolean, authorized: Boolean, geohash: String?) =
        controller.updateAvailability(
            locationEnabled = enabled,
            locationAuthorized = authorized,
            buildingGeohash = geohash,
        )

    override fun activate() = controller.activate()
    override fun deactivate() = controller.deactivate()
    override fun reveal() = controller.reveal()
}

internal class SystemNotesClock @Inject constructor() : NotesClock {
    override fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NotesPortsModule {
    @Binds
    abstract fun bindNotesLocationSource(impl: ManagerNotesLocationSource): NotesLocationSource

    @Binds
    abstract fun bindNotesStore(impl: ManagerNotesStore): NotesStore

    @Binds
    abstract fun bindNearbyNotesGate(impl: ControllerNearbyNotesGate): NearbyNotesGate

    @Binds
    abstract fun bindNotesClock(impl: SystemNotesClock): NotesClock
}
