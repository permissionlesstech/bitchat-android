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

/**
 * WifiAwareController manages lifecycle and debug surfacing for the WifiAwareMeshService.
 * It starts/stops the service based on debug preferences and exposes simple flows for UI.
 */
object WifiAwareController {
    private const val TAG = "WifiAwareController"

    private var service: WifiAwareMeshService? = null
    private var appContext: Context? = null
    private val lifecycleLock = Any()
    private var starting = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Wi‑Fi Aware requires Android 10 (Q)+; disabled.")
            try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware not supported on this device (requires Android 10+)")) } catch (_: Exception) {}
            synchronized(lifecycleLock) { starting = false }
            return
        }
        val awareManager = ctx.getSystemService(android.net.wifi.aware.WifiAwareManager::class.java)
        if (awareManager == null || !awareManager.isAvailable) {
            Log.w(TAG, "Wi-Fi Aware is not currently available; not starting")
            try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware is not available on this device right now")) } catch (_: Exception) {}
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
            try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Enable Location Services to start Wi-Fi Aware")) } catch (_: Exception) {}
            synchronized(lifecycleLock) { starting = false }
            return
        }

        // Android 13+: require NEARBY_WIFI_DEVICES runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.NEARBY_WIFI_DEVICES) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "Missing NEARBY_WIFI_DEVICES permission; not starting Wi‑Fi Aware")
                try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Grant Nearby Wi‑Fi Devices to start Wi‑Fi Aware")) } catch (_: Exception) {}
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

    internal fun restartIfStillEnabled(delayMs: Long = 0L) {
        scope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (_enabled.value) startIfPossible()
        }
    }

    fun getService(): WifiAwareMeshService? = service
}
