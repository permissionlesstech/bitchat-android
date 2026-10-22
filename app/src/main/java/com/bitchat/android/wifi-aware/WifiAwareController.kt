package com.bitchat.android.wifiaware

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WifiAwareController manages lifecycle and debug surfacing for the WifiAwareMeshService.
 * It starts/stops the service based on debug preferences and exposes simple flows for UI.
 */
object WifiAwareController {
    private const val TAG = "WifiAwareController"
    private const val MAX_RESTART_ATTEMPTS = 15
    private const val RESTART_RETRY_DELAY_MS = 2_000L

    private var service: WifiAwareMeshService? = null
    private var appContext: Context? = null
    private val lifecycleLock = Any()
    private var starting = false
    private val restartInFlight = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)
    private var awareReceiverRegistered = false
    private var lastBlockedReason: String? = null

    /**
     * How many hotspot sessions currently need the radio.
     *
     * Wi-Fi Aware (NAN) and Wi-Fi Direct (P2P) cannot hold interfaces at the same time on
     * common chipsets — the HAL fails to create the P2P iface and every createGroup is
     * answered with BUSY. A non-zero count also blocks [startIfPossible], so a resume or
     * mesh-service restart cannot bring Aware back while a hotspot is up.
     *
     * A count rather than a flag because teardown is asynchronous and sessions can
     * overlap. Callers receive a once-releasable [HotspotLease], so one session cannot
     * accidentally consume another session's hold.
     */
    private val hotspotHolds = AtomicInteger(0)

    /** True while any hotspot session still needs the radio. */
    @VisibleForTesting
    internal fun isHeldForHotspot(): Boolean = hotspotHolds.get() > 0

    private fun heldForHotspot(): Boolean = isHeldForHotspot()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _supported = MutableStateFlow(false)
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _supportStatus = MutableStateFlow<WifiAwareSupport.Status?>(null)
    val supportStatus: StateFlow<WifiAwareSupport.Status?> = _supportStatus.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    // Simple debug surfacing
    private val _connectedPeers = MutableStateFlow<Map<String, String>>(emptyMap()) // peerID -> ip
    val connectedPeers: StateFlow<Map<String, String>> = _connectedPeers.asStateFlow()

    private val _knownPeers = MutableStateFlow<Map<String, String>>(emptyMap()) // peerID -> nickname
    val knownPeers: StateFlow<Map<String, String>> = _knownPeers.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<Set<String>>(emptySet())
    val discoveredPeers: StateFlow<Set<String>> = _discoveredPeers.asStateFlow()

    fun initialize(context: Context, enabledByDefault: Boolean) {
        appContext = context.applicationContext
        val status = refreshSupportStatus(appContext!!)
        if (status.supported) {
            registerAwareStateReceiver(appContext!!)
        } else {
            Log.i(TAG, "Wi-Fi Aware unsupported: ${status.reason}")
        }
        setEnabled(enabledByDefault)
    }

    internal fun publishDebugSnapshot(
        connected: Map<String, String>,
        known: Map<String, String>,
        discovered: Set<String>
    ) {
        _connectedPeers.value = connected
        _knownPeers.value = known
        _discoveredPeers.value = discovered
    }

    private fun clearDebugSnapshot() {
        publishDebugSnapshot(emptyMap(), emptyMap(), emptySet())
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        if (value) startIfPossible() else stop()
    }

    /**
     * A session-scoped claim on the Wi-Fi radio.
     *
     * Closing the same lease twice is a no-op; raw decrements are intentionally not
     * exposed because lifecycle retries and duplicate teardown calls must not release a
     * different hotspot session's hold.
     */
    class HotspotLease internal constructor(
        private val releaseAction: () -> Unit
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) {
                releaseAction()
            }
        }
    }

    /**
     * Releases the Wi-Fi radio so a Wi-Fi Direct hotspot can create its P2P interface,
     * and prevents Aware restarting until the returned lease is closed.
     *
     * Counted because a second share can start while the first is still cleaning up.
     */
    fun acquireHotspotLease(): HotspotLease {
        if (hotspotHolds.getAndIncrement() == 0) {
            Log.i(TAG, "Holding Wi-Fi Aware down so the hotspot can use the radio")
            stop()
        }
        return HotspotLease(::releaseHotspotLease)
    }

    private fun releaseHotspotLease() {
        var remaining: Int
        while (true) {
            val current = hotspotHolds.get()
            if (current == 0) return
            remaining = current - 1
            if (hotspotHolds.compareAndSet(current, remaining)) break
        }

        if (remaining != 0) {
            Log.d(TAG, "Hotspot finished, but $remaining other hold(s) remain")
            return
        }

        Log.i(TAG, "Hotspot finished; restoring Wi-Fi Aware if enabled")
        restartIfStillEnabled()
    }

    @VisibleForTesting
    internal fun resetHotspotLeasesForTest() {
        hotspotHolds.set(0)
    }

    fun startIfPossible() {
        val reusableService = synchronized(lifecycleLock) {
            if (!_enabled.value) return
            if (heldForHotspot()) {
                Log.d(TAG, "Not starting Wi-Fi Aware: held down for the hotspot")
                return
            }
            val existing = service
            if (existing?.isRunning() == true) {
                _running.value = true
                return
            }
            if (starting) return
            starting = true
            existing
        }

        val ctx = appContext ?: run {
            synchronized(lifecycleLock) { starting = false }
            return
        }

        val status = refreshSupportStatus(ctx)
        if (!status.supported) {
            val reason = status.reason ?: "not supported"
            Log.w(TAG, "Wi‑Fi Aware unsupported; not starting ($reason)")
            addBlockedDebugMessage("unsupported:$reason", "Wi-Fi Aware not supported on this device ($reason)")
            synchronized(lifecycleLock) { starting = false }
            return
        }

        val awareManager = WifiAwareSupport.getManager(ctx)
        if (awareManager == null || !status.available) {
            Log.w(TAG, "Wi-Fi Aware is not currently available; not starting")
            addBlockedDebugMessage("unavailable", "Wi-Fi Aware is not available right now")
            synchronized(lifecycleLock) { starting = false }
            return
        }

        // Check system location setting: WifiAwareManager.attach() throws SecurityException if disabled
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
            lm?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
        }

        if (!locationEnabled) {
            Log.w(TAG, "Location services are disabled; Wi-Fi Aware cannot start.")
            addBlockedDebugMessage("location-disabled", "Enable Location Services to start Wi-Fi Aware")
            synchronized(lifecycleLock) { starting = false }
            return
        }

        // Android 13+: require NEARBY_WIFI_DEVICES runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.NEARBY_WIFI_DEVICES) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "Missing NEARBY_WIFI_DEVICES permission; not starting Wi‑Fi Aware")
                addBlockedDebugMessage("missing-nearby-wifi", "Grant Nearby Wi-Fi Devices to start Wi-Fi Aware")
                synchronized(lifecycleLock) { starting = false }
                return
            }
        }
        if (!_enabled.value || heldForHotspot()) {
            synchronized(lifecycleLock) { starting = false }
            return
        }
        try {
            val startedService = reusableService ?: run {
                Log.i(TAG, "Instantiating WifiAwareMeshService...")
                WifiAwareMeshService(ctx)
            }
            startedService.startServices()

            // Test the hold inside the same lock that publishes the service, and that
            // stop() takes. Testing it outside leaves a window where acquiring a lease
            // sets the flag and stop() finds nothing published yet, and this block then
            // publishes anyway — resurrecting NAN while the hotspot owns the radio.
            // Ordering holds because acquireHotspotLease() increments before calling
            // stop(): either we see the flag here, or stop() sees our published service.
            val published = synchronized(lifecycleLock) {
                val canPublish = !heldForHotspot() && startedService.isRunning()
                if (canPublish) {
                    service = startedService
                    _running.value = true
                } else {
                    if (service === startedService) service = null
                    _running.value = false
                }
                canPublish
            }

            if (published) {
                try { com.bitchat.android.service.MeshServiceHolder.unifiedMeshService?.refreshDelegates() } catch (_: Exception) { }
                clearBlockedDebugMessage()
                try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware started")) } catch (_: Exception) {}
            } else {
                // stopServices() can block, so keep it out of the lock.
                val heldForHotspot = heldForHotspot()
                if (heldForHotspot || reusableService == null) {
                    try { startedService.stopServices() } catch (_: Exception) { }
                }
                if (heldForHotspot) {
                    Log.i(TAG, "Abandoned Wi-Fi Aware start: hotspot claimed the radio")
                }
                val detail = if (heldForHotspot) "held down for the hotspot" else "did not start"
                try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware $detail")) } catch (_: Exception) {}
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start WifiAwareMeshService", e)
            _running.value = false
            try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware failed to start: ${e.message}")) } catch (_: Exception) {}
        } finally {
            synchronized(lifecycleLock) { starting = false }
        }
    }

    fun stop() {
        val stopped = synchronized(lifecycleLock) {
            val current = service
            service = null
            starting = false
            _running.value = false
            current
        }
        try { stopped?.stopServices() } catch (_: Exception) { }
        try { com.bitchat.android.services.AppStateStore.clearTransportPeers("WIFI") } catch (_: Exception) { }
        clearDebugSnapshot()
        try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware stopped")) } catch (_: Exception) {}
    }

    internal fun onServiceStopped(stoppedService: WifiAwareMeshService) {
        synchronized(lifecycleLock) {
            if (service !== stoppedService) return
            service = null
            _running.value = false
            try { com.bitchat.android.services.AppStateStore.clearTransportPeers("WIFI") } catch (_: Exception) { }
            clearDebugSnapshot()
        }
    }

    /**
     * Schedules a restart of the Wi-Fi Aware transport. Concurrent requests are coalesced into
     * a single in-flight loop that retries with backoff. This is important because a single fixed
     * delay can land while the service is still tearing down (recoveryInProgress), in which case
     * startServices() defers and we must try again rather than give up.
     */
    internal fun restartIfStillEnabled(delayMs: Long = 0L) {
        // Record the request before coalescing. A request that arrives while a loop is
        // already running — the final lease closing during a loop that has been
        // burning attempts against the hold — would otherwise be dropped, and the old
        // loop would exhaust MAX_RESTART_ATTEMPTS without ever seeing the cleared hold,
        // leaving Aware down despite the user's setting.
        restartRequested.set(true)

        if (!restartInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Restart already in flight; coalescing request")
            return
        }
        scope.launch {
            try {
                if (delayMs > 0L) delay(delayMs)
                do {
                    // Consume the request before working, so anything arriving during
                    // the attempts below schedules another pass.
                    restartRequested.set(false)
                    var attempt = 0
                    while (_enabled.value && !_running.value && attempt < MAX_RESTART_ATTEMPTS) {
                        val ctx = appContext
                        if (ctx != null && !refreshSupportStatus(ctx).supported) break
                        startIfPossible()
                        if (_running.value) break
                        attempt++
                        delay(RESTART_RETRY_DELAY_MS)
                    }
                } while (restartRequested.get() && _enabled.value && !_running.value)
            } finally {
                restartInFlight.set(false)
            }

            // A request landing between the loop exiting and the flag clearing finds
            // restartInFlight still set and returns; pick it up here. Terminates because
            // each pass consumes the flag before doing any work.
            if (restartRequested.get() && _enabled.value && !_running.value) {
                restartIfStillEnabled()
            }
        }
    }

    /**
     * Listens for system Wi-Fi Aware availability changes. Aware can flip off/on at runtime
     * (Wi-Fi toggling, hotspot/SoftAP, location changes); without this we would only recover on
     * an unrelated trigger.
     */
    private fun registerAwareStateReceiver(ctx: Context) {
        if (awareReceiverRegistered) return
        if (!refreshSupportStatus(ctx).supported) return
        try {
            val filter = android.content.IntentFilter(
                android.net.wifi.aware.WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED
            )
            ctx.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    val status = refreshSupportStatus(ctx)
                    Log.i(TAG, "Wi-Fi Aware availability changed: supported=${status.supported} available=${status.available} enabled=${_enabled.value} running=${_running.value}")
                    if (status.available) {
                        if (_enabled.value) restartIfStillEnabled(500)
                    } else if (_running.value) {
                        // Aware went away; tear down cleanly so we can re-attach when it returns.
                        // Note: this does not change the enabled preference.
                        stop()
                    }
                }
            }, filter)
            awareReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Wi-Fi Aware state receiver: ${e.message}")
        }
    }

    private fun refreshSupportStatus(ctx: Context): WifiAwareSupport.Status {
        val status = WifiAwareSupport.evaluate(ctx)
        _supported.value = status.supported
        _available.value = status.available
        _supportStatus.value = status
        return status
    }

    private fun addBlockedDebugMessage(key: String, message: String) {
        if (lastBlockedReason == key) return
        lastBlockedReason = key
        try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
                .addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage(message))
        } catch (_: Exception) { }
    }

    private fun clearBlockedDebugMessage() {
        lastBlockedReason = null
    }

    fun getService(): WifiAwareMeshService? = service
}
