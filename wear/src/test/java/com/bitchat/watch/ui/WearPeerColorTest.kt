package com.bitchat.watch.ui

import androidx.compose.ui.graphics.toArgb
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.PeerIdentity
import com.bitchat.android.ui.theme.DarkBitchatPalette
import com.bitchat.android.ui.theme.colorForPeer
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test

/** The watch colors a peer by the same identity key as the phone and iOS. */
class WearPeerColorTest {

    private val palette = DarkBitchatPalette
    private val myPeerID = "fedcba9876543210"

    private fun message(sender: String) = BitchatMessage(
        sender = sender,
        content = "hi",
        timestamp = Date(0),
        senderPeerID = "0123456789abcdef",
    )

    @Test
    fun `a mesh sender gets the phone's color for that peer`() {
        assertEquals(
            colorForPeer(PeerIdentity.mesh("0123456789abcdef").stableKey, palette),
            messageSenderColor(message(sender = "alice"), myPeerID, palette),
        )
    }

    @Test
    fun `a nickname change does not recolor the peer`() {
        assertEquals(
            messageSenderColor(message(sender = "alice"), myPeerID, palette),
            messageSenderColor(message(sender = "bob"), myPeerID, palette),
        )
    }

    @Test
    fun `identity keys produce the phone's pinned colors`() {
        assertEquals(-9449122, colorForPeer(PeerIdentity.mesh("0123456789abcdef").stableKey, palette).toArgb())
        assertEquals(
            -8167727,
            colorForPeer(PeerIdentity.nostr("fedcba98" + "0".repeat(56)).stableKey, palette).toArgb(),
        )
        assertEquals(-3186991, colorForPeer(PeerIdentity.nickname("alice").stableKey, palette).toArgb())
    }

    @Test
    fun `peer ID case does not change the color`() {
        assertEquals(
            colorForPeer(PeerIdentity.mesh("0123456789abcdef").stableKey, palette),
            colorForPeer(PeerIdentity.mesh("0123456789ABCDEF").stableKey, palette),
        )
    }
}
