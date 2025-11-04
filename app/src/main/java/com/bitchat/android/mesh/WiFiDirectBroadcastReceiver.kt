package com.bitchat.android.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

/**
 * Broadcast receiver for Wi-Fi Direct events
 */
class WiFiDirectBroadcastReceiver(
    private val wifiP2pManager: WifiP2pManager,
    private val wifiP2pChannel: WifiP2pManager.Channel,
    private val connectionManager: WiFiDirectConnectionManager
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "WiFiDirectBroadcastReceiver"
    }

    private var isRegistered = false

    /**
     * Register the broadcast receiver
     */
    fun register(context: Context) {
        if (isRegistered) return

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        context.registerReceiver(this, intentFilter)
        isRegistered = true
        Log.d(TAG, "Wi-Fi Direct broadcast receiver registered")
    }

    /**
     * Unregister the broadcast receiver
     */
    fun unregister(context: Context) {
        if (!isRegistered) return

        try {
            context.unregisterReceiver(this)
            isRegistered = false
            Log.d(TAG, "Wi-Fi Direct broadcast receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering Wi-Fi Direct broadcast receiver", e)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                connectionManager.onWifiP2pStateChanged(enabled)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                // Request current peers list
                wifiP2pManager.requestPeers(wifiP2pChannel) { peers: WifiP2pDeviceList ->
                    connectionManager.onPeersAvailable(peers)
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                // Request connection info
                wifiP2pManager.requestConnectionInfo(wifiP2pChannel) { info: WifiP2pInfo ->
                    connectionManager.onConnectionInfoAvailable(info)
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = intent.getParcelableExtra<android.net.wifi.p2p.WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                device?.let { connectionManager.onThisDeviceChanged(it) }
            }
        }
    }
}
