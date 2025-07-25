package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages active peers, nicknames, RSSI tracking, and peer fingerprints
 * Extracted from BluetoothMeshService for better separation of concerns
 * 
 * Now includes centralized peer fingerprint management via PeerFingerprintManager singleton
 */
class PeerManager {
    
    companion object {
        private const val TAG = "PeerManager"
        private const val STALE_PEER_TIMEOUT = 180000L // 3 minutes (same as iOS)
        private const val CLEANUP_INTERVAL = 60000L // 1 minute
    }
    
    // Peer tracking data
    private val peerNicknames = ConcurrentHashMap<String, String>()
    private val activePeers = ConcurrentHashMap<String, Long>() // peerID -> lastSeen timestamp
    private val peerRSSI = ConcurrentHashMap<String, Int>()
    private val announcedPeers = CopyOnWriteArrayList<String>()
    private val announcedToPeers = CopyOnWriteArrayList<String>()
    
    // Centralized fingerprint management
    private val fingerprintManager = PeerFingerprintManager.getInstance()
    
    // Delegate for callbacks
    var delegate: PeerManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Update peer last seen timestamp
     */
    fun updatePeerLastSeen(peerID: String) {
        if (peerID != "unknown") {
            activePeers[peerID] = System.currentTimeMillis()
        }
    }
    
    /**
     * Add or update peer with nickname
     */
    fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
        if (peerID == "unknown") return false
        
        // Clean up stale peer IDs with the same nickname (exact same logic as iOS)
        val stalePeerIDs = mutableListOf<String>()
        peerNicknames.forEach { (existingPeerID, existingNickname) ->
            if (existingNickname == nickname && existingPeerID != peerID) {
                val lastSeen = activePeers[existingPeerID] ?: 0
                val wasRecentlySeen = (System.currentTimeMillis() - lastSeen) < 10000
                if (!wasRecentlySeen) {
                    stalePeerIDs.add(existingPeerID)
                }
            }
        }
        
        // Remove stale peer IDs
        stalePeerIDs.forEach { stalePeerID ->
            removePeer(stalePeerID, notifyDelegate = false)
        }
        
        // Check if this is a new peer announcement
        val isFirstAnnounce = !announcedPeers.contains(peerID)
        
        // Update peer data
        peerNicknames[peerID] = nickname
        activePeers[peerID] = System.currentTimeMillis()
        
        // Handle first announcement
        if (isFirstAnnounce) {
            announcedPeers.add(peerID)
            delegate?.onPeerConnected(nickname)
            notifyPeerListUpdate()
            return true
        }
        Log.d(TAG, "Updated peer: $peerID ($nickname)")
        return false
    }
    
    /**
     * Remove peer
     */
    fun removePeer(peerID: String, notifyDelegate: Boolean = true) {
        val nickname = peerNicknames.remove(peerID)
        activePeers.remove(peerID)
        peerRSSI.remove(peerID)
        announcedPeers.remove(peerID)
        announcedToPeers.remove(peerID)
        
        // Also remove fingerprint mappings
        fingerprintManager.removePeer(peerID)
        
        if (notifyDelegate && nickname != null) {
            delegate?.onPeerDisconnected(nickname)
            notifyPeerListUpdate()
        }
    }
    
    /**
     * Update peer RSSI
     */
    fun updatePeerRSSI(peerID: String, rssi: Int) {
        if (peerID != "unknown") {
            peerRSSI[peerID] = rssi
        }
    }
    
    /**
     * Check if peer has been announced to
     */
    fun hasAnnouncedToPeer(peerID: String): Boolean {
        return announcedToPeers.contains(peerID)
    }
    
    /**
     * Mark peer as announced to
     */
    fun markPeerAsAnnouncedTo(peerID: String) {
        if (!announcedToPeers.contains(peerID)) {
            announcedToPeers.add(peerID)
        }
    }
    
    /**
     * Check if peer is active
     */
    fun isPeerActive(peerID: String): Boolean {
        return activePeers.containsKey(peerID)
    }
    
    /**
     * Get peer nickname
     */
    fun getPeerNickname(peerID: String): String? {
        return peerNicknames[peerID]
    }
    
    /**
     * Get all peer nicknames
     */
    fun getAllPeerNicknames(): Map<String, String> {
        return peerNicknames.toMap()
    }
    
    /**
     * Get all peer RSSI values
     */
    fun getAllPeerRSSI(): Map<String, Int> {
        return peerRSSI.toMap()
    }
    
    /**
     * Get list of active peer IDs
     */
    fun getActivePeerIDs(): List<String> {
        return activePeers.keys.toList().sorted()
    }
    
    /**
     * Get active peer count
     */
    fun getActivePeerCount(): Int {
        return activePeers.size
    }
    
    /**
     * Clear all peer data
     */
    fun clearAllPeers() {
        peerNicknames.clear()
        activePeers.clear()
        peerRSSI.clear()
        announcedPeers.clear()
        announcedToPeers.clear()
        
        // Also clear fingerprint mappings
        fingerprintManager.clearAllFingerprints()
        
        notifyPeerListUpdate()
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(addressPeerMap: Map<String, String>? = null): String {
        return buildString {
            appendLine("=== Peer Manager Debug Info ===")
            appendLine("Active Peers: ${activePeers.size}")
            activePeers.forEach { (peerID, lastSeen) ->
                val nickname = peerNicknames[peerID] ?: "Unknown"
                val timeSince = (System.currentTimeMillis() - lastSeen) / 1000
                val rssi = peerRSSI[peerID]?.let { "${it} dBm" } ?: "No RSSI"
                
                // Find device address for this peer ID
                val deviceAddress = addressPeerMap?.entries?.find { it.value == peerID }?.key
                val addressInfo = deviceAddress?.let { " [Device: $it]" } ?: " [Device: Unknown]"
                
                appendLine("  - $peerID ($nickname)$addressInfo - last seen ${timeSince}s ago, RSSI: $rssi")
            }
            appendLine("Announced Peers: ${announcedPeers.size}")
            appendLine("Announced To Peers: ${announcedToPeers.size}")
        }
    }
    
    /**
     * Get debug information with device addresses
     */
    fun getDebugInfoWithDeviceAddresses(addressPeerMap: Map<String, String>): String {
        return buildString {
            appendLine("=== Device Address to Peer Mapping ===")
            if (addressPeerMap.isEmpty()) {
                appendLine("No device address mappings available")
            } else {
                addressPeerMap.forEach { (deviceAddress, peerID) ->
                    val nickname = peerNicknames[peerID] ?: "Unknown"
                    val isActive = activePeers.containsKey(peerID)
                    val status = if (isActive) "ACTIVE" else "INACTIVE"
                    appendLine("  Device: $deviceAddress -> Peer: $peerID ($nickname) [$status]")
                }
            }
            appendLine()
            appendLine(getDebugInfo(addressPeerMap))
        }
    }
    
    /**
     * Notify delegate of peer list updates
     */
    private fun notifyPeerListUpdate() {
        val peerList = getActivePeerIDs()
        delegate?.onPeerListUpdated(peerList)
    }
    
    /**
     * Start periodic cleanup of stale peers
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupStalePeers()
            }
        }
    }
    
    /**
     * Clean up stale peers (same 3-minute threshold as iOS)
     */
    private fun cleanupStalePeers() {
        val now = System.currentTimeMillis()
        
        val peersToRemove = activePeers.entries.filter { (_, lastSeen) ->
            now - lastSeen > STALE_PEER_TIMEOUT
        }.map { it.key }
        
        peersToRemove.forEach { peerID ->
            Log.d(TAG, "Removing stale peer: $peerID")
            removePeer(peerID)
        }
        
        if (peersToRemove.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${peersToRemove.size} stale peers")
        }
    }
    
    // MARK: - Fingerprint Management (Centralized)
    
    /**
     * Store fingerprint for a peer after successful Noise handshake
     * This should only be called when a Noise session is established
     * 
     * @param peerID The peer's ID
     * @param publicKey The peer's static public key from Noise handshake
     */
    fun storeFingerprintForPeer(peerID: String, publicKey: ByteArray): String {
        return fingerprintManager.storeFingerprintForPeer(peerID, publicKey)
    }
    
    /**
     * Update peer ID mapping for peer ID rotation
     * 
     * @param oldPeerID The previous peer ID (nullable)
     * @param newPeerID The new peer ID
     * @param fingerprint The persistent fingerprint
     */
    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        fingerprintManager.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }
    
    /**
     * Get fingerprint for a specific peer
     * 
     * @param peerID The peer ID to look up
     * @return The fingerprint if found, null otherwise
     */
    fun getFingerprintForPeer(peerID: String): String? {
        return fingerprintManager.getFingerprintForPeer(peerID)
    }
    
    /**
     * Get current peer ID for a specific fingerprint
     * 
     * @param fingerprint The fingerprint to look up
     * @return The current peer ID if found, null otherwise
     */
    fun getPeerIDForFingerprint(fingerprint: String): String? {
        return fingerprintManager.getPeerIDForFingerprint(fingerprint)
    }
    
    /**
     * Check if we have a fingerprint for a specific peer
     * 
     * @param peerID The peer ID to check
     * @return True if we have a fingerprint for this peer, false otherwise
     */
    fun hasFingerprintForPeer(peerID: String): Boolean {
        return fingerprintManager.hasFingerprintForPeer(peerID)
    }
    
    /**
     * Get all current peer ID to fingerprint mappings
     * 
     * @return Immutable copy of all mappings
     */
    fun getAllPeerFingerprints(): Map<String, String> {
        return fingerprintManager.getAllPeerFingerprints()
    }
    
    /**
     * Clear all fingerprint mappings (used for emergency clear)
     */
    fun clearAllFingerprints() {
        fingerprintManager.clearAllFingerprints()
    }
    
    /**
     * Get fingerprint manager debug info
     */
    fun getFingerprintDebugInfo(): String {
        return fingerprintManager.getDebugInfo()
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllPeers()
    }
}

/**
 * Delegate interface for peer manager callbacks
 */
interface PeerManagerDelegate {
    fun onPeerConnected(nickname: String)
    fun onPeerDisconnected(nickname: String)
    fun onPeerListUpdated(peerIDs: List<String>)
}
