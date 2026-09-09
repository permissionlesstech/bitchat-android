package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.Noise
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NoiseSessionManagerIdentityBindingTest {
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
    fun `valid derived identities establish in initiator and responder roles`() {
        val alice = identity()
        val bob = identity()
        val aliceManager = manager(alice)
        val bobManager = manager(bob)

        completeHandshake(aliceManager, alice.peerID, bobManager, bob.peerID)

        assertTrue(aliceManager.hasEstablishedSession(bob.peerID))
        assertTrue(bobManager.hasEstablishedSession(alice.peerID))
        assertArrayEquals(bob.publicKey, aliceManager.getRemoteStaticKey(bob.peerID))
        assertArrayEquals(alice.publicKey, bobManager.getRemoteStaticKey(alice.peerID))

        val plaintext = "bound transport".toByteArray()
        val aliceSession = aliceManager.getAuthenticatedSession(bob.peerID)!!
        val bobSession = bobManager.getAuthenticatedSession(alice.peerID)!!
        val ciphertext = aliceManager.encryptForSession(plaintext, bob.peerID, aliceSession)
        val decrypted = bobManager.decryptWithSession(ciphertext, alice.peerID)
        assertArrayEquals(plaintext, decrypted.plaintext)
        assertArrayEquals(bobSession.sessionToken, decrypted.authenticatedSession.sessionToken)
    }

    @Test
    fun `initiator rejects remote static key before returning message three`() {
        val alice = identity()
        val bob = identity()
        val victim = identity()
        val aliceManager = manager(alice)
        val bobManager = manager(bob)
        var authenticatedCallbacks = 0
        aliceManager.onSessionEstablished = { _, _ -> authenticatedCallbacks += 1 }

        val message1 = aliceManager.initiateHandshake(victim.peerID)!!
        val message2 = bobManager.processHandshakeMessage(alice.peerID, message1)!!

        expectIdentityMismatch {
            aliceManager.processHandshakeMessage(victim.peerID, message2)
        }

        assertFalse(aliceManager.hasEstablishedSession(victim.peerID))
        assertNull(aliceManager.getSession(victim.peerID))
        assertTrue("No authenticated callback may escape a mismatched initiator", authenticatedCallbacks == 0)
    }

    @Test
    fun `responder rejects authenticated initiator key under a different claimed ID`() {
        val alice = identity()
        val bob = identity()
        val victim = identity()
        val aliceManager = manager(alice)
        val bobManager = manager(bob)
        var authenticatedCallbacks = 0
        bobManager.onSessionEstablished = { _, _ -> authenticatedCallbacks += 1 }

        val message1 = aliceManager.initiateHandshake(bob.peerID)!!
        val message2 = bobManager.processHandshakeMessage(victim.peerID, message1)!!
        val message3 = aliceManager.processHandshakeMessage(bob.peerID, message2)!!

        expectIdentityMismatch {
            bobManager.processHandshakeMessage(victim.peerID, message3)
        }

        assertFalse(bobManager.hasEstablishedSession(victim.peerID))
        assertNull(bobManager.getSession(victim.peerID))
        assertTrue("No authenticated callback may escape a mismatched responder", authenticatedCallbacks == 0)
    }

    @Test
    fun `mismatched responder replacement preserves established session and transport keys`() {
        val alice = identity()
        val bob = identity()
        val attacker = identity()
        val aliceManager = manager(alice)
        val bobManager = manager(bob)
        val attackerManager = manager(attacker)
        var aliceAuthenticatedCallbacks = 0
        aliceManager.onSessionEstablished = { _, _ -> aliceAuthenticatedCallbacks += 1 }

        completeHandshake(aliceManager, alice.peerID, bobManager, bob.peerID)
        val originalSession = aliceManager.getSession(bob.peerID)
        assertTrue(aliceAuthenticatedCallbacks == 1)

        val attackerMessage1 = attackerManager.initiateHandshake(alice.peerID)!!
        val aliceMessage2 = aliceManager.processHandshakeMessage(bob.peerID, attackerMessage1)!!
        val attackerMessage3 = attackerManager.processHandshakeMessage(alice.peerID, aliceMessage2)!!

        expectIdentityMismatch {
            aliceManager.processHandshakeMessage(bob.peerID, attackerMessage3)
        }

        assertSame("Rejected candidate must not replace the working session", originalSession, aliceManager.getSession(bob.peerID))
        assertTrue(aliceManager.hasEstablishedSession(bob.peerID))
        assertArrayEquals(bob.publicKey, aliceManager.getRemoteStaticKey(bob.peerID))
        assertTrue("Rejected replacement must not fire authentication callback", aliceAuthenticatedCallbacks == 1)

        val plaintext = "original session survives".toByteArray()
        val ciphertext = aliceManager.encrypt(plaintext, bob.peerID)
        assertArrayEquals(plaintext, bobManager.decrypt(ciphertext, alice.peerID))

        // The failed candidate must be fully removed so a later valid restart can replace cleanly.
        val restartedBobManager = manager(bob)
        val validMessage1 = restartedBobManager.initiateHandshake(alice.peerID)!!
        val validMessage2 = aliceManager.processHandshakeMessage(bob.peerID, validMessage1)!!
        val validMessage3 = restartedBobManager.processHandshakeMessage(alice.peerID, validMessage2)!!
        assertNull(aliceManager.processHandshakeMessage(bob.peerID, validMessage3))
        assertTrue(aliceAuthenticatedCallbacks == 2)

        val retriedPlaintext = "valid retry promoted".toByteArray()
        val retriedCiphertext = restartedBobManager.encrypt(retriedPlaintext, alice.peerID)
        assertArrayEquals(retriedPlaintext, aliceManager.decrypt(retriedCiphertext, bob.peerID))
    }

    @Test
    fun `valid responder replacement promotes only after bound handshake completes`() {
        val alice = identity()
        val bob = identity()
        val aliceManager = manager(alice)
        val originalBobManager = manager(bob)
        var aliceAuthenticatedCallbacks = 0
        aliceManager.onSessionEstablished = { _, _ -> aliceAuthenticatedCallbacks += 1 }

        completeHandshake(aliceManager, alice.peerID, originalBobManager, bob.peerID)
        val originalSession = aliceManager.getSession(bob.peerID)
        val originalBinding = aliceManager.getAuthenticatedSession(bob.peerID)!!

        // Simulate Bob restarting with the same persistent static identity and no session state.
        val restartedBobManager = manager(bob)
        val message1 = restartedBobManager.initiateHandshake(alice.peerID)!!
        val message2 = aliceManager.processHandshakeMessage(bob.peerID, message1)!!
        val message3 = restartedBobManager.processHandshakeMessage(alice.peerID, message2)!!
        assertNull(aliceManager.processHandshakeMessage(bob.peerID, message3))

        assertNotSame(originalSession, aliceManager.getSession(bob.peerID))
        assertTrue(aliceManager.hasEstablishedSession(bob.peerID))
        assertArrayEquals(bob.publicKey, aliceManager.getRemoteStaticKey(bob.peerID))
        assertTrue(aliceAuthenticatedCallbacks == 2)
        val replacementBinding = aliceManager.getAuthenticatedSession(bob.peerID)!!
        assertFalse(originalBinding.sessionToken.contentEquals(replacementBinding.sessionToken))
        try {
            aliceManager.encryptForSession(
                "stale generation".toByteArray(),
                bob.peerID,
                originalBinding
            )
            fail("Expected stale generation-bound encryption to be rejected")
        } catch (_: NoiseSessionError.SessionGenerationChanged) {
            // Expected.
        }

        val plaintext = "replacement transport".toByteArray()
        val ciphertext = restartedBobManager.encryptForSession(
            plaintext,
            alice.peerID,
            restartedBobManager.getAuthenticatedSession(alice.peerID)!!
        )
        assertArrayEquals(plaintext, aliceManager.decrypt(ciphertext, bob.peerID))
    }

    @Test
    fun `generation lease blocks replacement and rejects stale token afterward`() {
        val alice = identity()
        val bob = identity()
        val aliceManager = manager(alice)
        val originalBobManager = manager(bob)
        completeHandshake(aliceManager, alice.peerID, originalBobManager, bob.peerID)
        val originalBinding = aliceManager.getAuthenticatedSession(bob.peerID)!!

        val restartedBobManager = manager(bob)
        val message1 = restartedBobManager.initiateHandshake(alice.peerID)!!
        val message2 = aliceManager.processHandshakeMessage(bob.peerID, message1)!!
        val message3 = restartedBobManager.processHandshakeMessage(alice.peerID, message2)!!

        val leaseEntered = CountDownLatch(1)
        val releaseLease = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val replacementCompleted = CountDownLatch(1)
        val replacementFinished = AtomicBoolean(false)
        val threadFailure = AtomicReference<Throwable?>(null)
        val leaseThread = Thread {
            try {
                assertTrue(
                    aliceManager.withAuthenticatedSession(bob.peerID, originalBinding) {
                        leaseEntered.countDown()
                        releaseLease.await(2, TimeUnit.SECONDS)
                    }
                )
            } catch (error: Throwable) {
                threadFailure.set(error)
            }
        }
        val replacementThread = Thread {
            try {
                replacementStarted.countDown()
                aliceManager.processHandshakeMessage(bob.peerID, message3)
                replacementFinished.set(true)
            } catch (error: Throwable) {
                threadFailure.set(error)
            } finally {
                replacementCompleted.countDown()
            }
        }

        leaseThread.start()
        assertTrue(leaseEntered.await(1, TimeUnit.SECONDS))
        replacementThread.start()
        assertTrue(replacementStarted.await(1, TimeUnit.SECONDS))
        try {
            assertFalse(
                "Replacement must wait while the old generation lease is active",
                replacementCompleted.await(100, TimeUnit.MILLISECONDS)
            )
        } finally {
            releaseLease.countDown()
        }
        leaseThread.join(2_000)
        replacementThread.join(2_000)
        assertTrue(replacementCompleted.await(1, TimeUnit.SECONDS))
        threadFailure.get()?.let { throw it }
        assertTrue(replacementFinished.get())

        try {
            aliceManager.encryptForSession(byteArrayOf(1), bob.peerID, originalBinding)
            fail("Expected old token to be rejected after replacement")
        } catch (_: NoiseSessionError.SessionGenerationChanged) {
            // Expected.
        }
    }

    @Test
    fun `fresh initiator replacement preserves active session until authentication completes`() {
        val alice = identity()
        val bob = identity()
        val aliceManager = manager(alice)
        val originalBobManager = manager(bob)

        completeHandshake(aliceManager, alice.peerID, originalBobManager, bob.peerID)
        val originalSession = aliceManager.getSession(bob.peerID)

        val restartedBobManager = manager(bob)
        val message1 = aliceManager.initiateHandshake(bob.peerID, replaceEstablished = true)!!
        assertSame(originalSession, aliceManager.getSession(bob.peerID))
        assertTrue(aliceManager.hasEstablishedSession(bob.peerID))

        val message2 = restartedBobManager.processHandshakeMessage(alice.peerID, message1)!!
        val message3 = aliceManager.processHandshakeMessage(bob.peerID, message2)!!
        assertNull(restartedBobManager.processHandshakeMessage(alice.peerID, message3))

        assertNotSame(originalSession, aliceManager.getSession(bob.peerID))
        val plaintext = "fresh link authenticated".toByteArray()
        val ciphertext = aliceManager.encrypt(plaintext, bob.peerID)
        assertArrayEquals(plaintext, restartedBobManager.decrypt(ciphertext, alice.peerID))
    }

    @Test
    fun `simultaneous initiator replacements use peer ID tie break and complete`() {
        val alice = identity()
        val bob = identity()
        val aliceManager = manager(alice)
        val bobManager = manager(bob)
        completeHandshake(aliceManager, alice.peerID, bobManager, bob.peerID)

        val originalAliceSession = aliceManager.getSession(bob.peerID)
        val originalBobSession = bobManager.getSession(alice.peerID)
        val aliceMessage1 = aliceManager.initiateHandshake(
            bob.peerID,
            replaceEstablished = true
        )!!
        val bobMessage1 = bobManager.initiateHandshake(
            alice.peerID,
            replaceEstablished = true
        )!!

        val aliceCollisionResponse = aliceManager.processHandshakeMessage(
            bob.peerID,
            bobMessage1
        )
        val bobCollisionResponse = bobManager.processHandshakeMessage(
            alice.peerID,
            aliceMessage1
        )

        if (alice.peerID < bob.peerID) {
            assertNull(aliceCollisionResponse)
            val message2 = bobCollisionResponse!!
            val message3 = aliceManager.processHandshakeMessage(bob.peerID, message2)!!
            assertNull(bobManager.processHandshakeMessage(alice.peerID, message3))
        } else {
            assertNull(bobCollisionResponse)
            val message2 = aliceCollisionResponse!!
            val message3 = bobManager.processHandshakeMessage(alice.peerID, message2)!!
            assertNull(aliceManager.processHandshakeMessage(bob.peerID, message3))
        }

        assertNotSame(originalAliceSession, aliceManager.getSession(bob.peerID))
        assertNotSame(originalBobSession, bobManager.getSession(alice.peerID))
        val plaintext = "collision replacement transport".toByteArray()
        val ciphertext = aliceManager.encrypt(plaintext, bob.peerID)
        assertArrayEquals(plaintext, bobManager.decrypt(ciphertext, alice.peerID))
    }

    @Test
    fun `simultaneous handshake collision matrix has one deterministic winner`() {
        val identities = listOf(
            identity("e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1"),
            identity("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893"),
            identity("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"),
            identity("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        )

        identities.indices.forEach { leftIndex ->
            ((leftIndex + 1) until identities.size).forEach { rightIndex ->
                val left = identities[leftIndex]
                val right = identities[rightIndex]
                val leftManager = manager(left)
                val rightManager = manager(right)
                val leftMessage1 = leftManager.initiateHandshake(right.peerID)!!
                val rightMessage1 = rightManager.initiateHandshake(left.peerID)!!

                val leftResponse = leftManager.processHandshakeMessage(right.peerID, rightMessage1)
                val rightResponse = rightManager.processHandshakeMessage(left.peerID, leftMessage1)

                if (left.peerID < right.peerID) {
                    assertNull(leftResponse)
                    val message3 = leftManager.processHandshakeMessage(right.peerID, rightResponse!!)!!
                    assertNull(rightManager.processHandshakeMessage(left.peerID, message3))
                } else {
                    assertNull(rightResponse)
                    val message3 = rightManager.processHandshakeMessage(left.peerID, leftResponse!!)!!
                    assertNull(leftManager.processHandshakeMessage(right.peerID, message3))
                }

                assertTrue(leftManager.hasEstablishedSession(right.peerID))
                assertTrue(rightManager.hasEstablishedSession(left.peerID))
                val payload = "matrix-$leftIndex-$rightIndex".toByteArray()
                assertArrayEquals(
                    payload,
                    rightManager.decrypt(
                        leftManager.encrypt(payload, right.peerID),
                        left.peerID
                    )
                )
            }
        }
    }

    @Test
    fun `peer ID derivation rejects malformed keys and non-wire claims`() {
        val peer = identity()

        assertTrue(NoisePeerIdentity.matchesClaimedPeerID(peer.peerID, peer.publicKey))
        assertFalse(NoisePeerIdentity.matchesClaimedPeerID(peer.peerID.uppercase(), peer.publicKey))
        assertFalse(NoisePeerIdentity.matchesClaimedPeerID("not-a-wire-id", peer.publicKey))
        assertFalse(NoisePeerIdentity.matchesClaimedPeerID(peer.peerID, ByteArray(31)))
        assertNull(NoisePeerIdentity.derivePeerID(ByteArray(31)))
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

    private fun expectIdentityMismatch(block: () -> Unit) {
        try {
            block()
            fail("Expected authenticated Noise key to be rejected for the claimed peer ID")
        } catch (_: NoiseSessionError.PeerIdentityMismatch) {
            // Expected.
        }
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

    private fun identity(privateKeyHex: String): TestIdentity {
        val privateKey = privateKeyHex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        val dh = Noise.createDH("25519")
        return try {
            dh.setPrivateKey(privateKey, 0)
            val publicKey = ByteArray(32)
            dh.getPublicKey(publicKey, 0)
            TestIdentity(privateKey, publicKey, NoisePeerIdentity.derivePeerID(publicKey)!!)
        } finally {
            dh.destroy()
        }
    }
}
