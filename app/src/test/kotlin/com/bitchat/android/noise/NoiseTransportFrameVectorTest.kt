package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.Noise
import com.bitchat.android.util.dataFromHexString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseTransportFrameVectorTest {

    private fun newTransportSession(
        isInitiator: Boolean,
        sendKey: ByteArray,
        receiveKey: ByteArray,
        initialSendNonce: Long = 0L
    ): NoiseSession {
        val dummyKey = ByteArray(32) { 0x11 }
        val session = NoiseSession("0102030405060708", isInitiator, dummyKey, dummyKey)
        val sendCipher = Noise.createCipher("ChaChaPoly").apply { initializeKey(sendKey, 0) }
        val receiveCipher = Noise.createCipher("ChaChaPoly").apply { initializeKey(receiveKey, 0) }
        NoiseSession::class.java.getDeclaredField("sendCipher").apply {
            isAccessible = true
            set(session, sendCipher)
        }
        NoiseSession::class.java.getDeclaredField("receiveCipher").apply {
            isAccessible = true
            set(session, receiveCipher)
        }
        NoiseSession::class.java.getDeclaredField("messagesSent").apply {
            isAccessible = true
            setLong(session, initialSendNonce)
        }
        NoiseSession::class.java.getDeclaredField("state").apply {
            isAccessible = true
            set(session, NoiseSession.NoiseSessionState.Established)
        }
        return session
    }

    @Test
    fun `NoiseSession transport encrypt and decrypt match independent RFC 8439 ChaCha20-Poly1305 golden vector with nonzero nonce`() {
        val transportKey = ByteArray(32) { it.toByte() }
        val plaintext = "bitchat-noise-transport-vector".toByteArray(Charsets.UTF_8)
        val nonce = 0x00000102L
        // Computed independently via JDK RFC 8439 ChaCha20-Poly1305 with:
        // key = 000102...1f, 12-byte Noise IV = 00000000 || LE64(0x00000102), AD = empty,
        // wire frame = BE32(0x00000102) || ciphertext || 16-byte Poly1305 tag.
        val expectedWireFrame = (
            "00000102" +
                "7fdc8c5e79eb9fbe3fe2260f50800ef416c308706c833707efdb0f9422456b44" +
                "9d65ef2c2cb05224827ea8812ec2"
            ).dataFromHexString()!!

        val sender = newTransportSession(
            isInitiator = true,
            sendKey = transportKey,
            receiveKey = transportKey,
            initialSendNonce = nonce
        )
        val receiver = newTransportSession(
            isInitiator = false,
            sendKey = transportKey,
            receiveKey = transportKey
        )
        try {
            val encrypted = sender.encrypt(plaintext)
            assertArrayEquals(expectedWireFrame, encrypted)

            val decrypted = receiver.decrypt(expectedWireFrame)
            assertArrayEquals(plaintext, decrypted)

            // Replaying the exact same frame must fail closed
            assertThrows(SessionError.DecryptionFailed::class.java) {
                receiver.decrypt(expectedWireFrame)
            }

            // Flipping a bit in nonce prefix, ciphertext, or Poly1305 tag on a fresh receiver
            // exercises AEAD authentication directly (before replay window marks nonce seen).
            for (bitOffset in listOf(3, 4, expectedWireFrame.lastIndex)) {
                val freshReceiver = newTransportSession(
                    isInitiator = false,
                    sendKey = transportKey,
                    receiveKey = transportKey
                )
                try {
                    val tampered = expectedWireFrame.copyOf()
                    tampered[bitOffset] = (tampered[bitOffset].toInt() xor 0x01).toByte()
                    assertThrows(SessionError.DecryptionFailed::class.java) {
                        freshReceiver.decrypt(tampered)
                    }
                } finally {
                    freshReceiver.destroy()
                }
            }
        } finally {
            sender.destroy()
            receiver.destroy()
        }
    }

    @Test
    fun `NoiseSession replay window rejects previously accepted nonces across window advances`() {
        val dhA = Noise.createDH("25519").apply { generateKeyPair() }
        val dhB = Noise.createDH("25519").apply { generateKeyPair() }
        val privA = ByteArray(32).also { dhA.getPrivateKey(it, 0) }
        val pubA = ByteArray(32).also { dhA.getPublicKey(it, 0) }
        val privB = ByteArray(32).also { dhB.getPrivateKey(it, 0) }
        val pubB = ByteArray(32).also { dhB.getPublicKey(it, 0) }

        val peerIdA = NoisePeerIdentity.derivePeerID(pubA)!!
        val peerIdB = NoisePeerIdentity.derivePeerID(pubB)!!
        val initiator = NoiseSession(peerIdB, isInitiator = true, localStaticPrivateKey = privA, localStaticPublicKey = pubA)
        val responder = NoiseSession(peerIdA, isInitiator = false, localStaticPrivateKey = privB, localStaticPublicKey = pubB)
        try {
            val m1 = initiator.startHandshake()
            val m2 = responder.processHandshakeMessage(m1)!!
            val m3 = initiator.processHandshakeMessage(m2)!!
            assertNull(responder.processHandshakeMessage(m3))
            assertTrue(initiator.isEstablished() && responder.isEstablished())

            val ciphertexts = List(10) { idx -> initiator.encrypt("msg-$idx".toByteArray()) }

            // Deliver 0, 1, 2 -> window shifts within byte 0
            assertEquals("msg-0", String(responder.decrypt(ciphertexts[0])))
            assertEquals("msg-1", String(responder.decrypt(ciphertexts[1])))
            assertEquals("msg-2", String(responder.decrypt(ciphertexts[2])))

            assertThrows(SessionError.DecryptionFailed::class.java) { responder.decrypt(ciphertexts[0]) }
            assertThrows(SessionError.DecryptionFailed::class.java) { responder.decrypt(ciphertexts[1]) }

            // Advance across byte boundary (nonce 9 -> offset 9 for nonce 0, offset 7 for nonce 2)
            assertEquals("msg-9", String(responder.decrypt(ciphertexts[9])))

            assertThrows(SessionError.DecryptionFailed::class.java) { responder.decrypt(ciphertexts[0]) }

            // Unseen out-of-order nonce 5 succeeds once and fails on replay
            assertEquals("msg-5", String(responder.decrypt(ciphertexts[5])))
            assertThrows(SessionError.DecryptionFailed::class.java) { responder.decrypt(ciphertexts[5]) }
        } finally {
            initiator.destroy()
            responder.destroy()
        }
    }
}
