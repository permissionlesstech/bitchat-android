package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.geohash.LiveLocationPrivacyGate

/**
 * NostrSubscriptionManager
 * - Encapsulates ordered subscription lifecycle with NostrRelayManager.
 *
 * Relay-manager operations are already non-blocking and schedule network I/O on their own scope.
 * Keeping this facade synchronous prevents lifecycle cancellation from dropping unsubscribe work
 * and guarantees that a channel switch closes the old subscription before opening the new one.
 */
class NostrSubscriptionManager(
    private val application: Application,
    private val owner: String = NostrRelayManager.OWNER_LEGACY
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

    fun subscribeGiftWraps(
        pubkey: String,
        sinceMs: Long,
        id: String,
        handler: (NostrEvent) -> Unit,
        liveLocationToken: Long? = null
    ) {
        if (!isAllowed(liveLocationToken)) return
        val filter = NostrFilter.giftWrapsFor(pubkey, sinceMs)
        relayManager.subscribe(
            filter = filter,
            id = id,
            handler = handler,
            owner = owner,
            liveLocationToken = liveLocationToken
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
        if (!isAllowed(liveLocationToken)) return
        val filter = NostrFilter.geohashMessages(geohash, sinceMs, limit)
        relayManager.subscribeForGeohash(
            geohash,
            filter,
            id,
            handler,
            includeDefaults = false,
            nRelays = 5,
            owner = owner,
            liveLocationToken = liveLocationToken
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
        if (!isAllowed(liveLocationToken)) return
        val filter = NostrFilter.geohashPresence(geohash, sinceMs, limit)
        relayManager.subscribeForGeohash(
            geohash,
            filter,
            id,
            handler,
            includeDefaults = false,
            nRelays = 5,
            owner = owner,
            liveLocationToken = liveLocationToken
        )
    }

    fun unsubscribe(id: String) {
        runCatching { relayManager.unsubscribe(id) }
    }

    fun unsubscribeAllOwned() {
        runCatching { relayManager.unsubscribeOwner(owner) }
    }

    private fun isAllowed(liveLocationToken: Long?): Boolean =
        liveLocationToken == null || LiveLocationPrivacyGate.accepts(liveLocationToken)
}
