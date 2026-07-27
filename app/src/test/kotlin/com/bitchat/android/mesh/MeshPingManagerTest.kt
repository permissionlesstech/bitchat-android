package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MeshDiagnosticsConstants
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshPingManagerTest {
    private val localPeerID = "1111111111111111"
    private val remotePeerID = "2222222222222222"

    @Test
    fun `pong received on another transport completes pending ping`() = runTest {
        var outbound: BitchatPacket? = null
        var callbackCount = 0
        var result: MeshPingResult? = null
        val originTransport = MeshPingManager(localPeerID, this) { outbound = it }
        val returnTransport = MeshPingManager(localPeerID, this) {}

        originTransport.ping(remotePeerID) {
            callbackCount += 1
            result = it
        }

        val ping = requireNotNull(outbound)
        returnTransport.handlePong(
            RoutedPacket(
                packet = ping.copy(
                    type = MessageType.PONG.value,
                    senderID = MeshPacketUtils.hexStringToByteArray(remotePeerID),
                    recipientID = MeshPacketUtils.hexStringToByteArray(localPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    ttl = (MeshDiagnosticsConstants.TTL - 1u).toUByte(),
                ),
                peerID = remotePeerID,
            )
        )

        assertEquals(1, callbackCount)
        assertNotNull(result)
        assertEquals(2, result?.hopCount)
        assertTrue(requireNotNull(result).rttMillis >= 0)
    }
}
