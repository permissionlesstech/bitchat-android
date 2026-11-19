package com.bitchat.android.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the phone's peer colors as they were before the hash moved into this module.
 *
 * The keys are the `PeerIdentity.stableKey` values for a mesh peer, a Nostr peer and a
 * nickname-only peer. This module cannot see `PeerIdentity` (it lives in :core:domain), so the
 * keys are spelled out; WearPeerColorTest checks that the identities still produce them.
 */
class PeerColorsTest {

    @Test
    fun `mesh peer keeps its dark color`() {
        assertEquals(-9449122, colorForPeer("noise:0123456789abcdef", DarkBitchatPalette).toArgb())
    }

    @Test
    fun `nostr peer keeps its dark color`() {
        assertEquals(-8167727, colorForPeer("nostr:nostr:fedcba98", DarkBitchatPalette).toArgb())
    }

    @Test
    fun `nickname-only peer keeps its dark color`() {
        assertEquals(-3186991, colorForPeer("alice", DarkBitchatPalette).toArgb())
    }

    @Test
    fun `mesh peer keeps its light color`() {
        assertEquals(-13931744, colorForPeer("noise:0123456789abcdef", LightBitchatPalette).toArgb())
    }
}
