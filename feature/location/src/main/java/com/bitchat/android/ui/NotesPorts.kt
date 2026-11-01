package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
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

/** The part of [LocationChannelManager] the notes and channels screens read and drive. */
interface NotesLocationSource {
    val permissionState: StateFlow<PermissionState>

    /** The app's location toggle and the system's, both on. */
    val effectiveLocationEnabled: StateFlow<Boolean>
    val availableChannels: StateFlow<List<GeohashChannel>>
    val isLoadingLocation: StateFlow<Boolean>
    val locationNames: StateFlow<Map<GeohashChannelLevel, String>>
    val selectedChannel: StateFlow<ChannelID>

    /** The app's own location toggle. */
    val locationServicesEnabled: StateFlow<Boolean>

    /** The system's location setting. */
    val systemLocationEnabled: StateFlow<Boolean>

    /** A one-shot location request when authorized and enabled. */
    fun refreshChannels()

    /** Turns the persistent app location toggle on (Q-N6). */
    fun enableLocationServices()
    fun disableLocationServices()
    fun enableLocationChannels()

    /** Re-reads the runtime permission, publishes it and returns it. */
    fun syncPermissionState(): PermissionState

    /** Continuous location updates, until [endLiveRefresh]. */
    fun beginLiveRefresh()
    fun endLiveRefresh()
    fun select(channel: ChannelID)

    /** Selects a live nearby channel; false when it cannot be used. */
    fun selectNearby(channel: GeohashChannel): Boolean
    fun selectManual(channel: GeohashChannel, teleported: Boolean)
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

/** The part of [NearbyNotesController] the notes and channels screens hold while shown. */
interface NearbyNotesGate {
    /** Whether the user has asked to see nearby notes. */
    val revealed: StateFlow<Boolean>
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
    override val selectedChannel: StateFlow<ChannelID> get() = manager.selectedChannel
    override val locationServicesEnabled: StateFlow<Boolean> get() = manager.locationServicesEnabled
    override val systemLocationEnabled: StateFlow<Boolean> get() = manager.systemLocationEnabled

    override fun refreshChannels() = manager.refreshChannels()
    override fun enableLocationServices() = manager.enableLocationServices()
    override fun disableLocationServices() = manager.disableLocationServices()
    override fun enableLocationChannels() = manager.enableLocationChannels()
    override fun syncPermissionState(): PermissionState = manager.syncPermissionState()
    override fun beginLiveRefresh() = manager.beginLiveRefresh()
    override fun endLiveRefresh() = manager.endLiveRefresh()
    override fun select(channel: ChannelID) = manager.select(channel)
    override fun selectNearby(channel: GeohashChannel): Boolean = manager.selectNearby(channel)
    override fun selectManual(channel: GeohashChannel, teleported: Boolean) =
        manager.selectManual(channel, teleported)
}

internal class ManagerNotesStore @Inject constructor(
    private val manager: LocationNotesManager,
) : NotesStore {

    override val notes: StateFlow<List<LocationNotesManager.Note>> get() = manager.notes
    override val state: StateFlow<LocationNotesManager.State> get() = manager.state
    override val errorMessage: StateFlow<String?> get() = manager.errorMessage
    override val initialLoadComplete: StateFlow<Boolean> get() = manager.initialLoadComplete

    override suspend fun send(content: String, nickname: String?): NoteSendResult =
        manager.send(content, nickname)
    override fun refresh() = manager.refresh()
    override fun clearError() = manager.clearError()
}

internal class ControllerNearbyNotesGate @Inject constructor(
    private val controller: NearbyNotesController,
) : NearbyNotesGate {

    override val revealed: StateFlow<Boolean> get() = controller.revealed

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
