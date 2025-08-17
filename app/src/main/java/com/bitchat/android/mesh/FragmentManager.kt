package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.FragmentPayload
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages message fragmentation and reassembly - 100% iOS Compatible
 * 
 * This implementation exactly matches iOS SimplifiedBluetoothService fragmentation:
 * - Same fragment payload structure (13-byte header + data)
 * - Same MTU thresholds and fragment sizes
 * - Same reassembly logic and timeout handling
 * - Uses new FragmentPayload model for type safety
 */
class FragmentManager {
    
    companion object {
        private const val TAG = "FragmentManager"
        // iOS values: 512 MTU threshold, 469 max fragment size (512 MTU - headers)
        private const val FRAGMENT_SIZE_THRESHOLD = 512 // Matches iOS: if data.count > 512
        private const val MAX_FRAGMENT_SIZE = 469        // Matches iOS: maxFragmentSize = 469 
        private const val FRAGMENT_TIMEOUT = 30000L     // Matches iOS: 30 seconds cleanup
        private const val CLEANUP_INTERVAL = 10000L     // 10 seconds cleanup check
    }
    
    // Fragment storage - iOS equivalent: incomingFragments: [String: [Int: Data]]
    private val incomingFragments = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    // iOS equivalent: fragmentMetadata: [String: (type: UInt8, total: Int, timestamp: Date)]
    private val fragmentMetadata = ConcurrentHashMap<String, Triple<UByte, Int, Long>>() // originalType, totalFragments, timestamp
    
    // Delegate for callbacks
    var delegate: FragmentManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Create fragments from a large packet - 100% iOS Compatible
     * Matches iOS sendFragmentedPacket() implementation exactly
     */
    fun createFragments(packet: BitchatPacket): List<BitchatPacket> {
        val fullData = packet.toBinaryData() ?: return emptyList()
        
        // iOS logic: if data.count > 512 && packet.type != MessageType.fragment.rawValue
        if (fullData.size <= FRAGMENT_SIZE_THRESHOLD) {
            return listOf(packet) // No fragmentation needed
        }
        
        val fragments = mutableListOf<BitchatPacket>()
        
        // iOS: let fragmentID = Data((0..<8).map { _ in UInt8.random(in: 0...255) })
        val fragmentID = FragmentPayload.generateFragmentID()
        
        // iOS: stride(from: 0, to: fullData.count, by: maxFragmentSize)
        val fragmentChunks = stride(0, fullData.size, MAX_FRAGMENT_SIZE) { offset ->
            val endOffset = minOf(offset + MAX_FRAGMENT_SIZE, fullData.size)
            fullData.sliceArray(offset..<endOffset)
        }
        
        Log.d(TAG, "Creating ${fragmentChunks.size} fragments for ${fullData.size} byte packet (iOS compatible)")
        
        // iOS: for (index, fragment) in fragments.enumerated()
        for (index in fragmentChunks.indices) {
            val fragmentData = fragmentChunks[index]
            
            // Create iOS-compatible fragment payload
            val fragmentPayload = FragmentPayload(
                fragmentID = fragmentID,
                index = index,
                total = fragmentChunks.size,
                originalType = packet.type,
                data = fragmentData
            )
            
            // iOS: MessageType.fragment.rawValue (single fragment type)
            val fragmentPacket = BitchatPacket(
                type = MessageType.FRAGMENT.value,
                ttl = packet.ttl,
                senderID = packet.senderID,
                recipientID = packet.recipientID,
                timestamp = packet.timestamp,
                payload = fragmentPayload.encode(),
                signature = null // iOS: signature: nil
            )
            
            fragments.add(fragmentPacket)
        }
        
        return fragments
    }
    
    /**
     * Handle incoming fragment - 100% iOS Compatible  
     * Matches iOS handleFragment() implementation exactly
     */
    fun handleFragment(packet: BitchatPacket): BitchatPacket? {
        // iOS: guard packet.payload.count > 13 else { return }
        if (packet.payload.size < FragmentPayload.HEADER_SIZE) {
            Log.w(TAG, "Fragment packet too small: ${packet.payload.size}")
            return null
        }
        
        // Don't process our own fragments - iOS equivalent check
        // This would be done at a higher level but we'll include for safety
        
        try {
            // Use FragmentPayload for type-safe decoding
            val fragmentPayload = FragmentPayload.decode(packet.payload)
            if (fragmentPayload == null || !fragmentPayload.isValid()) {
                Log.w(TAG, "Invalid fragment payload")
                return null
            }
            
            // iOS: let fragmentID = packet.payload[0..<8].map { String(format: "%02x", $0) }.joined()
            val fragmentIDString = fragmentPayload.getFragmentIDString()
            
            Log.d(TAG, "Received fragment ${fragmentPayload.index}/${fragmentPayload.total} for fragmentID: $fragmentIDString, originalType: ${fragmentPayload.originalType}")
            
            // iOS: if incomingFragments[fragmentID] == nil
            if (!incomingFragments.containsKey(fragmentIDString)) {
                incomingFragments[fragmentIDString] = mutableMapOf()
                fragmentMetadata[fragmentIDString] = Triple(
                    fragmentPayload.originalType, 
                    fragmentPayload.total, 
                    System.currentTimeMillis()
                )
            }
            
            // iOS: incomingFragments[fragmentID]?[index] = Data(fragmentData)
            incomingFragments[fragmentIDString]?.put(fragmentPayload.index, fragmentPayload.data)
            
            // iOS: if let fragments = incomingFragments[fragmentID], fragments.count == total
            val fragmentMap = incomingFragments[fragmentIDString]
            if (fragmentMap != null && fragmentMap.size == fragmentPayload.total) {
                Log.d(TAG, "All fragments received for $fragmentIDString, reassembling...")
                
                // iOS reassembly logic: for i in 0..<total { if let fragment = fragments[i] { reassembled.append(fragment) } }
                val reassembledData = mutableListOf<Byte>()
                for (i in 0 until fragmentPayload.total) {
                    fragmentMap[i]?.let { data ->
                        reassembledData.addAll(data.asIterable())
                    }
                }
                
                // iOS: if let metadata = fragmentMetadata[fragmentID] 
                val metadata = fragmentMetadata[fragmentIDString]
                if (metadata != null) {
                    // iOS: let reassembledPacket = BitchatPacket(type: metadata.type, ...)
                    val reassembledPacket = BitchatPacket(
                        type = metadata.first,
                        senderID = packet.senderID,
                        recipientID = packet.recipientID,
                        timestamp = packet.timestamp,
                        payload = reassembledData.toByteArray(),
                        signature = packet.signature,
                        ttl = if (packet.ttl > 0u) (packet.ttl - 1u).toUByte() else 0u
                    )
                    
                    // iOS cleanup: incomingFragments.removeValue(forKey: fragmentID)
                    incomingFragments.remove(fragmentIDString)
                    fragmentMetadata.remove(fragmentIDString)
                    
                    Log.d(TAG, "Successfully reassembled packet of ${reassembledData.size} bytes")
                    return reassembledPacket
                }
            } else {
                val received = fragmentMap?.size ?: 0
                Log.d(TAG, "Fragment ${fragmentPayload.index} stored, have $received/${fragmentPayload.total} fragments for $fragmentIDString")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle fragment: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Helper function to match iOS stride functionality
     * stride(from: 0, to: fullData.count, by: maxFragmentSize)
     */
    private fun <T> stride(from: Int, to: Int, by: Int, transform: (Int) -> T): List<T> {
        val result = mutableListOf<T>()
        var current = from
        while (current < to) {
            result.add(transform(current))
            current += by
        }
        return result
    }
    
    /**
     * iOS cleanup - exactly matching performCleanup() implementation
     * Clean old fragments (> 30 seconds old)
     */
    private fun cleanupOldFragments() {
        val now = System.currentTimeMillis()
        val cutoff = now - FRAGMENT_TIMEOUT
        
        // iOS: let oldFragments = fragmentMetadata.filter { $0.value.timestamp < cutoff }.map { $0.key }
        val oldFragments = fragmentMetadata.filter { it.value.third < cutoff }.map { it.key }
        
        // iOS: for fragmentID in oldFragments { incomingFragments.removeValue(forKey: fragmentID) }
        for (fragmentID in oldFragments) {
            incomingFragments.remove(fragmentID)
            fragmentMetadata.remove(fragmentID)
        }
        
        if (oldFragments.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${oldFragments.size} old fragment sets (iOS compatible)")
        }
    }
    
    /**
     * Get debug information - matches iOS debugging
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Fragment Manager Debug Info (iOS Compatible) ===")
            appendLine("Active Fragment Sets: ${incomingFragments.size}")
            appendLine("Fragment Size Threshold: $FRAGMENT_SIZE_THRESHOLD bytes")
            appendLine("Max Fragment Size: $MAX_FRAGMENT_SIZE bytes")
            
            fragmentMetadata.forEach { (fragmentID, metadata) ->
                val (originalType, totalFragments, timestamp) = metadata
                val received = incomingFragments[fragmentID]?.size ?: 0
                val ageSeconds = (System.currentTimeMillis() - timestamp) / 1000
                appendLine("  - $fragmentID: $received/$totalFragments fragments, type: $originalType, age: ${ageSeconds}s")
            }
        }
    }
    
    /**
     * Start periodic cleanup of old fragments - matches iOS maintenance timer
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldFragments()
            }
        }
    }
    
    /**
     * Clear all fragments
     */
    fun clearAllFragments() {
        incomingFragments.clear()
        fragmentMetadata.clear()
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllFragments()
    }
}

/**
 * Delegate interface for fragment manager callbacks
 */
interface FragmentManagerDelegate {
    fun onPacketReassembled(packet: BitchatPacket)
}
