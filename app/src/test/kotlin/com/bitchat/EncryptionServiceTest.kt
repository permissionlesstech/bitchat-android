package com.bitchat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.crypto.EncryptionService
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptionServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val encryptionService = EncryptionService(context)

    @Test
    fun is_combined_public_key_bytes_correct_size() {
        val publicKeyData = encryptionService.getCombinedPublicKeyData()
        assertEquals(EncryptionService.Companion.PUBLIC_KEY_DATA_SIZE, publicKeyData.size)
    }

    @Test
    fun is_combined_public_key_bytes_not_empty() {
        val publicKeyData = encryptionService.getCombinedPublicKeyData()
        assertTrue(publicKeyData.isNotEmpty())
    }
}