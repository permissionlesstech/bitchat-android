package com.bitchat.android.crypto

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Ordinary signing-storage lifecycle checks; use a disposable target with synthetic data only. */
@RunWith(AndroidJUnit4::class)
class EncryptionServiceInstrumentedTest {
    private lateinit var context: Context
    private lateinit var legacyPrefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences
    private val services = mutableListOf<EncryptionService>()

    @Before
    fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("signingStorageDisposableData") == "true") {
            "Requires explicit signingStorageDisposableData=true on a disposable test target"
        }
        context = instrumentation.targetContext
        check(context.packageName == "com.bitchat.droid") { "Unexpected test target package" }
        check(context.applicationContext.javaClass == Application::class.java) {
            "Requires the bare Application supplied by PerformanceTestRunner"
        }
        legacyPrefs = context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE)
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        securePrefs = EncryptedSharedPreferences.create(
            context,
            "bitchat_crypto_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        assertTrue(legacyPrefs.edit().clear().commit())
        assertTrue(securePrefs.edit().clear().commit())
    }

    @After
    fun tearDown() {
        services.forEach { it.shutdown() }
    }

    @Test
    fun completedResetRotatesFreshIdentityAndReloadsReplacementSigningKey() {
        val service = newService()
        val initialStaticKey = service.getStaticPublicKey()!!.copyOf()
        val initialFingerprint = service.getIdentityFingerprint()
        assertResetReplacesSigningKey(service)
        assertFalse("Static key should change after reset",
            initialStaticKey.contentEquals(service.getStaticPublicKey()))
        assertNotEquals("Fingerprint should change after reset",
            initialFingerprint, service.getIdentityFingerprint())
    }

    @Test
    fun completedResetRemovesIdenticalLegacySigningCopyAndPreservesUnrelatedPreference() {
        assertDualStoreReset(legacySeed = 11, encryptedSeed = 11)
    }

    @Test
    fun completedResetDoesNotImportDifferingLegacySigningKey() {
        assertDualStoreReset(legacySeed = 22, encryptedSeed = 33)
    }

    @Test
    fun oldOnlyKeyMigratesWithoutChangingSigningIdentityBeforeReset() {
        val oldKey = seed(44)
        putKey(legacyPrefs, oldKey)
        putSentinel()
        val service = newService()
        assertPublicKeyMatches(oldKey, service)
        assertUsableSignature(service)
        assertFalse("Successful migration removes its source", legacyPrefs.contains(SIGNING_KEY))
        assertTrue("Migration writes encrypted storage", securePrefs.contains(SIGNING_KEY))
        val migrated = newService()
        assertPublicKeyMatches(oldKey, migrated)
        closeService(migrated)
        assertResetReplacesSigningKey(service, oldKey)
        assertSentinelPreserved()
    }

    @Test
    fun encryptedOnlyKeyLoadsUnchangedAndCompletedResetReplacesIt() {
        val encryptedKey = seed(55)
        putKey(securePrefs, encryptedKey)
        val service = newService()
        assertPublicKeyMatches(encryptedKey, service)
        assertUsableSignature(service)
        assertResetReplacesSigningKey(service, encryptedKey)
    }

    private fun assertDualStoreReset(legacySeed: Int, encryptedSeed: Int) {
        val legacyKey = seed(legacySeed)
        val encryptedKey = seed(encryptedSeed)
        putKey(legacyPrefs, legacyKey)
        putKey(securePrefs, encryptedKey)
        putSentinel()
        val service = newService()
        assertPublicKeyMatches(encryptedKey, service)
        assertTrue("Dual-store fixture retains readable legacy entry before reset",
            legacyPrefs.contains(SIGNING_KEY))
        assertTrue("Legacy fixture should decode to the seeded key",
            legacyKey.contentEquals(Base64.decode(legacyPrefs.getString(SIGNING_KEY, null), Base64.DEFAULT)))
        assertResetReplacesSigningKey(service, legacyKey, encryptedKey)
        assertSentinelPreserved()
    }

    private fun assertResetReplacesSigningKey(service: EncryptionService, vararg priorSeeds: ByteArray) {
        val priorPublicKey = service.getSigningPublicKey()!!.copyOf()
        assertTrue("Completed identity reset should report success", service.clearPersistentIdentity())
        assertFalse("Reset removes the legacy migration source", legacyPrefs.contains(SIGNING_KEY))
        val replacement = service.getSigningPublicKey()!!
        assertFalse("Reset replaces the active signing key", priorPublicKey.contentEquals(replacement))
        priorSeeds.forEach { oldSeed ->
            assertFalse("Reset must not reuse a seeded signing key",
                Ed25519PrivateKeyParameters(oldSeed, 0).generatePublicKey().encoded.contentEquals(replacement))
        }
        assertUsableSignature(service)
        closeService(service)
        val reloaded = newService()
        assertArrayEquals("A later instance loads the replacement signing key",
            replacement, reloaded.getSigningPublicKey())
        assertUsableSignature(reloaded)
        assertFalse("Reload does not restore the legacy entry", legacyPrefs.contains(SIGNING_KEY))
    }

    private fun assertUsableSignature(service: EncryptionService) {
        val message = "synthetic signing lifecycle fixture".toByteArray(Charsets.UTF_8)
        val signature = service.signData(message)
        assertNotNull("Signing should return a signature", signature)
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(service.getSigningPublicKey()!!, 0))
        verifier.update(message, 0, message.size)
        assertTrue("Signature should verify with the advertised signing key", verifier.verifySignature(signature!!))
    }

    private fun assertPublicKeyMatches(privateSeed: ByteArray, service: EncryptionService) {
        // Compare without printing private fixture material on assertion failure.
        assertTrue("Loaded public key should match the seeded signing identity",
            Ed25519PrivateKeyParameters(privateSeed, 0).generatePublicKey().encoded
                .contentEquals(service.getSigningPublicKey()))
    }

    private fun newService(): EncryptionService = EncryptionService(context).also { services.add(it) }

    private fun closeService(service: EncryptionService) {
        service.shutdown()
        services.remove(service)
    }

    private fun putKey(prefs: SharedPreferences, privateSeed: ByteArray) {
        assertTrue("Synthetic key fixture should be stored", prefs.edit()
            .putString(SIGNING_KEY, Base64.encodeToString(privateSeed, Base64.NO_WRAP)).commit())
    }

    private fun putSentinel() {
        assertTrue(legacyPrefs.edit().putString(SENTINEL, "synthetic retained value").commit())
    }

    private fun assertSentinelPreserved() {
        assertEquals("synthetic retained value", legacyPrefs.getString(SENTINEL, null))
    }

    private fun seed(value: Int): ByteArray = ByteArray(32) { value.toByte() }

    companion object {
        private const val SIGNING_KEY = "ed25519_signing_private_key"
        private const val SENTINEL = "synthetic_unrelated_preference"
    }
}
