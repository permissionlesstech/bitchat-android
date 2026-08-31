package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.util.hexEncodedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VerificationServiceTest {
    private lateinit var encryptionService: EncryptionService

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        encryptionService = EncryptionService(context)
        VerificationService.configure(encryptionService)
    }

    @Test
    fun `freshness check rejects both stale and future-dated timestamps`() {
        assertEquals(0L, verificationTimestampSkewSeconds(1_700_000_000L, 1_700_000_000L))
        assertEquals(60L, verificationTimestampSkewSeconds(1_700_000_060L, 1_700_000_000L))
        assertEquals(3_600L, verificationTimestampSkewSeconds(1_700_000_000L, 1_700_003_600L))
    }

    @Test
    fun `verifyScannedQR accepts a freshly signed payload`() {
        val qr = VerificationService.buildMyQRString(nickname = "alice", npub = null)
        assertNotNull(qr)
        assertNotNull(VerificationService.verifyScannedQR(qr!!, maxAgeSeconds = 60))
    }

    @Test
    fun `verifyScannedQR rejects a future-dated payload`() {
        val qr = signedQr(ts = (System.currentTimeMillis() / 1000L) + 3_600L)
        assertNull(VerificationService.verifyScannedQR(qr, maxAgeSeconds = 60))
    }

    @Test
    fun `verifyScannedQR rejects an expired payload`() {
        val qr = signedQr(ts = (System.currentTimeMillis() / 1000L) - 3_600L)
        assertNull(VerificationService.verifyScannedQR(qr, maxAgeSeconds = 60))
    }

    private fun signedQr(ts: Long): String {
        val noise = encryptionService.getStaticPublicKey()!!.joinToString("") { "%02x".format(it) }
        val sign = encryptionService.getSigningPublicKey()!!.joinToString("") { "%02x".format(it) }
        val payload = VerificationService.VerificationQR(
            v = 1,
            noiseKeyHex = noise,
            signKeyHex = sign,
            npub = null,
            nickname = "future-alice",
            ts = ts,
            nonceB64 = "AAAAAAAAAAAAAAAAAAAAAA",
            sigHex = ""
        )
        val signature = encryptionService.signData(payload.canonicalBytes())!!
        return payload.copy(sigHex = signature.hexEncodedString()).toUrlString()
    }
}
