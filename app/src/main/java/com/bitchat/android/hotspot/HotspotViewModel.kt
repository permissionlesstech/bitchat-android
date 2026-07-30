package com.bitchat.android.hotspot

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

        // Upper bound on waiting for the framework to acknowledge group removal
        // before the Wi-Fi Aware lease is released anyway.
        private const val TEARDOWN_FALLBACK_MILLIS = 10_000L
    }

    private val _state = MutableStateFlow<HotspotState>(HotspotState.Intro)
    val state: StateFlow<HotspotState> = _state.asStateFlow()

    private var hotspotManager: HotspotManager? = null
    private var webServer: ApkWebServer? = null

    /** APK waiting on the user's answer to the disconnect confirmation. */
    private var pendingApk: File? = null

    /** Group the pending confirmation is about; consent binds to this name only. */
    private var pendingGroupName: String? = null

    /** Once-releasable radio claim owned by the current hotspot session. */
    private var awareLease: WifiAwareController.HotspotLease? = null
    private val context = application.applicationContext

    /**
     * Start the hotspot with the provided APK file.
     */
    fun startHotspot(apkFile: File) {
        startHotspot(apkFile, confirmedGroupName = null)
    }

    private fun startHotspot(apkFile: File, confirmedGroupName: String?) {
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
                                failWith("Failed to get hotspot connection info")
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
                                failWith("Failed to start web server: ${e.message}")
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

                    override fun onExistingGroupConflict(groupName: String) {
                        viewModelScope.launch {
                            // Nothing was disturbed; the manager already stopped
                            // itself. Release our resources and ask the user.
                            teardown()
                            pendingApk = apkFile
                            pendingGroupName = groupName
                            _state.value = HotspotState.ConfirmDisconnect
                        }
                    }

                    override fun onError(message: String) {
                        viewModelScope.launch { failWith(message) }
                    }
                }, confirmedGroupName)

            } catch (e: Exception) {
                Log.e(TAG, "Error starting hotspot", e)
                failWith(e.message ?: "Unknown error")
            }
        }
    }

    /** The user agreed that starting may disconnect the existing Wi-Fi Direct group. */
    fun confirmDisconnectAndStart() {
        if (_state.value !is HotspotState.ConfirmDisconnect) return
        val apk = pendingApk ?: return
        val groupName = pendingGroupName
        pendingApk = null
        pendingGroupName = null
        startHotspot(apk, confirmedGroupName = groupName)
    }

    fun cancelDisconnect() {
        // A tap landing late (e.g. through an exit animation) must not tear down
        // the session a just-processed confirmation is starting.
        if (_state.value !is HotspotState.ConfirmDisconnect) return
        pendingApk = null
        pendingGroupName = null
        // The conflict path already tore down; this only covers a stray state.
        teardown()
        _state.value = HotspotState.Intro
    }

    /**
     * Stop the hotspot and web server.
     */
    fun stopHotspot() {
        Log.d(TAG, "Stopping hotspot")
        pendingApk = null
        pendingGroupName = null
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
    private fun failWith(message: String) {
        Log.e(TAG, "Hotspot failed: $message")
        pendingApk = null
        pendingGroupName = null
        teardown()
        _state.value = HotspotState.Error(message)
    }

    /** Releases every resource startHotspot may have acquired. Safe to call twice. */
    private fun teardown() {
        webServer?.stopServer()
        webServer = null

        val manager = hotspotManager
        hotspotManager = null

        // Nothing of ours to hand back. An earlier teardown may already have passed
        // its once-releasable lease to the manager's completion callback.
        val lease = awareLease ?: return
        awareLease = null

        if (manager == null) {
            lease.close()
            return
        }

        // Close the lease only when the manager has finished removing our group.
        // Restoring Wi-Fi Aware earlier recreates the NAN/P2P radio contention the
        // lease exists to prevent. The lease is idempotent, so a duplicate or late
        // completion cannot release a newer session's claim.
        manager.stopHotspot { lease.close() }

        // A framework that never acknowledges the removal must not pin Wi-Fi Aware
        // down for the life of the process. close() is idempotent and this lease
        // belongs to this session alone, so whichever path runs second is a no-op.
        // A plain handler rather than viewModelScope: onCleared() cancels the scope
        // right when this teardown may be running.
        Handler(Looper.getMainLooper()).postDelayed(
            { lease.close() },
            TEARDOWN_FALLBACK_MILLIS
        )
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

        /** A foreign Wi-Fi Direct group is up; waiting for the user's go-ahead. */
        object ConfirmDisconnect : HotspotState()

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
