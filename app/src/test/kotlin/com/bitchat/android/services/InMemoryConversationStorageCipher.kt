package com.bitchat.android.services

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class InMemoryConversationStorageCipher : ConversationStorageCipher {
    private var key: SecretKey = generateKey()

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray {
        require(envelope.size > 13 && envelope[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, envelope.copyOfRange(1, 13))
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(envelope.copyOfRange(13, envelope.size))
    }

    override fun destroyKey() {
        key = generateKey()
    }

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply {
            init(256, SecureRandom())
        }.generateKey()
}
