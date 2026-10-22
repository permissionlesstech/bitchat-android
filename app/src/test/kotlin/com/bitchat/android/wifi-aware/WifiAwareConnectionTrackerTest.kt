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

    private fun syncedSocket(): SyncedSocket {
        val raw = mock<Socket> {
            on { getInputStream() } doReturn ByteArrayInputStream(byteArrayOf())
            on { getOutputStream() } doReturn ByteArrayOutputStream()
        }
        return SyncedSocket(raw)
    }
}
