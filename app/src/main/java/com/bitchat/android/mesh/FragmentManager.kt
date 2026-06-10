package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.MessagePadding
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
        private const val FRAGMENT_SIZE_THRESHOLD = com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_SIZE_THRESHOLD // Matches iOS: if data.count > 512
        private const val MAX_FRAGMENT_SIZE = com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_SIZE        // Matches iOS: maxFragmentSize = 469 
        private const val FRAGMENT_TIMEOUT = com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_TIMEOUT_MS     // Matches iOS: 30 seconds cleanup
        private const val CLEANUP_INTERVAL = com.bitchat.android.util.AppConstants.Fragmentation.CLEANUP_INTERVAL_MS     // 10 seconds cleanup check
    }
    
    // Fragment storage - iOS equivalent: incomingFragments: [String: [Int: Data]]
    private val incomingFragments = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    // iOS equivalent: fragmentMetadata: [String: (type: UInt8, total: Int, timestamp: Date)]
    private val fragmentMetadata = ConcurrentHashMap<String, Triple<UByte, Int, Long>>() // originalType, totalFragments, timestamp
    private val fragmentCumulativeSize = ConcurrentHashMap<String, Int>()

    private val fragmentStateLock = Any()
    private var globalBufferedBytes: Long = 0L

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
        try {
            Log.d(TAG, "🔀 Creating fragments for packet type ${packet.type}, payload: ${packet.payload.size} bytes")
        val encoded = packet.toBinaryData()
            if (encoded == null) {
                Log.e(TAG, "❌ Failed to encode packet to binary data")
                return emptyList()
            }
            Log.d(TAG, "📦 Encoded to ${encoded.size} bytes")
        
        // Fragment the unpadded frame; each fragment will be encoded (and padded) independently - iOS fix
        val fullData = try {
                MessagePadding.unpad(encoded)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to unpad data: ${e.message}", e)
                return emptyList()
            }
            Log.d(TAG, "📏 Unpadded to ${fullData.size} bytes")
        
        // iOS logic: if data.count > 512 && packet.type != MessageType.fragment.rawValue
        if (fullData.size <= FRAGMENT_SIZE_THRESHOLD) {
            return listOf(packet) // No fragmentation needed
        }
        
        val fragments = mutableListOf<BitchatPacket>()
        
        // iOS: let fragmentID = Data((0..<8).map { _ in UInt8.random(in: 0...255) })
        val fragmentID = FragmentPayload.generateFragmentID()
        
        // iOS: stride(from: 0, to: fullData.count, by: maxFragmentSize)
        // Calculate dynamic fragment size to fit in MTU (512)
        // Packet = Header + Sender + Recipient + Route + FragmentHeader + Payload + PaddingBuffer
        val hasRoute = packet.route != null
        val version = if (hasRoute) 2 else 1
        val headerSize = if (version == 2) 15 else 13
        val senderSize = 8
        val recipientSize = if (packet.recipientID != null) 8 else 0
        // Route: 1 byte count + 8 bytes per hop
        val routeSize = if (hasRoute) (1 + (packet.route?.size ?: 0) * 8) else 0
        val fragmentHeaderSize = 13 // FragmentPayload header
        val paddingBuffer = 16 // MessagePadding.optimalBlockSize adds 16 bytes overhead

        // 512 - Overhead
        val packetOverhead = headerSize + senderSize + recipientSize + routeSize + fragmentHeaderSize + paddingBuffer
        val maxDataSize = (512 - packetOverhead).coerceAtMost(MAX_FRAGMENT_SIZE)
        
        if (maxDataSize <= 0) {
            Log.e(TAG, "❌ Calculated maxDataSize is non-positive ($maxDataSize). Route too large?")
            return emptyList()
        }

        Log.d(TAG, "📏 Dynamic fragment size: $maxDataSize (MAX: $MAX_FRAGMENT_SIZE, Overhead: $packetOverhead)")

        val fragmentChunks = stride(0, fullData.size, maxDataSize) { offset ->
            val endOffset = minOf(offset + maxDataSize, fullData.size)
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
            // Fix: Fragments must inherit source route and use v2 if routed
            val fragmentPacket = BitchatPacket(
                version = if (packet.route != null) 2u else 1u,
                type = MessageType.FRAGMENT.value,
                ttl = packet.ttl,
                senderID = packet.senderID,
                recipientID = packet.recipientID,
                timestamp = packet.timestamp,
                payload = fragmentPayload.encode(),
                route = packet.route,
                signature = null // iOS: signature: nil
            )
            
            fragments.add(fragmentPacket)
        }
        
        Log.d(TAG, "✅ Created ${fragments.size} fragments successfully")
            return fragments
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fragment creation failed: ${e.message}", e)
            Log.e(TAG, "❌ Packet type: ${packet.type}, payload: ${packet.payload.size} bytes")
            return emptyList()
        }
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

            val maxFragments = com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
            if (fragmentPayload.total > maxFragments) {
                Log.w(TAG, "Rejecting fragment with excessive total count: ${fragmentPayload.total} > $maxFragments")
                return null
            }

            synchronized(fragmentStateLock) {
                fragmentMetadata[fragmentIDString]?.let { (expectedType, expectedTotal, _) ->
                    if (expectedTotal != fragmentPayload.total || expectedType != fragmentPayload.originalType) {
                        Log.w(
                            TAG,
                            "Rejecting fragment for $fragmentIDString: inconsistent metadata " +
                                "(expected type=$expectedType total=$expectedTotal, got type=${fragmentPayload.originalType} total=${fragmentPayload.total})"
                        )
                        removeFragmentSetLocked(fragmentIDString)
                        return null
                    }
                }

                val isNewSet = !incomingFragments.containsKey(fragmentIDString)
                if (isNewSet) {
                    val maxActive = com.bitchat.android.util.AppConstants.Fragmentation.MAX_ACTIVE_FRAGMENT_SETS
                    if (incomingFragments.size >= maxActive) {
                        Log.w(TAG, "Rejecting new fragment set $fragmentIDString: active fragment sets ${incomingFragments.size} >= $maxActive")
                        return null
                    }

                    incomingFragments[fragmentIDString] = mutableMapOf()
                    fragmentMetadata[fragmentIDString] = Triple(
                        fragmentPayload.originalType,
                        fragmentPayload.total,
                        System.currentTimeMillis()
                    )
                    fragmentCumulativeSize[fragmentIDString] = 0
                }

                val fragmentMap = incomingFragments[fragmentIDString]
                if (fragmentMap == null) {
                    Log.w(TAG, "Dropping fragment set $fragmentIDString due to missing fragment map")
                    removeFragmentSetLocked(fragmentIDString)
                    return null
                }

                val currentSize = fragmentCumulativeSize[fragmentIDString]
                if (currentSize == null) {
                    Log.w(TAG, "Dropping fragment set $fragmentIDString due to missing size tracker")
                    removeFragmentSetLocked(fragmentIDString)
                    return null
                }

                val oldEntrySize = fragmentMap[fragmentPayload.index]?.size ?: 0
                val newSize = currentSize - oldEntrySize + fragmentPayload.data.size
                val maxTotalBytes = com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES
                if (newSize > maxTotalBytes) {
                    Log.w(TAG, "Rejecting fragment for $fragmentIDString: cumulative size $newSize exceeds cap $maxTotalBytes")
                    removeFragmentSetLocked(fragmentIDString)
                    return null
                }

                val delta = (fragmentPayload.data.size - oldEntrySize).toLong()
                val maxGlobalBytes = com.bitchat.android.util.AppConstants.Fragmentation.MAX_GLOBAL_FRAGMENT_TOTAL_BYTES
                if (globalBufferedBytes + delta > maxGlobalBytes) {
                    Log.w(
                        TAG,
                        "Rejecting fragment for $fragmentIDString: global buffered bytes ${(globalBufferedBytes + delta)} exceeds cap $maxGlobalBytes"
                    )
                    if (isNewSet) {
                        removeFragmentSetLocked(fragmentIDString)
                    }
                    return null
                }

                fragmentMap[fragmentPayload.index] = fragmentPayload.data
                fragmentCumulativeSize[fragmentIDString] = newSize
                globalBufferedBytes += delta

                val expectedTotal = fragmentMetadata[fragmentIDString]?.second ?: fragmentPayload.total
                if (fragmentMap.size == expectedTotal) {
                    Log.d(TAG, "All fragments received for $fragmentIDString, reassembling...")

                    // iOS reassembly logic: for i in 0..<total { if let fragment = fragments[i] { reassembled.append(fragment) } }
                    val reassembledData = mutableListOf<Byte>()
                    for (i in 0 until expectedTotal) {
                        fragmentMap[i]?.let { data ->
                            reassembledData.addAll(data.asIterable())
                        }
                    }

                    val originalPacket = BitchatPacket.fromBinaryData(reassembledData.toByteArray())
                    if (originalPacket != null) {
                        removeFragmentSetLocked(fragmentIDString)

                        val suppressedTtlPacket = originalPacket.copy(ttl = 0u.toUByte())
                        Log.d(TAG, "Successfully reassembled original (${reassembledData.size} bytes); set TTL=0 to suppress relay")
                        return suppressedTtlPacket
                    } else {
                        val metadata = fragmentMetadata[fragmentIDString]
                        Log.e(TAG, "Failed to decode reassembled packet (type=${metadata?.first}, total=${metadata?.second})")
                    }
                } else {
                    val received = fragmentMap.size
                    Log.d(TAG, "Fragment ${fragmentPayload.index} stored, have $received/$expectedTotal fragments for $fragmentIDString")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle fragment: ${e.message}")
        }
        
        return null
    }

    private fun removeFragmentSetLocked(fragmentIDString: String) {
        incomingFragments.remove(fragmentIDString)
        fragmentMetadata.remove(fragmentIDString)
        val bytes = fragmentCumulativeSize.remove(fragmentIDString)?.toLong() ?: 0L
        if (bytes != 0L) {
            globalBufferedBytes = (globalBufferedBytes - bytes).coerceAtLeast(0L)
        }
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
        synchronized(fragmentStateLock) {
            val now = System.currentTimeMillis()
            val cutoff = now - FRAGMENT_TIMEOUT

            // iOS: let oldFragments = fragmentMetadata.filter { $0.value.timestamp < cutoff }.map { $0.key }
            val oldFragments = fragmentMetadata.filter { it.value.third < cutoff }.map { it.key }

            for (fragmentID in oldFragments) {
                removeFragmentSetLocked(fragmentID)
            }

            if (oldFragments.isNotEmpty()) {
                Log.d(TAG, "Cleaned up ${oldFragments.size} old fragment sets (iOS compatible)")
            }
        }
    }
    
    /**
     * Get debug information - matches iOS debugging
     */
    fun getDebugInfo(): String {
        synchronized(fragmentStateLock) {
            return buildString {
                appendLine("=== Fragment Manager Debug Info (iOS Compatible) ===")
                appendLine("Active Fragment Sets: ${incomingFragments.size}")
                appendLine("Fragment Size Threshold: $FRAGMENT_SIZE_THRESHOLD bytes")
                appendLine("Max Fragment Size: $MAX_FRAGMENT_SIZE bytes")
                appendLine("Global Buffered Bytes: $globalBufferedBytes")

                fragmentMetadata.forEach { (fragmentID, metadata) ->
                    val (originalType, totalFragments, timestamp) = metadata
                    val received = incomingFragments[fragmentID]?.size ?: 0
                    val ageSeconds = (System.currentTimeMillis() - timestamp) / 1000
                    val bytes = fragmentCumulativeSize[fragmentID] ?: 0
                    appendLine("  - $fragmentID: $received/$totalFragments fragments, bytes=$bytes, type: $originalType, age: ${ageSeconds}s")
                }
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
        synchronized(fragmentStateLock) {
            incomingFragments.clear()
            fragmentMetadata.clear()
            fragmentCumulativeSize.clear()
            globalBufferedBytes = 0L
        }
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
