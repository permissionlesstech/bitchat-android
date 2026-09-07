package com.bitchat.android.identity

import android.content.Context
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.VouchAttestation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class VouchPersistenceTest {
    private lateinit var manager: SecureIdentityStateManager
    private lateinit var prefs: android.content.SharedPreferences
    private val voucher = fingerprint(VOUCHER_INDEX)
    private val vouchee = fingerprint(VOUCHEE_INDEX)
    private val voucheeSigningKey = ByteArray(VouchAttestation.SIGNING_KEY_SIZE) {
        VOUCHEE_SIGNING_KEY_BYTE
    }

    @Before
    fun setup() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
            "$PREFS_PREFIX${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
        manager = SecureIdentityStateManager(prefs, testOnly = true)
        manager.clearIdentityData()
        manager.setVerifiedFingerprint(voucher, true)
        manager.storeAuthenticatedPeerState(
            vouchee,
            AuthenticatedPeerState(PeerCapabilities.VOUCH, voucheeSigningKey)
        )
    }

    @After
    fun tearDown() = manager.clearIdentityData()

    @Test
    fun `vouch persists and derives trust only while voucher remains verified`() {
        assertTrue(
            manager.recordVouch(vouchee, voucher, voucheeSigningKey, TEST_NOW_MS, TEST_NOW_MS)
        )
        assertTrue(manager.isVouched(vouchee, TEST_NOW_MS))
        assertTrue(SecureIdentityStateManager(prefs, testOnly = true).isVouched(vouchee, TEST_NOW_MS))

        manager.setVerifiedFingerprint(voucher, false)
        assertFalse(manager.isVouched(vouchee, TEST_NOW_MS))
        manager.setVerifiedFingerprint(voucher, true)
        assertTrue(manager.isVouched(vouchee, TEST_NOW_MS))
    }

    @Test
    fun `storage rejects self verified stale and future vouches`() {
        assertFalse(
            manager.recordVouch(voucher, voucher, voucheeSigningKey, TEST_NOW_MS, TEST_NOW_MS)
        )
        manager.setVerifiedFingerprint(vouchee, true)
        assertFalse(
            manager.recordVouch(vouchee, voucher, voucheeSigningKey, TEST_NOW_MS, TEST_NOW_MS)
        )
        manager.setVerifiedFingerprint(vouchee, false)
        assertFalse(
            manager.recordVouch(
                vouchee,
                voucher,
                voucheeSigningKey,
                TEST_NOW_MS - VouchAttestation.MAX_AGE_MS - INVALID_TIME_DELTA_MS,
                TEST_NOW_MS
            )
        )
        assertFalse(
            manager.recordVouch(
                vouchee,
                voucher,
                voucheeSigningKey,
                TEST_NOW_MS + VouchAttestation.MAX_CLOCK_SKEW_MS + INVALID_TIME_DELTA_MS,
                TEST_NOW_MS
            )
        )
    }

    @Test
    fun `only the most recent bounded voucher set is retained`() {
        repeat(SecureIdentityStateManager.MAX_VOUCHERS_PER_VOUCHEE + EXTRA_VOUCHER_COUNT) { index ->
            val candidate = fingerprint(index + FIRST_GENERATED_VOUCHER_INDEX)
            manager.setVerifiedFingerprint(candidate, true)
            manager.recordVouch(
                vouchee,
                candidate,
                voucheeSigningKey,
                TEST_NOW_MS + index,
                TEST_NOW_MS
            )
        }

        val records = manager.validVouchers(vouchee, TEST_NOW_MS)
        assertEquals(SecureIdentityStateManager.MAX_VOUCHERS_PER_VOUCHEE, records.size)
        assertFalse(records.any { it.voucherFingerprint == fingerprint(FIRST_GENERATED_VOUCHER_INDEX) })
    }

    @Test
    fun `vouch only counts for the attested authenticated signing key`() {
        assertTrue(
            manager.recordVouch(vouchee, voucher, voucheeSigningKey, TEST_NOW_MS, TEST_NOW_MS)
        )
        assertTrue(manager.isVouched(vouchee, TEST_NOW_MS))

        manager.storeAuthenticatedPeerState(
            vouchee,
            AuthenticatedPeerState(PeerCapabilities.VOUCH, rotatedSigningKey())
        )
        assertFalse(manager.isVouched(vouchee, TEST_NOW_MS))

        manager.storeAuthenticatedPeerState(
            vouchee,
            AuthenticatedPeerState(PeerCapabilities.VOUCH, voucheeSigningKey)
        )
        assertTrue(manager.isVouched(vouchee, TEST_NOW_MS))
    }

    @Test
    fun `next expiry tracks when derived trust must refresh`() {
        manager.recordVouch(vouchee, voucher, voucheeSigningKey, TEST_NOW_MS, TEST_NOW_MS)
        val expectedExpiry = TEST_NOW_MS + VouchAttestation.MAX_AGE_MS + EXPIRY_TRANSITION_OFFSET_MS

        assertEquals(expectedExpiry, manager.nextVouchExpiryMs(TEST_NOW_MS))
        assertEquals(null, manager.nextVouchExpiryMs(expectedExpiry))
    }

    @Test
    fun `rate limit and vouch graph clear with panic wipe`() {
        manager.markVouchBatchSent(voucher, TEST_NOW_MS)
        manager.recordVouch(vouchee, voucher, voucheeSigningKey, TEST_NOW_MS, TEST_NOW_MS)
        assertEquals(TEST_NOW_MS, manager.lastVouchBatchSent(voucher))

        manager.clearIdentityData()
        assertEquals(null, manager.lastVouchBatchSent(voucher))
        assertTrue(manager.validVouchers(vouchee, TEST_NOW_MS).isEmpty())
    }

    @Test
    fun `manager surviving panic cannot recreate vouch state`() {
        val wipingManager = SecureIdentityStateManager(prefs, testOnly = true)
        wipingManager.clearIdentityData()

        assertFalse(
            manager.recordVouch(vouchee, voucher, voucheeSigningKey, TEST_NOW_MS, TEST_NOW_MS)
        )
        manager.markVouchBatchSent(voucher, TEST_NOW_MS)

        val reloaded = SecureIdentityStateManager(prefs, testOnly = true)
        assertTrue(reloaded.validVouchers(vouchee, TEST_NOW_MS).isEmpty())
        assertEquals(null, reloaded.lastVouchBatchSent(voucher))
    }

    private fun rotatedSigningKey() = ByteArray(VouchAttestation.SIGNING_KEY_SIZE) {
        ROTATED_SIGNING_KEY_BYTE
    }

    private fun fingerprint(index: Int): String =
        index.toString(HEX_RADIX).padStart(FINGERPRINT_HEX_LENGTH, FINGERPRINT_PAD_CHAR)
            .takeLast(FINGERPRINT_HEX_LENGTH)

    companion object {
        private const val PREFS_PREFIX = "vouch-persistence-"
        private const val VOUCHER_INDEX = 1
        private const val VOUCHEE_INDEX = 2
        private const val FIRST_GENERATED_VOUCHER_INDEX = 10
        private const val EXTRA_VOUCHER_COUNT = 2
        private const val INVALID_TIME_DELTA_MS = 1L
        private const val EXPIRY_TRANSITION_OFFSET_MS = 1L
        private const val VOUCHEE_SIGNING_KEY_BYTE: Byte = 0x31
        private const val ROTATED_SIGNING_KEY_BYTE: Byte = 0x32
        private const val TEST_NOW_MS = 1_700_000_000_000L
        private const val HEX_RADIX = 16
        private const val FINGERPRINT_HEX_LENGTH = VouchAttestation.FINGERPRINT_SIZE * 2
        private const val FINGERPRINT_PAD_CHAR = '0'
    }
}
