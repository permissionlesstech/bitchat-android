package com.bitchat.android.board

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest

/**
 * Signing identity used by board payloads.
 *
 * Location boards use a key derived from the already-unlinkable per-geohash
 * Nostr secret. Domain separation keeps the board Ed25519 identity distinct
 * from the secp256k1 identity used on relays.
 */
class BoardSigningIdentity(
    publicKey: ByteArray,
    private val signer: (ByteArray) -> ByteArray?
) {
    val publicKey: ByteArray = publicKey.copyOf()

    init {
        require(publicKey.size == BoardWireConstants.SIGNING_KEY_LENGTH)
    }

    fun sign(message: ByteArray): ByteArray? = signer(message)?.copyOf()

    companion object {
        private const val GEO_IDENTITY_CONTEXT = "bitchat-board-geo-identity-v1"

        fun fromNostrPrivateKeyHex(privateKeyHex: String): BoardSigningIdentity? =
            runCatching {
                val nostrSecret = privateKeyHex.hexToByteArray()
                    .takeIf { it.size == BoardWireConstants.SIGNING_KEY_LENGTH }
                    ?: return@runCatching null
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(GEO_IDENTITY_CONTEXT.toByteArray(Charsets.UTF_8))
                digest.update(nostrSecret)
                fromEd25519Seed(digest.digest())
            }.getOrNull()

        fun fromEd25519Seed(seed: ByteArray): BoardSigningIdentity {
            require(seed.size == BoardWireConstants.SIGNING_KEY_LENGTH)
            val privateKey = Ed25519PrivateKeyParameters(seed.copyOf(), 0)
            return BoardSigningIdentity(privateKey.generatePublicKey().encoded) { message ->
                Ed25519Signer().run {
                    init(true, privateKey)
                    update(message, 0, message.size)
                    generateSignature()
                }
            }
        }

        private fun String.hexToByteArray(): ByteArray {
            require(length % 2 == 0)
            return ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
