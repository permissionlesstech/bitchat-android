package com.bitchat.android.noise

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * SIMPLIFIED Noise session manager - focuses on core functionality only
 */
class NoiseSessionManager(
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {
    
    companion object {
        private const val TAG = "NoiseSessionManager"
    }
    
    private val sessions = ConcurrentHashMap<String, NoiseSession>()
    
    // Callbacks
    var onSessionEstablished: ((String, ByteArray) -> Unit)? = null
    var onSessionFailed: ((String, Throwable) -> Unit)? = null
    
    // MARK: - Simple Session Management

    /**
     * Add new session for a peer
     */
    fun addSession(peerID: String, session: NoiseSession) {
        sessions[peerID] = session
        Log.d(TAG, "Added new session for $peerID")
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
    fun removeSession(peerID: String) {
        sessions[peerID]?.destroy()
        sessions.remove(peerID)
        Log.d(TAG, "Removed session for $peerID")
    }
    
    /**
     * SIMPLIFIED: Initiate handshake - no tie breaker, just start
     */
    fun initiateHandshake(peerID: String): ByteArray {
        Log.d(TAG, "initiateHandshake($peerID)")

        // Remove any existing session first
        removeSession(peerID)
        
        // Create new session as initiator
        val session = NoiseSession(
            peerID = peerID,
            isInitiator = true,
            localStaticPrivateKey = localStaticPrivateKey,
            localStaticPublicKey = localStaticPublicKey
        )
        Log.d(TAG, "Storing new INITIATOR session for $peerID")
        addSession(peerID, session)
        
        try {
            val handshakeData = session.startHandshake()
            Log.d(TAG, "Started handshake with $peerID as INITIATOR")
            return handshakeData
        } catch (e: Exception) {
            sessions.remove(peerID)
            throw e
        }
    }
    
    /**
     * Handle incoming handshake message
     */
    fun processHandshakeMessage(peerID: String, message: ByteArray): ByteArray? {
        Log.d(TAG, "processHandshakeMessage($peerID, ${message.size} bytes)")
        
        try {
            var session = getSession(peerID)
            
            // If no session exists, create one as responder
            if (session == null) {
                Log.d(TAG, "Creating new RESPONDER session for $peerID")
                session = NoiseSession(
                    peerID = peerID,
                    isInitiator = false,
                    localStaticPrivateKey = localStaticPrivateKey,
                    localStaticPublicKey = localStaticPublicKey
                )
                addSession(peerID, session)
            }
            
            // Process handshake message
            val response = session.processHandshakeMessage(message)
            
            // Check if session is established
            if (session.isEstablished()) {
                Log.d(TAG, "✅ Session ESTABLISHED with $peerID")
                val remoteStaticKey = session.getRemoteStaticPublicKey()
                if (remoteStaticKey != null) {
                    onSessionEstablished?.invoke(peerID, remoteStaticKey)
                }
            }
            
            return response
            
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed with $peerID: ${e.message}")
            sessions.remove(peerID)
            onSessionFailed?.invoke(peerID, e)
            throw e
        }
    }
    
    /**
     * SIMPLIFIED: Encrypt data
     */
    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val session = getSession(peerID) ?: throw IllegalStateException("No session found for $peerID")
        if (!session.isEstablished()) {
            throw IllegalStateException("Session not established with $peerID")
        }
        return session.encrypt(data)
    }
    
    /**
     * SIMPLIFIED: Decrypt data
     */
    fun decrypt(encryptedData: ByteArray, peerID: String): ByteArray {
        val session = getSession(peerID)
        if (session == null) {
            Log.e(TAG, "No session found for $peerID when trying to decrypt")
            throw IllegalStateException("No session found for $peerID")
        }
        if (!session.isEstablished()) {
            Log.e(TAG, "Session not established with $peerID when trying to decrypt")
            throw IllegalStateException("Session not established with $peerID")
        }
        return session.decrypt(encryptedData)
    }
    
    /**
     * Check if session is established with peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        val hasSession = getSession(peerID)?.isEstablished() ?: false
        Log.d(TAG, "hasEstablishedSession($peerID): $hasSession")
        return hasSession
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
    fun getRemoteStaticKey(peerID: String): ByteArray? {
        return getSession(peerID)?.getRemoteStaticPublicKey()
    }
    
    /**
     * Get handshake hash for channel binding (if session established)
     */
    fun getHandshakeHash(peerID: String): ByteArray? {
        return getSession(peerID)?.getHandshakeHash()
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
    fun shutdown() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
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
