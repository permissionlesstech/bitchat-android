package com.bitchat.android.mesh

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized peer fingerprint management singleton
 * 
 * This class manages all peer fingerprint storage and retrieval operations, 
 * providing a single source of truth for peer identity across the entire application.
 * 
 * Fingerprints are SHA-256 hashes of peer static public keys and are only stored 
 * after successful Noise handshake session establishment.
 * 
 * Key Design Principles:
 * - Thread-safe operations using ConcurrentHashMap
 * - Bidirectional mapping (peerID ↔ fingerprint) 
 * - Support for peer ID rotation while maintaining persistent identity
 * - Centralized logging for debugging identity management
 */
class PeerFingerprintManager private constructor() {
    
    companion object {
        private const val TAG = "PeerFingerprintManager"
        
        @Volatile
        private var INSTANCE: PeerFingerprintManager? = null
        
        /**
         * Get the singleton instance
         */
        fun getInstance(): PeerFingerprintManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PeerFingerprintManager().also { INSTANCE = it }
            }
        }
    }
    
    // Bidirectional mapping for efficient lookups
    private val peerIDToFingerprint = ConcurrentHashMap<String, String>() // peerID -> fingerprint
    private val fingerprintToPeerID = ConcurrentHashMap<String, String>() // fingerprint -> current peerID
    
    // MARK: - Fingerprint Storage (Only called after successful Noise handshake)
    
    /**
     * Store fingerprint mapping after successful Noise handshake session establishment
     * This is the ONLY place where fingerprints should be stored
     * 
     * @param peerID The peer's current ID
     * @param publicKey The peer's static public key from Noise handshake
     */
    fun storeFingerprintForPeer(peerID: String, publicKey: ByteArray): String {
        // get existing fingerprint for this peer and compare
        val existingFingerprint = getFingerprintForPeer(peerID)
        val fingerprint = calculateFingerprint(publicKey)

        if (existingFingerprint != null && existingFingerprint != fingerprint) {
            Log.w(TAG, "Fingerprint mismatch for peer $peerID: $existingFingerprint != $fingerprint")
            throw IllegalStateException("Fingerprint mismatch for peer $peerID: $existingFingerprint != $fingerprint")
        }
        
        // Store bidirectional mapping
        peerIDToFingerprint[peerID] = fingerprint
        fingerprintToPeerID[fingerprint] = peerID
        
        Log.d(TAG, "Stored fingerprint for peer $peerID: ${fingerprint.take(16)}...")
        return fingerprint
    }
    
    /**
     * Update peer ID mapping while preserving fingerprint identity
     * Used for peer ID rotation - when a peer changes their ID but maintains the same static key
     * 
     * @param oldPeerID The previous peer ID (nullable if this is a fresh mapping)
     * @param newPeerID The new peer ID
     * @param fingerprint The persistent fingerprint (should match existing one)
     */
    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        if (newPeerID.isBlank()) {
            Log.w(TAG, "Attempted to update mapping with blank newPeerID")
            return
        }
        
        if (fingerprint.isBlank()) {
            Log.w(TAG, "Attempted to update mapping with blank fingerprint")
            return
        }
        
        // Remove old mapping if exists
        oldPeerID?.takeIf { it.isNotBlank() }?.let { oldID ->
            val removedFingerprint = peerIDToFingerprint.remove(oldID)
            if (removedFingerprint != null && removedFingerprint == fingerprint) {
                Log.d(TAG, "Removed old mapping: $oldID -> ${removedFingerprint.take(16)}...")
            }
        }
        
        // Add new mapping
        peerIDToFingerprint[newPeerID] = fingerprint
        fingerprintToPeerID[fingerprint] = newPeerID
        
        Log.d(TAG, "Updated peer ID mapping: $newPeerID (was: $oldPeerID), fingerprint: ${fingerprint.take(16)}...")
    }
    
    // MARK: - Fingerprint Retrieval
    
    /**
     * Get fingerprint for a specific peer ID
     * 
     * @param peerID The peer ID to look up
     * @return The fingerprint if found, null otherwise
     */
    fun getFingerprintForPeer(peerID: String): String? {
        if (peerID.isBlank()) return null
        return peerIDToFingerprint[peerID]
    }
    
    /**
     * Get current peer ID for a specific fingerprint
     * 
     * @param fingerprint The fingerprint to look up
     * @return The current peer ID if found, null otherwise
     */
    fun getPeerIDForFingerprint(fingerprint: String): String? {
        if (fingerprint.isBlank()) return null
        return fingerprintToPeerID[fingerprint]
    }
    
    /**
     * Check if we have a fingerprint for a specific peer
     * 
     * @param peerID The peer ID to check
     * @return True if we have a fingerprint for this peer, false otherwise
     */
    fun hasFingerprintForPeer(peerID: String): Boolean {
        return getFingerprintForPeer(peerID) != null
    }
    
    /**
     * Get all current peer ID to fingerprint mappings
     * 
     * @return Immutable copy of all mappings
     */
    fun getAllPeerFingerprints(): Map<String, String> {
        return peerIDToFingerprint.toMap()
    }
    
    /**
     * Get all current fingerprint to peer ID mappings
     * 
     * @return Immutable copy of all reverse mappings
     */
    fun getAllFingerprintMappings(): Map<String, String> {
        return fingerprintToPeerID.toMap()
    }
    
    // MARK: - Peer Management
    
    /**
     * Remove all mappings for a specific peer (called when peer disconnects)
     * 
     * @param peerID The peer ID to remove
     */
    fun removePeer(peerID: String) {
        if (peerID.isBlank()) return
        
        val fingerprint = peerIDToFingerprint.remove(peerID)
        if (fingerprint != null) {
            fingerprintToPeerID.remove(fingerprint)
            Log.d(TAG, "Removed peer mappings for $peerID: ${fingerprint.take(16)}...")
        }
    }
    
    /**
     * Remove all mappings for a specific fingerprint
     * 
     * @param fingerprint The fingerprint to remove
     */
    fun removeFingerprint(fingerprint: String) {
        if (fingerprint.isBlank()) return
        
        val peerID = fingerprintToPeerID.remove(fingerprint)
        if (peerID != null) {
            peerIDToFingerprint.remove(peerID)
            Log.d(TAG, "Removed fingerprint mappings for ${fingerprint.take(16)}...: $peerID")
        }
    }
    
    /**
     * Clear all fingerprint mappings (used for emergency clear/panic mode)
     */
    fun clearAllFingerprints() {
        val count = peerIDToFingerprint.size
        peerIDToFingerprint.clear()
        fingerprintToPeerID.clear()
    }
    
    // MARK: - Utility Functions
    
    /**
     * Calculate SHA-256 fingerprint from public key
     * 
     * @param publicKey The peer's static public key
     * @return The hex-encoded SHA-256 hash
     */
    private fun calculateFingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Get debug information about current fingerprint mappings
     * 
     * @return Debug string with mapping counts and summary
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== PeerFingerprintManager Debug Info ===")
            appendLine("Total mappings: ${peerIDToFingerprint.size}")
            
            if (peerIDToFingerprint.isNotEmpty()) {
                appendLine("Peer ID -> Fingerprint mappings:")
                peerIDToFingerprint.forEach { (peerID, fingerprint) ->
                    appendLine("  $peerID -> ${fingerprint.take(16)}...")
                }
            } else {
                appendLine("No fingerprint mappings stored")
            }
            
            // Verify bidirectional mapping consistency
            val inconsistentMappings = mutableListOf<String>()
            peerIDToFingerprint.forEach { (peerID, fingerprint) ->
                val reversePeerID = fingerprintToPeerID[fingerprint]
                if (reversePeerID != peerID) {
                    inconsistentMappings.add("$peerID -> $fingerprint -> $reversePeerID")
                }
            }
            
            if (inconsistentMappings.isNotEmpty()) {
                appendLine("⚠️ INCONSISTENT MAPPINGS DETECTED:")
                inconsistentMappings.forEach { appendLine("  $it") }
            }
        }
    }
}
