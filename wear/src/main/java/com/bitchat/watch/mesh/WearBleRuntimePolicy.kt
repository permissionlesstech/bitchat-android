package com.bitchat.watch.mesh

import android.bluetooth.BluetoothGatt
import com.bitchat.android.mesh.BleConnectionLimits
import com.bitchat.android.mesh.BleRuntimePolicy
import com.bitchat.android.mesh.BleScanPlan
import com.bitchat.android.mesh.PowerManager

/**
 * Aggressive Wear policy: at most two links, no discovery once full, and sparse discovery while
 * an existing background link can already carry messages and route traffic.
 */
object WearBleRuntimePolicy : BleRuntimePolicy {
    const val MAX_CONNECTIONS = 2

    private const val BACKGROUND_ONE_LINK_SCAN_ON_MS = 1_000L
    private const val BACKGROUND_ONE_LINK_SCAN_OFF_MS = 299_000L
    private const val BACKGROUND_NO_LINK_NORMAL_OFF_MS = 59_000L
    private const val BACKGROUND_NO_LINK_LOW_OFF_MS = 119_000L
    private const val BACKGROUND_NO_LINK_CRITICAL_OFF_MS = 299_000L

    // The Watch has no packet-debug UI. Avoid allocating telemetry queues even in debug builds;
    // the transport's fixed-size power counters remain available to Mesh Lab.
    override val collectDebugTelemetry: Boolean = false

    override fun connectionLimits(requested: BleConnectionLimits): BleConnectionLimits =
        BleConnectionLimits(
            overall = requested.overall.coerceAtMost(MAX_CONNECTIONS),
            server = requested.server.coerceAtMost(MAX_CONNECTIONS),
            client = requested.client.coerceAtMost(MAX_CONNECTIONS)
        )

    override fun scanPlan(
        profile: PowerManager.RuntimePerformanceProfile,
        activeConnections: Int
    ): BleScanPlan {
        if (activeConnections >= MAX_CONNECTIONS) return BleScanPlan(enabled = false)

        if (!profile.isBackground) {
            // Foreground discovery stays responsive. Even while charging, avoid an unbounded
            // low-latency scan on a watch; an 8 s burst followed by a short pause is enough.
            return BleScanPlan(
                enabled = true,
                scanOnMs = 8_000L,
                scanOffMs = 2_000L,
                continuous = false
            )
        }

        if (activeConnections == 1) {
            return BleScanPlan(
                enabled = true,
                scanOnMs = BACKGROUND_ONE_LINK_SCAN_ON_MS,
                scanOffMs = BACKGROUND_ONE_LINK_SCAN_OFF_MS
            )
        }

        val offMs = when (profile.batteryBand) {
            PowerManager.BatteryBand.NORMAL -> BACKGROUND_NO_LINK_NORMAL_OFF_MS
            PowerManager.BatteryBand.LOW -> BACKGROUND_NO_LINK_LOW_OFF_MS
            PowerManager.BatteryBand.CRITICAL -> BACKGROUND_NO_LINK_CRITICAL_OFF_MS
        }
        return BleScanPlan(enabled = true, scanOnMs = 1_000L, scanOffMs = offMs)
    }

    override fun shouldAdvertise(
        profile: PowerManager.RuntimePerformanceProfile,
        activeConnections: Int
    ): Boolean = activeConnections < MAX_CONNECTIONS

    override fun gattConnectionPriority(profile: PowerManager.RuntimePerformanceProfile): Int =
        if (profile.isBackground) {
            BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        } else {
            BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        }

    override fun transferGattConnectionPriority(): Int = BluetoothGatt.CONNECTION_PRIORITY_HIGH
}
