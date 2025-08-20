package com.bitchat.android.nostr

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.math.BigInteger

/**
 * Cryptographic utilities for Nostr protocol
 * Includes secp256k1 operations, ECDH, and NIP-44 encryption
 */
object NostrCrypto {
    
    private val secureRandom = SecureRandom()
    
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
        
        // Get private key as 32-byte hex
        val privateKeyBytes = privateKey.d.toByteArray()
        val privateKeyPadded = ByteArray(32)
        if (privateKeyBytes.size <= 32) {
            System.arraycopy(
                privateKeyBytes, 
                maxOf(0, privateKeyBytes.size - 32),
                privateKeyPadded, 
                maxOf(0, 32 - privateKeyBytes.size),
                minOf(privateKeyBytes.size, 32)
            )
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
        
        // Try to recover full public key point from x-only coordinate
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
    
    /**
     * NIP-44 key derivation using HKDF
     */
    fun deriveNIP44Key(sharedSecret: ByteArray): ByteArray {
        val salt = "nip44-v2".toByteArray(Charsets.UTF_8)
        
        // HKDF-Extract
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(salt))
        hmac.update(sharedSecret, 0, sharedSecret.size)
        val prk = ByteArray(hmac.macSize)
        hmac.doFinal(prk, 0)
        
        // HKDF-Expand (we need 32 bytes for AES-256)
        hmac.init(KeyParameter(prk))
        hmac.update(byteArrayOf(0x01), 0, 1) // info = empty, N = 1
        val okm = ByteArray(hmac.macSize)
        hmac.doFinal(okm, 0)
        
        // Return first 32 bytes
        return okm.copyOf(32)
    }
    
    /**
     * NIP-44 encryption using AES-256-GCM
     */
    fun encryptNIP44(plaintext: String, recipientPublicKeyHex: String, senderPrivateKeyHex: String): String {
        try {
            // Perform ECDH
            val sharedSecret = performECDH(senderPrivateKeyHex, recipientPublicKeyHex)
            
            // Derive encryption key
            val encryptionKey = deriveNIP44Key(sharedSecret)
            
            // Generate random nonce (12 bytes for GCM)
            val nonce = ByteArray(12)
            secureRandom.nextBytes(nonce)
            
            // Encrypt using AES-256-GCM
            val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(encryptionKey, "AES")
            val gcmSpec = GCMParameterSpec(128, nonce) // 128-bit auth tag
            
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            val ciphertext = cipher.doFinal(plaintextBytes)
            
            // Combine nonce + ciphertext (includes auth tag)
            val result = ByteArray(nonce.size + ciphertext.size)
            System.arraycopy(nonce, 0, result, 0, nonce.size)
            System.arraycopy(ciphertext, 0, result, nonce.size, ciphertext.size)
            
            // Base64 encode
            return android.util.Base64.encodeToString(result, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            throw RuntimeException("NIP-44 encryption failed: ${e.message}", e)
        }
    }
    
    /**
     * NIP-44 decryption using AES-256-GCM
     */
    fun decryptNIP44(ciphertext: String, senderPublicKeyHex: String, recipientPrivateKeyHex: String): String {
        try {
            // Decode base64
            val encryptedData = android.util.Base64.decode(ciphertext, android.util.Base64.NO_WRAP)
            
            // Extract nonce and ciphertext
            require(encryptedData.size >= 12 + 16) { "Ciphertext too short" }
            val nonce = encryptedData.copyOfRange(0, 12)
            val ciphertextBytes = encryptedData.copyOfRange(12, encryptedData.size)
            
            // Perform ECDH
            val sharedSecret = performECDH(recipientPrivateKeyHex, senderPublicKeyHex)
            
            // Derive decryption key
            val decryptionKey = deriveNIP44Key(sharedSecret)
            
            // Decrypt using AES-256-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(decryptionKey, "AES")
            val gcmSpec = GCMParameterSpec(128, nonce)
            
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            val plaintextBytes = cipher.doFinal(ciphertextBytes)
            
            return String(plaintextBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            throw RuntimeException("NIP-44 decryption failed: ${e.message}", e)
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
}
