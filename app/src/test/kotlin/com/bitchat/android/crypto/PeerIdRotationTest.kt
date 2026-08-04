package com.bitchat.android.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerIdRotationTest {
    @Test
    fun `section 7 rotation vectors match byte for byte`() {
        val privateKey = ByteArray(32) { (it + 1).toByte() }

        val secret = PeerIdRotation.rotationSecret(privateKey)
        val peerId = PeerIdRotation.peerId(secret, 100u)

        assertArrayEquals(
            "fb82dfec0c0a2a4677beca44e2f72c80e7c5de773dd5fce6ee47af83d3c25f09".hex(),
            secret
        )
        assertArrayEquals("f7c08c528506a374".hex(), peerId)
    }

    @Test
    fun `section 7 directional recognition vectors match byte for byte`() {
        val key = PeerIdRotation.recognitionKey(ByteArray(32) { 0x42 })
        val announcedId = ByteArray(8) { 0xA1.toByte() }

        val aToB = PeerIdRotation.recognitionTag(
            key, 100u, ByteArray(32) { 0x0A }, ByteArray(32) { 0x0B }, announcedId
        )
        val bToA = PeerIdRotation.recognitionTag(
            key, 100u, ByteArray(32) { 0x0B }, ByteArray(32) { 0x0A }, announcedId
        )

        assertArrayEquals("4568f61d61d6cbfb".hex(), aToB)
        assertArrayEquals("5313c7731f629959".hex(), bToA)
        assertFalse(aToB.contentEquals(bToA))
    }

    @Test
    fun `real X25519 peers derive the same recognition key`() {
        val aPrivate = X25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
        val bPrivate = X25519PrivateKeyParameters(ByteArray(32) { (0x40 + it).toByte() }, 0)
        val aPublic = aPrivate.generatePublicKey().encoded
        val bPublic = bPrivate.generatePublicKey().encoded

        val fromA = PeerIdRotation.recognitionKey(
            PeerIdRotation.x25519SharedSecret(aPrivate.encoded, bPublic)
        )
        val fromB = PeerIdRotation.recognitionKey(
            PeerIdRotation.x25519SharedSecret(bPrivate.encoded, aPublic)
        )

        assertArrayEquals(fromA, fromB)
    }

    @Test
    fun `epoch window and tag block pin matching properties`() {
        assertTrue(PeerIdRotation.candidateEpochs(100u) == listOf(99u, 100u, 101u))
        assertTrue(PeerIdRotation.isEpochAccepted(99u, 100u))
        assertTrue(PeerIdRotation.isEpochAccepted(101u, 100u))
        assertFalse(PeerIdRotation.isEpochAccepted(102u, 100u))
        assertNotEquals(
            PeerIdRotation.peerId(ByteArray(32) { 1 }, 100u).toList(),
            PeerIdRotation.peerId(ByteArray(32) { 1 }, 101u).toList()
        )

        val tag = "4568f61d61d6cbfb".hex()
        val block = PeerIdRotation.paddedTagBlock(listOf(tag), DeterministicSecureRandom())
        assertTrue(block.size == 64)
        assertTrue(PeerIdRotation.tagBlockContains(block, tag))
    }

    @Test
    fun `joint binding signature vector is fixed and verifies`() {
        val signingPrivateKey = ByteArray(32) { (it + 1).toByte() }
        val signingPublicKey = Ed25519PrivateKeyParameters(signingPrivateKey, 0).generatePublicKey().encoded
        val peerId = "f7c08c528506a374".hex()
        val noisePublicKey = X25519PrivateKeyParameters(signingPrivateKey, 0).generatePublicKey().encoded

        val message = PeerIdRotation.bindingMessage(100u, peerId, noisePublicKey)
        val signature = PeerIdRotation.signBinding(signingPrivateKey, 100u, peerId, noisePublicKey)

        assertTrue(message.size == 69)
        assertArrayEquals(JOINT_BINDING_SIGNATURE.hex(), signature)
        assertTrue(PeerIdRotation.verifyBindingSignature(signature, signingPublicKey, 100u, peerId, noisePublicKey))
        assertFalse(PeerIdRotation.verifyBindingSignature(signature, signingPublicKey, 101u, peerId, noisePublicKey))
    }

    private class DeterministicSecureRandom : java.security.SecureRandom() {
        private var next = 0
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { bytes[it] = next++.toByte() }
        }
    }

    companion object {
        // Seed 01..20, epoch 100, ID f7c08c528506a374, and the X25519 public key
        // derived from the same 32-byte seed. Independently generated from the spec.
        private const val JOINT_BINDING_SIGNATURE =
            "5b1235d4fd4ff0bdef76007578dc83141e34a2014f249aeb5df3e50c7d30199" +
                "b2fe1c92f7a3674a6170a1b8db3e9213aeac6fa3690fb4b5e7e4432ca69d28a0e"

        private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
