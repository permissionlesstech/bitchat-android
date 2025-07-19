package com.bitchat.android.crypto

import android.content.Context
import android.content.SharedPreferences
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * Encryption service that's 100% compatible with iOS version
 * Uses the same cryptographic algorithms and key derivation
 */
class EncryptionService(private val context: Context) {
    
    // Key agreement keys for encryption
    private val privateKey: X25519PrivateKeyParameters
    private val publicKey: X25519PublicKeyParameters
    
    // Signing keys for authentication
    private val signingPrivateKey: Ed25519PrivateKeyParameters
    private val signingPublicKey: Ed25519PublicKeyParameters
    
    // Persistent identity for favorites (separate from ephemeral keys)
    private lateinit var identityKey: Ed25519PrivateKeyParameters
    private lateinit var identityPublicKey: Ed25519PublicKeyParameters
    
    // Storage for peer keys
    private val peerPublicKeys = mutableMapOf<String, X25519PublicKeyParameters>()
    private val peerSigningKeys = mutableMapOf<String, Ed25519PublicKeyParameters>()
    private val peerIdentityKeys = mutableMapOf<String, Ed25519PublicKeyParameters>()
    private val sharedSecrets = mutableMapOf<String, ByteArray>()
    
    private val prefs: SharedPreferences = context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()
    
    init {
        // Generate ephemeral key pairs for this session
        val x25519Generator = X25519KeyPairGenerator()
        x25519Generator.init(X25519KeyGenerationParameters(secureRandom))
        val x25519KeyPair = x25519Generator.generateKeyPair()
        privateKey = x25519KeyPair.private as X25519PrivateKeyParameters
        publicKey = x25519KeyPair.public as X25519PublicKeyParameters
        
        val ed25519Generator = Ed25519KeyPairGenerator()
        ed25519Generator.init(Ed25519KeyGenerationParameters(secureRandom))
        val ed25519KeyPair = ed25519Generator.generateKeyPair()
        signingPrivateKey = ed25519KeyPair.private as Ed25519PrivateKeyParameters
        signingPublicKey = ed25519KeyPair.public as Ed25519PublicKeyParameters
        
        // Load or create persistent identity key
        val identityKeyBytes = prefs.getString("identity_key", null)
        if (identityKeyBytes != null) {
            try {
                val keyBytes = android.util.Base64.decode(identityKeyBytes, android.util.Base64.DEFAULT)
                identityKey = Ed25519PrivateKeyParameters(keyBytes, 0)
            } catch (e: Exception) {
                // Create new identity key if loading fails
                val newIdentityKeyPair = ed25519Generator.generateKeyPair()
                identityKey = newIdentityKeyPair.private as Ed25519PrivateKeyParameters
                saveIdentityKey()
            }
        } else {
            // First run - create and save identity key
            val newIdentityKeyPair = ed25519Generator.generateKeyPair()
            identityKey = newIdentityKeyPair.private as Ed25519PrivateKeyParameters
            saveIdentityKey()
        }
        identityPublicKey = Ed25519PublicKeyParameters(identityKey.encoded, 0)
    }
    
    private fun saveIdentityKey() {
        val keyBytes = android.util.Base64.encodeToString(identityKey.encoded, android.util.Base64.DEFAULT)
        prefs.edit().putString("identity_key", keyBytes).apply()
    }
    
    /**
     * Create combined public key data for exchange - exactly same format as iOS
     * 96 bytes total: 32 (X25519) + 32 (Ed25519 signing) + 32 (Ed25519 identity)
     */
    fun getCombinedPublicKeyData(): ByteArray {
        val combined = ByteArray(PUBLIC_KEY_DATA_SIZE)
        System.arraycopy(publicKey.encoded, 0, combined, 0, 32)  // X25519 key
        System.arraycopy(signingPublicKey.encoded, 0, combined, 32, 32)  // Ed25519 signing key
        System.arraycopy(identityPublicKey.encoded, 0, combined, 64, 32)  // Ed25519 identity key
        return combined
    }
    
    /**
     * Add peer's combined public keys - exactly same logic as iOS
     */
    @Throws(Exception::class)
    fun addPeerPublicKey(peerID: String, publicKeyData: ByteArray) {
        if (publicKeyData.size != PUBLIC_KEY_DATA_SIZE) {
            throw Exception("Invalid public key data size: ${publicKeyData.size}, expected 96")
        }
        
        // Extract all three keys: 32 for key agreement + 32 for signing + 32 for identity
        val keyAgreementData = publicKeyData.sliceArray(0..31)
        val signingKeyData = publicKeyData.sliceArray(32..63)
        val identityKeyData = publicKeyData.sliceArray(64..95)
        
        val peerPublicKey = X25519PublicKeyParameters(keyAgreementData, 0)
        peerPublicKeys[peerID] = peerPublicKey
        
        val peerSigningKey = Ed25519PublicKeyParameters(signingKeyData, 0)
        peerSigningKeys[peerID] = peerSigningKey
        
        val peerIdentityKey = Ed25519PublicKeyParameters(identityKeyData, 0)
        peerIdentityKeys[peerID] = peerIdentityKey
        
        // Generate shared secret for encryption using X25519
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(peerPublicKey, sharedSecret, 0)
        
        // Derive symmetric key using HKDF with same salt as iOS
        val salt = "bitchat-v1".toByteArray()
        val derivedKey = hkdf(sharedSecret, salt, byteArrayOf(), 32)
        sharedSecrets[peerID] = derivedKey
    }
    
    /**
     * Get peer's persistent identity key for favorites
     */
    fun getPeerIdentityKey(peerID: String): ByteArray? {
        return peerIdentityKeys[peerID]?.encoded
    }
    
    /**
     * Clear persistent identity (for panic mode)
     */
    fun clearPersistentIdentity() {
        prefs.edit().remove("identity_key").apply()
    }
    
    /**
     * Encrypt data for a specific peer using AES-256-GCM
     */
    @Throws(Exception::class)
    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val symmetricKey = sharedSecrets[peerID] 
            ?: throw Exception("No shared secret for peer $peerID")
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(symmetricKey, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data)
        
        // Combine IV and ciphertext (same format as iOS AES.GCM.SealedBox.combined)
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        
        return combined
    }
    
    /**
     * Decrypt data from a specific peer
     */
    @Throws(Exception::class)
    fun decrypt(data: ByteArray, peerID: String): ByteArray {
        val symmetricKey = sharedSecrets[peerID] 
            ?: throw Exception("No shared secret for peer $peerID")
        
        if (data.size < 16) { // 12 bytes IV + 16 bytes tag minimum for GCM
            throw Exception("Invalid encrypted data size")
        }
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(symmetricKey, "AES")
        
        // Extract IV and ciphertext
        val iv = data.sliceArray(0..11)  // GCM IV is 12 bytes
        val ciphertext = data.sliceArray(12 until data.size)
        
        val gcmSpec = GCMParameterSpec(128, iv)  // 128-bit authentication tag
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        
        return cipher.doFinal(ciphertext)
    }
    
    /**
     * Sign data using Ed25519
     */
    @Throws(Exception::class)
    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, signingPrivateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }
    
    /**
     * Verify signature using Ed25519
     */
    @Throws(Exception::class)
    fun verify(signature: ByteArray, data: ByteArray, peerID: String): Boolean {
        val verifyingKey = peerSigningKeys[peerID] 
            ?: throw Exception("No signing key for peer $peerID")
        
        val signer = Ed25519Signer()
        signer.init(false, verifyingKey)
        signer.update(data, 0, data.size)
        return signer.verifySignature(signature)
    }
    
    /**
     * HKDF implementation using SHA256 - same as iOS HKDF
     */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // Extract
        val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
        val saltKey = SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256")
        hmac.init(saltKey)
        val prk = hmac.doFinal(ikm)
        
        // Expand
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var offset = 0
        var counter = 1
        
        while (offset < length) {
            hmac.reset()
            if (counter > 1) {
                hmac.update(result, offset - 32, 32)
            }
            hmac.update(info)
            hmac.update(counter.toByte())
            
            val t = hmac.doFinal()
            val remaining = length - offset
            val toCopy = minOf(t.size, remaining)
            System.arraycopy(t, 0, result, offset, toCopy)
            
            offset += toCopy
            counter++
        }
        
        return result
    }

    companion object {
        const val PUBLIC_KEY_DATA_SIZE = 96
    }
}

/**
 * Message padding utilities - exact same as iOS version
 */
object MessagePadding {
    // Standard block sizes for padding
    private val blockSizes = listOf(256, 512, 1024, 2048)
    
    /**
     * Add PKCS#7-style padding to reach target size
     */
    fun pad(data: ByteArray, targetSize: Int): ByteArray {
        if (data.size >= targetSize) return data
        
        val paddingNeeded = targetSize - data.size
        
        // PKCS#7 only supports padding up to 255 bytes
        if (paddingNeeded > 255) return data
        
        val padded = ByteArray(targetSize)
        System.arraycopy(data, 0, padded, 0, data.size)
        
        // Fill with random bytes except the last byte
        val random = SecureRandom()
        random.nextBytes(padded.sliceArray(data.size until targetSize - 1))
        
        // Last byte indicates padding length (PKCS#7)
        padded[targetSize - 1] = paddingNeeded.toByte()
        
        return padded
    }
    
    /**
     * Remove padding from data
     */
    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        
        // Last byte tells us how much padding to remove
        val paddingLength = (data.last() and 0xFF.toByte()).toInt()
        if (paddingLength <= 0 || paddingLength > data.size) return data
        
        return data.sliceArray(0 until (data.size - paddingLength))
    }
    
    /**
     * Find optimal block size for data
     */
    fun optimalBlockSize(dataSize: Int): Int {
        // Account for encryption overhead (~16 bytes for AES-GCM tag)
        val totalSize = dataSize + 16
        
        // Find smallest block that fits
        for (blockSize in blockSizes) {
            if (totalSize <= blockSize) {
                return blockSize
            }
        }
        
        // For very large messages, just use the original size
        return dataSize
    }
}
