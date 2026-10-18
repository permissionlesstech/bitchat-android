package com.bitchat.android.mesh

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncReceivePolicyRobolectricTest {
    private val now = 1_800_000_000_000L
    private val sender = "aaaabbbbccccdddd"
    private val neighbor = "1111222233334444"

    // These tests isolate policy. Real Ed25519 and persisted-store coverage lives in
    // SecurityManagerPersistedIdentityRobolectricTest.
    private fun accepts(offset: Long, marked: Boolean = false, ttl: UByte = 7u,
                        requested: Boolean = false, recipient: ByteArray? = null): Boolean {
        val delegate = mock<SecurityManagerDelegate>()
        whenever(delegate.getPeerInfo(sender)).thenReturn(PeerInfo(
            id = sender, nickname = "Synthetic", isConnected = true,
            isDirectConnection = true, noisePublicKey = null, signingPublicKey = ByteArray(32) { 2 },
            isVerifiedNickname = true, lastSeen = now
        ))
        whenever(delegate.peerIDForRelayAddress("synthetic-link")).thenReturn(neighbor)
        whenever(delegate.isValidSyncResponse(neighbor)).thenReturn(requested)
        val manager = SecurityManager(SecurityManagerTest.FakeEncryptionService(), "ffffffffffffffff") { now }
        manager.delegate = delegate
        return try {
            manager.validatePacket(BitchatPacket(
                type = MessageType.MESSAGE.value,
                senderID = MeshPacketUtils.hexStringToByteArray(sender), recipientID = recipient,
                timestamp = (now + offset).toULong(), payload = byteArrayOf(1, 2, 3),
                signature = ByteArray(64) { 1 }, ttl = ttl, isRSR = marked
            ), sender, "synthetic-link")
        } finally { manager.shutdown() }
    }

    @Test
    fun `live public messages accept the two minute boundary and reject beyond it`() {
        for (recipient in listOf(null, SpecialRecipients.BROADCAST)) {
            for (offset in listOf(-120_001L, -120_000L, 120_000L, 120_001L)) {
                assertEquals("offset=$offset", offset in -120_000L..120_000L,
                    accepts(offset, recipient = recipient))
            }
        }
    }

    @Test
    fun `both response markers keep the future bound and six hour past bound`() {
        for (marked in listOf(false, true)) {
            for (offset in listOf(-21_600_001L, -21_600_000L, 120_000L, 120_001L)) {
                assertEquals("marked=$marked offset=$offset", offset in -21_600_000L..120_000L,
                    accepts(offset, marked, 0u, true))
            }
        }
    }

    @Test
    fun `a fresh marked message still requires a requested source`() {
        assertEquals(false, accepts(0, marked = true))
        assertEquals(true, accepts(0, marked = true, requested = true))
    }

    @Test
    fun `an unmarked old message needs both zero TTL and a requested source`() {
        assertEquals(false, accepts(-3_600_000L, ttl = 0u))
        assertEquals(false, accepts(-3_600_000L, ttl = 7u, requested = true))
        assertEquals(true, accepts(-3_600_000L, ttl = 0u, requested = true))
    }

    @Test
    fun `addressed messages retain their earlier timestamp behavior`() {
        assertEquals(true, accepts(-86_400_000L, recipient = ByteArray(8) { 0x55 }))
    }
}
