package com.bitchat.android.nostr

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
        val liveLocationToken: Long?,
        val accountGeneration: Long
    )

    private data class Entry(
        val queueId: Long,
        val event: NostrEvent,
        val pendingRelayUrls: MutableSet<String>,
        val liveLocationToken: Long?,
        val accountGeneration: Long
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var nextQueueId = 1L

    fun enqueue(
        event: NostrEvent,
        relayUrls: Collection<String>,
        liveLocationToken: Long?,
        accountGeneration: Long
    ): Long? {
        val pendingRelays = relayUrls.filterTo(linkedSetOf()) { it.isNotBlank() }
        if (pendingRelays.isEmpty()) return null

        return synchronized(lock) {
            if (entries.size >= capacity) entries.removeFirst()
            val queueId = nextQueueId++
            entries.addLast(
                Entry(
                    queueId = queueId,
                    event = event,
                    pendingRelayUrls = pendingRelays,
                    liveLocationToken = liveLocationToken,
                    accountGeneration = accountGeneration
                )
            )
            queueId
        }
    }

    fun pendingForRelay(
        relayUrl: String,
        accountGeneration: Long
    ): List<Delivery> = synchronized(lock) {
        entries
            .asSequence()
            .filter {
                it.accountGeneration == accountGeneration &&
                    relayUrl in it.pendingRelayUrls
            }
            .map {
                Delivery(
                    queueId = it.queueId,
                    event = it.event,
                    liveLocationToken = it.liveLocationToken,
                    accountGeneration = it.accountGeneration
                )
            }
            .toList()
    }

    fun markDelivered(queueId: Long, relayUrl: String) {
        synchronized(lock) {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.queueId != queueId) continue
                entry.pendingRelayUrls.remove(relayUrl)
                if (entry.pendingRelayUrls.isEmpty()) iterator.remove()
                return
            }
        }
    }

    fun removeLiveLocationEvents(accountGeneration: Long) {
        synchronized(lock) {
            entries.removeAll {
                it.accountGeneration == accountGeneration &&
                    it.liveLocationToken != null
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    internal fun size(): Int = synchronized(lock) { entries.size }
}
