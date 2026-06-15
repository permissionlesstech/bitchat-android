package com.bitchat.android.identity

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureIdentityStateManagerTest {
    private lateinit var manager: SecureIdentityStateManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = SecureIdentityStateManager(context)
        manager.clearIdentityData()
    }

    @After
    fun tearDown() {
        manager.clearIdentityData()
    }

    @Test
    fun loadStaticKey_returnsNullWhenBothKeysAreAbsent() {
        assertNull(manager.loadStaticKey())
    }

    @Test
    fun loadStaticKey_throwsWhenOnlyOneKeyExists() {
        manager.storeSecureValue("static_private_key", encoded(ByteArray(32) { 1 }))

        assertThrows(IllegalStateException::class.java) {
            manager.loadStaticKey()
        }
    }

    @Test
    fun loadSigningKey_throwsWhenStoredKeyHasInvalidSize() {
        manager.storeSecureValue("signing_private_key", encoded(ByteArray(31) { 1 }))
        manager.storeSecureValue("signing_public_key", encoded(ByteArray(32) { 2 }))

        assertThrows(IllegalStateException::class.java) {
            manager.loadSigningKey()
        }
    }

    private fun encoded(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.DEFAULT)
    }
}
