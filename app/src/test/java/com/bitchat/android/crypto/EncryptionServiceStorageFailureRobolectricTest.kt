package com.bitchat.android.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Synthetic editor failures; does not model process death or physical storage failure. */
@RunWith(RobolectricTestRunner::class)
class EncryptionServiceStorageFailureRobolectricTest {
    private lateinit var context: Context
    private lateinit var legacy: SharedPreferences
    private lateinit var secure: SharedPreferences
    private lateinit var service: EncryptionService
    private val services = mutableListOf<EncryptionService>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        legacy = context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE)
        assertTrue(legacy.edit().clear().commit())
        service = newService()
        secure = preferencesField().get(service) as SharedPreferences
        val signingKey = requireNotNull(secure.getString(SIGNING_KEY, null))
        // Flush the generated destination and leave an identical legacy migration source.
        assertTrue(secure.edit().putString(SIGNING_KEY, signingKey).commit())
        assertTrue(legacy.edit().putString(SIGNING_KEY, signingKey).commit())
    }

    @After
    fun tearDown() {
        services.forEach { it.shutdown() }
    }

    @Test
    fun encryptedRemovalFailureReportsIncompleteWithoutReplacingSigningKey() {
        val before = requireNotNull(service.getSigningPublicKey()).clone()
        var removalAttempted = false
        var commitAttempted = false
        var replacementAttempted = false
        interceptEditors { editor ->
            object : SharedPreferences.Editor by editor {
                override fun remove(key: String): SharedPreferences.Editor {
                    if (key == SIGNING_KEY) removalAttempted = true
                    return this
                }
                override fun commit(): Boolean {
                    commitAttempted = true
                    return false
                }
                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                    replacementAttempted = true
                    return this
                }
            }
        }

        assertFalse("Unconfirmed encrypted removal must report failure", service.clearPersistentIdentity())
        assertFalse("Legacy cleanup precedes encrypted removal", legacy.contains(SIGNING_KEY))
        assertTrue("Encrypted signing removal must be attempted", removalAttempted)
        assertTrue("Encrypted removal result must be checked", commitAttempted)
        assertFalse("Removal failure must stop replacement saving", replacementAttempted)
        assertArrayEquals("Removal failure must not rotate the in-memory signing key",
            before, service.getSigningPublicKey())
    }

    @Test
    fun replacementSaveExceptionDoesNotRestoreRemovedSigningIdentity() {
        val before = requireNotNull(service.getSigningPublicKey()).clone()
        var saveExceptionReached = false
        interceptEditors { editor ->
            object : SharedPreferences.Editor by editor {
                override fun remove(key: String): SharedPreferences.Editor {
                    editor.remove(key)
                    return this
                }
                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }
                override fun apply() {
                    assertFalse("Legacy source must be removed before replacement saving",
                        legacy.contains(SIGNING_KEY))
                    assertFalse("Encrypted old key must be removed before replacement saving",
                        secure.contains(SIGNING_KEY))
                    saveExceptionReached = true
                    throw IllegalStateException("synthetic replacement-save exception")
                }
            }
        }

        // Do not require success on a failed save or prescribe replacement continuity.
        service.clearPersistentIdentity()
        assertTrue("Replacement save must reach the modeled exception", saveExceptionReached)
        assertFalse("Legacy source must stay removed", legacy.contains(SIGNING_KEY))
        assertFalse("The in-memory signing identity must not reuse the old key",
            before.contentEquals(service.getSigningPublicKey()))
        assertUsableSignature(service)
        service.shutdown()
        services.remove(service)

        val reloaded = newService()
        assertFalse("Later initialization must not recover the erased signing identity",
            before.contentEquals(reloaded.getSigningPublicKey()))
        assertFalse("Later initialization must not restore the legacy source",
            legacy.contains(SIGNING_KEY))
        assertUsableSignature(reloaded)
    }

    private fun interceptEditors(wrap: (SharedPreferences.Editor) -> SharedPreferences.Editor) {
        val intercepted = object : SharedPreferences by secure {
            override fun edit(): SharedPreferences.Editor = wrap(secure.edit())
        }
        preferencesField().set(service, intercepted)
    }

    private fun preferencesField() = EncryptionService::class.java.getDeclaredField("prefs")
        .apply { isAccessible = true }

    private fun newService() = EncryptionService(context).also { services.add(it) }

    private fun assertUsableSignature(target: EncryptionService) {
        val message = "synthetic signing failure fixture".toByteArray(Charsets.UTF_8)
        val signature = target.signData(message)
        assertNotNull("Signing must return a signature", signature)
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(requireNotNull(target.getSigningPublicKey()), 0))
        verifier.update(message, 0, message.size)
        assertTrue("Signature must verify with the advertised signing key",
            verifier.verifySignature(requireNotNull(signature)))
    }

    companion object {
        private const val SIGNING_KEY = "ed25519_signing_private_key"
    }
}
