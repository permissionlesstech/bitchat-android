package com.bitchat.android.nostr

import android.os.Handler
import android.os.Looper
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
    /** Runs the action after the delay and returns a function that cancels it. */
    private val scheduleRelease: (delayMs: Long, action: () -> Unit) -> () -> Unit =
        { _, action -> action(); {} },
) {
    private val _revealed = MutableStateFlow(false)
    val revealed: StateFlow<Boolean> = _revealed.asStateFlow()

    private var activeHolders = 0
    private var locationEnabled = false
    private var locationAuthorized = false
    private var appForeground = false
    private var buildingGeohash: String? = null
    private var subscribedGeohash: String? = null
    private var releasePending = false
    private var cancelRelease: (() -> Unit)? = null

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
        cancelPendingRelease()
        activeHolders += 1
        reconcileSubscription()
    }

    /**
     * Releases a matching [activate] hold. After the last one the subscription
     * closes once [RELEASE_GRACE_MS] passes without a new hold, so a surface
     * that is rebuilt (dispose, then compose again) keeps its notes.
     */
    fun deactivate() {
        activeHolders = (activeHolders - 1).coerceAtLeast(0)
        if (activeHolders == 0 && subscribedGeohash != null && !releasePending) {
            releasePending = true
            val cancel = scheduleRelease(RELEASE_GRACE_MS) {
                releasePending = false
                cancelRelease = null
                reconcileSubscription()
            }
            if (releasePending) cancelRelease = cancel
        }
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
        val allowed = buildingGeohash.takeIf {
            appForeground &&
                _revealed.value &&
                locationEnabled &&
                locationAuthorized
        }
        // The release grace only keeps an open subscription; any privacy change ends it
        if (allowed == null || (activeHolders == 0 && allowed != subscribedGeohash)) {
            cancelPendingRelease()
        }
        val target = allowed.takeIf { activeHolders > 0 || releasePending }

        if (subscribedGeohash != null && subscribedGeohash != target) {
            unsubscribe()
            subscribedGeohash = null
        }

        if (target != null && subscribedGeohash == null) {
            subscribe(target)
            subscribedGeohash = target
        }
    }

    private fun cancelPendingRelease() {
        cancelRelease?.invoke()
        cancelRelease = null
        releasePending = false
    }

    companion object {
        const val RELEASE_GRACE_MS = 1_000L

        val shared: NearbyNotesController by lazy {
            val manager = LocationNotesManager.getInstance()
            val mainHandler = Handler(Looper.getMainLooper())
            NearbyNotesController(
                subscribe = manager::setGeohash,
                unsubscribe = manager::stop,
                scheduleRelease = { delayMs, action ->
                    val runnable = Runnable(action)
                    mainHandler.postDelayed(runnable, delayMs)
                    ({ mainHandler.removeCallbacks(runnable) })
                },
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
