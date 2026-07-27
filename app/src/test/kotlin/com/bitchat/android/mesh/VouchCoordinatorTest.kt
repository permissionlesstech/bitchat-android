package com.bitchat.android.mesh

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.VouchAttestation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class VouchCoordinatorTest {
    private lateinit var identity: SecureIdentityStateManager
    private lateinit var scope: CoroutineScope
    private val sentPayloads = mutableListOf<ByteArray>()
    private val peerFingerprint = fingerprint(PEER_FINGERPRINT_INDEX)
    private val voucheeFingerprint = fingerprint(VOUCHEE_FINGERPRINT_INDEX)
    private var capabilities = PeerCapabilities.VOUCH

    @Before
    fun setup() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
            "$PREFS_PREFIX${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
        identity = SecureIdentityStateManager(prefs, testOnly = true)
        identity.clearIdentityData()
        identity.setVerifiedFingerprint(peerFingerprint, true)
        identity.setVerifiedFingerprint(voucheeFingerprint, true)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        identity.clearIdentityData()
    }

    @Test
    fun `send policy excludes recipient and persists interval`() {
        val coordinator = coordinator()

        assertTrue(coordinator.attemptVouch(PEER_ID, peerFingerprint, TEST_NOW_MS))
        val firstBatch = VouchAttestation.decodeList(sentPayloads.single())
        assertEquals(SINGLE_ATTESTATION_COUNT, firstBatch.size)
        assertEquals(voucheeFingerprint, firstBatch.single().voucheeFingerprint.toHex())
        assertFalse(
            coordinator.attemptVouch(
                PEER_ID,
                peerFingerprint,
                TEST_NOW_MS + VouchCoordinator.BATCH_INTERVAL_MS - BEFORE_INTERVAL_DELTA_MS
            )
        )
        assertTrue(
            coordinator.attemptVouch(
                PEER_ID,
                peerFingerprint,
                TEST_NOW_MS + VouchCoordinator.BATCH_INTERVAL_MS
            )
        )
    }

    @Test
    fun `unsupported capability blocks send but unknown remains race tolerant`() {
        capabilities = PeerCapabilities.PRIVATE_MEDIA
        assertFalse(coordinator().attemptVouch(PEER_ID, peerFingerprint, TEST_NOW_MS))

        capabilities = PeerCapabilities.NONE
        assertTrue(coordinator().attemptVouch(PEER_ID, peerFingerprint, TEST_NOW_MS))
    }

    private fun coordinator() = VouchCoordinator(
        scope = scope,
        identity = identity,
        connectedPeerIDs = { listOf(PEER_ID) },
        fingerprintForPeer = { peerFingerprint },
        peerInfo = {
            PeerInfo(
                id = PEER_ID,
                nickname = PEER_NICKNAME,
                isConnected = true,
                isDirectConnection = true,
                noisePublicKey = ByteArray(VouchAttestation.FINGERPRINT_SIZE),
                signingPublicKey = ByteArray(VouchAttestation.SIGNING_KEY_SIZE),
                isVerifiedNickname = true,
                lastSeen = TEST_NOW_MS,
                capabilities = capabilities
            )
        },
        signingKeyForFingerprint = { ByteArray(VouchAttestation.SIGNING_KEY_SIZE) },
        hasEstablishedSession = { true },
        sign = { ByteArray(VouchAttestation.SIGNATURE_SIZE) },
        verify = { _, _, _ -> true },
        send = { _, payload -> sentPayloads.add(payload) }
    )

    private fun fingerprint(index: Int): String =
        index.toString(HEX_RADIX)
            .padStart(FINGERPRINT_HEX_LENGTH, FINGERPRINT_PAD_CHAR)
            .takeLast(FINGERPRINT_HEX_LENGTH)

    private fun ByteArray.toHex(): String = joinToString(HEX_SEPARATOR) {
        HEX_BYTE_FORMAT.format(it.toInt() and BYTE_MASK)
    }

    companion object {
        private const val PREFS_PREFIX = "vouch-coordinator-"
        private const val PEER_ID = "peer-id"
        private const val PEER_NICKNAME = "peer"
        private const val PEER_FINGERPRINT_INDEX = 1
        private const val VOUCHEE_FINGERPRINT_INDEX = 2
        private const val SINGLE_ATTESTATION_COUNT = 1
        private const val BEFORE_INTERVAL_DELTA_MS = 1L
        private const val TEST_NOW_MS = 1_700_000_000_000L
        private const val HEX_RADIX = 16
        private const val FINGERPRINT_HEX_LENGTH = VouchAttestation.FINGERPRINT_SIZE * 2
        private const val FINGERPRINT_PAD_CHAR = '0'
        private const val HEX_SEPARATOR = ""
        private const val HEX_BYTE_FORMAT = "%02x"
        private const val BYTE_MASK = 0xFF
    }
}
