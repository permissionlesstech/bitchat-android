package com.bitchat.android.hotspot

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
                // Start hotspot
                val manager = HotspotManager(context)
                hotspotManager = manager

                manager.startHotspot(object : HotspotManager.HotspotCallback {
                    override fun onHotspotStarted() {
                        Log.d(TAG, "Hotspot started successfully")

                        // Get connection info
                        val info = manager.getConnectionInfo()
                        if (info == null) {
                            _state.value = HotspotState.Error("Failed to get hotspot connection info")
                            return
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
                            manager.stopHotspot()
                            _state.value = HotspotState.Error("Failed to start web server: ${e.message}")
                        }
                    }

                    override fun onConnectionInfoUpdated(info: HotspotManager.ConnectionInfo?) {
                        // Update peer count if we're active
                        val currentState = _state.value
                        if (currentState is HotspotState.Active && info != null) {
                            _state.value = currentState.copy(connectedPeers = info.connectedPeers)
                        }
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "Hotspot error: $message")
                        _state.value = HotspotState.Error(message)
                    }
                })

            } catch (e: Exception) {
                Log.e(TAG, "Error starting hotspot", e)
                _state.value = HotspotState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Stop the hotspot and web server.
     */
    fun stopHotspot() {
        Log.d(TAG, "Stopping hotspot")

        webServer?.stopServer()
        webServer = null

        hotspotManager?.stopHotspot()
        hotspotManager = null

        _state.value = HotspotState.Intro
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
}
