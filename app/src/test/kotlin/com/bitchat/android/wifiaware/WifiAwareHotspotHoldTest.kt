package com.bitchat.android.wifiaware

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
        // The controller is a singleton, so a count left by another test would carry over.
        repeat(8) { WifiAwareController.releaseHotspotHold() }
    }

    @Test
    fun `a single session holds and then releases`() {
        WifiAwareController.holdForHotspot()
        assertTrue(WifiAwareController.isHeldForHotspot())

        WifiAwareController.releaseHotspotHold()
        assertFalse(WifiAwareController.isHeldForHotspot())
    }

    @Test
    fun `a second session starting before the first finishes keeps the radio held`() {
        WifiAwareController.holdForHotspot()
        WifiAwareController.holdForHotspot()

        // The first session's asynchronous teardown completes.
        WifiAwareController.releaseHotspotHold()

        assertTrue(
            "the second session still needs the radio",
            WifiAwareController.isHeldForHotspot()
        )

        WifiAwareController.releaseHotspotHold()
        assertFalse(WifiAwareController.isHeldForHotspot())
    }

    @Test
    fun `an unbalanced release cannot leave a later hold unable to reach zero`() {
        // teardown() is documented as safe to call twice, so this is reachable.
        WifiAwareController.releaseHotspotHold()
        WifiAwareController.releaseHotspotHold()

        WifiAwareController.holdForHotspot()
        assertTrue(WifiAwareController.isHeldForHotspot())

        WifiAwareController.releaseHotspotHold()
        assertFalse(
            "a clamped count releases on the matching release, not several later",
            WifiAwareController.isHeldForHotspot()
        )
    }
}
