package com.bitchat.android.nostr

import org.bouncycastle.crypto.ec.CustomNamedCurves
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Channel-bound proof of work for geohash identities. Experimental: grinding is a debug switch.
 *
 * A key is proven in geohash `g` when SHA-256(DOMAIN ‖ pubkeyX ‖ g) has at least [requiredBits]
 * leading zero bits. The geohash is inside the hash, so work done for one channel is worthless in
 * any other: a spammer cannot grind keys once and reuse them everywhere, while an honest user,
 * whose key differs per channel by design, pays once per channel like everyone else.
 *
 * Checking costs one hash. Proven senders skip the spam filter's new-sender pool.
 */
object GeohashIdentityProof {
    private val DOMAIN = "bitchat-geohash-id-v1".toByteArray(Charsets.UTF_8)
    private val curve = CustomNamedCurves.getByName("secp256k1")
    private val CANONICAL_PUBKEY = Regex("[0-9a-fA-F]{64}")

    /** Debug experiment: when true, this device grinds its geohash keys before using them. */
    @Volatile
    var grindEnabled: Boolean = false

    @Volatile
    var requiredBits: Int = 21

    fun isProven(pubkeyHex: String, geohash: String): Boolean {
        // Only canonical keys: Kotlin's hex parsing also takes forms like "+f", which would let
        // one proven key appear as several different senders.
        if (!CANONICAL_PUBKEY.matches(pubkeyHex)) return false
        val pubkey = pubkeyHex.hexToBytesOrNull() ?: return false
        return leadingZeroBits(digest(MessageDigest.getInstance("SHA-256"), pubkey, geohash)) >= requiredBits
    }

    /**
     * Finds the smallest `j ≥ 0` for which the key `basePrivateKey + j` is proven in [geohash],
     * stepping the public key by adding G instead of deriving it from scratch each try.
     * Returns null when [isActive] turns false before a proof is found.
     */
    fun grind(basePrivateKey: BigInteger, geohash: String, bits: Int = requiredBits, isActive: () -> Boolean = { true }): Long? {
        val md = MessageDigest.getInstance("SHA-256")
        var point = curve.g.multiply(basePrivateKey.mod(curve.n)).normalize()
        var j = 0L
        while (true) {
            if ((j and 0x3FF) == 0L && !isActive()) return null
            val x = point.xCoord.encoded
            if (leadingZeroBits(digest(md, x, geohash)) >= bits && !point.isInfinity) return j
            point = point.add(curve.g).normalize()
            j++
        }
    }

    /** The private key `basePrivateKey + j` (mod n) as 64 hex characters. */
    fun offsetPrivateKeyHex(basePrivateKey: BigInteger, j: Long): String =
        basePrivateKey.add(BigInteger.valueOf(j)).mod(curve.n).toString(16).padStart(64, '0')

    private fun digest(md: MessageDigest, pubkeyX: ByteArray, geohash: String): ByteArray {
        md.reset()
        md.update(DOMAIN)
        md.update(pubkeyX)
        md.update(geohash.lowercase().toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    internal fun leadingZeroBits(bytes: ByteArray): Int {
        var total = 0
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v == 0) {
                total += 8
            } else {
                total += Integer.numberOfLeadingZeros(v) - 24
                break
            }
        }
        return total
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
