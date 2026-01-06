package com.bitchat.android.ui

import android.content.Context
import com.bitchat.android.R
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.services.VerificationService
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles QR verification logic and state, extracted from ChatViewModel.
 */
class VerificationHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val meshService: BluetoothMeshService,
    private val identityManager: SecureIdentityStateManager,
    private val state: ChatState,
    private val notificationManager: NotificationManager,
    private val messageManager: MessageManager
) {

    private val _verifiedFingerprints = MutableStateFlow<Set<String>>(emptySet())
    val verifiedFingerprints: StateFlow<Set<String>> = _verifiedFingerprints.asStateFlow()

    private val pendingQRVerifications = ConcurrentHashMap<String, PendingVerification>()
    private val lastVerifyNonceByPeer = ConcurrentHashMap<String, ByteArray>()
    private val lastInboundVerifyChallengeAt = ConcurrentHashMap<String, Long>()
    private val lastMutualToastAt = ConcurrentHashMap<String, Long>()

    fun loadVerifiedFingerprints() {
        _verifiedFingerprints.value = identityManager.getVerifiedFingerprints()
    }

    fun isPeerVerified(peerID: String): Boolean {
        if (peerID.startsWith("nostr_") || peerID.startsWith("nostr:")) return false
        val fingerprint = getPeerFingerprintForDisplay(peerID)
        return fingerprint != null && _verifiedFingerprints.value.contains(fingerprint)
    }

    fun isNoisePublicKeyVerified(noisePublicKey: ByteArray): Boolean {
        val fingerprint = fingerprintFromNoiseBytes(noisePublicKey)
        return _verifiedFingerprints.value.contains(fingerprint)
    }

    fun unverifyFingerprint(peerID: String) {
        val fingerprint = meshService.getPeerFingerprint(peerID) ?: return
        identityManager.setVerifiedFingerprint(fingerprint, false)
        val current = _verifiedFingerprints.value.toMutableSet()
        current.remove(fingerprint)
        _verifiedFingerprints.value = current
    }

    fun beginQRVerification(qr: VerificationService.VerificationQR): Boolean {
        val targetNoise = qr.noiseKeyHex.lowercase()
        val peerID = state.getConnectedPeersValue().firstOrNull { pid ->
            val noiseKeyHex = meshService.getPeerInfo(pid)?.noisePublicKey?.hexEncodedString()?.lowercase()
            noiseKeyHex == targetNoise
        } ?: return false

        if (pendingQRVerifications.containsKey(peerID)) return true
        val nonce = ByteArray(16)
        java.security.SecureRandom().nextBytes(nonce)
        val pending = PendingVerification(qr.noiseKeyHex, qr.signKeyHex, nonce, System.currentTimeMillis(), false)
        pendingQRVerifications[peerID] = pending

        if (meshService.getSessionState(peerID) is NoiseSession.NoiseSessionState.Established) {
            meshService.sendVerifyChallenge(peerID, qr.noiseKeyHex, nonce)
            pendingQRVerifications[peerID] = pending.copy(sent = true)
        } else {
            meshService.initiateNoiseHandshake(peerID)
        }
        fingerprintFromNoiseHex(qr.noiseKeyHex)?.let { fp ->
            identityManager.cacheFingerprintNickname(fp, qr.nickname)
            identityManager.cacheNoiseFingerprint(qr.noiseKeyHex, fp)
            identityManager.cachePeerNoiseKey(peerID, qr.noiseKeyHex)
        }
        return true
    }

    fun sendPendingVerificationIfNeeded(peerID: String) {
        val pending = pendingQRVerifications[peerID] ?: return
        if (pending.sent) return
        meshService.sendVerifyChallenge(peerID, pending.noiseKeyHex, pending.nonceA)
        pendingQRVerifications[peerID] = pending.copy(sent = true)
    }

    fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray) {
        scope.launch {
            val parsed = VerificationService.parseVerifyChallenge(payload) ?: return@launch
            val myNoiseHex = meshService.getStaticNoisePublicKey()?.hexEncodedString()?.lowercase() ?: return@launch
            if (parsed.first.lowercase() != myNoiseHex) return@launch

            val lastNonce = lastVerifyNonceByPeer[peerID]
            if (lastNonce != null && lastNonce.contentEquals(parsed.second)) return@launch
            lastVerifyNonceByPeer[peerID] = parsed.second

            val fp = meshService.getPeerFingerprint(peerID)
            if (fp != null) {
                lastInboundVerifyChallengeAt[fp] = System.currentTimeMillis()
                if (_verifiedFingerprints.value.contains(fp)) {
                    val lastToast = lastMutualToastAt[fp] ?: 0L
                    if (System.currentTimeMillis() - lastToast > 60_000L) {
                        lastMutualToastAt[fp] = System.currentTimeMillis()
                        val name = resolvePeerDisplayName(peerID)
                        val body = context.getString(R.string.verify_mutual_match_body, name)
                        addVerificationSystemMessage(peerID, context.getString(R.string.verify_mutual_system_message, name))
                        sendVerificationNotification(context.getString(R.string.verify_mutual_match_title), body, peerID)
                    }
                }
            }

            meshService.sendVerifyResponse(peerID, parsed.first, parsed.second)
        }
    }

    fun didReceiveVerifyResponse(peerID: String, payload: ByteArray) {
        scope.launch {
            val resp = VerificationService.parseVerifyResponse(payload) ?: return@launch
            val pending = pendingQRVerifications[peerID] ?: return@launch
            if (!resp.noiseKeyHex.equals(pending.noiseKeyHex, ignoreCase = true)) return@launch
            if (!resp.nonceA.contentEquals(pending.nonceA)) return@launch

            val ok = VerificationService.verifyResponseSignature(
                noiseKeyHex = resp.noiseKeyHex,
                nonceA = resp.nonceA,
                signature = resp.signature,
                signerPublicKeyHex = pending.signKeyHex
            )
            if (!ok) return@launch

            pendingQRVerifications.remove(peerID)
            val fp = meshService.getPeerFingerprint(peerID) ?: return@launch
            identityManager.setVerifiedFingerprint(fp, true)
            val current = _verifiedFingerprints.value.toMutableSet()
            current.add(fp)
            _verifiedFingerprints.value = current

            val name = resolvePeerDisplayName(peerID)
            identityManager.cacheFingerprintNickname(fp, name)
            val noiseKeyHex = try {
                meshService.getPeerInfo(peerID)?.noisePublicKey?.hexEncodedString()
            } catch (_: Exception) {
                null
            }
            if (noiseKeyHex != null) {
                identityManager.cachePeerNoiseKey(peerID, noiseKeyHex)
                identityManager.cacheNoiseFingerprint(noiseKeyHex, fp)
            }
            addVerificationSystemMessage(peerID, context.getString(R.string.verify_success_system_message, name))
            sendVerificationNotification(context.getString(R.string.verify_success_title), context.getString(R.string.verify_success_body, name), peerID)

            val lastChallenge = lastInboundVerifyChallengeAt[fp] ?: 0L
            if (System.currentTimeMillis() - lastChallenge < 600_000L) {
                val lastToast = lastMutualToastAt[fp] ?: 0L
                if (System.currentTimeMillis() - lastToast > 60_000L) {
                    lastMutualToastAt[fp] = System.currentTimeMillis()
                    val body = context.getString(R.string.verify_mutual_match_body, name)
                    addVerificationSystemMessage(peerID, context.getString(R.string.verify_mutual_system_message, name))
                    sendVerificationNotification(context.getString(R.string.verify_mutual_match_title), body, peerID)
                }
            }
        }
    }

    fun getPeerFingerprintForDisplay(peerID: String): String? {
        val fromMap = state.getPeerFingerprintsValue()[peerID]
        if (fromMap != null) return fromMap
        val hexRegex = Regex("^[0-9a-fA-F]+$")
        return try {
            when {
                peerID.length == 64 && peerID.matches(hexRegex) -> {
                    identityManager.getCachedNoiseFingerprint(peerID)?.let { return it }
                    fingerprintFromNoiseHex(peerID)?.also { identityManager.cacheNoiseFingerprint(peerID, it) }
                }
                peerID.length == 16 && peerID.matches(hexRegex) -> {
                    val meshFp = meshService.getPeerFingerprint(peerID)
                    if (meshFp != null) return meshFp
                    identityManager.getCachedPeerFingerprint(peerID)?.let { return it }
                    identityManager.getCachedNoiseKey(peerID)?.let { noiseHex ->
                        identityManager.getCachedNoiseFingerprint(noiseHex)?.let { return it }
                        return fingerprintFromNoiseHex(noiseHex)?.also { identityManager.cacheNoiseFingerprint(noiseHex, it) }
                    }
                    val favorite = try {
                        FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                    } catch (_: Exception) {
                        null
                    }
                    favorite?.peerNoisePublicKey?.let { fingerprintFromNoiseBytes(it) }
                }
                peerID.startsWith("nostr_") -> {
                    val pubHex = GeohashAliasRegistry.get(peerID)
                    val noiseKey = pubHex?.let {
                        FavoritesPersistenceService.shared.findNoiseKey(it)
                    }
                    noiseKey?.let {
                        val noiseHex = it.hexEncodedString()
                        identityManager.getCachedNoiseFingerprint(noiseHex) ?: fingerprintFromNoiseBytes(it)
                    }
                }
                peerID.startsWith("nostr:") -> {
                    val prefix = peerID.removePrefix("nostr:").lowercase()
                    val pubHex = GeohashAliasRegistry
                        .snapshot()
                        .values
                        .firstOrNull { it.lowercase().startsWith(prefix) }
                    val noiseKey = pubHex?.let {
                        FavoritesPersistenceService.shared.findNoiseKey(it)
                    }
                    noiseKey?.let {
                        val noiseHex = it.hexEncodedString()
                        identityManager.getCachedNoiseFingerprint(noiseHex) ?: fingerprintFromNoiseBytes(it)
                    }
                }
                else -> {
                    val meshFp = meshService.getPeerFingerprint(peerID)
                    if (meshFp != null) return meshFp
                    identityManager.getCachedPeerFingerprint(peerID)?.let { return it }
                    identityManager.getCachedNoiseKey(peerID)?.let { noiseHex ->
                        identityManager.getCachedNoiseFingerprint(noiseHex)?.let { return it }
                        return fingerprintFromNoiseHex(noiseHex)?.also { identityManager.cacheNoiseFingerprint(noiseHex, it) }
                    }
                    val favorite = try {
                        FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                    } catch (_: Exception) {
                        null
                    }
                    favorite?.peerNoisePublicKey?.let { fingerprintFromNoiseBytes(it) }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun resolvePeerDisplayNameForFingerprint(peerID: String): String {
        val nicknameMap = state.peerNicknames.value
        nicknameMap[peerID]?.let { return it }
        try {
            meshService.getPeerInfo(peerID)?.nickname?.let { return it }
        } catch (_: Exception) { }

        val fingerprint = getPeerFingerprintForDisplay(peerID)
        fingerprint?.let { fp ->
            identityManager.getCachedFingerprintNickname(fp)?.let { cached ->
                if (cached.isNotBlank()) return cached
            }
        }

        val hexRegex = Regex("^[0-9a-fA-F]+$")
        if (peerID.length == 64 && peerID.matches(hexRegex)) {
            val noiseKeyBytes = try {
                peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } catch (_: Exception) { null }
            val favorite = noiseKeyBytes?.let {
                FavoritesPersistenceService.shared.getFavoriteStatus(it)
            }
            favorite?.peerNickname?.takeIf { it.isNotBlank() }?.let { return it }
        }

        if (peerID.length == 16 && peerID.matches(hexRegex)) {
            val favorite = try {
                FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
            } catch (_: Exception) {
                null
            }
            favorite?.peerNickname?.takeIf { it.isNotBlank() }?.let { return it }
        }

        return peerID.take(8)
    }

    fun getMyFingerprint(): String {
        return meshService.getIdentityFingerprint()
    }

    fun verifyFingerprintValue(fingerprint: String) {
        if (fingerprint.isBlank()) return
        identityManager.setVerifiedFingerprint(fingerprint, true)
        val current = _verifiedFingerprints.value.toMutableSet()
        current.add(fingerprint)
        _verifiedFingerprints.value = current
    }

    fun unverifyFingerprintValue(fingerprint: String) {
        if (fingerprint.isBlank()) return
        identityManager.setVerifiedFingerprint(fingerprint, false)
        val current = _verifiedFingerprints.value.toMutableSet()
        current.remove(fingerprint)
        _verifiedFingerprints.value = current
    }

    private fun addVerificationSystemMessage(peerID: String, text: String) {
        val msg = BitchatMessage(
            sender = "system",
            content = text,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            senderPeerID = peerID
        )
        messageManager.addPrivateMessageNoUnread(peerID, msg)
    }

    private fun resolvePeerDisplayName(peerID: String): String {
        val nick = try { meshService.getPeerInfo(peerID)?.nickname } catch (_: Exception) { null }
        return nick ?: peerID.take(8)
    }

    private fun sendVerificationNotification(title: String, body: String, peerID: String) {
        notificationManager.showVerificationNotification(title, body, peerID)
    }

    private fun fingerprintFromNoiseHex(noiseHex: String): String? {
        val bytes = noiseHex.dataFromHexString() ?: return null
        return fingerprintFromNoiseBytes(bytes)
    }

    fun fingerprintFromNoiseBytes(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hash.hexEncodedString()
    }

    private data class PendingVerification(
        val noiseKeyHex: String,
        val signKeyHex: String,
        val nonceA: ByteArray,
        val startedAtMs: Long,
        val sent: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PendingVerification

            if (startedAtMs != other.startedAtMs) return false
            if (sent != other.sent) return false
            if (noiseKeyHex != other.noiseKeyHex) return false
            if (signKeyHex != other.signKeyHex) return false
            if (!nonceA.contentEquals(other.nonceA)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = startedAtMs.hashCode()
            result = 31 * result + sent.hashCode()
            result = 31 * result + noiseKeyHex.hashCode()
            result = 31 * result + signKeyHex.hashCode()
            result = 31 * result + nonceA.contentHashCode()
            return result
        }
    }
}
