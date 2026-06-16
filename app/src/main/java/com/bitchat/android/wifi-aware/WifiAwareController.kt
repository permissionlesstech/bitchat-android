package com.bitchat.android.wifiaware

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
    private var awareReceiverRegistered = false
    private var lastBlockedReason: String? = null

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
        // Start background poller for debug surfacing
        scope.launch {
            while (isActive) {
                try {
                    val s = service
                    if (s != null) {
                        _connectedPeers.value = s.getDeviceAddressToPeerMapping() // peerID -> ip
                        _knownPeers.value = s.getPeerNicknames()
                        _discoveredPeers.value = s.getDiscoveredPeerIds()
                    } else {
                        _connectedPeers.value = emptyMap()
                        _knownPeers.value = emptyMap()
                        _discoveredPeers.value = emptySet()
                    }
                } catch (_: Exception) { }
                delay(1000)
            }
        }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        if (value) startIfPossible() else stop()
    }

    fun startIfPossible() {
        val reusableService = synchronized(lifecycleLock) {
            if (!_enabled.value) return
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
        if (!_enabled.value) {
            synchronized(lifecycleLock) { starting = false }
            return
        }
        try {
            val startedService = reusableService ?: run {
                Log.i(TAG, "Instantiating WifiAwareMeshService...")
                WifiAwareMeshService(ctx)
            }
            startedService.startServices()
            if (startedService.isRunning()) {
                synchronized(lifecycleLock) {
                    service = startedService
                    _running.value = true
                }
                try { com.bitchat.android.service.MeshServiceHolder.unifiedMeshService?.refreshDelegates() } catch (_: Exception) { }
                clearBlockedDebugMessage()
                try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware started")) } catch (_: Exception) {}
            } else {
                if (reusableService == null) {
                    try { startedService.stopServices() } catch (_: Exception) { }
                }
                synchronized(lifecycleLock) {
                    if (service === startedService) service = null
                    _running.value = false
                }
                try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware did not start")) } catch (_: Exception) {}
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
        _connectedPeers.value = emptyMap()
        _knownPeers.value = emptyMap()
        _discoveredPeers.value = emptySet()
        try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware stopped")) } catch (_: Exception) {}
    }

    internal fun onServiceStopped(stoppedService: WifiAwareMeshService) {
        synchronized(lifecycleLock) {
            if (service !== stoppedService) return
            service = null
            _running.value = false
            try { com.bitchat.android.services.AppStateStore.clearTransportPeers("WIFI") } catch (_: Exception) { }
            _connectedPeers.value = emptyMap()
            _knownPeers.value = emptyMap()
            _discoveredPeers.value = emptySet()
        }
    }

    /**
     * Schedules a restart of the Wi-Fi Aware transport. Concurrent requests are coalesced into
     * a single in-flight loop that retries with backoff. This is important because a single fixed
     * delay can land while the service is still tearing down (recoveryInProgress), in which case
     * startServices() defers and we must try again rather than give up.
     */
    internal fun restartIfStillEnabled(delayMs: Long = 0L) {
        if (!restartInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Restart already in flight; coalescing request")
            return
        }
        scope.launch {
            try {
                if (delayMs > 0L) delay(delayMs)
                var attempt = 0
                while (_enabled.value && !_running.value && attempt < MAX_RESTART_ATTEMPTS) {
                    val ctx = appContext
                    if (ctx != null && !refreshSupportStatus(ctx).supported) break
                    startIfPossible()
                    if (_running.value) break
                    attempt++
                    delay(RESTART_RETRY_DELAY_MS)
                }
            } finally {
                restartInFlight.set(false)
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
