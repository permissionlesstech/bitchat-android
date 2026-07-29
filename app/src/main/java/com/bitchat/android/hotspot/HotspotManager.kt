package com.bitchat.android.hotspot

import android.Manifest
import android.annotation.SuppressLint
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

        // Group info polling interval
        private const val GROUP_INFO_POLL_INTERVAL_MILLIS = 1000L

        // Give up if the group never forms within this window after creation succeeded
        private const val GROUP_FORMATION_TIMEOUT_MILLIS = 15_000L

        // SSID and password configuration
        private const val SSID_SUFFIX_LENGTH = 8
        private const val PASSWORD_LENGTH = 16

        // Records the group we created so a later run can tell our own orphan apart
        // from a group belonging to Cast, Android Auto or Quick Share.
        private const val PREFS_NAME = "hotspot"
        private const val KEY_OWNED_GROUP = "owned_group_name"

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

    // Last Wi-Fi P2P state seen on the broadcast, or null before the first one arrives
    private var lastP2pState: Int? = null

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** Network name of the last group this app created, surviving process death. */
    private var ownedGroupName: String?
        get() = prefs.getString(KEY_OWNED_GROUP, null)
        set(value) = prefs.edit().putString(KEY_OWNED_GROUP, value).apply()

    // Broadcast receiver for Wi-Fi P2P events
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    lastP2pState = state
                    Log.d(TAG, "Wi-Fi P2P state changed: $state")

                    // Wi-Fi Direct going away is terminal for this session: without it
                    // the group cannot form, and any group already up is now dead.
                    if (state == WIFI_P2P_STATE_DISABLED && (isStarting || hasNotifiedStarted)) {
                        Log.w(TAG, "Wi-Fi P2P was disabled; aborting hotspot")
                        failStartup(HotspotStartupPolicy.P2P_DISABLED_MESSAGE)
                    }
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

        val missingPermissions = HotspotPermissions.missingFrom(context)
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "Cannot start hotspot; missing required permissions: $missingPermissions")
            val message = if (Manifest.permission.ACCESS_LOCAL_NETWORK in missingPermissions) {
                "Local network permission is required to share the app over the hotspot"
            } else {
                "Nearby Wi-Fi permission is required to start the hotspot"
            }
            callback.onError(message)
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

        // Start P2P framework (retries reuse this one channel)
        startWifiP2pFramework()
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

        // Detach the channel first so any in-flight listener sees the hotspot as stopped,
        // then remove the group and close the channel once the framework has replied.
        val staleChannel = channel
        channel = null

        if (staleChannel != null) {
            wifiP2pManager?.removeGroup(staleChannel, object : ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group removed successfully")
                    // Nothing of ours is left for a later run to clean up.
                    ownedGroupName = null
                    closeChannel(staleChannel)
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Failed to remove group: $reason")
                    closeChannel(staleChannel)
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
        callback = null
    }

    /**
     * Release the channel's binder registration with WifiP2pService. Without this the
     * registration survives until the process dies, and every start/stop cycle adds
     * another stale client to the framework's list.
     */
    private fun closeChannel(channelToClose: Channel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return

        try {
            channelToClose.close()
            Log.d(TAG, "P2P channel closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing P2P channel", e)
        }
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
     * Initialise the P2P framework once. Every retry reuses this channel — calling
     * initialize() per attempt registers a fresh binder with WifiP2pService that is
     * never reclaimed until the process dies.
     */
    private fun startWifiP2pFramework() {
        Log.d(TAG, "Initialising P2P channel")

        val newChannel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)

        if (newChannel == null) {
            // The service is unobtainable; retrying will not change that.
            Log.e(TAG, "Failed to initialize P2P channel")
            failStartup(HotspotStartupPolicy.P2P_UNSUPPORTED_MESSAGE)
            return
        }

        channel = newChannel
        createGroupWhenP2pAvailable()
    }

    /**
     * Ask the framework for the current P2P state before the first attempt.
     *
     * When P2P is disabled the state machine answers every createGroup with BUSY —
     * the same code a genuinely transient collision returns — so without this check
     * a permanent failure is indistinguishable from a retryable one.
     */
    @SuppressLint("MissingPermission")
    private fun createGroupWhenP2pAvailable() {
        val ch = channel ?: return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            clearStaleGroupThenCreate(ch, attempt = 1)
            return
        }

        try {
            wifiP2pManager?.requestP2pState(ch) { state ->
                if (channel !== ch) return@requestP2pState
                lastP2pState = state
                clearStaleGroupThenCreate(ch, attempt = 1)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Wi-Fi permission was revoked while reading P2P state", e)
            failStartup("A required Wi-Fi or local network permission was revoked. Grant it and try again.")
        }
    }

    /**
     * A P2P group survives the process that created it, so a previous session killed
     * while hosting leaves an orphan behind. The framework then rejects createGroup
     * with BUSY for as long as that group exists, which no retry can clear.
     */
    @SuppressLint("MissingPermission")
    private fun clearStaleGroupThenCreate(ch: Channel, attempt: Int) {
        try {
            wifiP2pManager?.requestGroupInfo(ch) { existingGroup ->
                if (channel !== ch) return@requestGroupInfo

                val action = HotspotStartupPolicy.startAction(
                    p2pState = lastP2pState,
                    existingGroupName = existingGroup?.networkName,
                    ownedGroupName = ownedGroupName
                )

                when (action) {
                    is HotspotStartupPolicy.StartAction.Fail -> {
                        Log.w(TAG, "Not attempting group creation: ${action.message}")
                        failStartup(action.message)
                    }
                    HotspotStartupPolicy.StartAction.Create -> createGroup(attempt)
                    HotspotStartupPolicy.StartAction.RemoveStaleGroupThenCreate -> {
                        Log.w(TAG, "Removing stale group '${existingGroup?.networkName}' before creating")
                        removeStaleGroup(ch, attempt)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Wi-Fi permission was revoked while reading group info", e)
            failStartup("A required Wi-Fi or local network permission was revoked. Grant it and try again.")
        }
    }

    private fun removeStaleGroup(ch: Channel, attempt: Int) {
        wifiP2pManager?.removeGroup(ch, object : ActionListener {
            override fun onSuccess() {
                if (channel !== ch) return
                Log.d(TAG, "Stale group removed")
                createGroup(attempt)
            }
            override fun onFailure(reason: Int) {
                if (channel !== ch) return
                // Creation may still succeed, and a BUSY reply here backs off as usual.
                Log.w(TAG, "Failed to remove stale group: $reason; attempting creation anyway")
                createGroup(attempt)
            }
        })
    }

    /**
     * Create Wi-Fi P2P group.
     */
    @SuppressLint("MissingPermission")
    private fun createGroup(attempt: Int) {
        val ch = channel ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Record before the call: if the process dies between creation and the
                // first group info, the next run still knows this orphan is ours.
                ownedGroupName = savedSsid

                // Android 10+: Custom SSID and password
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(savedSsid!!)
                    .setPassphrase(savedPassword!!)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) // Force 2.4GHz for compatibility
                    .build()

                wifiP2pManager?.createGroup(ch, config, groupActionListener(attempt, ch))
            } else {
                // Android 9 and below: System-generated SSID/password
                wifiP2pManager?.createGroup(ch, groupActionListener(attempt, ch))
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Wi-Fi permission was revoked while creating the group", e)
            failStartup("A required Wi-Fi or local network permission was revoked. Grant it and try again.")
        }
    }

    private fun groupActionListener(attempt: Int, requestChannel: Channel) = object : ActionListener {
        override fun onSuccess() {
            if (channel !== requestChannel) {
                Log.w(TAG, "Removing group created after hotspot was stopped")
                wifiP2pManager?.removeGroup(requestChannel, null)
                return
            }
            Log.d(TAG, "P2P group created successfully")
            isStarting = false
            // Don't call onHotspotStarted() yet - wait for group info
            startGroupInfoPolling()
        }

        override fun onFailure(reason: Int) {
            if (channel != null) {
                handleGroupCreationFailure(reason, attempt)
            }
        }
    }

    /**
     * Handle group creation failure, backing off only for genuinely transient causes.
     */
    private fun handleGroupCreationFailure(reason: Int, attempt: Int) {
        val reasonStr = when (reason) {
            ERROR -> "ERROR"
            P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            BUSY -> "BUSY"
            else -> "UNKNOWN($reason)"
        }

        Log.w(
            TAG,
            "Failed to create group: $reasonStr " +
                "(attempt $attempt/${HotspotStartupPolicy.MAX_ATTEMPTS}, p2pState=$lastP2pState)"
        )

        when (val decision = HotspotStartupPolicy.decide(reason, attempt, lastP2pState)) {
            is HotspotStartupPolicy.Decision.Retry -> {
                Log.d(TAG, "Retrying group creation in ${decision.delayMillis}ms")
                handler.postDelayed({
                    // Re-check for a stale group each round: BUSY is also how the
                    // framework reports "a group already exists".
                    channel?.let { clearStaleGroupThenCreate(it, attempt + 1) }
                }, decision.delayMillis)
            }
            is HotspotStartupPolicy.Decision.Fail -> failStartup(decision.message)
        }
    }

    /**
     * Terminal startup failure: release all resources (locks, receiver, handler
     * callbacks) before notifying the callback, so a failed attempt doesn't leak
     * and block subsequent attempts.
     */
    private fun failStartup(message: String) {
        val cb = callback
        stopHotspot()
        cb?.onError(message)
    }

    /**
     * Start polling for group info to track connected clients.
     */
    private fun startGroupInfoPolling() {
        requestGroupInfo()

        // Keep polling even while the group info is still null — the first
        // requestGroupInfo() after createGroup() can legitimately return null
        // while the group is forming. Give up only after a timeout.
        var elapsedMillis = 0L
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (channel == null) return

                elapsedMillis += GROUP_INFO_POLL_INTERVAL_MILLIS
                if (currentGroup == null && !hasNotifiedStarted &&
                    elapsedMillis >= GROUP_FORMATION_TIMEOUT_MILLIS
                ) {
                    Log.e(TAG, "Group never formed within ${GROUP_FORMATION_TIMEOUT_MILLIS}ms")
                    failStartup("Hotspot failed to start. Please try again.")
                    return
                }

                requestGroupInfo()
                handler.postDelayed(this, GROUP_INFO_POLL_INTERVAL_MILLIS)
            }
        }, GROUP_INFO_POLL_INTERVAL_MILLIS)
    }

    /**
     * Request current group information.
     */
    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        val ch = channel ?: return

        try {
            wifiP2pManager?.requestGroupInfo(ch) { group ->
                if (group != null) {
                    currentGroup = group

                    // Update saved credentials if using system-generated ones
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        savedSsid = group.networkName
                        savedPassword = group.passphrase
                    }

                    // Authoritative name straight from the framework
                    group.networkName?.let { ownedGroupName = it }

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
        } catch (e: SecurityException) {
            Log.e(TAG, "Wi-Fi permission was revoked while reading group info", e)
            failStartup("A required Wi-Fi or local network permission was revoked. Grant it and try again.")
        }
    }

    /**
     * Acquire WakeLock and WifiLock to keep hotspot active.
     */
    private fun acquireLocks() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BitChat:HotspotWakeLock"
            )
            wakeLock?.acquire(30 * 60 * 1000L)

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
        return "${HotspotStartupPolicy.SSID_PREFIX}$suffix"
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
