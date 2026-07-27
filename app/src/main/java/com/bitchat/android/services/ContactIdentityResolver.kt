package com.bitchat.android.services

import com.bitchat.android.nostr.Bech32
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import java.security.MessageDigest

object ContactIdentityResolver {
    private const val CONTACT_PREFIX = "contact_"
    private val meshPeerIdRegex = Regex("^[0-9a-fA-F]{16}$")
    private val noiseKeyRegex = Regex("^[0-9a-fA-F]{64}$")
    private val fingerprintRegex = Regex("^[0-9a-fA-F]{64}$")

    fun isMeshPeerId(value: String): Boolean = meshPeerIdRegex.matches(value)

    fun isNoiseKeyHex(value: String): Boolean = noiseKeyRegex.matches(value)

    fun isNostrAlias(value: String): Boolean =
        value.startsWith("nostr_") || value.startsWith("nostr:")

    fun noiseKeyHex(noisePublicKey: ByteArray): String = noisePublicKey.hexEncodedString()

    fun fingerprintHex(noisePublicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(noisePublicKey)
        return digest.hexEncodedString()
    }

    fun contactConversationIdForNoiseKey(noisePublicKey: ByteArray): String =
        CONTACT_PREFIX + fingerprintHex(noisePublicKey)

    fun contactConversationIdForFingerprint(fingerprint: String): String? =
        fingerprint
            .takeIf { fingerprintRegex.matches(it) }
            ?.let { CONTACT_PREFIX + it.lowercase() }

    fun isContactConversationId(value: String): Boolean =
        value.startsWith(CONTACT_PREFIX) &&
            fingerprintRegex.matches(value.removePrefix(CONTACT_PREFIX))

    fun fingerprintFromContactConversationId(value: String): String? =
        value
            .takeIf { isContactConversationId(it) }
            ?.removePrefix(CONTACT_PREFIX)
            ?.lowercase()

    fun peerIdForNoiseKey(noisePublicKey: ByteArray): String =
        fingerprintHex(noisePublicKey).take(16)

    fun peerIdForNoiseKeyHex(noiseKeyHex: String): String? =
        bytesFromHex(noiseKeyHex)
            ?.takeIf { it.size == 32 }
            ?.let { peerIdForNoiseKey(it) }

    fun bytesFromHex(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return null
        if (!clean.matches(Regex("^[0-9a-fA-F]+$"))) return null
        return clean.dataFromHexString()
    }

    fun nostrPubkeyHex(value: String): String? {
        val clean = value.trim()
        if (clean.startsWith("npub1", ignoreCase = true)) {
            return try {
                val (hrp, data) = Bech32.decode(clean.lowercase())
                if (hrp == "npub" && data.size == 32) data.hexEncodedString() else null
            } catch (_: Exception) {
                null
            }
        }
        return clean
            .takeIf { noiseKeyRegex.matches(it) }
            ?.lowercase()
    }

    fun npubFromHex(hex: String): String? =
        bytesFromHex(hex)
            ?.takeIf { it.size == 32 }
            ?.let { Bech32.encode("npub", it) }

    fun nostrAliasForPubkey(value: String): String? =
        nostrPubkeyHex(value)?.let { "nostr_${it.take(16)}" }
}
