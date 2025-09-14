package com.bitchat.android.nostr

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Nostr Proof of Work (PoW) implementation following NIP-13
 * 
 * This implements the Proof of Work system for Nostr events to provide spam deterrence.
 * The difficulty is defined as the number of leading zero bits in the event ID.
 * 
 * Reference: https://github.com/nostr-protocol/nips/blob/master/13.md
 */
object NostrProofOfWork {
    
    private const val TAG = "NostrProofOfWork"
    
    /**
     * Calculate the difficulty (number of leading zero bits) of an event ID
     * @param eventIdHex The hexadecimal event ID
     * @return The number of leading zero bits
     */
    fun calculateDifficulty(eventIdHex: String): Int {
        var count = 0
        
        for (i in eventIdHex.indices) {
            val nibble = eventIdHex[i].toString().toInt(16)
            if (nibble == 0) {
                count += 4
            } else {
                // Count leading zeros in the nibble
                count += when (nibble) {
                    1 -> 3  // 0001
                    2, 3 -> 2  // 001x
                    4, 5, 6, 7 -> 1  // 01xx
                    else -> 0  // 1xxx
                }
                break
            }
        }
        
        return count
    }
    
    /**
     * Validate that an event meets the minimum difficulty requirement
     * @param event The Nostr event to validate
     * @param minimumDifficulty The minimum required difficulty
     * @return true if the event meets the difficulty requirement
     */
    fun validateDifficulty(event: NostrEvent, minimumDifficulty: Int): Boolean {
        if (minimumDifficulty <= 0) return true
        
        // Require explicit nonce tag to recognize PoW per NIP-13 intent
        if (!hasNonce(event)) {
            Log.w(TAG, "Event ${event.id.take(16)}... missing nonce tag; treating as no PoW")
            return false
        }
        
        val actualDifficulty = calculateDifficulty(event.id)
        val committedDifficulty = getCommittedDifficulty(event)
        
        Log.d(TAG, "Validating PoW: actual=$actualDifficulty, required=$minimumDifficulty, committed=$committedDifficulty")
        
        // Check if actual difficulty meets requirement
        if (actualDifficulty < minimumDifficulty) {
            Log.w(TAG, "Event ${event.id.take(16)}... has insufficient difficulty: $actualDifficulty < $minimumDifficulty")
            return false
        }
        
        // If there's a committed difficulty, it should match or exceed the minimum
        if (committedDifficulty != null && committedDifficulty < minimumDifficulty) {
            Log.w(TAG, "Event ${event.id.take(16)}... has committed difficulty $committedDifficulty but achieved $actualDifficulty (possible spam)")
            return false
        }
        
        return true
    }
    
    /**
     * Mine a Nostr event to achieve the target difficulty
     * @param event The event to mine (will be modified with nonce tag)
     * @param targetDifficulty The target difficulty to achieve
     * @param maxIterations Maximum number of iterations before giving up (default: 1,000,000)
     * @return The mined event with nonce tag, or null if mining failed
     */
    suspend fun mineEvent(
        event: NostrEvent,
        targetDifficulty: Int,
        maxIterations: Int = 1_000_000
    ): NostrEvent? = withContext(Dispatchers.Default) {
        if (targetDifficulty <= 0) return@withContext event
        
        Log.d(TAG, "Starting PoW mining for difficulty $targetDifficulty...")
        val startTime = System.currentTimeMillis()
        
        var nonce = Random.nextLong(0, 1_000_000).toString()
        var iterations = 0
        
        while (iterations < maxIterations) {
            // Create a copy of the event with the nonce tag
            val eventWithNonce = addNonceTag(event, nonce, targetDifficulty)
            
            // Calculate the event ID
            val eventId = eventWithNonce.computeEventIdHex()
            val actualDifficulty = calculateDifficulty(eventId)
            
            if (actualDifficulty >= targetDifficulty) {
                val timeElapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "✅ PoW mining successful! Difficulty: $actualDifficulty, iterations: $iterations, time: ${timeElapsed}ms")
                
                // Return the event with the computed ID
                return@withContext eventWithNonce.copy(id = eventId)
            }
            
            // Increment nonce and try again
            nonce = (nonce.toLongOrNull()?.plus(1) ?: Random.nextLong()).toString()
            iterations++
            
            // Log progress every 100,000 iterations
            if (iterations % 100_000 == 0) {
                val timeElapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "PoW mining progress: $iterations iterations, ${timeElapsed}ms elapsed")
            }
        }
        
        val timeElapsed = System.currentTimeMillis() - startTime
        Log.w(TAG, "❌ PoW mining failed after $maxIterations iterations (${timeElapsed}ms)")
        return@withContext null
    }
    
    /**
     * Add or update the nonce tag in an event
     * @param event The original event
     * @param nonce The nonce value
     * @param targetDifficulty The target difficulty being attempted
     * @return A new event with the nonce tag added/updated
     */
    private fun addNonceTag(event: NostrEvent, nonce: String, targetDifficulty: Int): NostrEvent {
        val newTags = event.tags.toMutableList()
        
        // Remove existing nonce tag if present
        newTags.removeAll { tag -> tag.isNotEmpty() && tag[0] == "nonce" }
        
        // Add new nonce tag with format: ["nonce", nonce_value, target_difficulty]
        newTags.add(listOf("nonce", nonce, targetDifficulty.toString()))
        
        // Update created_at as recommended by NIP-13
        val updatedCreatedAt = (System.currentTimeMillis() / 1000).toInt()
        
        return event.copy(
            tags = newTags,
            createdAt = updatedCreatedAt
        )
    }
    
    /**
     * Get the committed difficulty from an event's nonce tag
     * @param event The event to check
     * @return The committed difficulty, or null if not present
     */
    private fun getCommittedDifficulty(event: NostrEvent): Int? {
        val nonceTag = event.tags.find { tag -> 
            tag.isNotEmpty() && tag[0] == "nonce" && tag.size >= 3 
        }
        
        return nonceTag?.get(2)?.toIntOrNull()
    }
    
    /**
     * Check if an event has a nonce tag (indicating it was mined)
     * @param event The event to check
     * @return true if the event has a nonce tag
     */
    fun hasNonce(event: NostrEvent): Boolean {
        return event.tags.any { tag -> tag.isNotEmpty() && tag[0] == "nonce" }
    }
    
    /**
     * Get the nonce value from an event
     * @param event The event to check
     * @return The nonce value, or null if not present
     */
    fun getNonce(event: NostrEvent): String? {
        val nonceTag = event.tags.find { tag -> 
            tag.isNotEmpty() && tag[0] == "nonce" && tag.size >= 2 
        }
        
        return nonceTag?.get(1)
    }
    
    /**
     * Estimate the computational work required for a given difficulty
     * @param difficulty The target difficulty
     * @return Estimated number of hash operations required
     */
    fun estimateWork(difficulty: Int): Long {
        return if (difficulty <= 0) 1L else 1L shl difficulty
    }
    
    /**
     * Get a human-readable description of the estimated mining time
     * @param difficulty The target difficulty
     * @param hashesPerSecond Estimated hashes per second (default: 100,000)
     * @return Human-readable time estimate
     */
    fun estimateMiningTime(difficulty: Int, hashesPerSecond: Int = 100_000): String {
        val estimatedHashes = estimateWork(difficulty)
        val estimatedSeconds = estimatedHashes / hashesPerSecond
        
        return when {
            estimatedSeconds < 1 -> "< 1 second"
            estimatedSeconds < 60 -> "${estimatedSeconds}s"
            estimatedSeconds < 3600 -> "${estimatedSeconds / 60}m ${estimatedSeconds % 60}s"
            estimatedSeconds < 86400 -> "${estimatedSeconds / 3600}h ${(estimatedSeconds % 3600) / 60}m"
            else -> "${estimatedSeconds / 86400}d ${(estimatedSeconds % 86400) / 3600}h"
        }
    }
}
