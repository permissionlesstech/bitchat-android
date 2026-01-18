package com.bitchat.android.hotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Manages Wi-Fi P2P (Wi-Fi Direct) hotspot for offline APK sharing.
 * Based on Briar's implementation.
 */
class HotspotManager(private val context: Context) {

    companion object {
        private const val TAG = "HotspotMgr"

        // Retry configuration
        private const val MAX_FRAMEWORK_ATTEMPTS = 5
        private const val RETRY_DELAY_MILLIS = 1000L

        // Group info polling interval
        private const val GROUP_INFO_POLL_INTERVAL_MILLIS = 1000L

        // SSID and password configuration
        private const val SSID_PREFIX = "DIRECT-BC-" // BC for BitChat
        private const val SSID_SUFFIX_LENGTH = 8
        private const val PASSWORD_LENGTH = 16

        // Characters to use for random generation (excluding confusing ones)
        private const val RANDOM_CHARS = "ABCDEFGHJKLMNPQRTUVWXY34679" // No 0,O,5,S,1,l,I
    }

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: Channel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    private var currentGroup: WifiP2pGroup? = null
    private var callback: HotspotCallback? = null
    private var isStarting = false
    private var hasNotifiedStarted = false // Track if we've notified the callback
    private var isReceiverRegistered = false // Track receiver registration to prevent leaks

    // Saved credentials for reconnection
    private var savedSsid: String? = null
    private var savedPassword: String? = null

    // Broadcast receiver for Wi-Fi P2P events
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Log.d(TAG, "Wi-Fi P2P state changed: $state")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.d(TAG, "Wi-Fi P2P connection changed")
                    requestGroupInfo()
                }
            }
        }
    }

    /**
     * Start the Wi-Fi P2P hotspot.
     */
    fun startHotspot(callback: HotspotCallback) {
        if (isStarting) {
            Log.w(TAG, "Hotspot already starting")
            return
        }

        if (wifiP2pManager == null) {
            Log.e(TAG, "Wi-Fi P2P not available on this device")
            callback.onError("Wi-Fi Direct not supported on this device")
            return
        }

        this.callback = callback
        isStarting = true

        Log.d(TAG, "Starting Wi-Fi P2P hotspot")

        // Register broadcast receiver (only if not already registered)
        if (!isReceiverRegistered) {
            val intentFilter = IntentFilter().apply {
                addAction(WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            context.registerReceiver(broadcastReceiver, intentFilter)
            isReceiverRegistered = true
            Log.d(TAG, "Broadcast receiver registered")
        }

        // Acquire locks
        acquireLocks()

        // Load or generate credentials
        if (savedSsid == null || savedPassword == null) {
            savedSsid = generateSsid()
            savedPassword = generatePassword()
            Log.d(TAG, "Generated new credentials: SSID=$savedSsid")
        } else {
            Log.d(TAG, "Using saved credentials: SSID=$savedSsid")
        }

        // Start P2P framework with retries
        startWifiP2pFramework(1)
    }

    /**
     * Stop the hotspot.
     */
    fun stopHotspot() {
        Log.d(TAG, "Stopping hotspot")

        isStarting = false
        hasNotifiedStarted = false

        // Stop group info polling
        handler.removeCallbacksAndMessages(null)

        // Remove group
        channel?.let { ch ->
            wifiP2pManager?.removeGroup(ch, object : ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group removed successfully")
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Failed to remove group: $reason")
                }
            })
        }

        // Release locks
        releaseLocks()

        // Unregister receiver (only if registered)
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(broadcastReceiver)
                isReceiverRegistered = false
                Log.d(TAG, "Broadcast receiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver was not registered", e)
                isReceiverRegistered = false
            }
        }

        currentGroup = null
        channel = null
        callback = null
    }

    /**
     * Get current connection information.
     */
    fun getConnectionInfo(): ConnectionInfo? {
        val group = currentGroup ?: return null
        val ipAddress = getAccessPointAddress()

        return ConnectionInfo(
            ssid = group.networkName ?: savedSsid ?: "",
            password = group.passphrase ?: savedPassword ?: "",
            ipAddress = ipAddress ?: "192.168.49.1", // Fallback to standard P2P IP
            connectedPeers = group.clientList?.size ?: 0
        )
    }

    /**
     * Start Wi-Fi P2P framework with retry logic.
     */
    private fun startWifiP2pFramework(attempt: Int) {
        if (attempt > MAX_FRAMEWORK_ATTEMPTS) {
            Log.e(TAG, "Failed to start P2P framework after $MAX_FRAMEWORK_ATTEMPTS attempts")
            isStarting = false
            callback?.onError("Failed to start hotspot. Please try again.")
            return
        }

        Log.d(TAG, "Starting P2P framework (attempt $attempt/$MAX_FRAMEWORK_ATTEMPTS)")

        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)

        if (channel == null) {
            Log.e(TAG, "Failed to initialize P2P channel")
            handler.postDelayed({
                startWifiP2pFramework(attempt + 1)
            }, RETRY_DELAY_MILLIS)
            return
        }

        createGroup(attempt)
    }

    /**
     * Create Wi-Fi P2P group.
     */
    private fun createGroup(attempt: Int) {
        val ch = channel ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: Custom SSID and password
            val config = WifiP2pConfig.Builder()
                .setNetworkName(savedSsid!!)
                .setPassphrase(savedPassword!!)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) // Force 2.4GHz for compatibility
                .build()

            wifiP2pManager?.createGroup(ch, config, object : ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "P2P group created successfully")
                    isStarting = false
                    // Don't call onHotspotStarted() yet - wait for group info
                    startGroupInfoPolling()
                }

                override fun onFailure(reason: Int) {
                    handleGroupCreationFailure(reason, attempt)
                }
            })
        } else {
            // Android 9 and below: System-generated SSID/password
            wifiP2pManager?.createGroup(ch, object : ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "P2P group created successfully")
                    isStarting = false
                    // Don't call onHotspotStarted() yet - wait for group info
                    startGroupInfoPolling()
                }

                override fun onFailure(reason: Int) {
                    handleGroupCreationFailure(reason, attempt)
                }
            })
        }
    }

    /**
     * Handle group creation failure with retry logic.
     */
    private fun handleGroupCreationFailure(reason: Int, attempt: Int) {
        val reasonStr = when (reason) {
            ERROR -> "ERROR"
            P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            BUSY -> "BUSY"
            else -> "UNKNOWN($reason)"
        }

        Log.w(TAG, "Failed to create group: $reasonStr")

        if (reason == BUSY && attempt < MAX_FRAMEWORK_ATTEMPTS) {
            // Framework is busy, retry
            Log.d(TAG, "P2P framework busy, retrying...")
            handler.postDelayed({
                startWifiP2pFramework(attempt + 1)
            }, RETRY_DELAY_MILLIS)
        } else {
            isStarting = false
            callback?.onError("Failed to create hotspot: $reasonStr")
        }
    }

    /**
     * Start polling for group info to track connected clients.
     */
    private fun startGroupInfoPolling() {
        requestGroupInfo()

        handler.postDelayed(object : Runnable {
            override fun run() {
                if (channel != null && currentGroup != null) {
                    requestGroupInfo()
                    handler.postDelayed(this, GROUP_INFO_POLL_INTERVAL_MILLIS)
                }
            }
        }, GROUP_INFO_POLL_INTERVAL_MILLIS)
    }

    /**
     * Request current group information.
     */
    private fun requestGroupInfo() {
        val ch = channel ?: return

        wifiP2pManager?.requestGroupInfo(ch) { group ->
            if (group != null) {
                currentGroup = group

                // Update saved credentials if using system-generated ones
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    savedSsid = group.networkName
                    savedPassword = group.passphrase
                }

                // Notify callback on FIRST successful group info retrieval
                if (!hasNotifiedStarted) {
                    hasNotifiedStarted = true
                    Log.d(TAG, "Group info received, notifying callback")
                    callback?.onHotspotStarted()
                } else {
                    // Subsequent updates
                    callback?.onConnectionInfoUpdated(getConnectionInfo())
                }
            } else {
                Log.w(TAG, "requestGroupInfo returned null group")
            }
        }
    }

    /**
     * Acquire WakeLock and WifiLock to keep hotspot active.
     */
    private fun acquireLocks() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK,
                "BitChat:HotspotWakeLock"
            )
            wakeLock?.acquire()

            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                android.net.wifi.WifiManager.WIFI_MODE_FULL
            }
            wifiLock = wifiManager.createWifiLock(lockType, "BitChat:HotspotWifiLock")
            wifiLock?.acquire()

            Log.d(TAG, "Acquired WakeLock and WifiLock")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring locks", e)
        }
    }

    /**
     * Release WakeLock and WifiLock.
     */
    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null

            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wifiLock = null

            Log.d(TAG, "Released WakeLock and WifiLock")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks", e)
        }
    }

    /**
     * Get the IP address of the P2P access point.
     * Looks for network interface starting with "p2p".
     */
    private fun getAccessPointAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name.startsWith("p2p")) {
                    val addresses = iface.interfaceAddresses
                    for (addr in addresses) {
                        val address = addr.address
                        // IPv4 only (4 bytes)
                        if (address.address.size == 4) {
                            return address.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting access point address", e)
        }
        return null
    }

    /**
     * Generate random SSID.
     * Format: DIRECT-BC-XXXXXXXX
     */
    private fun generateSsid(): String {
        val suffix = (1..SSID_SUFFIX_LENGTH)
            .map { RANDOM_CHARS[random.nextInt(RANDOM_CHARS.length)] }
            .joinToString("")
        return "$SSID_PREFIX$suffix"
    }

    /**
     * Generate random password.
     * 16 characters, excluding confusing characters.
     */
    private fun generatePassword(): String {
        return (1..PASSWORD_LENGTH)
            .map { RANDOM_CHARS[random.nextInt(RANDOM_CHARS.length)] }
            .joinToString("")
    }

    /**
     * Connection information for the hotspot.
     */
    data class ConnectionInfo(
        val ssid: String,
        val password: String,
        val ipAddress: String,
        val connectedPeers: Int
    )

    /**
     * Callback interface for hotspot events.
     */
    interface HotspotCallback {
        fun onHotspotStarted()
        fun onConnectionInfoUpdated(info: ConnectionInfo?)
        fun onError(message: String)
    }
}
