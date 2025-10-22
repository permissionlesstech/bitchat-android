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
        if (_running.value) return
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Wi‑Fi Aware requires Android 10 (Q)+; disabled.")
            try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware not supported on this device (requires Android 10+)")) } catch (_: Exception) {}
            return
        }
        // Android 13+: require NEARBY_WIFI_DEVICES runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.NEARBY_WIFI_DEVICES) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "Missing NEARBY_WIFI_DEVICES permission; not starting Wi‑Fi Aware")
                try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Grant Nearby Wi‑Fi Devices to start Wi‑Fi Aware")) } catch (_: Exception) {}
                return
            }
        }
        try {
            service = WifiAwareMeshService(ctx).also {
                it.startServices()
                _running.value = true
                try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware started")) } catch (_: Exception) {}
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start WifiAwareMeshService", e)
            _running.value = false
            try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware failed to start: ${e.message}")) } catch (_: Exception) {}
        }
    }

    fun stop() {
        try { service?.stopServices() } catch (_: Exception) { }
        service = null
        _running.value = false
        try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi‑Fi Aware stopped")) } catch (_: Exception) {}
    }

    fun getService(): WifiAwareMeshService? = service

    // Optional bridge to BLE mesh for cross-transport relaying
    @Volatile private var bleMesh: com.bitchat.android.mesh.BluetoothMeshService? = null
    fun setBleMeshService(svc: com.bitchat.android.mesh.BluetoothMeshService) { bleMesh = svc }
    fun getBleMeshService(): com.bitchat.android.mesh.BluetoothMeshService? = bleMesh
}
