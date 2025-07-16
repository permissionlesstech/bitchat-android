package com.bitchat.android.noise

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe manager for Noise sessions - 100% compatible with iOS implementation
 * 
 * Manages the lifecycle of Noise sessions for each peer, including:
 * - Handshake initiation and processing
 * - Session establishment and transport encryption
 * - Session cleanup and rekey support
 * - Thread-safe operations for concurrent access
 */
class NoiseSessionManager(
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {
    
    companion object {
        private const val TAG = "NoiseSessionManager"
        private const val MAX_SESSIONS = 50 // Prevent memory exhaustion
        private const val SESSION_CLEANUP_INTERVAL = 300000L // 5 minutes
    }
    
    // Thread-safe session storage
    private val sessions = ConcurrentHashMap<String, NoiseSession>() 
    private val sessionLock = ReentrantReadWriteLock()
    
    // Pending handshakes to handle handshake storms and prevent duplicates
    private val pendingHandshakes = ConcurrentHashMap<String, HandshakeInfo>()
    
    // Callbacks
    var onSessionEstablished: ((String, ByteArray) -> Unit)? = null // (peerID, remoteStaticKey)
    var onSessionFailed: ((String, Throwable) -> Unit)? = null // (peerID, error)
    
    /**
     * Information about ongoing handshakes
     */
    data class HandshakeInfo(
        val startTime: Long,
        val isInitiator: Boolean,
        var step: Int = 1
    )
    
    // MARK: - Session Management
    
    /**
     * Create a new session for a peer with specified role
     */
    private fun createSession(peerID: String, isInitiator: Boolean): NoiseSession {
        return sessionLock.write {
            val session = NoiseSession(
                peerID = peerID,
                isInitiator = isInitiator,
                localStaticPrivateKey = localStaticPrivateKey,
                localStaticPublicKey = localStaticPublicKey
            )
            sessions[peerID] = session
            session
        }
    }
    
    /**
     * Get existing session for a peer (thread-safe)
     */
    fun getSession(peerID: String): NoiseSession? {
        return sessionLock.read {
            sessions[peerID]
        }
    }
    
    /**
     * Remove session for a peer (thread-safe)
     */
    fun removeSession(peerID: String) {
        sessionLock.write {
            sessions[peerID]?.destroy()
            sessions.remove(peerID)
            pendingHandshakes.remove(peerID)
        }
    }
    
    /**
     * Get all established sessions
     */
    fun getEstablishedSessions(): Map<String, NoiseSession> {
        return sessionLock.read {
            sessions.filter { it.value.isEstablished() }
        }
    }
    
    // MARK: - Handshake Management
    
    /**
     * Initiate handshake as the initiator
     * Implements tie-breaker mechanism to prevent handshake storms
     */
    fun initiateHandshake(peerID: String): ByteArray {
        return sessionLock.write {
            // Check if we already have an established session
            val existingSession = sessions[peerID]
            if (existingSession?.isEstablished() == true) {
                throw IllegalStateException("Session already established with $peerID")
            }
            
            // Apply tie-breaker mechanism (same as iOS implementation)
            val shouldInitiate = resolveTieBreaker(peerID)
            if (!shouldInitiate) {
                Log.d(TAG, "Tie-breaker: Waiting for $peerID to initiate handshake")
                throw IllegalStateException("Tie-breaker: Should not initiate with $peerID")
            }
            
            // Remove any existing non-established session
            if (existingSession != null && !existingSession.isEstablished()) {
                existingSession.destroy()
                sessions.remove(peerID)
            }
            
            // Create new session as initiator
            val session = createSession(peerID, isInitiator = true)
            
            // Mark as pending handshake
            pendingHandshakes[peerID] = HandshakeInfo(
                startTime = System.currentTimeMillis(),
                isInitiator = true,
                step = 1
            )
            
            try {
                val handshakeData = session.startHandshake()
                Log.d(TAG, "Started handshake with $peerID as initiator")
                handshakeData
            } catch (e: Exception) {
                // Clean up failed session
                sessions.remove(peerID)
                pendingHandshakes.remove(peerID)
                throw e
            }
        }
    }
    
    /**
     * Handle incoming handshake message
     * Supports both initiating and responding to handshakes
     */
    fun handleIncomingHandshake(peerID: String, message: ByteArray): ByteArray? {
        return sessionLock.write {
            var shouldCreateNew = false
            var existingSession: NoiseSession? = null
            
            val existing = sessions[peerID]
            if (existing == null) {
                shouldCreateNew = true
            } else {
                when {
                    existing.isEstablished() -> {
                        // If this is a handshake initiation (first message), help complete their handshake
                        if (isFirstHandshakeMessage(message)) {
                            Log.d(TAG, "Received new handshake from established peer $peerID, resetting")
                            existing.destroy()
                            sessions.remove(peerID)
                            shouldCreateNew = true
                        } else {
                            // Ignore other handshake messages if already established
                            Log.d(TAG, "Ignoring handshake message from established peer $peerID")
                            return@write null
                        }
                    }
                    existing.isHandshaking() -> {
                        // Continue existing handshake
                        if (isFirstHandshakeMessage(message)) {
                            // Peer restarted handshake, reset and start fresh
                            existing.destroy()
                            sessions.remove(peerID)
                            shouldCreateNew = true
                        } else {
                            existingSession = existing
                        }
                    }
                    else -> {
                        // Session in invalid state, recreate
                        existing.destroy()
                        sessions.remove(peerID)
                        shouldCreateNew = true
                    }
                }
            }
            
            // Get or create session
            val session: NoiseSession = if (shouldCreateNew) {
                val newSession = createSession(peerID, isInitiator = false)
                pendingHandshakes[peerID] = HandshakeInfo(
                    startTime = System.currentTimeMillis(),
                    isInitiator = false,
                    step = 1
                )
                newSession
            } else {
                existingSession!!
            }
            
            // Process handshake message
            try {
                val response = session.processHandshakeMessage(message)
                
                // Update handshake step
                pendingHandshakes[peerID]?.let { it.step += 1 }
                
                // Check if session is established
                if (session.isEstablished()) {
                    pendingHandshakes.remove(peerID)
                    
                    // Get remote static key and notify 
                    val remoteStaticKey = session.getRemoteStaticPublicKey()
                    if (remoteStaticKey != null) {
                        Log.d(TAG, "Handshake completed with $peerID")
                        onSessionEstablished?.invoke(peerID, remoteStaticKey)
                    }
                }
                
                response
            } catch (e: Exception) {
                // Clean up failed session
                sessions.remove(peerID)
                pendingHandshakes.remove(peerID)
                
                Log.e(TAG, "Handshake failed with $peerID: ${e.message}")
                onSessionFailed?.invoke(peerID, e)
                
                throw e
            }
        }
    }
    
    // MARK: - Encryption/Decryption
    
    /**
     * Encrypt data for a specific peer using established session
     */
    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val session = getSession(peerID)
            ?: throw IllegalStateException("No session found for $peerID")
            
        if (!session.isEstablished()) {
            throw IllegalStateException("Session not established with $peerID")
        }
        
        return session.encrypt(data)
    }
    
    /**
     * Decrypt data from a specific peer using established session
     */
    fun decrypt(encryptedData: ByteArray, peerID: String): ByteArray {
        val session = getSession(peerID)
            ?: throw IllegalStateException("No session found for $peerID")
            
        if (!session.isEstablished()) {
            throw IllegalStateException("Session not established with $peerID")
        }
        
        return session.decrypt(encryptedData)
    }
    
    // MARK: - Session Information
    
    /**
     * Check if session is established with peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return sessionLock.read {
            sessions[peerID]?.isEstablished() ?: false
        }
    }
    
    /**
     * Get remote static public key for a peer (if session established)
     */
    fun getRemoteStaticKey(peerID: String): ByteArray? {
        return getSession(peerID)?.getRemoteStaticPublicKey()
    }
    
    /**
     * Get handshake hash for channel binding (if session established)
     */
    fun getHandshakeHash(peerID: String): ByteArray? {
        return getSession(peerID)?.getHandshakeHash()
    }
    
    // MARK: - Session Rekey Support
    
    /**
     * Get sessions that need rekeying based on time or message count
     */
    fun getSessionsNeedingRekey(): List<String> {
        return sessionLock.read {
            sessions.entries
                .filter { (_, session) -> 
                    session.isEstablished() && session.needsRekey()
                }
                .map { it.key }
        }
    }
    
    // MARK: - Session Cleanup
    
    /**
     * Clean up stale sessions and pending handshakes
     */
    fun cleanupStaleSessions() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()
        
        sessionLock.write {
            // Clean up old pending handshakes (30 second timeout)
            pendingHandshakes.entries.removeAll { (peerID, info) ->
                if (now - info.startTime > 30000) {
                    toRemove.add(peerID)
                    true
                } else false
            }
            
            // Remove corresponding failed sessions
            toRemove.forEach { peerID ->
                sessions[peerID]?.destroy()
                sessions.remove(peerID)
            }
            
            // Enforce session limits (remove oldest sessions if too many)
            if (sessions.size > MAX_SESSIONS) {
                val oldestSessions = sessions.entries
                    .filter { !it.value.isEstablished() } // Remove non-established first
                    .sortedBy { it.value.getCreationTime() }
                    .take(sessions.size - MAX_SESSIONS)
                
                oldestSessions.forEach { (peerID, session) ->
                    session.destroy()
                    sessions.remove(peerID)
                }
            }
        }
        
        if (toRemove.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${toRemove.size} stale sessions")
        }
    }
    
    // MARK: - Private Helpers
    
    /**
     * Tie-breaker mechanism to prevent handshake storms
     * Same logic as iOS implementation
     */
    private fun resolveTieBreaker(peerID: String): Boolean {
        // Convert peer IDs to comparable format and compare
        // The peer with lexicographically smaller ID should initiate
        val myIDString = localStaticPublicKey.joinToString("") { "%02x".format(it) }
        val peerIDString = peerID.padEnd(myIDString.length, '0')
        
        return myIDString < peerIDString
    }
    
    /**
     * Check if message is the first handshake message (32 bytes for XX pattern)
     */
    private fun isFirstHandshakeMessage(message: ByteArray): Boolean {
        return message.size == 32 // First message in XX pattern is just the ephemeral key
    }
    
    /**
     * Get debug information about session manager state
     */
    fun getDebugInfo(): String = buildString {
        sessionLock.read {
            appendLine("=== Noise Session Manager Debug ===")
            appendLine("Active sessions: ${sessions.size}")
            appendLine("Pending handshakes: ${pendingHandshakes.size}")
            appendLine("")
            
            if (sessions.isNotEmpty()) {
                appendLine("Sessions:")
                sessions.forEach { (peerID, session) ->
                    appendLine("  $peerID: ${session.getState()}")
                }
            }
            
            if (pendingHandshakes.isNotEmpty()) {
                appendLine("Pending handshakes:")
                pendingHandshakes.forEach { (peerID, info) ->
                    appendLine("  $peerID: step ${info.step}, ${if (info.isInitiator) "initiator" else "responder"}")
                }
            }
        }
    }
    
    /**
     * Shutdown manager and clean up all sessions
     */
    fun shutdown() {
        sessionLock.write {
            sessions.values.forEach { it.destroy() }
            sessions.clear()
            pendingHandshakes.clear()
        }
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
    object AlreadyEstablished : NoiseSessionError("Session already established")
}
