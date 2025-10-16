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
     */
    fun subscribe(geohash: String) {
        if (_geohash.value == geohash && subscriptionID != null) {
            Log.d(TAG, "Already subscribed to geohash: $geohash")
            return
        }
        
        Log.d(TAG, "Subscribing to count notes for geohash: $geohash")
        
        // Cancel existing subscription
        cancel()
        
        // Reset state
        _geohash.value = geohash
        _count.value = 0
        eventIDs.clear()
        _initialLoadComplete.value = false
        
        // Check relay availability
        val relayManager = relayLookup?.invoke()
        val hasRelays = relayManager?.getRelayStatuses()?.any { it.isConnected } == true
        _relayAvailable.value = hasRelays
        
        if (!hasRelays) {
            Log.w(TAG, "No relays available for counter subscription")
            return
        }
        
        val subscribe = subscribeFunc
        if (subscribe == null) {
            Log.e(TAG, "Cannot subscribe - subscribe function not initialized")
            return
        }
        
        val filter = NostrFilter.geohashNotes(
            geohash = geohash,
            since = null,
            limit = 200 // Count up to 200 recent notes
        )
        
        val subId = "location-notes-count-$geohash"
        
        subscriptionID = subscribe(filter, subId) { event ->
            handleEvent(event, geohash)
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
