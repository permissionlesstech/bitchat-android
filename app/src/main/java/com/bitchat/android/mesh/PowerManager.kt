package com.bitchat.android.mesh

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide source of truth for background and battery-aware scheduling.
 *
 * The manager deliberately exposes policy only. Consumers retain ownership of their hardware and
 * network jobs, so changing profile never implies disconnecting a transport or suspending Tor.
 */
class PowerManager private constructor(context: Context) : LifecycleEventObserver {

    enum class PowerMode {
        PERFORMANCE,
        BALANCED,
        POWER_SAVER,
        ULTRA_LOW_POWER
    }

    enum class BatteryBand { NORMAL, LOW, CRITICAL }

    data class BleSchedule(
        val scanOnMs: Long,
        val scanOffMs: Long,
        val continuousScan: Boolean,
        val maxConnections: Int = 8
    )

    data class WifiAwareSchedule(
        val tcpKeepAliveMs: Long,
        val discoveryKeepAliveMs: Long,
        val connectionMaintenanceMs: Long,
        val discoveryIdleRefreshMs: Long,
        val discoverySessionRefreshMinMs: Long,
        val discoveryStaleMs: Long
    )

    data class NostrSchedule(
        val presenceHeartbeatMinMs: Long,
        val presenceHeartbeatMaxMs: Long,
        val subscriptionValidationMs: Long
    )

    data class RuntimePerformanceProfile(
        val mode: PowerMode,
        val batteryBand: BatteryBand,
        val isBackground: Boolean,
        val isCharging: Boolean,
        val hasDirectPeers: Boolean,
        val ble: BleSchedule,
        val meshAnnouncementIntervalMs: Long,
        val wifiAware: WifiAwareSchedule,
        val nostr: NostrSchedule
    )

    companion object {
        private const val TAG = "PowerManager"

        @Volatile
        private var INSTANCE: PowerManager? = null

        fun getInstance(context: Context): PowerManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PowerManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var batteryLevel = 100
    @Volatile private var isCharging = false
    @Volatile private var isAppInBackground = true
    @Volatile private var hasDirectPeers = false
    @Volatile private var shutdown = false

    private val _profile = MutableStateFlow(
        PowerProfileResolver.resolve(
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            isBackground = isAppInBackground,
            hasDirectPeers = hasDirectPeers
        )
    )
    val profile: StateFlow<RuntimePerformanceProfile> = _profile.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) batteryLevel = (level * 100) / scale

                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    refreshProfile()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    isCharging = true
                    refreshProfile()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isCharging = false
                    refreshProfile()
                }
            }
        }
    }

    init {
        registerBatteryReceiver()
        Handler(Looper.getMainLooper()).post {
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(this)
                isAppInBackground = !ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
                refreshProfile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register process lifecycle observer: ${e.message}")
            }
        }
        scope.launch {
            AppStateStore.directPeers
                .collect { peers ->
                    hasDirectPeers = peers.isNotEmpty()
                    refreshProfile()
                }
        }
    }

    /** Kept for existing callers; initialization is eager and idempotent. */
    fun start() = Unit

    /**
     * Process-level teardown for explicit full application shutdown only.
     * Transport stop/restart paths must not call this.
     */
    fun shutdown() {
        if (shutdown) return
        shutdown = true
        scope.cancel()
        try { appContext.unregisterReceiver(batteryReceiver) } catch (_: Exception) { }
        Handler(Looper.getMainLooper()).post {
            try { ProcessLifecycleOwner.get().lifecycle.removeObserver(this) } catch (_: Exception) { }
        }
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> {
                isAppInBackground = false
                refreshProfile()
            }
            Lifecycle.Event.ON_STOP -> {
                isAppInBackground = true
                refreshProfile()
            }
            else -> Unit
        }
    }

    fun getScanSettings(): ScanSettings {
        val current = profile.value
        val builder = ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)

        when (current.mode) {
            PowerMode.PERFORMANCE -> builder
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            PowerMode.BALANCED -> builder
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            PowerMode.POWER_SAVER,
            PowerMode.ULTRA_LOW_POWER -> builder
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        }
        return builder.setReportDelay(0).build()
    }

    fun getAdvertiseSettings(): AdvertiseSettings = when (profile.value.mode) {
        PowerMode.PERFORMANCE -> AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).setTimeout(0).build()
        PowerMode.BALANCED -> AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true).setTimeout(0).build()
        PowerMode.POWER_SAVER -> AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true).setTimeout(0).build()
        PowerMode.ULTRA_LOW_POWER -> AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW)
            .setConnectable(true).setTimeout(0).build()
    }

    fun getMaxConnections(): Int = profile.value.ble.maxConnections

    fun getRSSIThreshold(): Int = when (profile.value.mode) {
        PowerMode.PERFORMANCE -> -95
        PowerMode.BALANCED -> -85
        PowerMode.POWER_SAVER -> -75
        PowerMode.ULTRA_LOW_POWER -> -65
    }

    fun shouldUseDutyCycle(): Boolean = !profile.value.ble.continuousScan

    fun getPowerInfo(): String = profile.value.let { current ->
        buildString {
            appendLine("=== Power Manager Status ===")
            appendLine("Current Mode: ${current.mode}")
            appendLine("Battery Band: ${current.batteryBand} ($batteryLevel%)")
            appendLine("Is Charging: ${current.isCharging}")
            appendLine("App In Background: ${current.isBackground}")
            appendLine("Has Direct Peers: ${current.hasDirectPeers}")
            appendLine("BLE Scan: ${current.ble.scanOnMs}ms ON / ${current.ble.scanOffMs}ms OFF")
            appendLine("Max Connections: ${current.ble.maxConnections}")
        }
    }

    private fun refreshProfile() {
        if (shutdown) return
        val next = PowerProfileResolver.resolve(
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            isBackground = isAppInBackground,
            hasDirectPeers = hasDirectPeers
        )
        if (_profile.value != next) {
            Log.i(
                TAG,
                "Profile changed: mode=${next.mode}, battery=${next.batteryBand}, " +
                    "background=${next.isBackground}, directPeers=${next.hasDirectPeers}"
            )
            _profile.value = next
        }
    }

    private fun registerBatteryReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            appContext.registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register battery receiver: ${e.message}")
        }
    }
}

/**
 * Pure policy resolver so cadence and battery-boundary behavior can be tested without Android.
 */
internal object PowerProfileResolver {
    fun resolve(
        batteryLevel: Int,
        isCharging: Boolean,
        isBackground: Boolean,
        hasDirectPeers: Boolean
    ): PowerManager.RuntimePerformanceProfile {
        val batteryBand = when {
            batteryLevel <= AppConstants.Power.CRITICAL_BATTERY_PERCENT ->
                PowerManager.BatteryBand.CRITICAL
            batteryLevel <= AppConstants.Power.LOW_BATTERY_PERCENT ->
                PowerManager.BatteryBand.LOW
            else -> PowerManager.BatteryBand.NORMAL
        }

        val mode = when {
            isBackground && batteryBand == PowerManager.BatteryBand.CRITICAL ->
                PowerManager.PowerMode.ULTRA_LOW_POWER
            isBackground -> PowerManager.PowerMode.POWER_SAVER
            isCharging -> PowerManager.PowerMode.PERFORMANCE
            batteryBand == PowerManager.BatteryBand.CRITICAL ->
                PowerManager.PowerMode.ULTRA_LOW_POWER
            batteryBand == PowerManager.BatteryBand.LOW ->
                PowerManager.PowerMode.POWER_SAVER
            else -> PowerManager.PowerMode.BALANCED
        }

        val ble = when {
            isBackground && hasDirectPeers ->
                PowerManager.BleSchedule(
                    scanOnMs = 1_000L,
                    scanOffMs = 29_000L,
                    continuousScan = false
                )
            isBackground ->
                PowerManager.BleSchedule(
                    scanOnMs = 1_000L,
                    scanOffMs = 59_000L,
                    continuousScan = false
                )
            mode == PowerManager.PowerMode.PERFORMANCE ->
                PowerManager.BleSchedule(Long.MAX_VALUE, 0L, true)
            mode == PowerManager.PowerMode.BALANCED ->
                PowerManager.BleSchedule(8_000L, 2_000L, false)
            mode == PowerManager.PowerMode.POWER_SAVER ->
                PowerManager.BleSchedule(2_000L, 28_000L, false)
            else ->
                PowerManager.BleSchedule(1_000L, 29_000L, false)
        }

        val announcementInterval = when {
            isBackground && batteryBand == PowerManager.BatteryBand.NORMAL -> 60_000L
            isBackground && batteryBand == PowerManager.BatteryBand.LOW -> 120_000L
            isBackground -> 300_000L
            mode == PowerManager.PowerMode.POWER_SAVER -> 60_000L
            mode == PowerManager.PowerMode.ULTRA_LOW_POWER -> 120_000L
            else -> 30_000L
        }

        val wifi = when {
            isBackground && batteryBand == PowerManager.BatteryBand.NORMAL ->
                wifi(10, 60, 60, 5, 5, 10)
            isBackground && batteryBand == PowerManager.BatteryBand.LOW ->
                wifi(20, 120, 120, 10, 10, 15)
            isBackground ->
                wifi(30, 180, 180, 15, 15, 20)
            mode == PowerManager.PowerMode.PERFORMANCE ->
                PowerManager.WifiAwareSchedule(2_000L, 20_000L, 15_000L, 120_000L, 90_000L, 300_000L)
            mode == PowerManager.PowerMode.BALANCED ->
                PowerManager.WifiAwareSchedule(5_000L, 30_000L, 30_000L, 180_000L, 120_000L, 300_000L)
            mode == PowerManager.PowerMode.POWER_SAVER ->
                PowerManager.WifiAwareSchedule(10_000L, 60_000L, 60_000L, 300_000L, 180_000L, 600_000L)
            else ->
                PowerManager.WifiAwareSchedule(15_000L, 90_000L, 90_000L, 600_000L, 300_000L, 900_000L)
        }

        val nostr = when {
            isBackground && batteryBand == PowerManager.BatteryBand.NORMAL ->
                PowerManager.NostrSchedule(300_000L, 330_000L, 300_000L)
            isBackground && batteryBand == PowerManager.BatteryBand.LOW ->
                PowerManager.NostrSchedule(600_000L, 660_000L, 600_000L)
            isBackground ->
                PowerManager.NostrSchedule(900_000L, 990_000L, 900_000L)
            mode == PowerManager.PowerMode.POWER_SAVER ->
                PowerManager.NostrSchedule(120_000L, 132_000L, 60_000L)
            mode == PowerManager.PowerMode.ULTRA_LOW_POWER ->
                PowerManager.NostrSchedule(300_000L, 330_000L, 120_000L)
            else ->
                PowerManager.NostrSchedule(40_000L, 80_000L, 30_000L)
        }

        return PowerManager.RuntimePerformanceProfile(
            mode = mode,
            batteryBand = batteryBand,
            isBackground = isBackground,
            isCharging = isCharging,
            hasDirectPeers = hasDirectPeers,
            ble = ble,
            meshAnnouncementIntervalMs = announcementInterval,
            wifiAware = wifi,
            nostr = nostr
        )
    }

    private fun wifi(
        tcpSeconds: Long,
        discoverySeconds: Long,
        maintenanceSeconds: Long,
        idleMinutes: Long,
        minRefreshMinutes: Long,
        staleMinutes: Long
    ) = PowerManager.WifiAwareSchedule(
        tcpKeepAliveMs = tcpSeconds * 1_000L,
        discoveryKeepAliveMs = discoverySeconds * 1_000L,
        connectionMaintenanceMs = maintenanceSeconds * 1_000L,
        discoveryIdleRefreshMs = idleMinutes * 60_000L,
        discoverySessionRefreshMinMs = minRefreshMinutes * 60_000L,
        discoveryStaleMs = staleMinutes * 60_000L
    )
}
