package com.bitchat.android.mesh

import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncReceiveSignatureRobolectricTest {
    private val now = 1_800_000_000_000L
    private val sender = "aaaabbbbccccdddd"
    private val neighbor = "1111222233334444"
    private val key = Ed25519PrivateKeyParameters(ByteArray(32) { 0x35 }, 0)
    private lateinit var manager: SecurityManager
    private lateinit var delegate: SecurityManagerDelegate

    @Before
    fun setUp() {
        val encryption = object : EncryptionService(RuntimeEnvironment.getApplication()) {
            override fun initialize() = Unit
        }
        manager = SecurityManager(encryption, "ffffffffffffffff") { now }
        delegate = mock()
        whenever(delegate.getPeerInfo(sender)).thenReturn(PeerInfo(
            id = sender, nickname = "Synthetic", isConnected = true,
            isDirectConnection = true, noisePublicKey = null,
            signingPublicKey = key.generatePublicKey().encoded,
            isVerifiedNickname = true, lastSeen = now
        ))
        whenever(delegate.peerIDForRelayAddress("synthetic-link")).thenReturn(neighbor)
        whenever(delegate.isValidSyncResponse(neighbor)).thenReturn(true)
        manager.delegate = delegate
    }

    @After
    fun tearDown() = manager.shutdown()

    private fun signed(age: Long, marked: Boolean): BitchatPacket {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value, ttl = 0u, senderID = sender,
            payload = "synthetic history".toByteArray()
        ).copy(timestamp = (now - age).toULong(), isRSR = marked)
        val signer = Ed25519Signer()
        signer.init(true, key)
        val bytes = requireNotNull(packet.toBinaryDataForSigning())
        signer.update(bytes, 0, bytes.size)
        return packet.copy(signature = signer.generateSignature())
    }

    @Test
    fun `requested history still requires a real valid signature and a live key`() {
        var age = 3_600_000L
        for (marked in listOf(false, true)) {
            for (unsigned in listOf(false, true)) {
                val packet = signed(age++, marked)
                val invalid = if (unsigned) packet.copy(signature = null)
                    else packet.copy(payload = "tampered history".toByteArray())
                assertFalse(manager.validatePacket(invalid, sender, "synthetic-link"))
                assertTrue(manager.validatePacket(packet, sender, "synthetic-link"))
            }
        }
        whenever(delegate.getPeerInfo(sender)).thenReturn(null)
        assertFalse("This policy does not add departed-sender key recovery",
            manager.validatePacket(signed(age, true), sender, "synthetic-link"))
    }

    @Test
    fun `packet ingress supplies the requested neighbor to the real verifier`() = runBlocking {
        val processor = PacketProcessor("ffffffffffffffff")
        val dispatch = mock<PacketProcessorDelegate>()
        val received = CompletableDeferred<RoutedPacket>()
        whenever(dispatch.validatePacketSecurity(any(), any(), anyOrNull())).thenAnswer {
            manager.validatePacket(it.getArgument(0), it.getArgument(1), it.getArgument(2))
        }
        doAnswer { received.complete(it.getArgument(0)); null }.whenever(dispatch).handleMessage(any())
        processor.delegate = dispatch
        try {
            val packet = signed(3_600_000L, true)
            processor.processPacket(RoutedPacket(packet, sender, "synthetic-link"))
            assertEquals(packet, withTimeout(2_000) { received.await() }.packet)
            whenever(delegate.isValidSyncResponse(neighbor)).thenReturn(false)
            assertFalse(manager.validatePacket(signed(3_600_001L, true), sender, "synthetic-link"))
        } finally { processor.shutdown() }
    }
}
