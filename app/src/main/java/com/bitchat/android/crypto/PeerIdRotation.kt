package com.bitchat.android.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** Cross-platform primitives from docs/PEER-ID-ROTATION-ANDROID.md. */
object PeerIdRotation {
    const val ROTATION_PERIOD_SECONDS = 3_600L
    const val TAG_SLOTS = 8
    const val ID_SIZE = 8
    const val TAG_SIZE = 8

    private val ROTATION_INFO = "bitchat-peer-rotation-v1".toByteArray(Charsets.US_ASCII)
    private val PEER_ID_CONTEXT = "bitchat-peer-id-v2".toByteArray(Charsets.US_ASCII)
    private val RECOGNITION_INFO = "bitchat-recognition-v1".toByteArray(Charsets.US_ASCII)
    private val BINDING_CONTEXT = "bitchat-peerid-binding-v1".toByteArray(Charsets.US_ASCII)

    fun epoch(unixTimeSeconds: Long): UInt {
        require(unixTimeSeconds >= 0) { "Unix time must be non-negative" }
        val value = unixTimeSeconds / ROTATION_PERIOD_SECONDS
        require(value <= UInt.MAX_VALUE.toLong()) { "Epoch exceeds UInt32" }
        return value.toUInt()
    }

    fun candidateEpochs(current: UInt): List<UInt> = buildList {
        if (current > UInt.MIN_VALUE) add(current - 1u)
        add(current)
        if (current < UInt.MAX_VALUE) add(current + 1u)
    }

    fun isEpochAccepted(advertised: UInt, current: UInt): Boolean =
        candidateEpochs(current).contains(advertised)

    fun rotationSecret(noiseStaticPrivateKey: ByteArray): ByteArray {
        require(noiseStaticPrivateKey.size == 32) { "Noise static private key must be 32 bytes" }
        return hkdf(noiseStaticPrivateKey, ROTATION_INFO)
    }

    fun peerId(rotationSecret: ByteArray, epoch: UInt): ByteArray {
        require(rotationSecret.size == 32) { "Rotation secret must be 32 bytes" }
        return hmac(rotationSecret, PEER_ID_CONTEXT + uint32be(epoch)).copyOf(ID_SIZE)
    }

    fun recognitionKey(sharedSecret: ByteArray): ByteArray {
        require(sharedSecret.size == 32) { "X25519 shared secret must be 32 bytes" }
        return hkdf(sharedSecret, RECOGNITION_INFO)
    }

    fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        require(publicKey.size == 32) { "X25519 public key must be 32 bytes" }
        return ByteArray(32).also {
            X25519PrivateKeyParameters(privateKey, 0)
                .generateSecret(X25519PublicKeyParameters(publicKey, 0), it, 0)
        }
    }

    fun recognitionTag(
        recognitionKey: ByteArray,
        epoch: UInt,
        senderNoisePublicKey: ByteArray,
        recipientNoisePublicKey: ByteArray,
        announcedPeerId: ByteArray
    ): ByteArray {
        require(recognitionKey.size == 32) { "Recognition key must be 32 bytes" }
        require(senderNoisePublicKey.size == 32) { "Sender Noise key must be 32 bytes" }
        require(recipientNoisePublicKey.size == 32) { "Recipient Noise key must be 32 bytes" }
        require(announcedPeerId.size == ID_SIZE) { "Announced peer ID must be 8 bytes" }
        val message = uint32be(epoch) + senderNoisePublicKey + recipientNoisePublicKey + announcedPeerId
        return hmac(recognitionKey, message).copyOf(TAG_SIZE)
    }

    fun paddedTagBlock(tags: List<ByteArray>, random: SecureRandom = SecureRandom()): ByteArray {
        require(tags.size <= TAG_SLOTS) { "At most $TAG_SLOTS recognition tags fit in an announce" }
        require(tags.all { it.size == TAG_SIZE }) { "Recognition tags must be 8 bytes" }
        val slots = tags.map(ByteArray::copyOf).toMutableList()
        while (slots.size < TAG_SLOTS) slots += ByteArray(TAG_SIZE).also(random::nextBytes)
        slots.shuffle(random)
        return slots.fold(ByteArray(0)) { block, tag -> block + tag }
    }

    fun tagBlockContains(block: ByteArray, candidate: ByteArray): Boolean {
        require(block.size == TAG_SLOTS * TAG_SIZE) { "Recognition tag block must be 64 bytes" }
        require(candidate.size == TAG_SIZE) { "Recognition tag must be 8 bytes" }
        return block.asList().chunked(TAG_SIZE).any { it.toByteArray().contentEquals(candidate) }
    }

    fun bindingMessage(epoch: UInt, peerId: ByteArray, noiseStaticPublicKey: ByteArray): ByteArray {
        require(peerId.size == ID_SIZE) { "Peer ID must be 8 bytes" }
        require(noiseStaticPublicKey.size == 32) { "Noise static public key must be 32 bytes" }
        return BINDING_CONTEXT + uint32be(epoch) + peerId + noiseStaticPublicKey
    }

    fun signBinding(
        signingPrivateKey: ByteArray,
        epoch: UInt,
        peerId: ByteArray,
        noiseStaticPublicKey: ByteArray
    ): ByteArray {
        require(signingPrivateKey.size == 32) { "Ed25519 private key must be 32 bytes" }
        val message = bindingMessage(epoch, peerId, noiseStaticPublicKey)
        return Ed25519Signer().run {
            init(true, Ed25519PrivateKeyParameters(signingPrivateKey, 0))
            update(message, 0, message.size)
            generateSignature()
        }
    }

    /** Verifies only the Ed25519 signature; callers must also enforce every session binding check. */
    fun verifyBindingSignature(
        signature: ByteArray,
        signingPublicKey: ByteArray,
        epoch: UInt,
        peerId: ByteArray,
        noiseStaticPublicKey: ByteArray
    ): Boolean {
        if (signature.size != 64 || signingPublicKey.size != 32) return false
        val message = try {
            bindingMessage(epoch, peerId, noiseStaticPublicKey)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(signingPublicKey, 0))
            update(message, 0, message.size)
            verifySignature(signature)
        }
    }

    private fun hkdf(input: ByteArray, info: ByteArray): ByteArray = ByteArray(32).also { output ->
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(input, byteArrayOf(), info))
            generateBytes(output, 0, output.size)
        }
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray = ByteArray(32).also { output ->
        HMac(SHA256Digest()).apply {
            init(KeyParameter(key))
            update(message, 0, message.size)
            doFinal(output, 0)
        }
    }

    private fun uint32be(value: UInt): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
}
