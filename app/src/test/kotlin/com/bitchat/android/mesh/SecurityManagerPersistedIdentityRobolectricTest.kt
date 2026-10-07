package com.bitchat.android.mesh

import android.content.Context
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.model.RoutedPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class SecurityManagerPersistedIdentityRobolectricTest {
    private val fixtureTime = 1_800_000_000_000L
    private val noiseKey = ByteArray(32) { (it + 1).toByte() }
    private val fingerprint = MessageDigest.getInstance("SHA-256").digest(noiseKey)
        .joinToString("") { "%02x".format(it) }
    private val peerID = fingerprint.take(16)
    private val signingKey = Ed25519PrivateKeyParameters(ByteArray(32) { 0x35 }, 0)
    private val wrongKey = Ed25519PrivateKeyParameters(ByteArray(32) { 0x46 }, 0)
    private lateinit var identity: SecureIdentityStateManager
    private lateinit var manager: SecurityManager
    private lateinit var delegate: SecurityManagerDelegate
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("persisted-signature-regression", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        identity = SecureIdentityStateManager(prefs, testOnly = true)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = AuthenticatedPeerStateCoordinator(
            scope = scope,
            authenticatedSessionProvider = { null },
            withAuthenticatedSession = { _, _, _ -> false },
            store = SecureAuthenticatedPeerStateStore(context, identity),
            localStateProvider = { AuthenticatedPeerState(PeerCapabilities.NONE, signingKey.generatePublicKey().encoded) },
            applyAuthenticatedState = { _, _, _ -> },
            sendState = { _, _, _ -> false },
            onResolution = {}
        )
        // Keep the production verifier; bypass EncryptionService initialization.
        val encryption = object : EncryptionService(context) {
            override fun initialize() = Unit
        }
        delegate = mock()
        whenever(delegate.getPersistedSigningKey(any())).thenAnswer {
            coordinator.persistedSigningKeyFor(it.getArgument<String>(0))
        }
        manager = SecurityManager(encryption, "ffffffffffffffff") { fixtureTime }
        manager.delegate = delegate
    }

    @After
    fun tearDown() {
        manager.shutdown()
        scope.cancel()
    }

    private fun persistKey() {
        assertTrue(identity.storeAuthenticatedPeerState(
            fingerprint, AuthenticatedPeerState(PeerCapabilities.NONE, signingKey.generatePublicKey().encoded)
        ))
    }

    private fun signedPacket(key: Ed25519PrivateKeyParameters = signingKey, ageMs: Long = 0L): BitchatPacket {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 0u,
            senderID = peerID,
            payload = "synthetic replay".toByteArray()
        ).copy(timestamp = (fixtureTime - ageMs).toULong())
        val bytes = requireNotNull(packet.toBinaryDataForSigning())
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(bytes, 0, bytes.size)
        packet.signature = signer.generateSignature()
        return packet
    }

    @Test
    fun `a departed sender passes using a real signature and the persisted store`() {
        persistKey()
        assertTrue(manager.validatePacket(signedPacket(), peerID))
    }

    @Test
    fun `a real signature without a stored identity is rejected`() {
        assertFalse(manager.validatePacket(signedPacket(), peerID))
    }

    @Test
    fun `a different signing key cannot impersonate a persisted sender`() {
        persistKey()
        assertFalse(manager.validatePacket(signedPacket(wrongKey), peerID))
    }

    @Test
    fun `changing the payload after signing is rejected with a persisted identity`() {
        persistKey()
        val packet = signedPacket().copy(payload = "changed payload".toByteArray())
        assertFalse(manager.validatePacket(packet, peerID))
    }

    @Test
    fun `an unsigned packet is rejected with a persisted identity`() {
        persistKey()
        assertFalse(manager.validatePacket(signedPacket().copy(signature = null), peerID))
    }

    @Test
    fun `a failed live key is not retried against the persisted key`() {
        persistKey()
        whenever(delegate.getPeerInfo(peerID)).thenReturn(PeerInfo(
            id = peerID, nickname = "synthetic", isConnected = true,
            isDirectConnection = true, noisePublicKey = noiseKey,
            signingPublicKey = wrongKey.generatePublicKey().encoded,
            isVerifiedNickname = true, lastSeen = 0L
        ))
        assertFalse(manager.validatePacket(signedPacket(), peerID))
    }

    @Test
    fun `a rejected forgery does not suppress a later authentic copy`() {
        persistKey()
        val packet = signedPacket()
        assertFalse(manager.validatePacket(packet.copy(signature = ByteArray(64)), peerID))
        assertTrue(manager.validatePacket(packet, peerID))
    }

    @Test
    fun `panic wipe removes the persisted verification fallback`() {
        persistKey()
        identity.clearIdentityData()
        assertFalse(manager.validatePacket(signedPacket(), peerID))
    }

    @Test
    fun `requested history rejects unsigned and tampered packets with a real verifier`() {
        persistKey()
        val neighbor = "aaaabbbbccccdddd"
        whenever(delegate.peerIDForRelayAddress("synthetic-link")).thenReturn(neighbor)
        whenever(delegate.isValidSyncResponse(neighbor)).thenReturn(true)
        var age = 3_600_000L
        for (marked in listOf(false, true)) {
            for (unsigned in listOf(false, true)) {
                val original = signedPacket(ageMs = age++).copy(ttl = 0u, isRSR = marked)
                val invalid = if (unsigned) original.copy(signature = null)
                    else original.copy(payload = "tampered history".toByteArray())
                assertFalse(manager.validatePacket(invalid, peerID, "synthetic-link"))
                assertTrue(manager.validatePacket(original, peerID, "synthetic-link"))
            }
        }
    }

    @Test
    fun `requested history from a departed sender survives packet processing and real signature verification`() = runBlocking {
        persistKey()
        val neighbor = "aaaabbbbccccdddd"
        var elapsed = 10_000L
        val gossip = GossipSyncManager("ffffffffffffffff", scope,
            object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity() = 100
                override fun gcsMaxBytes() = 400
                override fun gcsTargetFpr() = 0.01
            }, Dispatchers.Unconfined, { elapsed })
        var requests = 0
        gossip.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) = Unit
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { requests++ }
            override fun signPacketForBroadcast(packet: BitchatPacket) = packet
        }
        whenever(delegate.peerIDForRelayAddress("synthetic-link")).thenReturn(neighbor)
        whenever(delegate.isValidSyncResponse(neighbor)).thenAnswer { gossip.isValidSyncResponse(neighbor) }
        gossip.scheduleInitialSyncToPeer(neighbor, 0)
        assertEquals(1, requests)
        val packet = signedPacket(ageMs = 3_600_000).copy(isRSR = true)
        val received = CompletableDeferred<RoutedPacket>()
        val processor = PacketProcessor("ffffffffffffffff")
        val dispatch = mock<PacketProcessorDelegate>()
        whenever(dispatch.validatePacketSecurity(any(), any(), anyOrNull())).thenAnswer {
            manager.validatePacket(it.getArgument(0), it.getArgument(1), it.getArgument(2))
        }
        doAnswer { received.complete(it.getArgument(0)); null }.whenever(dispatch).handleMessage(any())
        processor.delegate = dispatch
        try {
            processor.processPacket(RoutedPacket(packet, peerID, "synthetic-link"))
            assertEquals(packet, withTimeout(2_000) { received.await() }.packet)
            val reassembled = signedPacket(ageMs = 3_600_002).copy(isRSR = true)
            val fragmentDelivery = CompletableDeferred<RoutedPacket>()
            whenever(dispatch.handleFragment(any())).thenReturn(reassembled)
            doAnswer { fragmentDelivery.complete(it.getArgument(0)); null }.whenever(dispatch).handleMessage(any())
            // Isolate PacketProcessor's reassembly handoff; FragmentManager has separate wire tests.
            processor.processPacket(RoutedPacket(packet.copy(
                type = MessageType.FRAGMENT.value, payload = byteArrayOf(0x44)
            ), peerID, "synthetic-link"))
            assertEquals(reassembled, withTimeout(2_000) { fragmentDelivery.await() }.packet)
            elapsed += 60_001
            assertFalse(manager.validatePacket(signedPacket(ageMs = 3_600_001).copy(isRSR = true), peerID, "synthetic-link"))
        } finally { processor.shutdown() }
    }
}
