package com.bitchat.android.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Channel password crypto: PBKDF2 + AES-256-GCM roundtrip and key commitments.
 */
class NoiseChannelEncryptionTest {

    private lateinit var encryption: NoiseChannelEncryption

    @Before
    fun setup() {
        encryption = NoiseChannelEncryption()
    }

    @Test
    fun encryptDecryptRoundtrip() {
        val channel = "#secret"
        val password = "correct-horse-battery"
        val plaintext = "hello from the mesh"

        encryption.setChannelPassword(password, channel)
        val encrypted = encryption.encryptChannelMessage(plaintext, channel)
        assertTrue(encrypted.size > 12)

        val decrypted = encryption.decryptChannelMessage(encrypted, channel)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun wrongPasswordFailsDecrypt() {
        val channel = "#secret"
        encryption.setChannelPassword("right-password", channel)
        val encrypted = encryption.encryptChannelMessage("classified", channel)

        val other = NoiseChannelEncryption()
        other.setChannelPassword("wrong-password", channel)

        try {
            other.decryptChannelMessage(encrypted, channel)
            throw AssertionError("Expected decrypt to fail with wrong password")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun keyCommitmentMatchesSamePassword() {
        val channel = "#ops"
        encryption.setChannelPassword("shared-secret", channel)
        val commitment = encryption.calculateKeyCommitment(channel)
        assertNotNull(commitment)
        assertTrue(encryption.verifyKeyCommitment(channel, commitment!!))

        val peer = NoiseChannelEncryption()
        peer.setChannelPassword("shared-secret", channel)
        assertTrue(peer.verifyKeyCommitment(channel, commitment))
    }

    @Test
    fun keyCommitmentRejectsWrongPassword() {
        val channel = "#ops"
        encryption.setChannelPassword("shared-secret", channel)
        val commitment = encryption.calculateKeyCommitment(channel)!!

        val peer = NoiseChannelEncryption()
        peer.setChannelPassword("different-secret", channel)
        assertFalse(peer.verifyKeyCommitment(channel, commitment))
        assertNotEquals(commitment, peer.calculateKeyCommitment(channel))
    }

    @Test
    fun samePasswordSameChannelDerivesSameKey() {
        val channel = "#room"
        val password = "pw"

        encryption.setChannelPassword(password, channel)
        val first = encryption.calculateKeyCommitment(channel)

        val again = NoiseChannelEncryption()
        again.setChannelPassword(password, channel)
        assertEquals(first, again.calculateKeyCommitment(channel))
    }
}
