package com.bitchat.android.onboarding

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Manages Wi-Fi Direct enable/disable state and user prompts
 * Checks Wi-Fi Direct status on every app startup
 */
class WiFiDirectStatusManager(
    private val context: Context,
    private val onWifiDirectEnabled: () -> Unit,
    private val onWifiDirectDisabled: (String) -> Unit
) {

    companion object {
        private const val TAG = "WiFiDirectStatusManager"
    }

    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var wifiDirectBroadcastReceiver: BroadcastReceiver? = null
    private var isRegistered = false

    enum class WiFiDirectStatus {
        ENABLED,
        DISABLED,
        NOT_SUPPORTED
    }

    init {
        setupWifiP2pManager()
        setupBroadcastReceiver()
    }

    /**
     * Setup Wi-Fi P2P manager
     */
    private fun setupWifiP2pManager() {
        try {
            wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (wifiP2pManager != null) {
                wifiP2pChannel = wifiP2pManager?.initialize(context, context.mainLooper, object : WifiP2pManager.ChannelListener {
                    override fun onChannelDisconnected() {
                        Log.w(TAG, "Wi-Fi P2P channel disconnected")
                        wifiP2pChannel = null
                    }
                })
            }
            Log.d(TAG, "Wi-Fi P2P manager initialized: ${wifiP2pManager != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Wi-Fi P2P manager", e)
            wifiP2pManager = null
            wifiP2pChannel = null
        }
    }

    /**
     * Setup broadcast receiver for Wi-Fi P2P state changes
     */
    private fun setupBroadcastReceiver() {
        wifiDirectBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Log.d(TAG, "Wi-Fi P2P state changed: $isEnabled")

                        if (isEnabled) {
                            onWifiDirectEnabled()
                        } else {
                            onWifiDirectDisabled("Wi-Fi Direct is disabled")
                        }
                    }
                }
            }
        }
    }

    /**
     * Register the broadcast receiver
     */
    fun register() {
        if (isRegistered || wifiDirectBroadcastReceiver == null) return

        try {
            val intentFilter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                context,
                wifiDirectBroadcastReceiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isRegistered = true
            Log.d(TAG, "Wi-Fi Direct broadcast receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Wi-Fi Direct broadcast receiver", e)
        }
    }

    /**
     * Unregister the broadcast receiver
     */
    fun unregister() {
        if (!isRegistered || wifiDirectBroadcastReceiver == null) return

        try {
            context.unregisterReceiver(wifiDirectBroadcastReceiver)
            isRegistered = false
            Log.d(TAG, "Wi-Fi Direct broadcast receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister Wi-Fi Direct broadcast receiver", e)
        }
    }

    /**
     * Check current Wi-Fi Direct status
     */
    fun getCurrentStatus(): WiFiDirectStatus {
        if (wifiP2pManager == null) {
            return WiFiDirectStatus.NOT_SUPPORTED
        }

        // For Wi-Fi Direct, we can't directly check if it's enabled like Bluetooth
        // We rely on the broadcast receiver to tell us the current state
        // For now, we'll assume it's available if the manager exists
        return WiFiDirectStatus.ENABLED
    }

    /**
     * Check if Wi-Fi Direct is supported on this device
     */
    fun isSupported(): Boolean {
        return wifiP2pManager != null
    }

    /**
     * Get Wi-Fi P2P manager instance
     */
    fun getWifiP2pManager(): WifiP2pManager? = wifiP2pManager

    /**
     * Get Wi-Fi P2P channel
     */
    fun getWifiP2pChannel(): WifiP2pManager.Channel? = wifiP2pChannel

    /**
     * Cleanup resources
     */
    fun cleanup() {
        unregister()
        wifiP2pChannel = null
    }
}
