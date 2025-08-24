package com.bitchat.android.nostr

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.security.SecureRandom
import java.security.MessageDigest
import java.math.BigInteger

/**
 * Cryptographic utilities for Nostr protocol
 * Includes secp256k1 operations, ECDH, and NIP-44 encryption
 */
object NostrCrypto {
    
    private val secureRandom = SecureRandom()
    // NIP-44 v2 only
    
    // secp256k1 curve parameters
    val secp256k1Curve = CustomNamedCurves.getByName("secp256k1")
    val secp256k1Params = ECDomainParameters(
        secp256k1Curve.curve,
        secp256k1Curve.g,
        secp256k1Curve.n,
        secp256k1Curve.h
    )
    
    /**
     * Generate secp256k1 key pair
     * Returns (privateKeyHex, publicKeyHex)
     */
    fun generateKeyPair(): Pair<String, String> {
        val generator = ECKeyPairGenerator()
        val keyGenParams = ECKeyGenerationParameters(secp256k1Params, secureRandom)
        generator.init(keyGenParams)
        
        val keyPair = generator.generateKeyPair()
        val privateKey = keyPair.private as ECPrivateKeyParameters
        val publicKey = keyPair.public as ECPublicKeyParameters
        
        // Get private key as 32-byte hex - ensure proper padding
        val privateKeyBigInt = privateKey.d
        val privateKeyBytes = privateKeyBigInt.toByteArray()
        
        val privateKeyPadded = ByteArray(32)
        if (privateKeyBytes.size <= 32) {
            val srcStart = maxOf(0, privateKeyBytes.size - 32)
            val destStart = maxOf(0, 32 - privateKeyBytes.size)
            val length = minOf(privateKeyBytes.size, 32)
            System.arraycopy(privateKeyBytes, srcStart, privateKeyPadded, destStart, length)
        } else {
            // If BigInteger added a sign byte, skip it
            System.arraycopy(privateKeyBytes, privateKeyBytes.size - 32, privateKeyPadded, 0, 32)
        }
        
        // Get x-only public key (32 bytes)
        val publicKeyPoint = publicKey.q.normalize()
        val xCoord = publicKeyPoint.xCoord.encoded
        
        return Pair(
            privateKeyPadded.toHexString(),
            xCoord.toHexString()
        )
    }
    
    /**
     * Derive public key from private key
     * Returns x-only public key (32 bytes hex)
     */
    fun derivePublicKey(privateKeyHex: String): String {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        val privateKeyBigInt = BigInteger(1, privateKeyBytes)
        
        val publicKeyPoint = secp256k1Params.g.multiply(privateKeyBigInt).normalize()
        val xCoord = publicKeyPoint.xCoord.encoded
        
        return xCoord.toHexString()
    }
    
    /**
     * Perform ECDH key agreement
     * Returns shared secret
     */
    fun performECDH(privateKeyHex: String, publicKeyHex: String): ByteArray {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        val publicKeyBytes = publicKeyHex.hexToByteArray()
        
        val privateKeyBigInt = BigInteger(1, privateKeyBytes)
        val privateKeyParams = ECPrivateKeyParameters(privateKeyBigInt, secp256k1Params)
        
        // Recover full public key point from x-only coordinate (prefer even y per BIP-340)
        val publicKeyPoint = recoverPublicKeyPoint(publicKeyBytes)
        val publicKeyParams = ECPublicKeyParameters(publicKeyPoint, secp256k1Params)
        
        val agreement = ECDHBasicAgreement()
        agreement.init(privateKeyParams)
        
        val sharedSecret = agreement.calculateAgreement(publicKeyParams)
        val sharedSecretBytes = sharedSecret.toByteArray()
        
        // Ensure 32 bytes
        val result = ByteArray(32)
        if (sharedSecretBytes.size <= 32) {
            System.arraycopy(
                sharedSecretBytes,
                maxOf(0, sharedSecretBytes.size - 32),
                result,
                maxOf(0, 32 - sharedSecretBytes.size),
                minOf(sharedSecretBytes.size, 32)
            )
        } else {
            // If BigInteger added a leading sign byte, keep the last 32 bytes
            System.arraycopy(sharedSecretBytes, sharedSecretBytes.size - 32, result, 0, 32)
        }
        
        return result
    }

    /**
     * Perform ECDH with explicit parity choice for the x-only public key
     * If preferOddY is true, use the odd-y lift; otherwise even-y lift.
     */
    private fun performECDHWithParity(privateKeyHex: String, publicKeyHex: String, preferOddY: Boolean): ByteArray {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        val publicKeyBytes = publicKeyHex.hexToByteArray()
        val privateKeyBigInt = BigInteger(1, privateKeyBytes)
        val privateKeyParams = ECPrivateKeyParameters(privateKeyBigInt, secp256k1Params)
        val point = recoverPublicKeyPointWithParity(publicKeyBytes, preferOddY)
        val publicKeyParams = ECPublicKeyParameters(point, secp256k1Params)
        val agreement = ECDHBasicAgreement()
        agreement.init(privateKeyParams)
        val sharedSecret = agreement.calculateAgreement(publicKeyParams)
        val sharedSecretBytes = sharedSecret.toByteArray()
        val result = ByteArray(32)
        if (sharedSecretBytes.size <= 32) {
            System.arraycopy(sharedSecretBytes, maxOf(0, sharedSecretBytes.size - 32), result, maxOf(0, 32 - sharedSecretBytes.size), minOf(sharedSecretBytes.size, 32))
        } else {
            // If BigInteger added a leading sign byte, keep the last 32 bytes
            System.arraycopy(sharedSecretBytes, sharedSecretBytes.size - 32, result, 0, 32)
        }
        return result
    }
    
    /**
     * Recover full EC point from x-only coordinate
     * Tries both possible y coordinates
     */
    private fun recoverPublicKeyPoint(xOnlyBytes: ByteArray): ECPoint {
        require(xOnlyBytes.size == 32) { "X-only public key must be 32 bytes" }
        
        val x = BigInteger(1, xOnlyBytes)
        
        // Try even y first (0x02 prefix)
        try {
            val compressedBytes = ByteArray(33)
            compressedBytes[0] = 0x02
            System.arraycopy(xOnlyBytes, 0, compressedBytes, 1, 32)
            return secp256k1Curve.curve.decodePoint(compressedBytes)
        } catch (e: Exception) {
            // Try odd y (0x03 prefix)
            val compressedBytes = ByteArray(33)
            compressedBytes[0] = 0x03
            System.arraycopy(xOnlyBytes, 0, compressedBytes, 1, 32)
            return secp256k1Curve.curve.decodePoint(compressedBytes)
        }
    }

    private fun recoverPublicKeyPointWithParity(xOnlyBytes: ByteArray, preferOddY: Boolean): ECPoint {
        require(xOnlyBytes.size == 32) { "X-only public key must be 32 bytes" }
        val prefix: Byte = if (preferOddY) 0x03 else 0x02
        val compressedBytes = ByteArray(33)
        compressedBytes[0] = prefix
        System.arraycopy(xOnlyBytes, 0, compressedBytes, 1, 32)
        return secp256k1Curve.curve.decodePoint(compressedBytes)
    }
    
    /**
     * Compute the ECDH shared point (uncompressed) with an explicit parity choice for the x-only public key
     */
    private fun computeSharedPointWithParity(privateKeyHex: String, publicKeyHex: String, preferOddY: Boolean): ECPoint {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        val publicKeyBytes = publicKeyHex.hexToByteArray()
        val privateKeyBigInt = BigInteger(1, privateKeyBytes)
        val point = recoverPublicKeyPointWithParity(publicKeyBytes, preferOddY)
        return point.multiply(privateKeyBigInt).normalize()
    }
    
    private fun compressedPoint(point: ECPoint): ByteArray {
        val normalized = point.normalize()
        val x = normalized.xCoord.encoded
        val prefix: Byte = if (hasEvenY(normalized)) 0x02 else 0x03
        val out = ByteArray(33)
        out[0] = prefix
        System.arraycopy(x, 0, out, 1, 32)
        return out
    }
    
    /**
     * NIP-44 v2 key derivation using HKDF-SHA256
     * salt = empty, info = "nip44-v2", length = 32 bytes
     */
    fun deriveNIP44Key(sharedSecret: ByteArray): ByteArray {
        val zeroSalt = ByteArray(0)
        val prk = hkdfExtract(zeroSalt, sharedSecret)
        return hkdfExpand(prk, info = "nip44-v2".toByteArray(Charsets.UTF_8), length = 32)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(salt))
        hmac.update(ikm, 0, ikm.size)
        val prk = ByteArray(hmac.macSize)
        hmac.doFinal(prk, 0)
        return prk
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray?, length: Int): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(prk))
        if (info != null && info.isNotEmpty()) {
            hmac.update(info, 0, info.size)
        }
        hmac.update(byteArrayOf(0x01), 0, 1)
        val t = ByteArray(hmac.macSize)
        hmac.doFinal(t, 0)
        return t.copyOf(length)
    }

    /**
     * NIP-44 v2 encryption using XChaCha20-Poly1305
     * Output format: "v2:" + base64url(nonce24 || ciphertext || tag)
     */
    fun encryptNIP44(
        plaintext: String,
        recipientPublicKeyHex: String,
        senderPrivateKeyHex: String
    ): String {
        try {
            // Match iOS: derive HKDF input from the compressed shared point (33 bytes)
            val sharedPoint = computeSharedPointWithParity(senderPrivateKeyHex, recipientPublicKeyHex, preferOddY = false)
            val secretMaterial = compressedPoint(sharedPoint)
            val encryptionKey = deriveNIP44Key(secretMaterial)
            val aead = XChaCha20Poly1305(encryptionKey)
            val combined = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), null) // nonce||ct||tag
            val b64 = base64UrlNoPad(combined)
            android.util.Log.d("NostrCrypto", "NIP44 v2 encrypt: len=${b64.length}")
            return "v2:$b64"
        } catch (e: Exception) {
            throw RuntimeException("NIP-44 v2 encryption failed: ${e.message}", e)
        }
    }

    /**
     * NIP-44 v2 decryption using XChaCha20-Poly1305
     * Only accepts the exact "v2:" base64url format.
     * Tries both even/odd Y parities for x-only pubkeys.
     */
    fun decryptNIP44(ciphertext: String, senderPublicKeyHex: String, recipientPrivateKeyHex: String): String {
        try {
            require(ciphertext.startsWith("v2:")) { "Invalid NIP-44 version prefix" }
            val encoded = ciphertext.substring(3)
            val encryptedData = base64UrlDecode(encoded)
                ?: throw IllegalArgumentException("Invalid base64url payload")

            var lastError: Exception? = null
            // Try even-Y first, then odd-Y
            for (preferOdd in listOf(false, true)) {
                try {
                    // Match iOS: derive HKDF input from the compressed shared point (33 bytes)
                    val point = computeSharedPointWithParity(recipientPrivateKeyHex, senderPublicKeyHex, preferOddY = preferOdd)
                    val secretMaterial = compressedPoint(point)
                    val key = deriveNIP44Key(secretMaterial)
                    val aead = XChaCha20Poly1305(key)
                    val pt = aead.decrypt(encryptedData, null) // expects nonce||ct||tag
                    return String(pt, Charsets.UTF_8)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: RuntimeException("NIP-44 v2 decryption failed")
        } catch (e: Exception) {
            throw RuntimeException("NIP-44 v2 decryption failed: ${e.message}", e)
        }
    }

    private fun base64UrlNoPad(data: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        return b64.replace('+', '-').replace('/', '_').replace("=", "")
    }

    private fun base64UrlDecode(s: String): ByteArray? {
        var str = s.replace('-', '+').replace('_', '/')
        val pad = (4 - (str.length % 4)) % 4
        if (pad > 0) str += "=".repeat(pad)
        return try {
            android.util.Base64.decode(str, android.util.Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    
    /**
     * Generate random timestamp offset for privacy (±15 minutes)
     */
    fun randomizeTimestamp(baseTimestamp: Long = System.currentTimeMillis() / 1000): Int {
        val offset = secureRandom.nextInt(1800) - 900 // ±15 minutes in seconds
        return (baseTimestamp + offset).toInt()
    }
    
    /**
     * Random timestamp up to maxPastSeconds in the past (default 2 days)
     */
    fun randomizeTimestampUpToPast(maxPastSeconds: Int = 172800): Int {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val offset = if (maxPastSeconds > 0) secureRandom.nextInt(maxPastSeconds + 1) else 0
        return now - offset
    }
    
    /**
     * Validate secp256k1 private key
     */
    fun isValidPrivateKey(privateKeyHex: String): Boolean {
        return try {
            val privateKeyBytes = privateKeyHex.hexToByteArray()
            if (privateKeyBytes.size != 32) return false
            
            val privateKeyBigInt = BigInteger(1, privateKeyBytes)
            
            // Must be less than curve order and greater than 0
            privateKeyBigInt > BigInteger.ZERO && privateKeyBigInt < secp256k1Params.n
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Validate x-only public key
     */
    fun isValidPublicKey(publicKeyHex: String): Boolean {
        return try {
            val publicKeyBytes = publicKeyHex.hexToByteArray()
            if (publicKeyBytes.size != 32) return false
            
            // Try to recover point
            recoverPublicKeyPoint(publicKeyBytes)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    // ==============================================================================
    // BIP-340 Schnorr Signatures Implementation
    // ==============================================================================
    
    /**
     * Tagged hash function for BIP-340
     */
    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagBytes = tag.toByteArray(Charsets.UTF_8)
        val tagHash = MessageDigest.getInstance("SHA-256").digest(tagBytes)
        
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(tagHash)
        digest.update(tagHash)
        digest.update(data)
        return digest.digest()
    }
    
    /**
     * Check if y coordinate is even
     */
    private fun hasEvenY(point: ECPoint): Boolean {
        val yCoord = point.normalize().yCoord.encoded
        return (yCoord[yCoord.size - 1].toInt() and 1) == 0
    }
    
    /**
     * Lift x coordinate to point with even y
     */
    private fun liftX(xBytes: ByteArray): ECPoint? {
        return try {
            val point = recoverPublicKeyPoint(xBytes)
            val normalizedPoint = point.normalize()
            
            if (hasEvenY(normalizedPoint)) {
                normalizedPoint
            } else {
                normalizedPoint.negate()
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * BIP-340 Schnorr signature creation
     * Returns 64-byte signature (r || s) as hex string
     */
    fun schnorrSign(messageHash: ByteArray, privateKeyHex: String): String {
        require(messageHash.size == 32) { "Message hash must be 32 bytes" }
        
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        require(privateKeyBytes.size == 32) { "Private key must be 32 bytes" }
        
        val d = BigInteger(1, privateKeyBytes)
        require(d > BigInteger.ZERO && d < secp256k1Params.n) { "Invalid private key" }
        
        // Compute public key point P = d * G
        val P = secp256k1Params.g.multiply(d).normalize()
        
        // Ensure P has even y coordinate, adjust d if necessary
        val (adjustedD, publicKeyBytes) = if (hasEvenY(P)) {
            Pair(d, P.xCoord.encoded)
        } else {
            Pair(secp256k1Params.n.subtract(d), P.xCoord.encoded)
        }
        
        // Generate nonce
        val k = generateNonce(adjustedD, messageHash, publicKeyBytes)
        
        // Compute R = k * G
        val R = secp256k1Params.g.multiply(k).normalize()
        
        // Ensure R has even y coordinate
        val adjustedK = if (hasEvenY(R)) k else secp256k1Params.n.subtract(k)
        val r = R.xCoord.encoded
        
        // Compute challenge e = H(r || P || m)
        val challengeData = ByteArray(96) // 32 + 32 + 32
        System.arraycopy(r, 0, challengeData, 0, 32)
        System.arraycopy(publicKeyBytes, 0, challengeData, 32, 32)
        System.arraycopy(messageHash, 0, challengeData, 64, 32)
        
        val eBytes = taggedHash("BIP0340/challenge", challengeData)
        val e = BigInteger(1, eBytes).mod(secp256k1Params.n)
        
        // Compute s = (k + e * d) mod n
        val s = adjustedK.add(e.multiply(adjustedD)).mod(secp256k1Params.n)
        
        // Return signature as r || s (64 bytes hex)
        val rPadded = ByteArray(32)
        val sPadded = ByteArray(32)
        
        val rBytes = r
        val sBytes = s.toByteArray()
        
        // Pad r to 32 bytes (should already be 32)
        System.arraycopy(rBytes, 0, rPadded, 0, minOf(32, rBytes.size))
        
        // Pad s to 32 bytes - handle BigInteger padding correctly
        if (sBytes.size <= 32) {
            val srcStart = maxOf(0, sBytes.size - 32)
            val destStart = maxOf(0, 32 - sBytes.size)
            val length = minOf(sBytes.size, 32)
            System.arraycopy(sBytes, srcStart, sPadded, destStart, length)
        } else {
            // If BigInteger added a sign byte, skip it
            System.arraycopy(sBytes, sBytes.size - 32, sPadded, 0, 32)
        }
        
        return (rPadded + sPadded).toHexString()
    }
    
    /**
     * BIP-340 Schnorr signature verification
     */
    fun schnorrVerify(messageHash: ByteArray, signatureHex: String, publicKeyHex: String): Boolean {
        return try {
            require(messageHash.size == 32) { "Message hash must be 32 bytes" }
            
            val signatureBytes = signatureHex.hexToByteArray()
            require(signatureBytes.size == 64) { "Signature must be 64 bytes" }
            
            val publicKeyBytes = publicKeyHex.hexToByteArray()
            require(publicKeyBytes.size == 32) { "Public key must be 32 bytes" }
            
            // Parse signature
            val r = signatureBytes.copyOfRange(0, 32)
            val sBytes = signatureBytes.copyOfRange(32, 64)
            val s = BigInteger(1, sBytes)
            
            // Validate r and s
            val rBigInt = BigInteger(1, r)
            if (rBigInt >= secp256k1Params.curve.field.characteristic) return false
            if (s >= secp256k1Params.n) return false
            
            // Lift public key
            val P = liftX(publicKeyBytes) ?: return false
            
            // Compute challenge e = H(r || P || m)
            val challengeData = ByteArray(96)
            System.arraycopy(r, 0, challengeData, 0, 32)
            System.arraycopy(publicKeyBytes, 0, challengeData, 32, 32)
            System.arraycopy(messageHash, 0, challengeData, 64, 32)
            
            val eBytes = taggedHash("BIP0340/challenge", challengeData)
            val e = BigInteger(1, eBytes).mod(secp256k1Params.n)
            
            // Compute R = s * G - e * P
            val sG = secp256k1Params.g.multiply(s)
            val eP = P.multiply(e)
            val R = sG.subtract(eP).normalize()
            
            // Check if R has even y and x coordinate matches r
            if (!hasEvenY(R)) return false
            
            val computedR = R.xCoord.encoded
            return r.contentEquals(computedR)
            
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Generate deterministic nonce for Schnorr signature (RFC 6979 style)
     */
    private fun generateNonce(privateKey: BigInteger, messageHash: ByteArray, publicKeyBytes: ByteArray): BigInteger {
        // Simple nonce generation - in production, use RFC 6979
        // For now, use SHA256(private_key || message || public_key || random)
        val random = ByteArray(32)
        secureRandom.nextBytes(random)
        
        val privateKeyBytes = privateKey.toByteArray()
        val nonceInput = ByteArray(privateKeyBytes.size + messageHash.size + publicKeyBytes.size + random.size)
        var offset = 0
        
        System.arraycopy(privateKeyBytes, 0, nonceInput, offset, privateKeyBytes.size)
        offset += privateKeyBytes.size
        
        System.arraycopy(messageHash, 0, nonceInput, offset, messageHash.size)
        offset += messageHash.size
        
        System.arraycopy(publicKeyBytes, 0, nonceInput, offset, publicKeyBytes.size)
        offset += publicKeyBytes.size
        
        System.arraycopy(random, 0, nonceInput, offset, random.size)
        
        val nonceHash = MessageDigest.getInstance("SHA-256").digest(nonceInput)
        val nonce = BigInteger(1, nonceHash)
        
        // Ensure nonce is in valid range
        return if (nonce >= secp256k1Params.n) {
            nonce.mod(secp256k1Params.n)
        } else {
            nonce
        }
    }
}
