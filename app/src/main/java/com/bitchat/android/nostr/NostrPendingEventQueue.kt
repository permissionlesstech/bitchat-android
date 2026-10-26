package com.bitchat.android.nostr

data class GeoRelayRoute(val geohash: String, val primaryCount: Int = 5, val includeDefaults: Boolean = false)

/**
 * Thread-safe bounded queue of relay deliveries awaiting a usable WebSocket.
 *
 * Queue entries have a local ID rather than using the Nostr event ID: the same signed event may be
 * intentionally published more than once with different relay sets or privacy provenance.
 */
internal class NostrPendingEventQueue(
    private val capacity: Int
) {
    init {
        require(capacity > 0)
    }

    data class Delivery(
        val queueId: Long,
        val event: NostrEvent,
        val liveLocationToken: Long?
    )

    private data class Entry(
        val queueId: Long,
        val event: NostrEvent,
        val pendingRelayUrls: MutableSet<String>,
        val liveLocationToken: Long?,
        val geoRoute: GeoRelayRoute? = null,
        val deliveredRelayUrls: MutableSet<String> = mutableSetOf()
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var nextQueueId = 1L

    fun enqueue(
        event: NostrEvent,
        relayUrls: Collection<String>,
        liveLocationToken: Long?,
        geoRoute: GeoRelayRoute? = null
    ): Long? {
        val pendingRelays = relayUrls.filterTo(linkedSetOf()) { it.isNotBlank() }
        if (pendingRelays.isEmpty() && geoRoute == null) return null

        return synchronized(lock) {
            if (entries.size >= capacity) entries.removeFirst()
            val queueId = nextQueueId++
            entries.addLast(
                Entry(
                    queueId = queueId,
                    event = event,
                    pendingRelayUrls = pendingRelays,
                    liveLocationToken = liveLocationToken,
                    geoRoute = geoRoute
                )
            )
            queueId
        }
    }

    fun pendingForRelay(relayUrl: String): List<Delivery> = synchronized(lock) {
        entries
            .asSequence()
            .filter { relayUrl in it.pendingRelayUrls }
            .map { Delivery(it.queueId, it.event, it.liveLocationToken) }
            .toList()
    }

    fun isPending(queueId: Long, relayUrl: String): Boolean = synchronized(lock) {
        entries.any { it.queueId == queueId && relayUrl in it.pendingRelayUrls }
    }

    fun markDelivered(queueId: Long, relayUrl: String) {
        synchronized(lock) {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.queueId != queueId) continue
                if (!entry.pendingRelayUrls.remove(relayUrl)) return
                entry.deliveredRelayUrls.add(relayUrl)
                if (entry.pendingRelayUrls.isEmpty()) iterator.remove()
                return
            }
        }
    }

    /** Replace geographic destinations without replaying to already-written relays. */
    fun rerouteGeohashEvents(lookup: (GeoRelayRoute) -> Collection<String>): Set<String> = synchronized(lock) {
        val targets = mutableSetOf<String>()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val route = entry.geoRoute ?: continue
            val selected = lookup(route).toSet()
            entry.pendingRelayUrls.clear()
            entry.pendingRelayUrls.addAll(selected - entry.deliveredRelayUrls)
            targets.addAll(entry.pendingRelayUrls)
            if (selected.isNotEmpty() && entry.pendingRelayUrls.isEmpty()) iterator.remove()
        }
        targets
    }

    fun pendingRelayUrls(): Set<String> = synchronized(lock) {
        entries.flatMapTo(mutableSetOf()) { it.pendingRelayUrls }
    }

    fun pendingGeoRoutes(): List<Pair<GeoRelayRoute, Long?>> = synchronized(lock) {
        entries.mapNotNull { entry -> entry.geoRoute?.let { it to entry.liveLocationToken } }
    }

    fun removeLiveLocationEvents() {
        synchronized(lock) {
            entries.removeAll { it.liveLocationToken != null }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    internal fun size(): Int = synchronized(lock) { entries.size }
}
