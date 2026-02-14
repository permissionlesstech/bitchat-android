package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * NostrSubscriptionManager
 * - Encapsulates subscription lifecycle with NostrRelayManager
 */
class NostrSubscriptionManager(
    private val application: Application,
    private val scope: CoroutineScope
) {
    companion object { private const val TAG = "NostrSubscriptionManager" }

    private val relayManager get() = NostrRelayManager.getInstance(application)

    fun connect() = scope.launch { runCatching { relayManager.connect() }.onFailure { Log.e(TAG, "connect failed: ${it.message}") } }
    fun disconnect() = scope.launch { runCatching { relayManager.disconnect() }.onFailure { Log.e(TAG, "disconnect failed: ${it.message}") } }

    fun subscribeGiftWraps(pubkey: String, sinceMs: Long, id: String, handler: (NostrEvent) -> Unit) {
        scope.launch {
            val filter = NostrFilter.giftWrapsFor(pubkey, sinceMs)
            relayManager.subscribe(filter, id, handler)
        }
    }

    fun subscribeGeohash(geohash: String, sinceMs: Long, limit: Int, id: String, handler: (NostrEvent) -> Unit) {
        scope.launch {
            val filter = NostrFilter.geohashEphemeral(geohash, sinceMs, limit)
            val optimalRelays = NostrRelayManager.optimalRelayCount(geohash)
            Log.d(TAG, "📡 Subscribing to $geohash with $optimalRelays relays (precision: ${geohash.length} chars)")
            relayManager.subscribeForGeohash(geohash, filter, id, handler, includeDefaults = false, nRelays = optimalRelays)
        }
    }

    fun subscribeGeohashMessages(geohash: String, sinceMs: Long, limit: Int, id: String, handler: (NostrEvent) -> Unit) {
        scope.launch {
            val filter = NostrFilter.geohashMessages(geohash, sinceMs, limit)
            val optimalRelays = NostrRelayManager.optimalRelayCount(geohash)
            Log.d(TAG, "📡 Subscribing to $geohash MESSAGES with $optimalRelays relays")
            relayManager.subscribeForGeohash(geohash, filter, id, handler, includeDefaults = false, nRelays = optimalRelays)
        }
    }

    fun subscribeGeohashPresence(geohash: String, sinceMs: Long, limit: Int, id: String, handler: (NostrEvent) -> Unit) {
        scope.launch {
            val filter = NostrFilter.geohashPresence(geohash, sinceMs, limit)
            val optimalRelays = NostrRelayManager.optimalRelayCount(geohash)
            Log.d(TAG, "📡 Subscribing to $geohash PRESENCE with $optimalRelays relays")
            relayManager.subscribeForGeohash(geohash, filter, id, handler, includeDefaults = false, nRelays = optimalRelays)
        }
    }

    fun unsubscribe(id: String) { scope.launch { runCatching { relayManager.unsubscribe(id) } } }
}
