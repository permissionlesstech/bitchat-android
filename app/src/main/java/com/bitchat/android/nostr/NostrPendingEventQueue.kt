package com.bitchat.android.nostr

/**
 * Thread-safe bounded queue of relay deliveries awaiting a relay acknowledgement.
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
        val authenticationIdentity: (() -> NostrIdentity?)? = null
    )

    private data class Entry(
        val queueId: Long,
        val event: NostrEvent,
        val pendingRelayUrls: MutableSet<String>,
        val liveLocationToken: Long?,
        val authenticationIdentity: (() -> NostrIdentity?)?
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var nextQueueId = 1L

    fun enqueue(
        event: NostrEvent,
        relayUrls: Collection<String>,
        liveLocationToken: Long?,
        authenticationIdentity: (() -> NostrIdentity?)? = null
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
                    authenticationIdentity = authenticationIdentity
                )
            )
            queueId
        }
    }

    fun pendingForRelay(relayUrl: String): List<Delivery> = synchronized(lock) {
        entries
            .asSequence()
            .filter { relayUrl in it.pendingRelayUrls }
            .map { Delivery(it.queueId, it.event, it.liveLocationToken, it.authenticationIdentity) }
            .toList()
    }

    /** Serialize a pending check and local WebSocket enqueue with acknowledgement, eviction or reset. */
    fun runIfPending(queueId: Long, relayUrl: String, action: () -> Unit) {
        synchronized(lock) {
            if (entries.none { it.queueId == queueId && relayUrl in it.pendingRelayUrls }) return
            action()
        }
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

    fun acknowledge(eventId: String, relayUrl: String) {
        synchronized(lock) {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.event.id != eventId) continue
                entry.pendingRelayUrls.remove(relayUrl)
                if (entry.pendingRelayUrls.isEmpty()) iterator.remove()
            }
        }
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
