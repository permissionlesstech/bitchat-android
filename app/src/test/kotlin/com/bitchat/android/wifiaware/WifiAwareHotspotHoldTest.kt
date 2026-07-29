package com.bitchat.android.wifiaware

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The hold keeps Wi-Fi Aware off the radio while a hotspot uses it. Getting the
 * bookkeeping wrong is not cosmetic: release it early and the P2P group cannot form,
 * fail to release it and the mesh stays down.
 *
 * Teardown is asynchronous, so sessions overlap and releases arrive out of order. These
 * cover the counting rules that make that safe.
 */
@RunWith(RobolectricTestRunner::class)
class WifiAwareHotspotHoldTest {

    @Before
    fun clearAnyLeftoverHolds() {
        WifiAwareController.resetHotspotLeasesForTest()
    }

    @After
    fun clearTestHolds() {
        WifiAwareController.resetHotspotLeasesForTest()
    }

    @Test
    fun `a single session holds and then releases`() {
        val lease = WifiAwareController.acquireHotspotLease()
        assertTrue(WifiAwareController.isHeldForHotspot())

        lease.close()
        assertFalse(WifiAwareController.isHeldForHotspot())
    }

    @Test
    fun `a second session starting before the first finishes keeps the radio held`() {
        val first = WifiAwareController.acquireHotspotLease()
        val second = WifiAwareController.acquireHotspotLease()

        // The first session's asynchronous teardown completes.
        first.close()

        assertTrue(
            "the second session still needs the radio",
            WifiAwareController.isHeldForHotspot()
        )

        second.close()
        assertFalse(WifiAwareController.isHeldForHotspot())
    }

    @Test
    fun `closing one lease twice cannot release another session`() {
        val first = WifiAwareController.acquireHotspotLease()
        val second = WifiAwareController.acquireHotspotLease()

        first.close()
        first.close()
        assertTrue(WifiAwareController.isHeldForHotspot())

        second.close()
        assertFalse(
            "only the matching session can release its radio claim",
            WifiAwareController.isHeldForHotspot()
        )
    }
}
