package com.bitchat.android.services

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts private-conversation payloads with a key that never leaves Android Keystore.
 *
 * Every value is bound to its database identity through AES-GCM associated data. This prevents an
 * encrypted payload copied from one message or conversation row from being accepted in another.
 * Deleting the dedicated alias provides practical cryptographic erasure before SQLite pages, WAL
 * records, and filesystem blocks are reclaimed.
 */
internal interface ConversationStorageCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray
    fun destroyKey()
}
internal class AndroidConversationStorageCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : ConversationStorageCipher {
    companion object {
        internal const val DEFAULT_KEY_ALIAS = "bitchat_conversation_storage_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENVELOPE_VERSION: Byte = 1
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
    }

    private val keyLock = Any()
    @Volatile
    private var cachedKey: SecretKey? = null

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        check(cipher.iv.size == IV_BYTES) { "Unexpected AES-GCM IV length" }
        return byteArrayOf(ENVELOPE_VERSION) + cipher.iv + ciphertext
    }

    override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray {
        require(envelope.size > 1 + IV_BYTES) { "Conversation payload envelope is truncated" }
        require(envelope[0] == ENVELOPE_VERSION) {
            "Unsupported conversation payload envelope version"
        }
        val iv = envelope.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = envelope.copyOfRange(1 + IV_BYTES, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }

    override fun destroyKey() {
        synchronized(keyLock) {
            cachedKey = null
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey =
        cachedKey ?: synchronized(keyLock) {
            cachedKey ?: run {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                (keyStore.getKey(keyAlias, null) as? SecretKey) ?: generateKey()
            }.also { cachedKey = it }
        }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
