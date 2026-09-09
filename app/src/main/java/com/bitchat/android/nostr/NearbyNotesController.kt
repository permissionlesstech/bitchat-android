package com.bitchat.android.nostr

import androidx.annotation.MainThread
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-scoped consent gate for nearby location notes.
 *
 * Merely rendering the mesh timeline must not open a building-precision Nostr
 * subscription. A subscription is eligible only after an explicit reveal and
 * while the app is foregrounded and at least one nearby-notes surface is active.
 */
@MainThread
class NearbyNotesController internal constructor(
    private val subscribe: (String) -> Unit,
    private val unsubscribe: () -> Unit,
) {
    private val _revealed = MutableStateFlow(false)
    val revealed: StateFlow<Boolean> = _revealed.asStateFlow()

    private var activeHolders = 0
    private var locationEnabled = false
    private var locationAuthorized = false
    private var appForeground = false
    private var buildingGeohash: String? = null
    private var subscribedGeohash: String? = null

    /**
     * Unlocks nearby notes for this process session. Deactivation deliberately
     * does not reset consent, matching the iOS privacy model.
     */
    fun reveal() {
        if (_revealed.value) return
        _revealed.value = true
        reconcileSubscription()
    }

    /** Holds the subscription while a nearby-notes surface is visible. */
    fun activate() {
        activeHolders += 1
        reconcileSubscription()
    }

    /** Releases a matching [activate] hold and unsubscribes after the last one. */
    fun deactivate() {
        activeHolders = (activeHolders - 1).coerceAtLeast(0)
        reconcileSubscription()
    }

    /** Closes the live subscription whenever the process leaves the foreground. */
    fun updateAppForeground(isForeground: Boolean) {
        appForeground = isForeground
        reconcileSubscription()
    }

    /**
     * Updates the privacy-sensitive inputs independently of view activation.
     * Permission revocation, location disable, or loss of the building cell
     * immediately closes any live subscription.
     */
    fun updateAvailability(
        locationEnabled: Boolean,
        locationAuthorized: Boolean,
        buildingGeohash: String?,
    ) {
        this.locationEnabled = locationEnabled
        this.locationAuthorized = locationAuthorized
        this.buildingGeohash = buildingGeohash
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        reconcileSubscription()
    }

    fun offersRevealHint(): Boolean =
        !_revealed.value &&
            locationEnabled &&
            locationAuthorized &&
            buildingGeohash != null

    private fun reconcileSubscription() {
        val target = buildingGeohash.takeIf {
            activeHolders > 0 &&
                appForeground &&
                _revealed.value &&
                locationEnabled &&
                locationAuthorized
        }

        if (subscribedGeohash != null && subscribedGeohash != target) {
            unsubscribe()
            subscribedGeohash = null
        }

        if (target != null && subscribedGeohash == null) {
            subscribe(target)
            subscribedGeohash = target
        }
    }

    companion object {
        val shared: NearbyNotesController by lazy {
            val manager = LocationNotesManager.getInstance()
            NearbyNotesController(
                subscribe = manager::setGeohash,
                unsubscribe = manager::stop,
            )
        }
    }
}

/**
 * Building precision is location-notes precision and remains private before a
 * reveal. Explicit bookmarks remain eligible because saving one is itself an
 * intentional location act.
 */
internal fun geohashesForSampling(
    availableChannels: List<GeohashChannel>,
    bookmarks: Collection<String>,
    notesRevealed: Boolean,
): List<String> = buildSet {
    availableChannels
        .filter { notesRevealed || it.level != GeohashChannelLevel.BUILDING }
        .mapTo(this) { it.geohash }
    addAll(bookmarks)
}.toList()
