package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import kotlinx.coroutines.CoroutineScope

/**
 * NostrSubscriptionManager
 * - Encapsulates subscription lifecycle with NostrRelayManager
 */
class NostrSubscriptionManager(
    private val application: Application,
    @Suppress("UNUSED_PARAMETER")
    private val scope: CoroutineScope
) {
    companion object { private const val TAG = "NostrSubscriptionManager" }

    private val relayManager get() = NostrRelayManager.getInstance(application)

    fun connect() {
        runCatching { relayManager.connect() }
            .onFailure { Log.e(TAG, "connect failed: ${it.message}") }
    }

    fun disconnect() {
        runCatching { relayManager.disconnect() }
            .onFailure { Log.e(TAG, "disconnect failed: ${it.message}") }
    }

    fun subscribeGiftWraps(pubkey: String, sinceMs: Long, id: String, handler: (NostrEvent) -> Unit) {
        val generation = relayManager.captureAccountGeneration()
        val filter = NostrFilter.giftWrapsFor(pubkey, sinceMs)
        relayManager.subscribe(
            filter = filter,
            id = id,
            handler = handler,
            expectedAccountGeneration = generation
        )
    }

    /** Subscribe to geohash chat messages only (kind 20000) — low-volume, kept alive in background. */
    fun subscribeGeohashMessages(
        geohash: String,
        sinceMs: Long,
        limit: Int,
        id: String,
        handler: (NostrEvent) -> Unit,
        liveLocationToken: Long? = null
    ) {
        if (liveLocationToken != null &&
            !LiveLocationPrivacyGate.accepts(liveLocationToken)
        ) return
        val generation = relayManager.captureAccountGeneration()
        val filter = NostrFilter.geohashMessages(geohash, sinceMs, limit)
        relayManager.subscribeForGeohash(
            geohash,
            filter,
            id,
            handler,
            includeDefaults = false,
            nRelays = 5,
            liveLocationToken = liveLocationToken,
            expectedAccountGeneration = generation
        )
    }

    /** Subscribe to geohash presence heartbeats only (kind 20001) — high-volume, paused in background. */
    fun subscribeGeohashPresence(
        geohash: String,
        sinceMs: Long,
        limit: Int,
        id: String,
        handler: (NostrEvent) -> Unit,
        liveLocationToken: Long? = null
    ) {
        if (liveLocationToken != null &&
            !LiveLocationPrivacyGate.accepts(liveLocationToken)
        ) return
        val generation = relayManager.captureAccountGeneration()
        val filter = NostrFilter.geohashPresence(geohash, sinceMs, limit)
        relayManager.subscribeForGeohash(
            geohash,
            filter,
            id,
            handler,
            includeDefaults = false,
            nRelays = 5,
            liveLocationToken = liveLocationToken,
            expectedAccountGeneration = generation
        )
    }

    fun unsubscribe(id: String) {
        runCatching { relayManager.unsubscribe(id) }
    }
}
