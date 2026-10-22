package com.bitchat.android.identity

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * Seals created before the nickname binding existed.
 *
 * [NicknameBinding.sealAppliesToAnnounced] now fails closed without a
 * baseline, which is only safe because every seal that *can* be given one is
 * given one at startup. These cover that half; the policy half lives in
 * [NicknameBindingTest], which stays free of Android so it runs on a plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
class VerifiedNicknameBackfillTest {
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setup() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
            "verified-nickname-backfill-${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
    }

    private fun manager() = SecureIdentityStateManager(prefs, testOnly = true)

    @Test
    fun `a seal from before the binding is bound to the last name it was seen under`() {
        val fingerprint = "ab".repeat(32)
        val old = manager()
        old.clearIdentityData()
        // What an older build left behind: the trust, and a last-seen name,
        // with nothing joining them.
        old.setVerifiedFingerprint(fingerprint, true)
        old.cacheFingerprintNickname(fingerprint, "ravi")
        assertNull("precondition: nothing bound yet", old.getVerifiedNickname(fingerprint))

        // Next launch.
        val upgraded = manager()

        assertEquals("ravi", upgraded.getVerifiedNickname(fingerprint))
        assertTrue(upgraded.verifiedNicknameMismatch(fingerprint, "medic"))
        assertFalse(upgraded.verifiedNicknameMismatch(fingerprint, "ravi"))
    }

    @Test
    fun `the backfill never re-anchors a baseline already earned`() {
        val fingerprint = "cd".repeat(32)
        val m = manager()
        m.clearIdentityData()
        m.setVerifiedFingerprint(fingerprint, true, "ravi")
        // The key has since renamed, so the last-seen cache already reads
        // "medic". Re-deriving from it would hand the rename the seal.
        m.cacheFingerprintNickname(fingerprint, "medic")

        m.migrateVerifiedNicknames()

        assertEquals("ravi", m.getVerifiedNickname(fingerprint))
        assertTrue(m.verifiedNicknameMismatch(fingerprint, "medic"))
    }

    @Test
    fun `a key never seen announcing is left unbound and its seal withheld`() {
        val fingerprint = "ef".repeat(32)
        val m = manager()
        m.clearIdentityData()
        m.setVerifiedFingerprint(fingerprint, true)   // no name ever recorded

        m.migrateVerifiedNicknames()

        assertNull(m.getVerifiedNickname(fingerprint))
        assertTrue("no baseline means the seal covers no name",
                   m.verifiedNicknameMismatch(fingerprint, "medic"))
    }

    @Test
    fun `the backfill is idempotent`() {
        val fingerprint = "12".repeat(32)
        val m = manager()
        m.clearIdentityData()
        m.setVerifiedFingerprint(fingerprint, true)
        m.cacheFingerprintNickname(fingerprint, "ravi")

        m.migrateVerifiedNicknames()
        m.cacheFingerprintNickname(fingerprint, "medic")   // later rename
        m.migrateVerifiedNicknames()

        assertEquals("the second pass must not re-anchor", "ravi", m.getVerifiedNickname(fingerprint))
    }
}
