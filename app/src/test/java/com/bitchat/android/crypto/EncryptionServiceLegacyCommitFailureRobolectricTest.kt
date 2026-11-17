package com.bitchat.android.crypto

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

// Models only the legacy signing-preference commit result; no phone or disk failure.
@RunWith(RobolectricTestRunner::class)
class EncryptionServiceLegacyCommitFailureRobolectricTest {
    @Test
    fun legacyCommitFailureReturnsFalseWithoutReplacingSigningKey() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val legacy = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java)
        Mockito.`when`(legacy.edit()).thenReturn(editor)
        Mockito.`when`(editor.remove("ed25519_signing_private_key")).thenReturn(editor)
        Mockito.`when`(editor.commit()).thenReturn(false)
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "bitchat_crypto") legacy else super.getSharedPreferences(name, mode)
        }
        val service = EncryptionService(context)
        try {
            val before = requireNotNull(service.getSigningPublicKey()).clone()

            assertFalse("Legacy commit failure must return false", service.clearPersistentIdentity())
            Mockito.verify(editor).remove("ed25519_signing_private_key")
            Mockito.verify(editor).commit()
            assertArrayEquals("Signing key must not rotate on this failure return", before, service.getSigningPublicKey())
        } finally {
            service.shutdown()
        }
    }
}
