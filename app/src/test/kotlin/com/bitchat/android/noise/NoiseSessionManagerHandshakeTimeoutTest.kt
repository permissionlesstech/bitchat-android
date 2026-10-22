package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.Noise
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class NoiseSessionManagerHandshakeTimeoutTest {
    private data class TestIdentity(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
        val peerID: String
    )

    private val managers = mutableListOf<NoiseSessionManager>()

    @After
    fun tearDown() {
        managers.forEach(NoiseSessionManager::shutdown)
    }

    @Test
    fun `stale initiator handshake is expired and reported as timeout`() {
        val alice = identity()
        val bob = identity()
        val aliceManager = manager(alice)
        val failure = AtomicReference<Throwable?>()
        aliceManager.onSessionFailed = { peerID, error ->
            if (peerID == bob.peerID) failure.set(error)
        }

        assertNotNull(aliceManager.initiateHandshake(bob.peerID))
        assertTrue(aliceManager.getSession(bob.peerID)!!.isHandshaking())

        aliceManager.cleanupStaleHandshakes(System.currentTimeMillis() + 11_000)

        assertNull(aliceManager.getSession(bob.peerID))
        assertTrue(failure.get() is NoiseSessionError.HandshakeTimeout)
    }

    @Test
    fun `fresh handshake is not expired`() {
        val alice = identity()
        val bob = identity()
        val aliceManager = manager(alice)

        assertNotNull(aliceManager.initiateHandshake(bob.peerID))

        aliceManager.cleanupStaleHandshakes(System.currentTimeMillis() + 9_000)

        assertTrue(aliceManager.getSession(bob.peerID)!!.isHandshaking())
    }

    @Test
    fun `expired handshake can be re-initiated immediately`() {
        val alice = identity()
        val bob = identity()
        val aliceManager = manager(alice)

        assertNotNull(aliceManager.initiateHandshake(bob.peerID))
        aliceManager.cleanupStaleHandshakes(System.currentTimeMillis() + 11_000)

        assertNotNull("Handshake must restart after expiry", aliceManager.initiateHandshake(bob.peerID))
        assertTrue(aliceManager.getSession(bob.peerID)!!.isHandshaking())
    }

    @Test
    fun `established session is never expired by the sweep`() {
        val alice = identity()
        val bob = identity()
        val aliceManager = manager(alice)
        val bobManager = manager(bob)
        completeHandshake(aliceManager, alice.peerID, bobManager, bob.peerID)
        val aliceSession = aliceManager.getSession(bob.peerID)
        val bobSession = bobManager.getSession(alice.peerID)

        aliceManager.cleanupStaleHandshakes(System.currentTimeMillis() + 60_000)
        bobManager.cleanupStaleHandshakes(System.currentTimeMillis() + 60_000)

        assertSame(aliceSession, aliceManager.getSession(bob.peerID))
        assertSame(bobSession, bobManager.getSession(alice.peerID))
        assertTrue(aliceManager.hasEstablishedSession(bob.peerID))
        assertTrue(bobManager.hasEstablishedSession(alice.peerID))

        val plaintext = "still alive".toByteArray()
        val ciphertext = aliceManager.encrypt(plaintext, bob.peerID)
        assertArrayEquals(plaintext, bobManager.decrypt(ciphertext, alice.peerID))
    }

    @Test
    fun `stale responder candidate expires while established session survives`() {
        val alice = identity()
        val bob = identity()
        val aliceManager = manager(alice)
        val bobManager = manager(bob)
        completeHandshake(aliceManager, alice.peerID, bobManager, bob.peerID)
        val established = aliceManager.getSession(bob.peerID)

        // Bob comes back and starts a replacement handshake: alice keeps the established
        // session and parks the new handshake as a responder candidate.
        val replacementManager = manager(bob)
        val message1 = replacementManager.initiateHandshake(alice.peerID)!!
        assertNotNull(aliceManager.processHandshakeMessage(bob.peerID, message1))
        assertSame(established, aliceManager.getSession(bob.peerID))

        // Bob never finishes (message 2 lost): the candidate must expire without touching
        // the working session.
        aliceManager.cleanupStaleHandshakes(System.currentTimeMillis() + 11_000)

        assertSame(established, aliceManager.getSession(bob.peerID))
        assertTrue(aliceManager.hasEstablishedSession(bob.peerID))
        val plaintext = "unharmed".toByteArray()
        val ciphertext = aliceManager.encrypt(plaintext, bob.peerID)
        assertArrayEquals(plaintext, bobManager.decrypt(ciphertext, alice.peerID))
    }

    private fun completeHandshake(
        initiator: NoiseSessionManager,
        initiatorPeerID: String,
        responder: NoiseSessionManager,
        responderPeerID: String
    ) {
        val message1 = initiator.initiateHandshake(responderPeerID)!!
        val message2 = responder.processHandshakeMessage(initiatorPeerID, message1)!!
        val message3 = initiator.processHandshakeMessage(responderPeerID, message2)!!
        assertNull(responder.processHandshakeMessage(initiatorPeerID, message3))
    }

    private fun manager(identity: TestIdentity): NoiseSessionManager = NoiseSessionManager(
        localStaticPrivateKey = identity.privateKey,
        localStaticPublicKey = identity.publicKey,
        localPeerID = identity.peerID
    ).also { managers += it }

    private fun identity(): TestIdentity {
        val dh = Noise.createDH("25519")
        return try {
            dh.generateKeyPair()
            val privateKey = ByteArray(32)
            val publicKey = ByteArray(32)
            dh.getPrivateKey(privateKey, 0)
            dh.getPublicKey(publicKey, 0)
            TestIdentity(privateKey, publicKey, NoisePeerIdentity.derivePeerID(publicKey)!!)
        } finally {
            dh.destroy()
        }
    }
}
