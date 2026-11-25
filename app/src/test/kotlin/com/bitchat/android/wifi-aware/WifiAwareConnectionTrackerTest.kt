package com.bitchat.android.wifiaware

import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket

class WifiAwareConnectionTrackerTest {
    @Test
    fun `compare and rebind rejects stale observed socket after replacement`() {
        val tracker = WifiAwareConnectionTracker(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mock<ConnectivityManager>()
        )
        val observedSocket = syncedSocket()
        val replacementSocket = syncedSocket()
        tracker.onClientConnected("provisional", observedSocket)
        tracker.onClientConnected("provisional", replacementSocket)

        assertFalse(
            tracker.rebindPeerIdIfCurrent(
                previousPeerId = "provisional",
                resolvedPeerId = "canonical",
                expectedSocket = observedSocket
            )
        )
        assertSame(replacementSocket, tracker.getSocketForPeer("provisional"))
        assertNull(tracker.getSocketForPeer("canonical"))

        assertTrue(
            tracker.rebindPeerIdIfCurrent(
                previousPeerId = "provisional",
                resolvedPeerId = "canonical",
                expectedSocket = replacementSocket
            )
        )
        assertSame(replacementSocket, tracker.getSocketForPeer("canonical"))
        assertSame(replacementSocket, tracker.getSocketForPeer("provisional"))
    }

    @Test
    fun `observed provisional socket cannot displace existing canonical socket`() {
        val tracker = WifiAwareConnectionTracker(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mock<ConnectivityManager>()
        )
        val provisionalSocket = syncedSocket()
        val canonicalSocket = syncedSocket()
        tracker.onClientConnected("provisional", provisionalSocket)
        tracker.onClientConnected("canonical", canonicalSocket)

        assertFalse(
            tracker.rebindPeerIdIfCurrent(
                previousPeerId = "provisional",
                resolvedPeerId = "canonical",
                expectedSocket = provisionalSocket
            )
        )
        assertSame(provisionalSocket, tracker.getSocketForPeer("provisional"))
        assertSame(canonicalSocket, tracker.getSocketForPeer("canonical"))
        assertTrue("Rejected promotion must not alias the provisional ID", tracker.canonicalPeerId("provisional") == "provisional")
    }

    @Test
    fun `canonicalPeerIdIfSocketCurrent returns peer only for active socket generation`() {
        val tracker = WifiAwareConnectionTracker(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mock<ConnectivityManager>()
        )
        val firstSocket = syncedSocket()
        val replacementSocket = syncedSocket()
        tracker.onClientConnected("provisional", firstSocket)
        assertTrue(
            tracker.rebindPeerIdIfCurrent(
                previousPeerId = "provisional",
                resolvedPeerId = "canonical-a",
                expectedSocket = firstSocket
            )
        )
        assertTrue(tracker.canonicalPeerIdIfSocketCurrent("provisional", firstSocket) == "canonical-a")
        assertTrue(tracker.canonicalPeerIdIfSocketCurrent("canonical-a", firstSocket) == "canonical-a")

        // Replace socket for canonical peer: stale reader holding firstSocket must get null
        tracker.onClientConnected("canonical-a", replacementSocket)
        assertNull(tracker.canonicalPeerIdIfSocketCurrent("provisional", firstSocket))
        assertNull(tracker.canonicalPeerIdIfSocketCurrent("canonical-a", firstSocket))
        assertTrue(tracker.canonicalPeerIdIfSocketCurrent("provisional", replacementSocket) == "canonical-a")
    }

    @Test
    fun `provisional socket stays unidentified until verified by direct announce`() {
        val tracker = WifiAwareConnectionTracker(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mock<ConnectivityManager>()
        )
        var notifications = 0
        tracker.onConnectionStateChanged = { notifications += 1 }

        val sock = syncedSocket()
        tracker.onClientConnected("provisional-peer", sock)

        // Before verified ANNOUNCE: treated as unidentified and NOT in verified peer set
        assertTrue(tracker.hasUnidentifiedConnection())
        assertTrue(tracker.getVerifiedConnectedPeerIDs().isEmpty())
        assertNull(tracker.verifiedCanonicalPeerIdIfSocketCurrent("provisional-peer", sock))
        assertTrue(notifications >= 1)

        // After validated ANNOUNCE verifies the exact socket: identified and verified
        assertTrue(tracker.markSocketVerified("provisional-peer", sock))
        assertFalse(tracker.hasUnidentifiedConnection())
        assertTrue(tracker.getVerifiedConnectedPeerIDs() == setOf("provisional-peer"))
        assertTrue(tracker.verifiedCanonicalPeerIdIfSocketCurrent("provisional-peer", sock) == "provisional-peer")

        // Replacing a verified socket with a provisional one resets it back to unidentified AND
        // synchronously notifies onVerifiedPeerDisconnected so stale graph edges are pruned immediately.
        val displacedPeers = mutableListOf<String>()
        tracker.onVerifiedPeerDisconnected = { displacedPeers += it }

        val replacement = syncedSocket()
        tracker.onClientConnected("provisional-peer", replacement)
        assertTrue(tracker.hasUnidentifiedConnection())
        assertTrue("Displacing a verified socket must notify verified-disconnect cleanup immediately", displacedPeers == listOf("provisional-peer"))
        assertNull(tracker.verifiedCanonicalPeerIdIfSocketCurrent("provisional-peer", sock))
        assertNull(tracker.verifiedCanonicalPeerIdIfSocketCurrent("provisional-peer", replacement))
    }

    private fun syncedSocket(): SyncedSocket {
        val raw = mock<Socket> {
            on { getInputStream() } doReturn ByteArrayInputStream(byteArrayOf())
            on { getOutputStream() } doReturn ByteArrayOutputStream()
        }
        return SyncedSocket(raw)
    }
}
