package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerProfileResolverTest {

    @Test
    fun `background normal without peers scans one second per minute`() {
        val profile = resolve(battery = 80, background = true, peers = false)

        assertEquals(PowerManager.BatteryBand.NORMAL, profile.batteryBand)
        assertEquals(1_000L, profile.ble.scanOnMs)
        assertEquals(59_000L, profile.ble.scanOffMs)
        assertFalse(profile.ble.continuousScan)
        assertEquals(8, profile.ble.maxConnections)
        assertEquals(60_000L, profile.meshAnnouncementIntervalMs)
    }

    @Test
    fun `any direct transport peer accelerates background BLE discovery to thirty seconds`() {
        val profile = resolve(battery = 80, background = true, peers = true)

        assertEquals(1_000L, profile.ble.scanOnMs)
        assertEquals(29_000L, profile.ble.scanOffMs)
    }

    @Test
    fun `background low profile uses selected wifi and nostr cadence`() {
        val profile = resolve(battery = 20, background = true)

        assertEquals(PowerManager.BatteryBand.LOW, profile.batteryBand)
        assertEquals(20_000L, profile.wifiAware.tcpKeepAliveMs)
        assertEquals(120_000L, profile.wifiAware.discoveryKeepAliveMs)
        assertEquals(120_000L, profile.wifiAware.connectionMaintenanceMs)
        assertEquals(600_000L, profile.wifiAware.discoveryIdleRefreshMs)
        assertEquals(900_000L, profile.wifiAware.discoveryStaleMs)
        assertEquals(600_000L, profile.nostr.presenceHeartbeatMinMs)
        assertEquals(600_000L, profile.nostr.subscriptionValidationMs)
        assertEquals(120_000L, profile.meshAnnouncementIntervalMs)
    }

    @Test
    fun `background critical profile uses slowest wifi and nostr cadence without reducing connections`() {
        val profile = resolve(battery = 10, background = true, peers = true)

        assertEquals(PowerManager.BatteryBand.CRITICAL, profile.batteryBand)
        assertEquals(30_000L, profile.wifiAware.tcpKeepAliveMs)
        assertEquals(180_000L, profile.wifiAware.discoveryKeepAliveMs)
        assertEquals(180_000L, profile.wifiAware.connectionMaintenanceMs)
        assertEquals(900_000L, profile.wifiAware.discoveryIdleRefreshMs)
        assertEquals(1_200_000L, profile.wifiAware.discoveryStaleMs)
        assertEquals(900_000L, profile.nostr.presenceHeartbeatMinMs)
        assertEquals(900_000L, profile.nostr.subscriptionValidationMs)
        assertEquals(300_000L, profile.meshAnnouncementIntervalMs)
        assertEquals(8, profile.ble.maxConnections)
    }

    @Test
    fun `foreground normal preserves balanced BLE cadence`() {
        val profile = resolve(battery = 80, background = false)

        assertEquals(PowerManager.PowerMode.BALANCED, profile.mode)
        assertEquals(8_000L, profile.ble.scanOnMs)
        assertEquals(2_000L, profile.ble.scanOffMs)
        assertEquals(5_000L, profile.wifiAware.tcpKeepAliveMs)
        assertEquals(30_000L, profile.meshAnnouncementIntervalMs)
    }

    @Test
    fun `foreground charging is performance but background charging stays power limited`() {
        val foreground = PowerProfileResolver.resolve(80, true, false, false)
        val background = PowerProfileResolver.resolve(80, true, true, false)

        assertEquals(PowerManager.PowerMode.PERFORMANCE, foreground.mode)
        assertTrue(foreground.ble.continuousScan)
        assertEquals(PowerManager.PowerMode.POWER_SAVER, background.mode)
        assertFalse(background.ble.continuousScan)
        assertEquals(59_000L, background.ble.scanOffMs)
    }

    @Test
    fun `battery thresholds are inclusive`() {
        assertEquals(PowerManager.BatteryBand.NORMAL, resolve(21, true).batteryBand)
        assertEquals(PowerManager.BatteryBand.LOW, resolve(20, true).batteryBand)
        assertEquals(PowerManager.BatteryBand.LOW, resolve(11, true).batteryBand)
        assertEquals(PowerManager.BatteryBand.CRITICAL, resolve(10, true).batteryBand)
    }

    private fun resolve(
        battery: Int,
        background: Boolean,
        peers: Boolean = false
    ) = PowerProfileResolver.resolve(
        batteryLevel = battery,
        isCharging = false,
        isBackground = background,
        hasDirectPeers = peers
    )
}
