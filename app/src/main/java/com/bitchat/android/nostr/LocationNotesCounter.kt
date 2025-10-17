package com.bitchat.android.nostr

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*

/**
 * Lightweight counter for location notes
 * Singleton manager for counting unique notes per geohash
 * iOS-compatible implementation
 */
object LocationNotesCounter {
    
    private const val TAG = "LocationNotesCounter"
    
    // Published state
    private val _geohash = MutableLiveData<String?>(null)
    val geohash: LiveData<String?> = _geohash
    
    private val _count = MutableLiveData(0)
    val count: LiveData<Int> = _count
    
    private val _initialLoadComplete = MutableLiveData(false)
    val initialLoadComplete: LiveData<Boolean> = _initialLoadComplete
    
    private val _relayAvailable = MutableLiveData(false)
    val relayAvailable: LiveData<Boolean> = _relayAvailable
    
    // Private state
    private var subscriptionID: String? = null
    private val eventIDs = mutableSetOf<String>() // For deduplication
    
    // Dependencies (injected)
    private var relayLookup: (() -> NostrRelayManager)? = null
    private var subscribeFunc: ((NostrFilter, String, (NostrEvent) -> Unit) -> String)? = null
    private var unsubscribeFunc: ((String) -> Unit)? = null
    
    /**
     * Initialize dependencies
     */
    fun initialize(
        relayManager: () -> NostrRelayManager,
        subscribe: (NostrFilter, String, (NostrEvent) -> Unit) -> String,
        unsubscribe: (String) -> Unit
    ) {
        this.relayLookup = relayManager
        this.subscribeFunc = subscribe
        this.unsubscribeFunc = unsubscribe
    }
    
    /**
     * Subscribe to count notes for a specific geohash
     * iOS: Validates building-level precision (8 chars) and checks for relay availability
     */
    fun subscribe(geohash: String) {
        val normalized = geohash.lowercase()
        
        // Skip if already subscribed to this geohash
        if (_geohash.value == normalized && subscriptionID != null) {
            Log.d(TAG, "Already subscribed to geohash: $normalized")
            return
        }
        
        // Validate geohash (building-level precision: 8 chars) - matches iOS
        if (!isValidBuildingGeohash(normalized)) {
            Log.w(TAG, "LocationNotesCounter: rejecting invalid geohash '$normalized' (expected 8 valid base32 chars)")
            return
        }
        
        Log.d(TAG, "Subscribing to count notes for geohash: $normalized")
        
        // Unsubscribe previous without clearing count to avoid flicker (iOS pattern)
        subscriptionID?.let { subId ->
            unsubscribeFunc?.invoke(subId)
        }
        subscriptionID = null
        
        // Reset state
        _geohash.value = normalized
        eventIDs.clear()
        _initialLoadComplete.value = false
        _relayAvailable.value = true
        
        // Get geo-specific relays (iOS pattern: dependencies.relayLookup(norm, TransportConfig.nostrGeoRelayCount))
        val relays = try {
            com.bitchat.android.nostr.RelayDirectory.closestRelaysForGeohash(normalized, 5)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lookup relays for geohash $normalized: ${e.message}")
            emptyList()
        }
        
        // Check if we have relays (iOS pattern: guard !relays.isEmpty())
        if (relays.isEmpty()) {
            Log.w(TAG, "LocationNotesCounter: no geo relays for geohash=$normalized")
            _relayAvailable.value = false
            _initialLoadComplete.value = true
            _count.value = 0
            return
        }
        
        val subscribe = subscribeFunc
        if (subscribe == null) {
            Log.e(TAG, "Cannot subscribe - subscribe function not initialized")
            return
        }
        
        val filter = NostrFilter.geohashNotes(
            geohash = normalized,
            since = null,
            limit = 200 // Count up to 200 recent notes
        )
        
        // iOS format: "locnotes-count-\(norm)-\(UUID().uuidString.prefix(6))"
        val subId = "locnotes-count-$normalized-${java.util.UUID.randomUUID().toString().take(6)}"
        
        subscriptionID = subscribe(filter, subId) { event ->
            handleEvent(event, normalized)
        }
        
        // Mark initial load complete after brief delay
        kotlinx.coroutines.MainScope().launch {
            kotlinx.coroutines.delay(1500)
            if (!_initialLoadComplete.value!!) {
                _initialLoadComplete.value = true
                Log.d(TAG, "Initial count load complete: ${_count.value} notes")
            }
        }
    }
    
    /**
     * Validate building-level geohash (precision 8) - matches iOS Geohash.isValidBuildingGeohash
     */
    private fun isValidBuildingGeohash(geohash: String): Boolean {
        if (geohash.length != 8) return false
        val base32Chars = "0123456789bcdefghjkmnpqrstuvwxyz"
        return geohash.all { it in base32Chars }
    }
    
    /**
     * Handle incoming event
     */
    private fun handleEvent(event: NostrEvent, expectedGeohash: String) {
        // Validate event
        if (event.kind != NostrKind.TEXT_NOTE) {
            return
        }
        
        // Check for geohash tag
        val geohashTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }
        if (geohashTag == null) {
            return
        }
        
        // Check if matches expected geohash
        val eventGeohash = geohashTag[1]
        if (eventGeohash != expectedGeohash) {
            return
        }
        
        // Deduplicate and count
        if (!eventIDs.contains(event.id)) {
            eventIDs.add(event.id)
            _count.value = eventIDs.size
            
            if (!_initialLoadComplete.value!!) {
                _initialLoadComplete.value = true
            }
        }
    }
    
    /**
     * Cancel subscription
     */
    fun cancel() {
        subscriptionID?.let { subId ->
            Log.d(TAG, "🚫 Canceling counter subscription: $subId")
            unsubscribeFunc?.invoke(subId)
            subscriptionID = null
        }
        
        _geohash.value = null
        _count.value = 0
        eventIDs.clear()
        _initialLoadComplete.value = false
    }
}
