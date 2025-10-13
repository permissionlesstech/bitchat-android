package com.bitchat.android.mesh

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Peer information structure with verification status
 * Compatible with iOS PeerInfo structure
 */
data class PeerInfo(
    val id: String,
    var nickname: String,
    var isConnected: Boolean,
    var isDirectConnection: Boolean,
    var noisePublicKey: ByteArray?,
    var signingPublicKey: ByteArray?,      // NEW: Ed25519 public key for verification
    var isVerifiedNickname: Boolean,       // NEW: Verification status flag
    var lastSeen: Long  // Using Long instead of Date for simplicity
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as PeerInfo
        
        if (id != other.id) return false
        if (nickname != other.nickname) return false
        if (isConnected != other.isConnected) return false
        if (isDirectConnection != other.isDirectConnection) return false
        if (noisePublicKey != null) {
            if (other.noisePublicKey == null) return false
            if (!noisePublicKey.contentEquals(other.noisePublicKey)) return false
        } else if (other.noisePublicKey != null) return false
        if (signingPublicKey != null) {
            if (other.signingPublicKey == null) return false
            if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        } else if (other.signingPublicKey != null) return false
        if (isVerifiedNickname != other.isVerifiedNickname) return false
        if (lastSeen != other.lastSeen) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + nickname.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + isDirectConnection.hashCode()
        result = 31 * result + (noisePublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (signingPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + isVerifiedNickname.hashCode()
        result = 31 * result + lastSeen.hashCode()
        return result
    }
}

/**
 * Manages active peers, nicknames, RSSI tracking, and peer fingerprints
 * Extracted from BluetoothMeshService for better separation of concerns
 * 
 * Now includes centralized peer fingerprint management via PeerFingerprintManager singleton
 * and support for signed announcement verification
 */
class PeerManager {
    
    companion object {
        private const val TAG = "PeerManager"
    }

    // Centralized timeout from AppConstants
    private val stalePeerTimeoutMs: Long = com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS
    
    // Peer tracking data - enhanced with verification status
    private val peers = ConcurrentHashMap<String, PeerInfo>() // peerID -> PeerInfo
    private val peerRSSI = ConcurrentHashMap<String, Int>()
    private val announcedPeers = CopyOnWriteArrayList<String>()
    private val announcedToPeers = CopyOnWriteArrayList<String>()
    
    // Legacy fields removed: use PeerInfo map exclusively
    
    // Centralized fingerprint management
    private val fingerprintManager = PeerFingerprintManager.getInstance()
    
    // Delegate for callbacks
    var delegate: PeerManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }

    // MARK: - New PeerInfo-based methods

    /**
     * Update peer information with verification data
     * Similar to iOS updatePeer method
     */
    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean {
        if (peerID == "unknown") return false
        
        val now = System.currentTimeMillis()
        val existingPeer = peers[peerID]
        val isNewPeer = existingPeer == null
        
        // Update or create peer info
        val peerInfo = PeerInfo(
            id = peerID,
            nickname = nickname,
            isConnected = true,
            isDirectConnection = existingPeer?.isDirectConnection ?: false,
            noisePublicKey = noisePublicKey,
            signingPublicKey = signingPublicKey,
            isVerifiedNickname = isVerified,
            lastSeen = now
        )
        
        peers[peerID] = peerInfo
        
        // Update derived state only
        // No legacy maps; peers map is the single source of truth
        // Maintain announcedPeers for first-time announce semantics
        
        if (isNewPeer && isVerified) {
            announcedPeers.add(peerID)
            notifyPeerListUpdate()
            Log.d(TAG, "🆕 New verified peer: $nickname ($peerID)")
            return true
        } else if (isVerified) {
            Log.d(TAG, "🔄 Updated verified peer: $nickname ($peerID)")
        } else {
            Log.d(TAG, "⚠️ Unverified peer announcement from: $nickname ($peerID)")
        }
        
        return false
    }

    /**
     * Get peer info
     */
    fun getPeerInfo(peerID: String): PeerInfo? {
        return peers[peerID]
    }

    /**
     * Check if peer is verified
     */
    fun isPeerVerified(peerID: String): Boolean {
        return peers[peerID]?.isVerifiedNickname == true
    }

    /**
     * Get all verified peers
     */
    fun getVerifiedPeers(): Map<String, PeerInfo> {
        return peers.filterValues { it.isVerifiedNickname }
    }

    /**
     * Set whether a peer is directly connected over Bluetooth.
     * Triggers a peer list update to refresh UI badges.
     */
    fun setDirectConnection(peerID: String, isDirect: Boolean) {
        peers[peerID]?.let { existing ->
            if (existing.isDirectConnection != isDirect) {
                peers[peerID] = existing.copy(isDirectConnection = isDirect)
                notifyPeerListUpdate()
                // NEW: notify UI state (if available via delegate path) about directness change
                try {
                    // Best-effort: delegate path flows up to ChatViewModel via didUpdatePeerList
                    // No direct reference to UI layer here by design.
                } catch (_: Exception) { }
            }
        }
    }

    // MARK: - Legacy Methods (maintained for compatibility)

    /**
     * Update peer last seen timestamp
     */
    fun updatePeerLastSeen(peerID: String) {
        if (peerID != "unknown") {
            peers[peerID]?.let { info ->
                peers[peerID] = info.copy(lastSeen = System.currentTimeMillis())
            }
        }
    }
    
    /**
     * Add or update peer with nickname
     * Maintained for compatibility. Uses peers map exclusively now.
     */
    fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
        if (peerID == "unknown") return false
        
        // Clean up stale peer IDs with the same nickname (exact same logic as iOS)
        val now = System.currentTimeMillis()
        val stalePeerIDs = mutableListOf<String>()
        peers.forEach { (existingPeerID, info) ->
            if (info.nickname == nickname && existingPeerID != peerID) {
                val wasRecentlySeen = (now - info.lastSeen) < 10000
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
        val existing = peers[peerID]
        if (existing != null) {
            peers[peerID] = existing.copy(nickname = nickname, lastSeen = now, isConnected = true)
        } else {
            peers[peerID] = PeerInfo(
                id = peerID,
                nickname = nickname,
                isConnected = true,
                isDirectConnection = false,
                noisePublicKey = null,
                signingPublicKey = null,
                isVerifiedNickname = false,
                lastSeen = now
            )
        }
        
        // Handle first announcement
        if (isFirstAnnounce) {
            announcedPeers.add(peerID)
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
        val removed = peers.remove(peerID)
        peerRSSI.remove(peerID)
        announcedPeers.remove(peerID)
        announcedToPeers.remove(peerID)
        
        // Also remove fingerprint mappings
        fingerprintManager.removePeer(peerID)
        
        if (notifyDelegate && removed != null) {
            // Notify specific removal event then list update
            try { delegate?.onPeerRemoved(peerID) } catch (_: Exception) {}
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
        val info = peers[peerID] ?: return false
        val now = System.currentTimeMillis()
        return (now - info.lastSeen) <= stalePeerTimeoutMs && info.isConnected
    }
    
    /**
     * Get peer nickname
     */
    fun getPeerNickname(peerID: String): String? {
        return peers[peerID]?.nickname
    }
    
    /**
     * Get all peer nicknames
     */
    fun getAllPeerNicknames(): Map<String, String> {
        return peers.mapValues { it.value.nickname }
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
        val now = System.currentTimeMillis()
        return peers.filterValues { (now - it.lastSeen) <= stalePeerTimeoutMs && it.isConnected }
            .keys
            .toList()
            .sorted()
    }
    
    /**
     * Get active peer count
     */
    fun getActivePeerCount(): Int {
        return getActivePeerIDs().size
    }
    
    /**
     * Clear all peer data
     */
    fun clearAllPeers() {
        peers.clear()
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
        val now = System.currentTimeMillis()
        val activeIds = getActivePeerIDs().toSet()
        return buildString {
            appendLine("=== Peer Manager Debug Info ===")
            appendLine("Active Peers: ${activeIds.size}")
            peers.forEach { (peerID, info) ->
                val timeSince = (now - info.lastSeen) / 1000
                val rssi = peerRSSI[peerID]?.let { "${it} dBm" } ?: "No RSSI"
                val deviceAddress = addressPeerMap?.entries?.find { it.value == peerID }?.key
                val addressInfo = deviceAddress?.let { " [Device: $it]" } ?: " [Device: Unknown]"
                val status = if (activeIds.contains(peerID)) "ACTIVE" else "INACTIVE"
                val direct = if (info.isDirectConnection) "DIRECT" else "ROUTED"
                appendLine("  - $peerID (${info.nickname})$addressInfo - $status/$direct, last seen ${timeSince}s ago, RSSI: $rssi")
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
                    val nickname = peers[peerID]?.nickname ?: "Unknown"
                    val isActive = isPeerActive(peerID)
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
                delay(com.bitchat.android.util.AppConstants.Mesh.PEER_CLEANUP_INTERVAL_MS)
                cleanupStalePeers()
            }
        }
    }
    
    /**
     * Clean up stale peers (same 3-minute threshold as iOS)
     */
    private fun cleanupStalePeers() {
        val now = System.currentTimeMillis()
        
        val peersToRemove = peers.filterValues { (now - it.lastSeen) > stalePeerTimeoutMs }
            .keys
            .toList()
        
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
    fun onPeerListUpdated(peerIDs: List<String>)
    fun onPeerRemoved(peerID: String)
}
