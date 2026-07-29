package com.bitchat.android.hotspot

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.R
import com.bitchat.android.wifiaware.WifiAwareController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for managing hotspot state and lifecycle.
 */
class HotspotViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "HotspotViewModel"
    }

    private val _state = MutableStateFlow<HotspotState>(HotspotState.Intro)
    val state: StateFlow<HotspotState> = _state.asStateFlow()

    private var hotspotManager: HotspotManager? = null
    private var webServer: ApkWebServer? = null

    /** Once-releasable radio claim owned by the current hotspot session. */
    private var awareLease: WifiAwareController.HotspotLease? = null
    private val context = application.applicationContext

    /**
     * Start the hotspot with the provided APK file.
     */
    fun startHotspot(apkFile: File) {
        if (_state.value is HotspotState.Starting || _state.value is HotspotState.Active) {
            Log.w(TAG, "Hotspot already starting or active")
            return
        }

        Log.d(TAG, "Starting hotspot with APK: ${apkFile.name}")
        _state.value = HotspotState.Starting

        viewModelScope.launch {
            try {
                // Wi-Fi Aware holds a NAN interface that blocks the P2P one; release it
                // first or every createGroup comes back BUSY. Restored when we stop.
                awareLease = WifiAwareController.acquireHotspotLease()

                // Start hotspot
                val manager = HotspotManager(context)
                hotspotManager = manager

                manager.startHotspot(object : HotspotManager.HotspotCallback {
                    override fun onHotspotStarted() {
                        viewModelScope.launch {
                            Log.d(TAG, "Hotspot started successfully")

                            // Get connection info
                            val info = manager.getConnectionInfo()
                            if (info == null) {
                                failWith(HotspotError.CONNECTION_INFO_UNAVAILABLE)
                                return@launch
                            }

                            // Start web server
                            try {
                                val server = ApkWebServer(context, apkFile)
                                server.startServer()
                                webServer = server

                                Log.d(TAG, "Web server started on port ${ApkWebServer.DEFAULT_PORT}")

                                // Update state with connection info
                                _state.value = HotspotState.Active(
                                    ssid = info.ssid,
                                    password = info.password,
                                    ipAddress = info.ipAddress,
                                    port = ApkWebServer.DEFAULT_PORT,
                                    connectedPeers = info.connectedPeers
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start web server", e)
                                failWith(HotspotError.WEB_SERVER_START_FAILED)
                            }
                        }
                    }

                    override fun onConnectionInfoUpdated(info: HotspotManager.ConnectionInfo?) {
                        viewModelScope.launch {
                            // Update peer count if we're active
                            val currentState = _state.value
                            if (currentState is HotspotState.Active && info != null) {
                                _state.value = currentState.copy(connectedPeers = info.connectedPeers)
                            }
                        }
                    }

                    override fun onError(error: HotspotError) {
                        viewModelScope.launch { failWith(error) }
                    }
                })

            } catch (e: Exception) {
                Log.e(TAG, "Error starting hotspot", e)
                failWith(HotspotError.UNKNOWN)
            }
        }
    }

    /**
     * Stop the hotspot and web server.
     */
    fun stopHotspot() {
        Log.d(TAG, "Stopping hotspot")
        teardown()
        _state.value = HotspotState.Intro
    }

    /**
     * Every failure after the hotspot has been requested must land here.
     *
     * Skipping any part of this leaves something running that shouldn't be: the web
     * server keeps serving the APK on whatever network the device joins next, and the
     * Wi-Fi Aware hold blocks the mesh until the user happens to retry or close the
     * screen.
     */
    private fun failWith(error: HotspotError) {
        val message = context.getString(error.stringResource)
        Log.e(TAG, "Hotspot failed: $message")
        teardown()
        _state.value = HotspotState.Error(message)
    }

    /** Releases every resource startHotspot may have acquired. Safe to call twice. */
    private fun teardown() {
        webServer?.stopServer()
        webServer = null

        val manager = hotspotManager
        hotspotManager = null

        // Nothing of ours to hand back. An earlier teardown may already have passed its
        // once-releasable lease to the manager's completion callback.
        val lease = awareLease ?: return
        awareLease = null

        if (manager == null) {
            lease.close()
            return
        }

        // Released on completion, not on return. A createGroup submitted before the stop
        // can still land afterwards, and letting Aware back onto the radio before that
        // group has been observed absent recreates the NAN/P2P contention the hold exists
        // to prevent. Duplicate teardown paths cannot close another session's lease.
        manager.stopHotspot { lease.close() }
    }

    /**
     * Reset to intro state (for retry after error).
     */
    fun resetToIntro() {
        stopHotspot()
        _state.value = HotspotState.Intro
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared, stopping hotspot")
        stopHotspot()
    }

    /**
     * Hotspot state sealed class.
     */
    sealed class HotspotState {
        object Intro : HotspotState()
        object Starting : HotspotState()
        data class Active(
            val ssid: String,
            val password: String,
            val ipAddress: String,
            val port: Int,
            val connectedPeers: Int
        ) : HotspotState()
        data class Error(val message: String) : HotspotState()
    }

    private val HotspotError.stringResource: Int
        get() = when (this) {
            HotspotError.P2P_UNSUPPORTED -> R.string.hotspot_error_p2p_unsupported
            HotspotError.LOCAL_NETWORK_PERMISSION_REQUIRED ->
                R.string.hotspot_error_local_network_permission
            HotspotError.NEARBY_WIFI_PERMISSION_REQUIRED ->
                R.string.hotspot_error_nearby_wifi_permission
            HotspotError.PREPARATION_FAILED -> R.string.hotspot_error_preparation
            HotspotError.PERMISSION_REVOKED -> R.string.hotspot_error_permission_revoked
            HotspotError.P2P_DISABLED -> R.string.hotspot_error_p2p_disabled
            HotspotError.FOREIGN_GROUP_ACTIVE -> R.string.hotspot_error_foreign_group
            HotspotError.P2P_BUSY -> R.string.hotspot_error_p2p_busy
            HotspotError.START_FAILED -> R.string.hotspot_error_start_failed
            HotspotError.PREFLIGHT_TIMEOUT -> R.string.hotspot_error_preflight_timeout
            HotspotError.STALE_GROUP_REMOVAL_FAILED ->
                R.string.hotspot_error_stale_group_removal
            HotspotError.GROUP_LOST -> R.string.hotspot_error_group_lost
            HotspotError.P2P_SERVICE_DISCONNECTED ->
                R.string.hotspot_error_service_disconnected
            HotspotError.CONNECTION_INFO_UNAVAILABLE ->
                R.string.hotspot_error_connection_info
            HotspotError.WEB_SERVER_START_FAILED -> R.string.hotspot_error_web_server
            HotspotError.UNKNOWN -> R.string.hotspot_error_unknown
        }
}
