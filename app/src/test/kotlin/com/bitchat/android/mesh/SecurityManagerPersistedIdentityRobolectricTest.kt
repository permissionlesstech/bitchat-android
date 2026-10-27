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
        // Keep the production verifier; only AndroidKeyStore initialization is excluded.
        val encryption = object : EncryptionService(context) {
            override fun initialize() = Unit
        }
        delegate = mock()
        whenever(delegate.getPersistedSigningKey(any())).thenAnswer {
            coordinator.persistedSigningKeyFor(it.getArgument<String>(0))
        }
        manager = SecurityManager(encryption, "ffffffffffffffff")
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

    private fun signedPacket(key: Ed25519PrivateKeyParameters = signingKey): BitchatPacket {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 0u,
            senderID = peerID,
            payload = "synthetic replay".toByteArray()
        )
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
}
