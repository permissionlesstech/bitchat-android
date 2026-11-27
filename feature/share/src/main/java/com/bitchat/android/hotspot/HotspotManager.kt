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

    // Set once our own createGroup command is accepted, and re-checked against every
    // group snapshot afterwards. stopHotspot() only calls removeGroup() when this is
    // true: removal is device-scoped, so issuing it when the group on the framework
    // is not ours could only tear down another app's session (Cast, Android Auto,
    // Quick Share).
    private var createdGroup = false

    // Framework-reported name of the group this session hosts, once known. Null while
    // the group is still forming, when a null snapshot carries no information.
    private var hostedGroupName: String? = null

    // Name of the foreign group the user explicitly agreed to disconnect, or null.
    // Consent is per-group: a group with a different name asks again.
    private var confirmedReplacementName: String? = null

    // stopHotspot() can be reached again while its group query/removal is still in
    // flight. Later callers wait for that same teardown instead of releasing the
    // Wi-Fi Aware lease early.
    private var teardownInProgress = false
    private val teardownCallbacks = mutableListOf<() -> Unit>()

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
     *
     * @param confirmedReplacementName name of the foreign Wi-Fi Direct group the
     *   user has confirmed may be disconnected, as previously reported through
     *   [HotspotCallback.onExistingGroupConflict]. When null (or when the group
     *   present no longer matches), a foreign group is reported instead of touched.
     */
    fun startHotspot(callback: HotspotCallback, confirmedReplacementName: String? = null) {
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
        this.confirmedReplacementName = confirmedReplacementName
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
     *
     * @param onTeardownComplete invoked once the framework has acknowledged the
     *   removal of our group (or immediately when this session created none). Lets
     *   the caller hold the Wi-Fi Aware radio back until the P2P group is gone.
     */
    fun stopHotspot(onTeardownComplete: (() -> Unit)? = null) {
        Log.d(TAG, "Stopping hotspot")

        onTeardownComplete?.let(teardownCallbacks::add)
        if (teardownInProgress) {
            Log.d(TAG, "Teardown already in progress; chaining completion")
            return
        }

        isStarting = false
        hasNotifiedStarted = false

        // Stop group info polling
        handler.removeCallbacksAndMessages(null)

        // Detach the channel first so any in-flight listener sees the hotspot as stopped,
        // then remove the group and close the channel once the framework has replied.
        val staleChannel = channel
        channel = null

        val hadOwnGroup = createdGroup
        val expectedGroupName = hostedGroupName ?: if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            savedSsid
        } else {
            null
        }
        createdGroup = false
        hostedGroupName = null

        var teardownAction: (() -> Unit)? = null
        if (staleChannel != null && hadOwnGroup) {
            teardownInProgress = true
            teardownAction = {
                removeOwnGroupIfStillPresent(staleChannel, expectedGroupName)
            }
        } else if (staleChannel != null) {
            // This session created nothing, so there is nothing of ours to remove.
            // removeGroup() here is exactly the bug this change fixes: device-scoped
            // removal would disconnect whatever group another app has running.
            closeChannel(staleChannel)
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

        if (teardownAction != null) {
            teardownAction.invoke()
        } else {
            finishTeardown()
        }
    }

    /**
     * Re-check the device-scoped group immediately before removing it. The last poll
     * is only a snapshot: our group may have disappeared and another app may have
     * claimed Wi-Fi Direct before stop was requested.
     */
    @SuppressLint("MissingPermission")
    private fun removeOwnGroupIfStillPresent(ch: Channel, expectedGroupName: String?) {
        val manager = wifiP2pManager ?: run {
            closeChannel(ch)
            finishTeardown()
            return
        }

        try {
            manager.requestGroupInfo(ch) { group ->
                val stillOurs = HotspotStartupPolicy.isExpectedHostedGroup(
                    existingGroupName = group?.networkName,
                    isGroupOwner = group?.isGroupOwner == true,
                    expectedGroupName = expectedGroupName
                )
                if (!stillOurs) {
                    Log.i(
                        TAG,
                        "Current group '${group?.networkName}' is not ours " +
                            "('$expectedGroupName'); leaving it alone"
                    )
                    closeChannel(ch)
                    finishTeardown()
                    return@requestGroupInfo
                }

                try {
                    manager.removeGroup(ch, object : ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG, "Group removed successfully")
                            clearOwnedGroupNameIfMatches(expectedGroupName)
                            closeChannel(ch)
                            finishTeardown()
                        }

                        override fun onFailure(reason: Int) {
                            Log.w(TAG, "Failed to remove group: $reason")
                            closeChannel(ch)
                            finishTeardown()
                        }
                    })
                } catch (e: SecurityException) {
                    Log.e(TAG, "Wi-Fi permission was revoked while removing the group", e)
                    closeChannel(ch)
                    finishTeardown()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Wi-Fi permission was revoked while confirming group ownership", e)
            closeChannel(ch)
            finishTeardown()
        }
    }

    private fun clearOwnedGroupNameIfMatches(removedGroupName: String?) {
        if (HotspotStartupPolicy.shouldClearOwnedGroupName(ownedGroupName, removedGroupName)) {
            ownedGroupName = null
        }
    }

    private fun finishTeardown() {
        teardownInProgress = false
        val callbacks = teardownCallbacks.toList()
        teardownCallbacks.clear()
        callbacks.forEach { it.invoke() }
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
                    ownedGroupName = ownedGroupName,
                    confirmedGroupName = confirmedReplacementName
                )

                when (action) {
                    is HotspotStartupPolicy.StartAction.Fail -> {
                        Log.w(TAG, "Not attempting group creation: ${action.message}")
                        failStartup(action.message)
                    }
                    HotspotStartupPolicy.StartAction.ConfirmReplaceExisting -> {
                        val name = existingGroup?.networkName
                        if (name == null) {
                            // Unreachable while the policy requires a name, but there
                            // is nothing safe to bind consent to without one.
                            failStartup(HotspotStartupPolicy.P2P_BUSY_MESSAGE)
                        } else {
                            Log.i(TAG, "Existing group '$name' is not ours; asking the user")
                            reportExistingGroupConflict(name)
                        }
                    }
                    HotspotStartupPolicy.StartAction.Create ->
                        createGroup(attempt, oldGroupCleared = true)
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
        try {
            wifiP2pManager?.removeGroup(ch, object : ActionListener {
                override fun onSuccess() {
                    if (channel !== ch) return
                    Log.d(TAG, "Stale group removed")
                    createGroup(attempt, oldGroupCleared = true)
                }
                override fun onFailure(reason: Int) {
                    if (channel !== ch) return
                    // Creation may still succeed, and a BUSY reply here backs off as usual.
                    Log.w(TAG, "Failed to remove stale group: $reason; attempting creation anyway")
                    createGroup(attempt, oldGroupCleared = false)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Wi-Fi permission was revoked while removing the existing group", e)
            failStartup("A required Wi-Fi or local network permission was revoked. Grant it and try again.")
        }
    }

    /**
     * Create Wi-Fi P2P group.
     *
     * @param oldGroupCleared false when a previous group may still exist (its removal
     *   just failed). The ownership marker keeps the OLD group's name in that case:
     *   overwriting it early would make a BUSY retry classify our own stale group as
     *   foreign and raise a spurious consent dialog. On success the group-info poll
     *   records the authoritative name anyway.
     */
    @SuppressLint("MissingPermission")
    private fun createGroup(attempt: Int, oldGroupCleared: Boolean) {
        val ch = channel ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Record before the call: if the process dies between creation and the
                // first group info, the next run still knows this orphan is ours.
                if (oldGroupCleared) {
                    ownedGroupName = savedSsid
                }

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
                // Ours by construction: this listener only observes our own createGroup.
                Log.w(TAG, "Removing group created after hotspot was stopped")
                try {
                    wifiP2pManager?.removeGroup(requestChannel, null)
                } catch (e: SecurityException) {
                    // The orphan stays; the next start recognises it via ownedGroupName.
                    Log.e(TAG, "Could not remove the late group; permission was revoked", e)
                }
                return
            }
            Log.d(TAG, "P2P group created successfully")
            createdGroup = true
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
     * A group belonging to another app is up. Stop cleanly — with [createdGroup]
     * false the stop path leaves that group untouched — and let the UI ask whether
     * starting the hotspot may disconnect it.
     */
    private fun reportExistingGroupConflict(groupName: String) {
        val cb = callback
        stopHotspot()
        cb?.onExistingGroupConflict(groupName)
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
                // A reply arriving after the hotspot stopped must not revive any
                // state the stop just cleared.
                if (channel !== ch) return@requestGroupInfo

                reconcileGroupOwnership(group)

                if (group == null) {
                    Log.w(TAG, "requestGroupInfo returned null group")
                    return@requestGroupInfo
                }

                if (!isOurHostedGroup(group)) {
                    // Someone else's group is on the radio. Reading anything from it
                    // — its name, its credentials, its client count — would report
                    // another app's session as our hotspot, and recording its name
                    // would let the next start remove it without asking.
                    Log.w(
                        TAG,
                        "Observed group '${group.networkName}' is not the one we created"
                    )
                    return@requestGroupInfo
                }

                currentGroup = group

                // Update saved credentials if using system-generated ones
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    savedSsid = group.networkName
                    savedPassword = group.passphrase
                }

                // Authoritative name straight from the framework, for the group we
                // just confirmed is ours.
                group.networkName?.let {
                    hostedGroupName = it
                    ownedGroupName = it
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
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Wi-Fi permission was revoked while reading group info", e)
            failStartup("A required Wi-Fi or local network permission was revoked. Grant it and try again.")
        }
    }

    /**
     * Is this snapshot the group this session created?
     *
     * `isGroupOwner` cannot answer that on its own: it reports that *this device*
     * hosts the group, which is equally true of an autonomous group another app
     * created here. Above Q we chose the network name, so it identifies our group
     * exactly. Below Q the framework names it, and the first snapshot after our own
     * createGroup succeeded is the only evidence available — after that the name is
     * fixed, and a group answering to a different one is not ours.
     */
    private fun isOurHostedGroup(group: WifiP2pGroup): Boolean {
        if (!group.isGroupOwner) return false
        val name = group.networkName ?: return false

        hostedGroupName?.let { return name == it }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            name == savedSsid
        } else {
            true
        }
    }

    /**
     * Keep [createdGroup] honest about what is actually on the framework.
     *
     * Our group can disappear without us — Wi-Fi toggled, another app issuing its own
     * device-scoped removeGroup(), a driver reset — and another app can then create
     * one in its place. Believing the group present is still ours would make stop
     * remove that replacement, the exact disruption consent exists to prevent.
     *
     * Reconciled only once the framework has named our group: before that a null
     * snapshot means the group is still forming, not that it is gone. Losing the flag
     * to a transient null is safe in a way that keeping it is not — the group we
     * created is then left behind, and the next start recognises it by name and
     * removes it silently.
     */
    private fun reconcileGroupOwnership(group: WifiP2pGroup?) {
        val expectedName = hostedGroupName ?: if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            savedSsid
        } else {
            null
        } ?: return

        // A null snapshot is normal before the configured group appears. A non-null
        // group with a different name is positive evidence that ours was replaced.
        if (hostedGroupName == null && group == null) return

        val stillOurs = group != null && isOurHostedGroup(group)
        if (createdGroup && !stillOurs) {
            Log.w(TAG, "Group '$expectedName' is no longer ours; leaving what is present alone")
        }
        createdGroup = stillOurs
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

        /**
         * A Wi-Fi Direct group belonging to another app is active and the caller has
         * not confirmed replacing it. Ask the user, then retry with this name as
         * `confirmedReplacementName` if they accept. Nothing was disturbed.
         */
        fun onExistingGroupConflict(groupName: String)

        fun onError(message: String)
    }
}
