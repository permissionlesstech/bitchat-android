package com.bitchat.android.nostr

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects and tracks Nostr relay metrics
 * Separates metric collection from relay management logic
 */
class NostrMetricsCollector {

    // Atomic counters for thread-safe updates
    private val totalBytesReceived = AtomicLong(0)
    private val totalBytesSent = AtomicLong(0)
    private val totalEventsReceived = AtomicLong(0)
    private val reconnectionCount = AtomicLong(0)

    // Per-relay and per-subscription tracking
    private val bytesReceivedPerRelay = ConcurrentHashMap<String, Long>()
    private val bytesSentPerRelay = ConcurrentHashMap<String, Long>()
    private val eventsReceivedPerSubscription = ConcurrentHashMap<String, Long>()

    // Relay message counters
    private val relayMessagesSent = ConcurrentHashMap<String, Int>()
    private val relayMessagesReceived = ConcurrentHashMap<String, Int>()

    // Track start time for uptime calculation
    private val startTime = System.currentTimeMillis()

    /**
     * Record bytes received from a relay
     */
    fun recordBytesReceived(relayUrl: String, bytes: Long) {
        totalBytesReceived.addAndGet(bytes)
        bytesReceivedPerRelay.merge(relayUrl, bytes) { old, new -> old + new }
    }

    /**
     * Record bytes sent to a relay
     */
    fun recordBytesSent(relayUrl: String, bytes: Long) {
        totalBytesSent.addAndGet(bytes)
        bytesSentPerRelay.merge(relayUrl, bytes) { old, new -> old + new }
    }

    /**
     * Record an event received for a subscription
     */
    fun recordEventReceived(subscriptionId: String, eventSize: Long) {
        totalEventsReceived.incrementAndGet()
        eventsReceivedPerSubscription.merge(subscriptionId, 1) { old, _ -> old + 1 }
    }

    /**
     * Record a relay reconnection
     */
    fun recordReconnection() {
        reconnectionCount.incrementAndGet()
    }

    /**
     * Record a message sent to a relay
     */
    fun recordMessageSent(relayUrl: String) {
        relayMessagesSent.merge(relayUrl, 1) { old, _ -> old + 1 }
    }

    /**
     * Record a message received from a relay
     */
    fun recordMessageReceived(relayUrl: String) {
        relayMessagesReceived.merge(relayUrl, 1) { old, _ -> old + 1 }
    }

    /**
     * Remove subscription tracking when unsubscribed
     */
    fun removeSubscription(subscriptionId: String) {
        eventsReceivedPerSubscription.remove(subscriptionId)
    }

    /**
     * Get current metrics snapshot
     */
    fun getMetrics(): NostrMetrics {
        return NostrMetrics(
            totalBytesReceived = totalBytesReceived.get(),
            totalBytesSent = totalBytesSent.get(),
            totalEventsReceived = totalEventsReceived.get(),
            reconnectionCount = reconnectionCount.get(),
            bytesReceivedPerRelay = bytesReceivedPerRelay.toMap(),
            bytesSentPerRelay = bytesSentPerRelay.toMap(),
            eventsReceivedPerSubscription = eventsReceivedPerSubscription.toMap(),
            relayMessagesSent = relayMessagesSent.toMap(),
            relayMessagesReceived = relayMessagesReceived.toMap(),
            uptimeMillis = System.currentTimeMillis() - startTime
        )
    }

    /**
     * Reset all metrics (for testing or panic reset)
     */
    fun reset() {
        totalBytesReceived.set(0)
        totalBytesSent.set(0)
        totalEventsReceived.set(0)
        reconnectionCount.set(0)
        bytesReceivedPerRelay.clear()
        bytesSentPerRelay.clear()
        eventsReceivedPerSubscription.clear()
        relayMessagesSent.clear()
        relayMessagesReceived.clear()
    }
}

/**
 * Immutable snapshot of Nostr metrics
 */
data class NostrMetrics(
    val totalBytesReceived: Long,
    val totalBytesSent: Long,
    val totalEventsReceived: Long,
    val reconnectionCount: Long,
    val bytesReceivedPerRelay: Map<String, Long>,
    val bytesSentPerRelay: Map<String, Long>,
    val eventsReceivedPerSubscription: Map<String, Long>,
    val relayMessagesSent: Map<String, Int>,
    val relayMessagesReceived: Map<String, Int>,
    val uptimeMillis: Long
) {
    val uptimeHours: Double
        get() = uptimeMillis / 3600000.0

    val averageEventSize: Long
        get() = if (totalEventsReceived > 0) totalBytesReceived / totalEventsReceived else 0

    val bandwidthPerHour: Double
        get() = if (uptimeHours > 0) (totalBytesReceived / 1048576.0) / uptimeHours else 0.0
}