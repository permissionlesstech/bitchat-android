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
            HotspotStartupPolicy.Decision.Fail(HotspotError.P2P_DISABLED),
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
            HotspotStartupPolicy.Decision.Fail(HotspotError.P2P_UNSUPPORTED),
            decision
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
