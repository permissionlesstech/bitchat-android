package com.bitchat.android.wifiaware

import com.bitchat.android.service.testWifiAwareController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 *
 * Each test has its own controller, so no hold leaks from one test into the next.
 */
@RunWith(RobolectricTestRunner::class)
class WifiAwareHotspotHoldTest {

    private val controller = testWifiAwareController()

    @Test
    fun `a single session holds and then releases`() {
        val lease = controller.acquireHotspotLease()
        assertTrue(controller.isHeldForHotspot())

        lease.close()
        assertFalse(controller.isHeldForHotspot())
    }

    @Test
    fun `a second session starting before the first finishes keeps the radio held`() {
        val first = controller.acquireHotspotLease()
        val second = controller.acquireHotspotLease()

        // The first session's asynchronous teardown completes.
        first.close()

        assertTrue(
            "the second session still needs the radio",
            controller.isHeldForHotspot()
        )

        second.close()
        assertFalse(controller.isHeldForHotspot())
    }

    @Test
    fun `closing one lease twice cannot release another session`() {
        val first = controller.acquireHotspotLease()
        val second = controller.acquireHotspotLease()

        first.close()
        first.close()
        assertTrue(controller.isHeldForHotspot())

        second.close()
        assertFalse(
            "only the matching session can release its radio claim",
            controller.isHeldForHotspot()
        )
    }
}
