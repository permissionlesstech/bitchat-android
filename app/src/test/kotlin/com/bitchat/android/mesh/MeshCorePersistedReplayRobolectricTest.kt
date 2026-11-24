package com.bitchat.android.mesh

import android.content.Context
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.sync.GossipSyncManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.withSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.MessageDigest

/** Exercises the production MeshCore used by standalone transports, including Wear. */
@RunWith(RobolectricTestRunner::class)
class MeshCorePersistedReplayRobolectricTest {
    private val myPeerID = "ffffffffffffffff"
    private val requester = "eeeeeeeeeeeeeeee"
    private val noiseKey = ByteArray(32) { (it + 1).toByte() }
    private val fingerprint = MessageDigest.getInstance("SHA-256").digest(noiseKey)
        .joinToString("") { "%02x".format(it) }
    private val peerID = fingerprint.take(16)
    private val signingKey = Ed25519PrivateKeyParameters(ByteArray(32) { 0x35 }, 0)
    private lateinit var identity: SecureIdentityStateManager
    private lateinit var construction: MockedConstruction<SecureIdentityStateManager>
    private lateinit var scope: CoroutineScope
    private lateinit var core: MeshCore
    private val delivered = mutableListOf<BitchatMessage>()
    private val sent = mutableListOf<Pair<String, BitchatPacket>>()

    @Before
    fun setUp() {
        AppStateStore.clear()
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("mesh-core-replay-test", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        identity = SecureIdentityStateManager(prefs, testOnly = true)
        // Replace only encrypted-storage construction. All calls still execute the real
        // identity implementation against test-only preferences, including fingerprint lookup.
        construction = mockConstruction(
            SecureIdentityStateManager::class.java,
            withSettings().defaultAnswer { call -> call.method.invoke(identity, *call.arguments) }
        ) { _, _ -> }
        val encryption = object : EncryptionService(context) {
            override fun initialize() = Unit
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        core = MeshCore(
            context, scope, object : MeshTransport {
                override val id = "TEST"
                override fun broadcastPacket(routed: RoutedPacket) = false
                override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
                    sent += peerID to packet
                    return true
                }
            }, encryption, myPeerID, 7u, null,
            object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity() = 100
                override fun gcsMaxBytes() = 400
                override fun gcsTargetFpr() = 0.01
            }, MeshCore.Hooks(onMessageReceived = { delivered += it; true })
        )
    }

    @After
    fun tearDown() {
        if (::core.isInitialized) core.shutdown()
        if (::scope.isInitialized) scope.cancel()
        if (::construction.isInitialized) construction.close()
        AppStateStore.clear()
    }

    private fun signedMessage(): BitchatPacket {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value, senderID = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            recipientID = SpecialRecipients.BROADCAST, ttl = 0u,
            timestamp = System.currentTimeMillis().toULong(),
            payload = "synthetic departed replay".toByteArray()
        )
        val data = requireNotNull(packet.toBinaryDataForSigning())
        val signer = Ed25519Signer()
        signer.init(true, signingKey)
        signer.update(data, 0, data.size)
        packet.signature = signer.generateSignature()
        return packet
    }

    /** Observe completion after the production handler/archive delegate, without sleeps. */
    private suspend fun receive(packet: BitchatPacket): Boolean {
        val field = MeshCore::class.java.getDeclaredField("packetProcessor").apply { isAccessible = true }
        val processor = field.get(core) as PacketProcessor
        val original = requireNotNull(processor.delegate)
        val finished = CompletableDeferred<Boolean>()
        processor.delegate = object : PacketProcessorDelegate by original {
            override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
                val valid = original.validatePacketSecurity(packet, peerID)
                if (!valid) finished.complete(false)
                return valid
            }
            override fun updatePeerLastSeen(peerID: String) {
                original.updatePeerLastSeen(peerID)
                finished.complete(true)
            }
        }
        return try {
            core.processIncoming(packet, peerID, "synthetic-link")
            withTimeout(5_000L) { finished.await() }
        } finally {
            processor.delegate = original
        }
    }

    @Test
    fun `persisted signed replay traverses validation and broadcast delivery without refilling archive`() = runBlocking {
        assertTrue(identity.storeAuthenticatedPeerState(
            fingerprint, AuthenticatedPeerState(PeerCapabilities.NONE, signingKey.generatePublicKey().encoded)
        ))
        identity.cacheFingerprintNickname(fingerprint, "cached-sender")
        val packet = signedMessage()
        assertNull(core.getPeerInfo(peerID))
        assertFalse(receive(packet.copy(signature = ByteArray(64))))
        assertTrue(delivered.isEmpty())
        assertTrue(receive(packet))
        assertEquals(1, delivered.size)
        assertEquals("cached-sender", delivered.single().sender)
        assertEquals("synthetic departed replay", delivered.single().content)
        assertEquals(peerID, delivered.single().senderPeerID)
        assertNull("delivery does not recreate a live peer", core.getPeerInfo(peerID))

        core.gossipSyncManager.handleRequestSync(requester, RequestSyncPacket(7, 1, ByteArray(0)))
        assertTrue("accepted absent-sender replay is not available for later sync", sent.isEmpty())
    }

    @Test
    fun `standalone core archive guard tracks its registry for absent present and removed senders`() {
        val gossip = core.gossipSyncManager
        val packet = signedMessage()
        assertFalse(requireNotNull(gossip.delegate).hasLivePeer(peerID))
        gossip.onPublicPacketSeen(packet)
        gossip.handleRequestSync(requester, RequestSyncPacket(7, 1, ByteArray(0)))
        assertTrue(sent.isEmpty())

        core.addOrUpdatePeer(peerID, "present-sender")
        assertTrue(requireNotNull(gossip.delegate).hasLivePeer(peerID))
        gossip.onPublicPacketSeen(packet)
        gossip.handleRequestSync(requester, RequestSyncPacket(7, 1, ByteArray(0)))
        assertEquals(1, sent.size)
        assertEquals(requester, sent.single().first)
        assertArrayEquals(packet.payload, sent.single().second.payload)

        core.removePeer(peerID)
        assertFalse(requireNotNull(gossip.delegate).hasLivePeer(peerID))
        sent.clear()
        gossip.onPublicPacketSeen(packet)
        gossip.handleRequestSync(requester, RequestSyncPacket(7, 1, ByteArray(0)))
        assertTrue("removed sender must not refill the archive", sent.isEmpty())
    }
}
