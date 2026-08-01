package com.bitchat.android.mesh

/**
 * Product-specific BLE policy injected into the shared transport.
 *
 * The phone keeps the historic behavior through [DefaultBleRuntimePolicy]. Wear can impose a
 * stricter hard ceiling and a connection-aware discovery schedule without forking the transport.
 */
interface BleRuntimePolicy {
    val collectDebugTelemetry: Boolean

    fun connectionLimits(requested: BleConnectionLimits): BleConnectionLimits

    fun scanPlan(
        profile: PowerManager.RuntimePerformanceProfile,
        activeConnections: Int
    ): BleScanPlan

    fun shouldAdvertise(
        profile: PowerManager.RuntimePerformanceProfile,
        activeConnections: Int
    ): Boolean

    fun shouldPollRssi(profile: PowerManager.RuntimePerformanceProfile): Boolean

    /** Return an Android BluetoothGatt connection-priority constant, or null to leave it alone. */
    fun gattConnectionPriority(profile: PowerManager.RuntimePerformanceProfile): Int?

    fun transferGattConnectionPriority(): Int? = null

    fun transferPriorityDurationMs(): Long = 10_000L
}

data class BleConnectionLimits(
    val overall: Int,
    val server: Int,
    val client: Int
) {
    init {
        require(overall >= 0)
        require(server >= 0)
        require(client >= 0)
    }
}

data class BleScanPlan(
    val enabled: Boolean,
    val scanOnMs: Long = 0L,
    val scanOffMs: Long = 0L,
    val continuous: Boolean = false
)

object DefaultBleRuntimePolicy : BleRuntimePolicy {
    override val collectDebugTelemetry: Boolean = true

    override fun connectionLimits(requested: BleConnectionLimits): BleConnectionLimits = requested

    override fun scanPlan(
        profile: PowerManager.RuntimePerformanceProfile,
        activeConnections: Int
    ): BleScanPlan = BleScanPlan(
        enabled = true,
        scanOnMs = profile.ble.scanOnMs,
        scanOffMs = profile.ble.scanOffMs,
        continuous = profile.ble.continuousScan
    )

    override fun shouldAdvertise(
        profile: PowerManager.RuntimePerformanceProfile,
        activeConnections: Int
    ): Boolean = true

    override fun shouldPollRssi(profile: PowerManager.RuntimePerformanceProfile): Boolean = true

    override fun gattConnectionPriority(profile: PowerManager.RuntimePerformanceProfile): Int? = null
}

/** Lightweight counters exposed only through local debug hooks for power regression testing. */
data class BlePowerSnapshot(
    val activeConnections: Int,
    val pendingConnections: Int,
    val connectionLimit: Int,
    val scanning: Boolean,
    val scanStarts: Long,
    val scanResults: Long,
    val scanActiveMs: Long,
    val advertising: Boolean,
    val advertiseStarts: Long,
    val advertiseActiveMs: Long,
    val rssiReads: Long,
    val background: Boolean,
    val batteryBand: PowerManager.BatteryBand
)
