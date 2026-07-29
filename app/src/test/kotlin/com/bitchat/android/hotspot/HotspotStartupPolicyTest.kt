package com.bitchat.android.hotspot

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotStartupPolicyTest {

    @Test
    fun `busy while P2P is disabled fails immediately instead of retrying`() {
        val decision = HotspotStartupPolicy.decide(
            reason = WifiP2pManager.BUSY,
            attempt = 1,
            p2pState = WifiP2pManager.WIFI_P2P_STATE_DISABLED
        )

        assertEquals(
            HotspotStartupPolicy.Decision.Fail(HotspotStartupPolicy.P2P_DISABLED_MESSAGE),
            decision
        )
    }

    @Test
    fun `busy before the P2P state is known still retries`() {
        val decision = HotspotStartupPolicy.decide(
            reason = WifiP2pManager.BUSY,
            attempt = 1,
            p2pState = null
        )

        assertTrue(decision is HotspotStartupPolicy.Decision.Retry)
    }

    @Test
    fun `busy retry delays back off exponentially`() {
        val delays = (1..4).map { attempt ->
            val decision = HotspotStartupPolicy.decide(
                reason = WifiP2pManager.BUSY,
                attempt = attempt,
                p2pState = WifiP2pManager.WIFI_P2P_STATE_ENABLED
            )
            (decision as HotspotStartupPolicy.Decision.Retry).delayMillis
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), delays)
    }

    @Test
    fun `busy on the final attempt gives up`() {
        val decision = HotspotStartupPolicy.decide(
            reason = WifiP2pManager.BUSY,
            attempt = HotspotStartupPolicy.MAX_ATTEMPTS,
            p2pState = WifiP2pManager.WIFI_P2P_STATE_ENABLED
        )

        assertTrue(decision is HotspotStartupPolicy.Decision.Fail)
    }

    @Test
    fun `unsupported P2P never retries`() {
        val decision = HotspotStartupPolicy.decide(
            reason = WifiP2pManager.P2P_UNSUPPORTED,
            attempt = 1,
            p2pState = WifiP2pManager.WIFI_P2P_STATE_ENABLED
        )

        assertEquals(
            HotspotStartupPolicy.Decision.Fail(HotspotStartupPolicy.P2P_UNSUPPORTED_MESSAGE),
            decision
        )
    }

    @Test
    fun `a group we recorded creating is removed before a new one is created`() {
        val action = HotspotStartupPolicy.startAction(
            p2pState = WifiP2pManager.WIFI_P2P_STATE_ENABLED,
            existingGroupName = "DIRECT-BC-CUF6EN63",
            ownedGroupName = "DIRECT-BC-CUF6EN63"
        )

        assertEquals(HotspotStartupPolicy.StartAction.RemoveStaleGroupThenCreate, action)
    }

    @Test
    fun `another app's group is left alone`() {
        val action = HotspotStartupPolicy.startAction(
            p2pState = WifiP2pManager.WIFI_P2P_STATE_ENABLED,
            existingGroupName = "DIRECT-xY-Chromecast",
            ownedGroupName = "DIRECT-BC-CUF6EN63"
        )

        assertEquals(
            HotspotStartupPolicy.StartAction.Fail(HotspotStartupPolicy.FOREIGN_GROUP_MESSAGE),
            action
        )
    }

    @Test
    fun `an orphan from before we recorded ownership is still recognised by its prefix`() {
        val action = HotspotStartupPolicy.startAction(
            p2pState = WifiP2pManager.WIFI_P2P_STATE_ENABLED,
            existingGroupName = "DIRECT-BC-OLDGROUP",
            ownedGroupName = null
        )

        assertEquals(HotspotStartupPolicy.StartAction.RemoveStaleGroupThenCreate, action)
    }

    @Test
    fun `a foreign group is left alone even when we recorded nothing`() {
        val action = HotspotStartupPolicy.startAction(
            p2pState = WifiP2pManager.WIFI_P2P_STATE_ENABLED,
            existingGroupName = "DIRECT-xY-Chromecast",
            ownedGroupName = null
        )

        assertEquals(
            HotspotStartupPolicy.StartAction.Fail(HotspotStartupPolicy.FOREIGN_GROUP_MESSAGE),
            action
        )
    }

    @Test
    fun `creation proceeds directly when no group exists`() {
        val action = HotspotStartupPolicy.startAction(
            p2pState = WifiP2pManager.WIFI_P2P_STATE_ENABLED,
            existingGroupName = null,
            ownedGroupName = null
        )

        assertEquals(HotspotStartupPolicy.StartAction.Create, action)
    }

    @Test
    fun `disabled P2P fails before touching any existing group`() {
        val action = HotspotStartupPolicy.startAction(
            p2pState = WifiP2pManager.WIFI_P2P_STATE_DISABLED,
            existingGroupName = "DIRECT-BC-CUF6EN63",
            ownedGroupName = "DIRECT-BC-CUF6EN63"
        )

        assertEquals(
            HotspotStartupPolicy.StartAction.Fail(HotspotStartupPolicy.P2P_DISABLED_MESSAGE),
            action
        )
    }

    @Test
    fun `busy is retryable so a group orphaned mid-session can be cleared`() {
        val decision = HotspotStartupPolicy.decide(
            reason = WifiP2pManager.BUSY,
            attempt = 1,
            p2pState = WifiP2pManager.WIFI_P2P_STATE_ENABLED
        )

        assertEquals(HotspotStartupPolicy.Decision.Retry(1_000L), decision)
    }

    @Test
    fun `generic framework error never retries`() {
        val decision = HotspotStartupPolicy.decide(
            reason = WifiP2pManager.ERROR,
            attempt = 1,
            p2pState = WifiP2pManager.WIFI_P2P_STATE_ENABLED
        )

        assertTrue(decision is HotspotStartupPolicy.Decision.Fail)
    }
}