package com.bitchat.watch.mesh

import android.bluetooth.BluetoothGatt
import com.bitchat.android.mesh.BleConnectionLimits
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.mesh.PowerProfileResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearBleRuntimePolicyTest {
    @Test
    fun `connection limits can be lowered but never raised above two`() {
        assertEquals(
            BleConnectionLimits(2, 2, 2),
            WearBleRuntimePolicy.connectionLimits(BleConnectionLimits(8, 8, 8))
        )
        assertEquals(
            BleConnectionLimits(1, 2, 2),
            WearBleRuntimePolicy.connectionLimits(BleConnectionLimits(1, 4, 4))
        )
    }

    @Test
    fun `two links disable both discovery and advertising`() {
        val profile = profile(background = true)

        assertFalse(WearBleRuntimePolicy.scanPlan(profile, 2).enabled)
        assertFalse(WearBleRuntimePolicy.shouldAdvertise(profile, 2))
    }

    @Test
    fun `one background link scans for one second every five minutes`() {
        val plan = WearBleRuntimePolicy.scanPlan(profile(background = true), 1)

        assertTrue(plan.enabled)
        assertFalse(plan.continuous)
        assertEquals(1_000L, plan.scanOnMs)
        assertEquals(299_000L, plan.scanOffMs)
    }

    @Test
    fun `disconnected background discovery slows with battery pressure`() {
        val normal = WearBleRuntimePolicy.scanPlan(profile(background = true, battery = 80), 0)
        val low = WearBleRuntimePolicy.scanPlan(profile(background = true, battery = 20), 0)
        val critical = WearBleRuntimePolicy.scanPlan(profile(background = true, battery = 10), 0)

        assertEquals(59_000L, normal.scanOffMs)
        assertEquals(119_000L, low.scanOffMs)
        assertEquals(299_000L, critical.scanOffMs)
    }

    @Test
    fun `foreground discovery remains responsive but never continuous`() {
        val plan = WearBleRuntimePolicy.scanPlan(
            PowerProfileResolver.resolve(80, true, false, false),
            activeConnections = 1
        )

        assertEquals(PowerManager.PowerMode.PERFORMANCE, profile(false, charging = true).mode)
        assertTrue(plan.enabled)
        assertFalse(plan.continuous)
        assertEquals(8_000L, plan.scanOnMs)
        assertEquals(2_000L, plan.scanOffMs)
    }

    @Test
    fun `RSSI polling is disabled only in the background`() {
        assertFalse(WearBleRuntimePolicy.shouldPollRssi(profile(background = true)))
        assertTrue(WearBleRuntimePolicy.shouldPollRssi(profile(background = false)))
    }

    @Test
    fun `bulk transfers temporarily request high connection priority`() {
        assertEquals(
            BluetoothGatt.CONNECTION_PRIORITY_HIGH,
            WearBleRuntimePolicy.transferGattConnectionPriority()
        )
    }

    private fun profile(
        background: Boolean,
        battery: Int = 80,
        charging: Boolean = false
    ) = PowerProfileResolver.resolve(
        batteryLevel = battery,
        isCharging = charging,
        isBackground = background,
        hasDirectPeers = false
    )
}
