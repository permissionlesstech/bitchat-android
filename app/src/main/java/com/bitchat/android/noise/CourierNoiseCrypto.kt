package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.HandshakeState
import com.bitchat.android.noise.southernstorm.protocol.Noise

/**
 * One-message Noise X helper used by iOS-compatible courier envelopes.
 *
 * Protocol: Noise_X_25519_ChaChaPoly_SHA256
 * Prologue: "bitchat-courier-v1"
 */
object CourierNoiseCrypto {
    private const val PROTOCOL_NAME = "Noise_X_25519_ChaChaPoly_SHA256"
    private val COURIER_PROLOGUE = "bitchat-courier-v1".toByteArray(Charsets.UTF_8)
    private val PREKEY_PROLOGUE_PREFIX = "bitchat-prekey-v1".toByteArray(Charsets.UTF_8)
    private const val X_OVERHEAD_BYTES = 32 + 48 + 16

    data class Opened(val payload: ByteArray, val senderStaticKey: ByteArray)

    fun seal(
        payload: ByteArray,
        senderStaticPrivateKey: ByteArray,
        recipientStaticPublicKey: ByteArray
    ): ByteArray = sealWithPrologue(
        payload,
        senderStaticPrivateKey,
        recipientStaticPublicKey,
        COURIER_PROLOGUE
    )

    fun sealToPrekey(
        payload: ByteArray,
        senderStaticPrivateKey: ByteArray,
        recipientPrekey: com.bitchat.android.model.PrekeyBundle.Prekey
    ): ByteArray = sealWithPrologue(
        payload,
        senderStaticPrivateKey,
        recipientPrekey.publicKey,
        prekeyPrologue(recipientPrekey.id)
    )

    private fun sealWithPrologue(
        payload: ByteArray,
        senderStaticPrivateKey: ByteArray,
        recipientStaticPublicKey: ByteArray,
        prologue: ByteArray
    ): ByteArray {
        require(senderStaticPrivateKey.size == 32)
        require(recipientStaticPublicKey.size == 32)
        val handshake = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
        return try {
            handshake.setPrologue(prologue, 0, prologue.size)
            handshake.getLocalKeyPair().setPrivateKey(senderStaticPrivateKey, 0)
            handshake.getRemotePublicKey().setPublicKey(recipientStaticPublicKey, 0)
            handshake.start()
            val message = ByteArray(payload.size + X_OVERHEAD_BYTES)
            val length = handshake.writeMessage(message, 0, payload, 0, payload.size)
            message.copyOf(length)
        } finally {
            handshake.destroy()
        }
    }

    fun open(
        ciphertext: ByteArray,
        recipientStaticPrivateKey: ByteArray
    ): Opened = openWithPrologue(ciphertext, recipientStaticPrivateKey, COURIER_PROLOGUE)

    fun openWithPrekey(
        ciphertext: ByteArray,
        recipientPrekeyPrivateKey: ByteArray,
        prekeyId: Long
    ): Opened = openWithPrologue(
        ciphertext,
        recipientPrekeyPrivateKey,
        prekeyPrologue(prekeyId)
    )

    private fun openWithPrologue(
        ciphertext: ByteArray,
        recipientStaticPrivateKey: ByteArray,
        prologue: ByteArray
    ): Opened {
        require(recipientStaticPrivateKey.size == 32)
        val handshake = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
        return try {
            handshake.setPrologue(prologue, 0, prologue.size)
            handshake.getLocalKeyPair().setPrivateKey(recipientStaticPrivateKey, 0)
            handshake.start()
            val payload = ByteArray(ciphertext.size)
            val length = handshake.readMessage(ciphertext, 0, ciphertext.size, payload, 0)
            val senderKey = ByteArray(handshake.getRemotePublicKey().publicKeyLength)
            handshake.getRemotePublicKey().getPublicKey(senderKey, 0)
            Opened(payload.copyOf(length), senderKey)
        } finally {
            handshake.destroy()
        }
    }

    /** Test/support helper that derives the X25519 public key used on the wire. */
    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32)
        val key = Noise.createDH("25519")
        return try {
            key.setPrivateKey(privateKey, 0)
            ByteArray(key.publicKeyLength).also { key.getPublicKey(it, 0) }
        } finally {
            key.destroy()
        }
    }

    private fun prekeyPrologue(prekeyId: Long): ByteArray {
        require(prekeyId in 0..0xFFFF_FFFFL)
        return PREKEY_PROLOGUE_PREFIX + byteArrayOf(
            (prekeyId ushr 24).toByte(),
            (prekeyId ushr 16).toByte(),
            (prekeyId ushr 8).toByte(),
            prekeyId.toByte()
        )
    }
}
