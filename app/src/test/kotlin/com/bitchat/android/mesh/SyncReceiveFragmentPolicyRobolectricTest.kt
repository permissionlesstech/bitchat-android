package com.bitchat.android.mesh

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.Random

@RunWith(RobolectricTestRunner::class)
class SyncReceiveFragmentPolicyRobolectricTest {
    private val now = 1_800_000_000_000L
    private val sender = "aaaabbbbccccdddd"
    private val neighbor = "1111222233334444"

    // Isolate receive policy with an accepting signature verifier; fragmentation is real.
    private fun accepts(packet: BitchatPacket): Boolean {
        val delegate = mock<SecurityManagerDelegate>()
        whenever(delegate.getPeerInfo(sender)).thenReturn(PeerInfo(
            id = sender, nickname = "Synthetic", isConnected = true,
            isDirectConnection = true, noisePublicKey = null,
            signingPublicKey = ByteArray(32) { 2 }, isVerifiedNickname = true, lastSeen = now
        ))
        whenever(delegate.peerIDForRelayAddress("synthetic-link")).thenReturn(neighbor)
        whenever(delegate.isValidSyncResponse(neighbor)).thenReturn(true)
        val verifier = object : SecurityManagerTest.FakeEncryptionService() {
            override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray,
                                                publicKeyBytes: ByteArray) = true
        }
        val manager = SecurityManager(verifier, "ffffffffffffffff") { now }
        manager.delegate = delegate
        return try { manager.validatePacket(packet, sender, "synthetic-link") }
        finally { manager.shutdown() }
    }

    @Test
    fun `fragmentation must not turn an old unmarked live packet into legacy history`() {
        val original = BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = MeshPacketUtils.hexStringToByteArray(sender), recipientID = null,
            timestamp = (now - 3_600_000L).toULong(),
            payload = ByteArray(4000).also { Random(854925L).nextBytes(it) },
            signature = ByteArray(64) { 0x55 }, ttl = 7u, isRSR = false
        )
        val fragmenter = FragmentManager()
        val receiver = FragmentManager()
        try {
            assertFalse("Direct old live packet rejects", accepts(original))
            assertTrue("Legacy requested history passes the test verifier", accepts(original.copy(ttl = 0u)))
            val fragments = fragmenter.createFragments(original)
            assertTrue("Exercise multiple wire fragments", fragments.size > 1)
            var restored: BitchatPacket? = null
            for (fragment in fragments) {
                val wire = BitchatPacket.fromBinaryData(fragment.toBinaryData()!!)!!
                receiver.handleFragment(wire)?.let { restored = it }
            }
            val reassembled = requireNotNull(restored)
            assertArrayEquals(original.payload, reassembled.payload)
            assertFalse("Preserving original TTL rejects the same reassembled data",
                accepts(reassembled.copy(ttl = original.ttl)))
            assertFalse("Reassembly must not grant the legacy exception", accepts(reassembled))
        } finally { fragmenter.shutdown(); receiver.shutdown() }
    }
    @Test
    fun `fragmented requested history retains marked and legacy acceptance`() {
        for (marked in listOf(false, true)) {
            val original = BitchatPacket(
                type = MessageType.MESSAGE.value,
                senderID = MeshPacketUtils.hexStringToByteArray(sender), recipientID = null,
                timestamp = (now - 3_600_000L).toULong(),
                payload = ByteArray(4000).also { Random(925926L).nextBytes(it) },
                signature = ByteArray(64) { 0x55 },
                ttl = if (marked) 7u else 0u, isRSR = marked
            )
            val fragmenter = FragmentManager()
            val receiver = FragmentManager()
            try {
                val fragments = fragmenter.createFragments(original)
                assertTrue(fragments.size > 1)
                var restored: BitchatPacket? = null
                for (fragment in fragments) {
                    val wire = BitchatPacket.fromBinaryData(fragment.toBinaryData()!!)!!
                    receiver.handleFragment(wire)?.let { restored = it }
                }
                val reassembled = requireNotNull(restored)
                assertEquals(0u.toUByte(), reassembled.ttl)
                assertEquals(original.ttl, reassembled.reassembledOriginalTtl)
                assertTrue("Requested history remains eligible, marked=$marked", accepts(reassembled))
            } finally { fragmenter.shutdown(); receiver.shutdown() }
        }
    }

    @Test
    fun `reassembly classification metadata changes neither wire bytes nor signing preimage`() {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = MeshPacketUtils.hexStringToByteArray(sender), recipientID = null,
            timestamp = now.toULong(), payload = byteArrayOf(1, 2, 3),
            signature = ByteArray(64) { 0x55 }, ttl = 0u
        )
        val classified = packet.copy(reassembledOriginalTtl = 7u)
        assertArrayEquals(packet.toBinaryData(), classified.toBinaryData())
        assertArrayEquals(packet.toBinaryDataForSigning(), classified.toBinaryDataForSigning())
        assertEquals(packet, classified)
        assertEquals(packet.hashCode(), classified.hashCode())
        assertNull(BitchatPacket.fromBinaryData(classified.toBinaryData()!!)!!.reassembledOriginalTtl)
    }

}
