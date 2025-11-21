package com.bitchat.android.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import jakarta.inject.Inject

/**
 * NostrSubscriptionManager
 * - Encapsulates subscription lifecycle with NostrRelayManager
 */
class NostrSubscriptionManager @Inject constructor(
    private val nostrRelayManager: NostrRelayManager,
    private val scope: CoroutineScope
) {
    companion object { private const val TAG = "NostrSubscriptionManager" }

    fun connect() = scope.launch { runCatching { nostrRelayManager.connect() }.onFailure { Log.e(TAG, "connect failed: ${it.message}") } }
    fun disconnect() = scope.launch { runCatching { nostrRelayManager.disconnect() }.onFailure { Log.e(TAG, "disconnect failed: ${it.message}") } }

    fun subscribeGiftWraps(pubkey: String, sinceMs: Long, id: String, handler: (NostrEvent) -> Unit) {
        scope.launch {
            val filter = NostrFilter.giftWrapsFor(pubkey, sinceMs)
            nostrRelayManager.subscribe(filter, id, handler)
        }
    }

    fun subscribeGeohash(geohash: String, sinceMs: Long, limit: Int, id: String, handler: (NostrEvent) -> Unit) {
        scope.launch {
            val filter = NostrFilter.geohashEphemeral(geohash, sinceMs, limit)
            nostrRelayManager.subscribeForGeohash(geohash, filter, id, handler, includeDefaults = false, nRelays = 5)
        }
    }

    fun unsubscribe(id: String) { scope.launch { runCatching { nostrRelayManager.unsubscribe(id) } } }
}
