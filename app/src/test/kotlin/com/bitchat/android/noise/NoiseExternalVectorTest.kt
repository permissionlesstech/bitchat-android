package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.CipherState
import com.bitchat.android.noise.southernstorm.protocol.HandshakeState
import com.bitchat.android.noise.southernstorm.protocol.Noise
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Cacophony/Noise-C vector for Noise_XX_25519_ChaChaPoly_SHA256.
 *
 * This exercises the vendored Noise state machine directly, independent of managers, Android
 * storage, and generated keys.
 */
class NoiseExternalVectorTest {
    private val messages = listOf(
        VectorMessage(
            "4c756477696720766f6e204d69736573",
            "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944" +
                "4c756477696720766f6e204d69736573"
        ),
        VectorMessage(
            "4d757272617920526f746862617264",
            "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843" +
                "81cbad1f276e038c48378ffce2b65285e08d6b68aaa3629a5a8639392490e5b9" +
                "bd5269c2f1e4f488ed8831161f19b7815528f8982ffe09be9b5c412f8a0db50f" +
                "8814c7194e83f23dbd8d162c9326ad"
        ),
        VectorMessage(
            "462e20412e20486179656b",
            "c7195ffacac1307ff99046f219750fc47693e23c3cb08b89c2af808b444850a8" +
                "0ae475b9df0f169ae80a89be0865b57f58c9fea0d4ec82a286427402f113e4b6" +
                "ae769a1d95941d49b25030"
        ),
        VectorMessage(
            "4361726c204d656e676572",
            "96763ed773f8e47bb3712f0e29b3060ffc956ffc146cee53d5e1df"
        ),
        VectorMessage(
            "4a65616e2d426170746973746520536179",
            "3e40f15f6f3a46ae446b253bf8b1d9ffb6ed9b174d272328ff91a7e2e5c79c07f5"
        ),
        VectorMessage(
            "457567656e2042f6686d20766f6e2042617765726b",
            "eb3f3515110702e047a6c9da4478b6ead94873c11c0f2d710ddb3f09fce024b3" +
                "a58502ae3f"
        )
    )

    @Test
    fun `Noise-C XX transcript matches every handshake and transport byte`() {
        val initiator = vectorState(HandshakeState.INITIATOR)
        val responder = vectorState(HandshakeState.RESPONDER)
        try {
            val states = listOf(
                initiator to responder,
                responder to initiator,
                initiator to responder
            )
            messages.take(3).zip(states).forEach { (message, peers) ->
                assertHandshakeMessage(peers.first, peers.second, message)
            }

            assertEquals(HandshakeState.SPLIT, initiator.action)
            assertEquals(HandshakeState.SPLIT, responder.action)
            assertArrayEquals(initiator.handshakeHash, responder.handshakeHash)

            val initiatorCiphers = initiator.split()
            val responderCiphers = responder.split()
            assertTransportMessage(
                responderCiphers.sender,
                initiatorCiphers.receiver,
                messages[3]
            )
            assertTransportMessage(
                initiatorCiphers.sender,
                responderCiphers.receiver,
                messages[4]
            )
            assertTransportMessage(
                responderCiphers.sender,
                initiatorCiphers.receiver,
                messages[5]
            )
            initiatorCiphers.sender.destroy()
            initiatorCiphers.receiver.destroy()
            responderCiphers.sender.destroy()
            responderCiphers.receiver.destroy()
        } finally {
            initiator.destroy()
            responder.destroy()
        }
    }

    @Test
    fun `Noise state machine rejects invalid actions and a tampered handshake tag`() {
        val initiator = vectorState(HandshakeState.INITIATOR)
        val responder = vectorState(HandshakeState.RESPONDER)
        try {
            assertThrows(IllegalStateException::class.java) { initiator.start() }
            assertThrows(IllegalStateException::class.java) {
                responder.writeMessage(ByteArray(256), 0, null, 0, 0)
            }

            assertHandshakeMessage(initiator, responder, messages[0])
            val message2 = write(responder, messages[1].payload)
            val tampered = message2.copyOf()
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()

            assertThrows(Exception::class.java) {
                initiator.readMessage(tampered, 0, tampered.size, ByteArray(256), 0)
            }
            assertEquals(HandshakeState.FAILED, initiator.action)
        } finally {
            initiator.destroy()
            responder.destroy()
        }
    }

    @Test
    fun `ChaChaPoly authentication binds nonce ciphertext tag and associated data`() {
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "associated".toByteArray()
        val associatedData = "header".toByteArray()
        val sender = Noise.createCipher("ChaChaPoly")
        val receiver = Noise.createCipher("ChaChaPoly")
        val wrongAdReceiver = Noise.createCipher("ChaChaPoly")
        try {
            sender.initializeKey(key, 0)
            receiver.initializeKey(key, 0)
            wrongAdReceiver.initializeKey(key, 0)
            sender.setNonce(7)
            receiver.setNonce(7)
            wrongAdReceiver.setNonce(7)
            val ciphertext = ByteArray(plaintext.size + sender.macLength)
            val length = sender.encryptWithAd(
                associatedData,
                plaintext,
                0,
                ciphertext,
                0,
                plaintext.size
            )

            assertThrows(Exception::class.java) {
                wrongAdReceiver.decryptWithAd(
                    "wrong".toByteArray(),
                    ciphertext,
                    0,
                    ByteArray(length),
                    0,
                    length
                )
            }
            val output = ByteArray(length)
            val outputLength = receiver.decryptWithAd(
                associatedData,
                ciphertext,
                0,
                output,
                0,
                length
            )
            assertArrayEquals(plaintext, output.copyOf(outputLength))
        } finally {
            sender.destroy()
            receiver.destroy()
            wrongAdReceiver.destroy()
        }
    }

    private fun vectorState(role: Int): HandshakeState {
        val state = HandshakeState(PROTOCOL, role)
        val prologue = hex("4a6f686e2047616c74")
        state.setPrologue(prologue, 0, prologue.size)
        val staticPrivate = if (role == HandshakeState.INITIATOR) {
            hex("e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1")
        } else {
            hex("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893")
        }
        val ephemeralPrivate = if (role == HandshakeState.INITIATOR) {
            hex("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a")
        } else {
            hex("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b")
        }
        state.localKeyPair.setPrivateKey(staticPrivate, 0)
        state.fixedEphemeralKey.setPrivateKey(ephemeralPrivate, 0)
        state.start()
        return state
    }

    private fun assertHandshakeMessage(
        writer: HandshakeState,
        reader: HandshakeState,
        message: VectorMessage
    ) {
        val actualCiphertext = write(writer, message.payload)
        assertArrayEquals(message.ciphertext, actualCiphertext)

        val plaintext = ByteArray(256)
        val length = reader.readMessage(
            actualCiphertext,
            0,
            actualCiphertext.size,
            plaintext,
            0
        )
        assertArrayEquals(message.payload, plaintext.copyOf(length))
    }

    private fun write(state: HandshakeState, payload: ByteArray): ByteArray {
        val output = ByteArray(512)
        val length = state.writeMessage(output, 0, payload, 0, payload.size)
        return output.copyOf(length)
    }

    private fun assertTransportMessage(
        sender: CipherState,
        receiver: CipherState,
        message: VectorMessage
    ) {
        val encrypted = ByteArray(message.payload.size + sender.macLength)
        val encryptedLength = sender.encryptWithAd(
            null,
            message.payload,
            0,
            encrypted,
            0,
            message.payload.size
        )
        assertArrayEquals(message.ciphertext, encrypted.copyOf(encryptedLength))

        val decrypted = ByteArray(encryptedLength)
        val decryptedLength = receiver.decryptWithAd(
            null,
            encrypted,
            0,
            decrypted,
            0,
            encryptedLength
        )
        assertArrayEquals(message.payload, decrypted.copyOf(decryptedLength))
    }

    private data class VectorMessage(
        private val payloadHex: String,
        private val ciphertextHex: String
    ) {
        val payload: ByteArray get() = hex(payloadHex)
        val ciphertext: ByteArray get() = hex(ciphertextHex)
    }

    companion object {
        private const val PROTOCOL = "Noise_XX_25519_ChaChaPoly_SHA256"

        private fun hex(value: String): ByteArray =
            value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
