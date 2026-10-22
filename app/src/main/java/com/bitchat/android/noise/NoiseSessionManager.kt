package com.bitchat.android.noise

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class NoiseHandshakeProcessingResult(
    val response: ByteArray?,
    val establishedNow: Boolean,
    /** The bound remote static key only when this exact call completed authentication. */
    val authenticatedRemoteStaticKey: ByteArray? = null,
    /** Handshake hash identifying that exact authenticated Noise generation. */
    val authenticatedSessionToken: ByteArray? = null
)

/** Atomic snapshot of the live authenticated Noise generation. */
class AuthenticatedNoiseSession(
    remoteStaticKey: ByteArray,
    sessionToken: ByteArray
) {
    private val remoteStaticKeyBytes = remoteStaticKey.copyOf()
    private val sessionTokenBytes = sessionToken.copyOf()

    val remoteStaticKey: ByteArray get() = remoteStaticKeyBytes.copyOf()
    val sessionToken: ByteArray get() = sessionTokenBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is AuthenticatedNoiseSession &&
                remoteStaticKeyBytes.contentEquals(other.remoteStaticKeyBytes) &&
                sessionTokenBytes.contentEquals(other.sessionTokenBytes))

    override fun hashCode(): Int =
        31 * remoteStaticKeyBytes.contentHashCode() + sessionTokenBytes.contentHashCode()
}

/** Plaintext and generation binding captured atomically from the session that decrypted it. */
data class NoiseDecryptionResult(
    val plaintext: ByteArray,
    val authenticatedSession: AuthenticatedNoiseSession
)

/**
 * SIMPLIFIED Noise session manager - focuses on core functionality only
 */
class NoiseSessionManager(
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray,
    private val localPeerID: String
) {
    
    companion object {
        private const val TAG = "NoiseSessionManager"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val HANDSHAKE_SWEEP_INTERVAL_MS = 2_000L
        private const val HANDSHAKE_MESSAGE_1_SIZE = 32
        private const val SESSION_TOKEN_SIZE = 32
    }

    private val sessions = ConcurrentHashMap<String, NoiseSession>()
    // An inbound replacement handshake must prove its authenticated static-key binding before it
    // can evict a working transport session. Keep responder candidates outside the active map.
    private val responderCandidates = ConcurrentHashMap<String, NoiseSession>()

    private val sweepScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "NoiseHandshakeSweeper").apply { isDaemon = true }
    }

    init {
        sweepScheduler.scheduleWithFixedDelay({
            try {
                cleanupStaleHandshakes(System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "Handshake sweep failed: ${e.message}")
            }
        }, HANDSHAKE_SWEEP_INTERVAL_MS, HANDSHAKE_SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }
    
    // Callbacks
    var onSessionEstablished: ((String, ByteArray) -> Unit)? = null
    var onSessionFailed: ((String, Throwable) -> Unit)? = null
    
    // MARK: - Simple Session Management

    /**
     * Add new session for a peer
     */
    @Synchronized
    fun addSession(peerID: String, session: NoiseSession) {
        val previous = sessions.put(peerID, session)
        if (previous != null && previous !== session) previous.destroy()
    }

    /**
     * Get existing session for a peer
     */
    fun getSession(peerID: String): NoiseSession? {
        val session = sessions[peerID]
        return session
    }
    
    /**
     * Remove session for a peer
     */
    @Synchronized
    fun removeSession(peerID: String) {
        sessions.remove(peerID)?.destroy()
        responderCandidates.remove(peerID)?.destroy()
    }
    
    /**
     * SIMPLIFIED: Initiate handshake - no tie breaker, just start
     */
    @Synchronized
    fun initiateHandshake(peerID: String, replaceEstablished: Boolean = false): ByteArray? {
        val now = System.currentTimeMillis()
        val existing = getSession(peerID)
        if (existing != null) {
            when {
                existing.isEstablished() -> {
                    if (!replaceEstablished) {
                        return null
                    }
                    val candidate = createSession(peerID, isInitiator = true)
                    responderCandidates.remove(peerID)?.destroy()
                    responderCandidates[peerID] = candidate
                    return try {
                        candidate.startHandshake()
                    } catch (e: Exception) {
                        responderCandidates.remove(peerID, candidate)
                        candidate.destroy()
                        throw e
                    }
                }
                existing.isHandshaking() -> {
                    if (!isHandshakeStale(existing, now)) {
                        return null
                    }
                    Log.d(TAG, "Restarting stale handshake with $peerID")
                    removeSession(peerID)
                }
                else -> {
                    removeSession(peerID)
                }
            }
        }
        
        // Create new session as initiator
        val session = NoiseSession(
            peerID = peerID,
            isInitiator = true,
            localStaticPrivateKey = localStaticPrivateKey,
            localStaticPublicKey = localStaticPublicKey
        )
        addSession(peerID, session)
        
        try {
            return session.startHandshake()
        } catch (e: Exception) {
            if (sessions.remove(peerID, session)) session.destroy()
            throw e
        }
    }
    
    /**
     * Handle incoming handshake message
     */
    fun processHandshakeMessage(peerID: String, message: ByteArray): ByteArray? {
        return processHandshakeMessageWithResult(peerID, message).response
    }

    @Synchronized
    fun processHandshakeMessageWithResult(
        peerID: String,
        message: ByteArray
    ): NoiseHandshakeProcessingResult {
        var activeSession: NoiseSession? = null
        var isReplacementCandidate = false
        var establishedRemoteKey: ByteArray? = null
        var establishedSessionToken: ByteArray? = null
        var response: ByteArray? = null

        try {
            val existingCandidate = responderCandidates[peerID]
            if (existingCandidate != null) {
                activeSession = if (message.size == HANDSHAKE_MESSAGE_1_SIZE) {
                    if (existingCandidate.isInitiatorRole()) {
                        val shouldYield = localPeerID > peerID
                        if (!shouldYield) {
                            return NoiseHandshakeProcessingResult(
                                response = null,
                                establishedNow = false
                            )
                        }
                        Log.d(TAG, "Replacement collision with $peerID; yielding to responder")
                    }
                    responderCandidates.remove(peerID, existingCandidate)
                    existingCandidate.destroy()
                    createSession(peerID, isInitiator = false).also {
                        responderCandidates[peerID] = it
                    }
                } else {
                    existingCandidate
                }
                isReplacementCandidate = true
            } else {
                var session = getSession(peerID)

                // Collision handling: both sides initiated and we received message 1.
                if (session != null &&
                    session.isHandshaking() &&
                    session.isInitiatorRole() &&
                    message.size == HANDSHAKE_MESSAGE_1_SIZE
                ) {
                    val shouldYield = localPeerID > peerID
                    if (shouldYield) {
                        Log.d(TAG, "Handshake collision with $peerID; yielding to responder")
                        if (sessions.remove(peerID, session)) session.destroy()
                        session = null
                    } else {
                        return NoiseHandshakeProcessingResult(response = null, establishedNow = false)
                    }
                }

                activeSession = when {
                    session == null -> {
                        createSession(peerID, isInitiator = false).also { sessions[peerID] = it }
                    }
                    session.isEstablished() -> {
                        isReplacementCandidate = true
                        createSession(peerID, isInitiator = false).also {
                            responderCandidates[peerID] = it
                        }
                    }
                    session.isHandshaking() &&
                        !session.isInitiatorRole() &&
                        message.size == HANDSHAKE_MESSAGE_1_SIZE -> {
                        // A restarted responder handshake can replace an incomplete session because
                        // there is no working transport state to preserve.
                        if (sessions.remove(peerID, session)) session.destroy()
                        createSession(peerID, isInitiator = false).also { sessions[peerID] = it }
                    }
                    else -> session
                }
            }

            val session = activeSession ?: throw NoiseSessionError.InvalidState

            response = session.processHandshakeMessage(message)

            if (session.isEstablished()) {
                val remoteStaticKey = session.getRemoteStaticPublicKey()
                    ?: throw NoiseSessionError.HandshakeFailed
                val derivedPeerID = NoisePeerIdentity.derivePeerID(remoteStaticKey)
                if (!NoisePeerIdentity.matchesClaimedPeerID(peerID, remoteStaticKey)) {
                    throw NoiseSessionError.PeerIdentityMismatch(peerID, derivedPeerID)
                }

                val sessionToken = session.getHandshakeHash()
                    ?.takeIf { it.size == SESSION_TOKEN_SIZE && it.any { byte -> byte != 0.toByte() } }
                    ?: throw NoiseSessionError.HandshakeFailed

                if (isReplacementCandidate) {
                    responderCandidates.remove(peerID, session)
                    val previous = sessions.put(peerID, session)
                    if (previous != null && previous !== session) previous.destroy()
                }

                establishedRemoteKey = remoteStaticKey
                establishedSessionToken = sessionToken
            }
        } catch (e: Exception) {
            val session = activeSession
            if (session != null) {
                if (isReplacementCandidate) {
                    responderCandidates.remove(peerID, session)
                } else {
                    sessions.remove(peerID, session)
                }
                session.destroy()
            }
            Log.e(TAG, "Handshake failed with $peerID: ${e.message}")
            runCatching { onSessionFailed?.invoke(peerID, e) }
            throw e
        }

        establishedRemoteKey?.let { onSessionEstablished?.invoke(peerID, it) }
        return NoiseHandshakeProcessingResult(
            response = response,
            establishedNow = establishedRemoteKey != null,
            authenticatedRemoteStaticKey = establishedRemoteKey?.clone(),
            authenticatedSessionToken = establishedSessionToken?.clone()
        )
    }

    private fun createSession(peerID: String, isInitiator: Boolean): NoiseSession = NoiseSession(
        peerID = peerID,
        isInitiator = isInitiator,
        localStaticPrivateKey = localStaticPrivateKey,
        localStaticPublicKey = localStaticPublicKey
    )

    private fun isHandshakeStale(session: NoiseSession, nowMs: Long): Boolean {
        val lastActivity = session.getLastHandshakeActivityMs() ?: session.getHandshakeStartMs()
        if (lastActivity == null) return false
        return (nowMs - lastActivity) > HANDSHAKE_TIMEOUT_MS
    }

    /**
     * Actively expire handshakes that stopped progressing (lost response, abandoned
     * responder candidates). Established sessions are never touched here.
     */
    @Synchronized
    fun cleanupStaleHandshakes(nowMs: Long) {
        sessions.entries.toList().forEach { (peerID, session) ->
            if (session.isHandshaking() && isHandshakeStale(session, nowMs)) {
                Log.d(TAG, "Expiring stale handshake with $peerID")
                if (sessions.remove(peerID, session)) {
                    session.destroy()
                    runCatching { onSessionFailed?.invoke(peerID, NoiseSessionError.HandshakeTimeout) }
                }
            }
        }
        responderCandidates.entries.toList().forEach { (peerID, session) ->
            if (session.isHandshaking() && isHandshakeStale(session, nowMs)) {
                Log.d(TAG, "Expiring stale responder candidate for $peerID")
                if (responderCandidates.remove(peerID, session)) {
                    session.destroy()
                }
            }
        }
    }
    
    /**
     * SIMPLIFIED: Encrypt data
     */
    @Synchronized
    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val session = getSession(peerID) ?: throw IllegalStateException("No session found for $peerID")
        if (!session.isEstablished()) {
            throw IllegalStateException("Session not established with $peerID")
        }
        return session.encrypt(data)
    }

    /** Encrypt only if the exact generation that authorized the operation is still active. */
    @Synchronized
    fun encryptForSession(
        data: ByteArray,
        peerID: String,
        expectedSession: AuthenticatedNoiseSession
    ): ByteArray {
        val session = getSession(peerID) ?: throw NoiseSessionError.SessionNotFound
        if (!session.isEstablished()) throw NoiseSessionError.SessionNotEstablished
        val current = authenticatedSession(session) ?: throw NoiseSessionError.SessionNotEstablished
        if (current != expectedSession) throw NoiseSessionError.SessionGenerationChanged
        return session.encrypt(data)
    }
    
    /**
     * SIMPLIFIED: Decrypt data
     */
    fun decrypt(encryptedData: ByteArray, peerID: String): ByteArray =
        decryptWithSession(encryptedData, peerID).plaintext

    @Synchronized
    fun decryptWithSession(encryptedData: ByteArray, peerID: String): NoiseDecryptionResult {
        val session = getSession(peerID)
        if (session == null) {
            Log.e(TAG, "No session found for $peerID when trying to decrypt")
            throw IllegalStateException("No session found for $peerID")
        }
        if (!session.isEstablished()) {
            Log.e(TAG, "Session not established with $peerID when trying to decrypt")
            throw IllegalStateException("Session not established with $peerID")
        }
        val plaintext = session.decrypt(encryptedData)
        val authenticatedSession = authenticatedSession(session)
            ?: throw IllegalStateException("Established session for $peerID has no channel binding")
        return NoiseDecryptionResult(plaintext, authenticatedSession)
    }
    
    /**
     * Check if session is established with peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return getSession(peerID)?.isEstablished() ?: false
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    fun getSessionState(peerID: String): NoiseSession.NoiseSessionState {
        return getSession(peerID)?.getState() ?: NoiseSession.NoiseSessionState.Uninitialized
    }
    
    /**
     * Get remote static public key for a peer (if session established)
     */
    fun getRemoteStaticKey(peerID: String): ByteArray? =
        getAuthenticatedSession(peerID)?.remoteStaticKey

    @Synchronized
    fun getAuthenticatedSession(peerID: String): AuthenticatedNoiseSession? {
        val session = getSession(peerID) ?: return null
        if (!session.isEstablished()) return null
        return authenticatedSession(session)
    }

    /** Execute a state transition while preventing replacement/removal of the expected session. */
    @Synchronized
    fun withAuthenticatedSession(
        peerID: String,
        expectedSession: AuthenticatedNoiseSession,
        action: () -> Boolean
    ): Boolean {
        val session = getSession(peerID) ?: return false
        if (!session.isEstablished()) return false
        if (authenticatedSession(session) != expectedSession) return false
        return action()
    }
    
    /**
     * Get handshake hash for channel binding (if session established)
     */
    fun getHandshakeHash(peerID: String): ByteArray? {
        return getAuthenticatedSession(peerID)?.sessionToken
    }

    private fun authenticatedSession(session: NoiseSession): AuthenticatedNoiseSession? {
        val remoteStaticKey = session.getRemoteStaticPublicKey()?.takeIf { it.size == 32 } ?: return null
        val sessionToken = session.getHandshakeHash()?.takeIf {
            it.size == SESSION_TOKEN_SIZE && it.any { byte -> byte != 0.toByte() }
        } ?: return null
        return AuthenticatedNoiseSession(remoteStaticKey, sessionToken)
    }
    
    /**
     * Get sessions that need rekeying based on time or message count
     */
    fun getSessionsNeedingRekey(): List<String> {
        return sessions.entries
            .filter { (_, session) -> 
                session.isEstablished() && session.needsRekey()
            }
            .map { it.key }
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== Noise Session Manager Debug ===")
        appendLine("Active sessions: ${sessions.size}")
        appendLine("Responder candidates: ${responderCandidates.size}")
        appendLine("")
        
        if (sessions.isNotEmpty()) {
            appendLine("Sessions:")
            sessions.forEach { (peerID, session) ->
                appendLine("  $peerID: ${session.getState()}")
            }
        }
    }
    
    /**
     * Shutdown manager and clean up all sessions
     */
    @Synchronized
    fun shutdown() {
        sweepScheduler.shutdownNow()
        sessions.values.forEach { it.destroy() }
        responderCandidates.values.forEach { it.destroy() }
        sessions.clear()
        responderCandidates.clear()
        Log.d(TAG, "Noise session manager shut down")
    }
}

/**
 * Session-related errors
 */
sealed class NoiseSessionError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object SessionNotFound : NoiseSessionError("Session not found")
    object SessionNotEstablished : NoiseSessionError("Session not established")
    object InvalidState : NoiseSessionError("Session in invalid state")
    object HandshakeFailed : NoiseSessionError("Handshake failed")
    object HandshakeTimeout : NoiseSessionError("Handshake timed out")
    object AlreadyEstablished : NoiseSessionError("Session already established")
    object SessionGenerationChanged : NoiseSessionError("Noise session generation changed")
    class PeerIdentityMismatch(claimedPeerID: String, derivedPeerID: String?) : NoiseSessionError(
        "Authenticated Noise key derives to ${derivedPeerID ?: "invalid"}, not claimed peer $claimedPeerID"
    )
}
