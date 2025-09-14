package com.bitchat.android.mesh

import android.bluetooth.BluetoothGatt
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized per-device monitoring and blocklist management.
 * - Blocks devices by MAC and auto-unblocks after a TTL
 * - Drops connections that don't ANNOUNCE within 15s
 * - Drops connections after 60s of packet inactivity
 * - Blocks devices with >=5 error disconnects within 5 minutes
 */
class DeviceMonitoringManager(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DeviceMonitoringManager"
        private const val ANNOUNCE_TIMEOUT_MS = 15_000L
        private const val INACTIVITY_TIMEOUT_MS = 60_000L
        private const val BLOCK_DURATION_MS = 15 * 60_000L
        private const val ERROR_WINDOW_MS = 5 * 60_000L
        private const val ERROR_THRESHOLD = 5
    }

    // Debug manager for chat-visible logs (guarded)
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    // Blocked devices with unblock-at timestamp
    private val blocked = ConcurrentHashMap<String, Long>()

    // Per-device timers and state
    private val announceTimers = ConcurrentHashMap<String, Job>()
    private val inactivityTimers = ConcurrentHashMap<String, Job>()
    private val lastPacketAt = ConcurrentHashMap<String, Long>()

    // Disconnect error history per device
    private val errorHistory = ConcurrentHashMap<String, MutableList<Long>>()

    // Callback to actually disconnect/close a device connection
    private var disconnectCallback: ((String) -> Unit)? = null

    fun setDisconnectCallback(cb: (address: String) -> Unit) {
        disconnectCallback = cb
    }

    fun isBlocked(address: String): Boolean {
        val expiry = blocked[address] ?: return false
        if (System.currentTimeMillis() > expiry) {
            blocked.remove(address)
            return false
        }
        return true
    }

    fun block(address: String, reason: String) {
        val until = System.currentTimeMillis() + BLOCK_DURATION_MS
        blocked[address] = until
        Log.w(TAG, "Blocked $address for 15m: $reason")
        debugManager?.addDebugMessage(
            com.bitchat.android.ui.debug.DebugMessage.SystemMessage("⛔ Blocked $address for 15m — $reason")
        )
        // Best-effort disconnect now
        disconnectCallback?.invoke(address)
        // Schedule auto-unblock
        scope.launch {
            delay(BLOCK_DURATION_MS)
            blocked.remove(address)
            Log.d(TAG, "Auto-unblocked $address after TTL")
            debugManager?.addDebugMessage(
                com.bitchat.android.ui.debug.DebugMessage.SystemMessage("✅ Auto-unblocked $address after 15m")
            )
        }
    }

    fun onConnectionEstablished(address: String) {
        if (isBlocked(address)) {
            // Safety: drop immediately if somehow connected while blocked
            block(address, "Connected while blocked; dropping")
            return
        }
        // Start ANNOUNCE timer
        announceTimers.remove(address)?.cancel()
        announceTimers[address] = scope.launch {
            delay(ANNOUNCE_TIMEOUT_MS)
            // If timer fires and still no announce, block
            Log.w(TAG, "No ANNOUNCE within 15s for $address — dropping + blocking")
            block(address, "No ANNOUNCE within 15s")
        }
        debugManager?.addDebugMessage(
            com.bitchat.android.ui.debug.DebugMessage.SystemMessage("📡 Monitoring $address — waiting for first ANNOUNCE (15s)")
        )
        // Start inactivity timer immediately so 60s without any packet also triggers
        scheduleInactivityTimer(address, System.currentTimeMillis())
    }

    fun onAnnounceReceived(address: String) {
        announceTimers.remove(address)?.cancel()
        Log.d(TAG, "First ANNOUNCE received on $address; cancel announce timer")
        debugManager?.addDebugMessage(
            com.bitchat.android.ui.debug.DebugMessage.SystemMessage("🆗 First ANNOUNCE received on $address — continuing")
        )
    }

    fun onAnyPacketReceived(address: String) {
        val now = System.currentTimeMillis()
        lastPacketAt[address] = now
        scheduleInactivityTimer(address, now)
        // Avoid spamming chat per-packet; rely on inactivity timer logs
    }

    private fun scheduleInactivityTimer(address: String, since: Long) {
        inactivityTimers.remove(address)?.cancel()
        inactivityTimers[address] = scope.launch {
            val now = System.currentTimeMillis()
            val elapsed = now - since
            val wait = if (elapsed >= INACTIVITY_TIMEOUT_MS) 0L else (INACTIVITY_TIMEOUT_MS - elapsed)
            delay(wait)
            val last = lastPacketAt[address] ?: since
            if (System.currentTimeMillis() - last >= INACTIVITY_TIMEOUT_MS) {
                Log.w(TAG, "No packets for >60s from $address — dropping + blocking")
                block(address, "Inactivity >60s")
            }
        }
    }

    fun onDeviceDisconnected(address: String, status: Int) {
        // Cancel timers on disconnect
        announceTimers.remove(address)?.cancel()
        inactivityTimers.remove(address)?.cancel()

        if (status == BluetoothGatt.GATT_SUCCESS) return
        val now = System.currentTimeMillis()
        val list = errorHistory.getOrPut(address) { mutableListOf() }
        // Drop entries older than window
        val cutoff = now - ERROR_WINDOW_MS
        list.removeAll { it < cutoff }
        list.add(now)
        if (list.size >= ERROR_THRESHOLD) {
            Log.w(TAG, "$address reached $ERROR_THRESHOLD error disconnects in 5m — blocking")
            block(address, ">=5 error disconnects in 5m")
            list.clear()
        }
    }

    fun clearForAddress(address: String) {
        announceTimers.remove(address)?.cancel()
        inactivityTimers.remove(address)?.cancel()
        lastPacketAt.remove(address)
    }

    fun clearAll() {
        announceTimers.values.forEach { it.cancel() }
        inactivityTimers.values.forEach { it.cancel() }
        announceTimers.clear()
        inactivityTimers.clear()
        lastPacketAt.clear()
        blocked.clear()
        errorHistory.clear()
        Log.w(TAG, "Cleared all device monitoring state + blocklist")
        debugManager?.addDebugMessage(
            com.bitchat.android.ui.debug.DebugMessage.SystemMessage("🗑️ Cleared blocklist and device monitoring state")
        )
    }
}
