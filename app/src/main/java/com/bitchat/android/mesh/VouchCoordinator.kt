package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.VouchAttestation
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Transport-neutral exchange and acceptance policy for transitive verification.
 *
 * All payloads supplied to [handlePayload] have already been authenticated and
 * decrypted by the Noise session for [peerID].
 */
class VouchCoordinator(
    private val scope: CoroutineScope,
    private val identity: SecureIdentityStateManager,
    private val connectedPeerIDs: () -> Collection<String>,
    private val fingerprintForPeer: (String) -> String?,
    private val peerInfo: (String) -> PeerInfo?,
    private val signingKeyForFingerprint: (String) -> ByteArray?,
    private val hasEstablishedSession: (String) -> Boolean,
    private val sign: (ByteArray) -> ByteArray?,
    private val verify: (ByteArray, ByteArray, ByteArray) -> Boolean,
    private val send: (String, ByteArray) -> Boolean
) {
    init {
        scope.launch {
            SecureIdentityStateManager.changes.collect {
                vouchToConnectedVerifiedPeers()
            }
        }
    }

    fun peerAuthenticated(peerID: String, fingerprint: String) {
        attemptVouch(peerID, fingerprint)
    }

    fun peersUpdated(peerIDs: Collection<String>) {
        peerIDs.forEach { peerID ->
            fingerprintForPeer(peerID)?.let { attemptVouch(peerID, it) }
        }
    }

    fun vouchToConnectedVerifiedPeers(nowMs: Long = System.currentTimeMillis()) {
        connectedPeerIDs().forEach { peerID ->
            fingerprintForPeer(peerID)?.let { attemptVouch(peerID, it, nowMs) }
        }
    }

    fun attemptVouch(
        peerID: String,
        peerFingerprint: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val normalizedPeerFingerprint = peerFingerprint.lowercase()
        if (!hasEstablishedSession(peerID) ||
            !identity.isVerifiedFingerprint(normalizedPeerFingerprint)
        ) return false

        val capabilities = peerInfo(peerID)?.capabilities
        if (capabilities != null && capabilities != PeerCapabilities.NONE &&
            !capabilities.contains(PeerCapabilities.VOUCH)
        ) return false

        val lastSent = identity.lastVouchBatchSent(normalizedPeerFingerprint)
        if (lastSent != null && nowMs - lastSent < BATCH_INTERVAL_MS) return false

        val attestations = identity.mostRecentlyVerifiedFingerprints(
            VouchAttestation.MAX_BATCH_COUNT,
            excluding = normalizedPeerFingerprint
        ).mapNotNull { vouchee ->
            val fingerprintBytes = vouchee.dataFromHexString() ?: return@mapNotNull null
            val signingKey = signingKeyForFingerprint(vouchee) ?: return@mapNotNull null
            VouchAttestation.build(fingerprintBytes, signingKey, nowMs, sign)
        }
        val payload = VouchAttestation.encodeList(attestations) ?: return false
        if (!send(peerID, payload)) return false
        identity.markVouchBatchSent(normalizedPeerFingerprint, nowMs)
        Log.d(TAG, "Sent ${attestations.size} vouch(es) to ${peerID.take(LOG_FINGERPRINT_LENGTH)}")
        return true
    }

    fun handlePayload(
        peerID: String,
        payload: ByteArray,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val senderFingerprint = fingerprintForPeer(peerID)?.lowercase() ?: return
        if (!identity.isVerifiedFingerprint(senderFingerprint)) return
        val senderSigningKey = signingKeyForFingerprint(senderFingerprint) ?: return

        var accepted = INITIAL_ACCEPTED_COUNT
        VouchAttestation.decodeList(payload).forEach { attestation ->
            if (!attestation.isExpired(nowMs) &&
                verify(attestation.signature, attestation.signableBytes(), senderSigningKey) &&
                identity.recordVouch(
                    attestation.voucheeFingerprint.hexEncodedString(),
                    senderFingerprint,
                    attestation.timestampMs,
                    nowMs
                )
            ) accepted++
        }
        if (accepted > INITIAL_ACCEPTED_COUNT) {
            Log.i(TAG, "Accepted $accepted vouch(es) from ${peerID.take(LOG_FINGERPRINT_LENGTH)}")
        }
    }

    companion object {
        private const val TAG = "VouchCoordinator"
        private const val INITIAL_ACCEPTED_COUNT = 0
        private const val LOG_FINGERPRINT_LENGTH = 8
        private const val HOURS_PER_DAY = 24L
        private const val MINUTES_PER_HOUR = 60L
        private const val SECONDS_PER_MINUTE = 60L
        private const val MILLIS_PER_SECOND = 1000L
        const val BATCH_INTERVAL_MS =
            HOURS_PER_DAY * MINUTES_PER_HOUR * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
        val NOISE_PACKET_VERSION: UByte = 1u
    }
}
