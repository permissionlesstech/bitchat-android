package com.bitchat.android.nostr

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Efficient LRU-based Nostr event deduplication system
 * 
 * This class provides thread-safe deduplication of Nostr events based on their event IDs.
 * It maintains an LRU cache of up to 10,000 event IDs to prevent memory bloat while ensuring
 * duplicate events (which commonly arrive via different relays) are processed only once.
 * 
 * Features:
 * - Thread-safe concurrent access
 * - LRU eviction when capacity is exceeded
 * - Configurable capacity (default 10,000)
 * - Efficient O(1) lookup and insertion
 * - Memory-bounded to prevent unbounded growth
 */
class NostrEventDeduplicator(
    private val maxCapacity: Int = DEFAULT_CAPACITY
) {
    companion object {
        private const val TAG = "NostrDeduplicator"
        private const val DEFAULT_CAPACITY = com.bitchat.android.util.AppConstants.Nostr.DEFAULT_DEDUP_CAPACITY
        
        @Volatile
        private var INSTANCE: NostrEventDeduplicator? = null
        
        /**
         * Get the singleton instance of the deduplicator
         */
        fun getInstance(): NostrEventDeduplicator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NostrEventDeduplicator().also { INSTANCE = it }
            }
        }
    }
    
    /**
     * Node for the doubly-linked list used in LRU implementation
     */
    private data class LRUNode(
        val eventId: String,
        var prev: LRUNode? = null,
        var next: LRUNode? = null
    )
    
    // Hash map for O(1) lookup - maps event ID to node
    private val nodeMap = ConcurrentHashMap<String, LRUNode>()
    
    // Doubly-linked list for LRU ordering
    private val head = LRUNode("HEAD") // Dummy head node
    private val tail = LRUNode("TAIL") // Dummy tail node
    
    // Lock for thread-safe LRU operations
    private val lruLock = Any()
    
    // Statistics
    @Volatile
    private var totalChecks = 0L
    @Volatile
    private var duplicateCount = 0L
    @Volatile
    private var evictionCount = 0L
    
    init {
        // Initialize the doubly-linked list
        head.next = tail
        tail.prev = head
        
        Log.d(TAG, "Initialized NostrEventDeduplicator with capacity: $maxCapacity")
    }
    
    /**
     * Check if an event has been seen before and mark it as seen
     * 
     * @param eventId The Nostr event ID to check
     * @return true if the event is a duplicate (already seen), false if it's new
     */
    fun isDuplicate(eventId: String): Boolean {
        totalChecks++
        
        synchronized(lruLock) {
            val existingNode = nodeMap[eventId]
            
            if (existingNode != null) {
                // Event is a duplicate - move to front (most recently used)
                moveToFront(existingNode)
                duplicateCount++
                
                if (duplicateCount % 100 == 0L) {
                    Log.v(TAG, "Duplicate event detected: $eventId (${duplicateCount} total duplicates)")
                }
                
                return true
            } else {
                // New event - add to front
                addToFront(eventId)
                
                // Check if we need to evict oldest entries
                if (nodeMap.size > maxCapacity) {
                    evictOldest()
                }
                
                return false
            }
        }
    }
    
    /**
     * Process a Nostr event with deduplication
     * 
     * @param event The Nostr event to process
     * @param processor Function to call if the event is not a duplicate
     * @return true if the event was processed (not a duplicate), false if it was deduplicated
     */
    fun processEvent(event: NostrEvent, processor: (NostrEvent) -> Unit): Boolean {
        return if (!isDuplicate(event.id)) {
            processor(event)
            true
        } else {
            false
        }
    }
    
    /**
     * Get current statistics about the deduplicator
     */
    fun getStats(): DeduplicationStats {
        synchronized(lruLock) {
            return DeduplicationStats(
                capacity = maxCapacity,
                currentSize = nodeMap.size,
                totalChecks = totalChecks,
                duplicateCount = duplicateCount,
                evictionCount = evictionCount,
                hitRate = if (totalChecks > 0) (duplicateCount.toDouble() / totalChecks.toDouble()) else 0.0
            )
        }
    }
    
    /**
     * Clear all cached event IDs (useful for testing or resetting state)
     */
    fun clear() {
        synchronized(lruLock) {
            nodeMap.clear()
            head.next = tail
            tail.prev = head
            
            // Reset statistics
            totalChecks = 0L
            duplicateCount = 0L
            evictionCount = 0L
            
            Log.d(TAG, "Cleared all cached event IDs")
        }
    }
    
    /**
     * Check if the deduplicator contains a specific event ID
     */
    fun contains(eventId: String): Boolean {
        return nodeMap.containsKey(eventId)
    }
    
    /**
     * Get the current size of the cache
     */
    fun size(): Int = nodeMap.size
    
    // MARK: - Private LRU Implementation Methods
    
    /**
     * Add a new event ID to the front of the LRU list
     */
    private fun addToFront(eventId: String) {
        val newNode = LRUNode(eventId)
        nodeMap[eventId] = newNode
        
        // Insert after head
        newNode.next = head.next
        newNode.prev = head
        head.next?.prev = newNode
        head.next = newNode
    }
    
    /**
     * Move an existing node to the front (most recently used position)
     */
    private fun moveToFront(node: LRUNode) {
        // Remove from current position
        node.prev?.next = node.next
        node.next?.prev = node.prev
        
        // Insert at front
        node.next = head.next
        node.prev = head
        head.next?.prev = node
        head.next = node
    }
    
    /**
     * Remove and return the least recently used node (at the tail)
     */
    private fun removeTail(): LRUNode? {
        val lastNode = tail.prev
        if (lastNode == head) {
            return null // Empty list
        }
        
        // Remove from linked list
        lastNode?.prev?.next = tail
        tail.prev = lastNode?.prev
        
        // Remove from hash map
        if (lastNode != null) {
            nodeMap.remove(lastNode.eventId)
        }
        
        return lastNode
    }
    
    /**
     * Evict the oldest (least recently used) entries when capacity is exceeded
     */
    private fun evictOldest() {
        while (nodeMap.size > maxCapacity) {
            val evictedNode = removeTail()
            if (evictedNode != null) {
                evictionCount++
                
                if (evictionCount % 500 == 0L) {
                    Log.v(TAG, "Evicted event ID: ${evictedNode.eventId} (${evictionCount} total evictions)")
                }
            } else {
                break // Should not happen, but safety check
            }
        }
    }
}

/**
 * Statistics about the deduplication system
 */
data class DeduplicationStats(
    val capacity: Int,
    val currentSize: Int,
    val totalChecks: Long,
    val duplicateCount: Long,
    val evictionCount: Long,
    val hitRate: Double
) {
    override fun toString(): String {
        return "DeduplicationStats(capacity=$capacity, size=$currentSize, " +
               "checks=$totalChecks, duplicates=$duplicateCount, evictions=$evictionCount, " +
               "hitRate=${"%.2f".format(hitRate * 100)}%)"
    }
}
