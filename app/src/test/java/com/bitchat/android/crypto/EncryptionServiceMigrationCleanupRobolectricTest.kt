package com.bitchat.android.crypto

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Models a migration cleanup exception; does not interrupt a device or fail a real disk write. */
@RunWith(RobolectricTestRunner::class)
class EncryptionServiceMigrationCleanupRobolectricTest {
    @Test
    fun ordinaryLegacyMigrationPreservesIdentityAndRemovesSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = prepareLegacyOnly(context)
        val service = EncryptionService(context)
        try {
            assertSeedIdentity(service)
            assertFalse("Completed migration removes its legacy source", legacy.contains(KEY))
            assertTrue("Migration writes its encrypted destination", secure(context).contains(KEY))
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun cleanupExceptionAfterMigrationCopyLeavesSourceThatPanicMustRemove() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val legacy = prepareLegacyOnly(base)
        var cleanupExceptionObserved = false
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name != LEGACY) return super.getSharedPreferences(name, mode)
                return object : SharedPreferences by legacy {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = legacy.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun remove(key: String): SharedPreferences.Editor {
                                editor.remove(key)
                                return this
                            }
                            override fun apply() {
                                if (!cleanupExceptionObserved) {
                                    cleanupExceptionObserved = true
                                    throw IllegalStateException("synthetic migration cleanup exception")
                                }
                                editor.apply()
                            }
                        }
                    }
                }
            }
        }
        val migrated = EncryptionService(context)
        try {
            assertTrue("Actual migration reached the modeled cleanup cut point", cleanupExceptionObserved)
            assertSeedIdentity(migrated)
            assertTrue("Cleanup exception leaves the original legacy source", legacy.contains(KEY))
            assertTrue("Destination was written before cleanup", secure(base).contains(KEY))
        } finally {
            migrated.shutdown()
        }
        val restarted = EncryptionService(base)
        try {
            assertSeedIdentity(restarted)
            assertTrue("Existing encrypted key prevents retrying legacy cleanup", legacy.contains(KEY))
            assertTrue("Identity reset should succeed", restarted.clearPersistentIdentity())
            assertFalse("Panic removes the leftover migration source", legacy.contains(KEY))
            assertFalse("Panic replaces the migrated signing identity",
                publicKey().contentEquals(restarted.getSigningPublicKey()))
        } finally {
            restarted.shutdown()
        }
    }

    private fun prepareLegacyOnly(context: Context): SharedPreferences {
        val legacy = context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE)
        assertTrue(legacy.edit().clear().putString(KEY,
            Base64.getEncoder().encodeToString(ByteArray(32) { 73 })).commit())
        assertTrue(secure(context).edit().clear().commit())
        return legacy
    }

    private fun secure(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context, "bitchat_crypto_secure",
        MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    private fun publicKey() = Ed25519PrivateKeyParameters(ByteArray(32) { 73 }, 0).generatePublicKey().encoded

    private fun assertSeedIdentity(service: EncryptionService) {
        assertTrue("Signing identity matches the synthetic legacy seed",
            publicKey().contentEquals(service.getSigningPublicKey()))
    }

    companion object {
        private const val LEGACY = "bitchat_crypto"
        private const val KEY = "ed25519_signing_private_key"
    }
}
