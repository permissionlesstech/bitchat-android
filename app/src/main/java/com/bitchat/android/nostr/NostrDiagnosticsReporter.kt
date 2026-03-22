package com.bitchat.android.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles diagnostics reporting and health checks for Nostr relays
 * Separates diagnostics logic from relay management
 */
class NostrDiagnosticsReporter(
    private val metricsCollector: NostrMetricsCollector,
    private val scope: CoroutineScope,
    private val getActiveSubscriptions: () -> Map<String, NostrRelayManager.SubscriptionInfo>,
    private val getConnections: () -> Map<String, *>,
    private val getSubscriptions: () -> Map<String, Set<String>>
) {

    companion object {
        private const val TAG = "NostrDiagnostics"
        private const val HEALTH_CHECK_INTERVAL = 300000L // 5 minutes
        private const val SUBSCRIPTION_VALIDATION_INTERVAL = 30000L // 30 seconds
    }

    private var healthCheckJob: Job? = null
    private var subscriptionValidationJob: Job? = null
    var diagnosticLoggingEnabled = false

    /**
     * Start periodic health check logging
     */
    fun startHealthCheck() {
        stopHealthCheck()

        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL)

                try {
                    val metrics = metricsCollector.getMetrics()
                    val activeSubscriptions = getActiveSubscriptions()
                    val now = System.currentTimeMillis()

                    // Find old subscriptions
                    val oldSubs = activeSubscriptions.filter { (_, info) ->
                        (now - info.createdAt) > 21600000 // > 6 hours
                    }

                    val mbReceived = metrics.totalBytesReceived / 1048576.0
                    val ratePerHour = if (metrics.uptimeHours > 0) mbReceived / metrics.uptimeHours else 0.0

                    Log.i(TAG, """
                        ═══ HEALTH CHECK ═══
                        📊 Subscriptions: ${activeSubscriptions.size} active, ${oldSubs.size} old (>6h)
                        📡 Relays: ${getConnections().size} connected, ${metrics.reconnectionCount} reconnections
                        📈 Data: ${formatBytes(metrics.totalBytesReceived)} received (${String.format("%.1f", ratePerHour)} MB/h)
                        📥 Events: ${metrics.totalEventsReceived} (avg ${formatBytes(metrics.averageEventSize)})
                        ${if (oldSubs.size > 5) "⚠️  WARNING: ${oldSubs.size} old subscriptions (possible leaks)" else ""}
                        ${if (ratePerHour > 100) "⚠️  WARNING: High bandwidth (${String.format("%.1f", ratePerHour)} MB/h)" else ""}
                    """.trimIndent())

                    if (oldSubs.size > 10 || ratePerHour > 500) {
                        Log.w(TAG, "🚨 CRITICAL ISSUE DETECTED - Full diagnostics:")
                        Log.w(TAG, generateDiagnosticsReport())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during health check: ${e.message}")
                }
            }
        }

        Log.d(TAG, "🔄 Started periodic health check (${HEALTH_CHECK_INTERVAL / 1000}s interval)")
    }

    /**
     * Stop health check logging
     */
    fun stopHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    /**
     * Generate comprehensive diagnostics report
     */
    fun generateDiagnosticsReport(): String {
        val metrics = metricsCollector.getMetrics()
        val activeSubscriptions = getActiveSubscriptions()
        val connections = getConnections()
        val now = System.currentTimeMillis()

        // Group subscriptions by age
        val subsByAge = activeSubscriptions.entries.groupBy { (_, info) ->
            val ageHours = (now - info.createdAt) / 3600000
            when {
                ageHours < 1 -> "0-1h"
                ageHours < 6 -> "1-6h"
                ageHours < 24 -> "6-24h"
                else -> "24h+"
            }
        }

        // Find old subscriptions (likely leaks)
        val oldSubs = activeSubscriptions.filter { (_, info) ->
            (now - info.createdAt) > 21600000 // > 6 hours
        }

        // Find top event-receiving subscriptions
        val topSubs = metrics.eventsReceivedPerSubscription.entries
            .sortedByDescending { it.value }
            .take(10)

        // Calculate per-relay bandwidth
        val relayBandwidth = metrics.bytesReceivedPerRelay.entries
            .sortedByDescending { it.value }
            .take(10)

        return """
════════════════════════════════════════════════════════════════
                NOSTR DATA USAGE DIAGNOSTICS REPORT
════════════════════════════════════════════════════════════════

📊 OVERVIEW
────────────────────────────────────────────────────────────────
Active Subscriptions: ${activeSubscriptions.size}
Connected Relays: ${connections.size}
Reconnections: ${metrics.reconnectionCount}
Uptime: ${String.format("%.1f", metrics.uptimeHours)} hours

🔍 DATA TRANSFER
────────────────────────────────────────────────────────────────
Total Sent: ${formatBytes(metrics.totalBytesSent)}
Total Received: ${formatBytes(metrics.totalBytesReceived)}
Total: ${formatBytes(metrics.totalBytesSent + metrics.totalBytesReceived)}

Events Received: ${metrics.totalEventsReceived}
Average Event Size: ${formatBytes(metrics.averageEventSize)}
${if (metrics.averageEventSize > 5000) "⚠️  WARNING: Large average event size!" else ""}

Rate: ${String.format("%.1f", metrics.bandwidthPerHour)} MB/hour
Projected Weekly: ${String.format("%.1f", metrics.bandwidthPerHour * 168)} MB

⚠️  LEAK DETECTION
────────────────────────────────────────────────────────────────
Subscriptions by Age:
  0-1 hour:   ${subsByAge["0-1h"]?.size ?: 0} subs
  1-6 hours:  ${subsByAge["1-6h"]?.size ?: 0} subs
  6-24 hours: ${subsByAge["6-24h"]?.size ?: 0} subs
  24+ hours:  ${subsByAge["24h+"]?.size ?: 0} subs

Old Subscriptions (>6h): ${oldSubs.size}
${if (oldSubs.size > 5) "⚠️  WARNING: Likely subscription leaks detected!" else ""}
${if (oldSubs.size > 20) "🚨 CRITICAL: Severe subscription leak! ${oldSubs.size} old subscriptions!" else ""}

📈 TOP EVENT-RECEIVING SUBSCRIPTIONS
────────────────────────────────────────────────────────────────
${topSubs.joinToString("\n") { (subId, count) ->
            val info = activeSubscriptions[subId]
            val ageMin = if (info != null) (now - info.createdAt) / 60000 else 0
            val geo = info?.originGeohash ?: "unknown"
            "  $subId: $count events (age: ${ageMin}min, geo: $geo)"
        }}
${if (topSubs.isEmpty()) "  No events received yet" else ""}

📡 TOP BANDWIDTH-CONSUMING RELAYS
────────────────────────────────────────────────────────────────
${relayBandwidth.joinToString("\n") { (relay, bytes) ->
            "  ${relay.substringAfter("wss://")}: ${formatBytes(bytes)}"
        }}

🔧 SUBSCRIPTION DETAILS (first 20)
────────────────────────────────────────────────────────────────
${activeSubscriptions.entries.take(20).joinToString("\n") { (id, info) ->
            val ageMin = (now - info.createdAt) / 60000
            val eventCount = metrics.eventsReceivedPerSubscription[id] ?: 0
            val targets = info.targetRelayUrls?.size ?: connections.size
            val geo = info.originGeohash?.take(6) ?: "global"
            "  $id: age=${ageMin}min, events=$eventCount, relays=$targets, geo=$geo"
        }}
${if (activeSubscriptions.size > 20) "  ... and ${activeSubscriptions.size - 20} more subscriptions" else ""}

🩺 HEALTH STATUS
────────────────────────────────────────────────────────────────
${when {
            activeSubscriptions.size > 100 -> "🚨 CRITICAL: Too many active subscriptions (${activeSubscriptions.size})"
            activeSubscriptions.size > 50 -> "⚠️  WARNING: High subscription count (${activeSubscriptions.size})"
            oldSubs.size > 20 -> "🚨 CRITICAL: Severe subscription leaks (${oldSubs.size} old subs)"
            oldSubs.size > 5 -> "⚠️  WARNING: Possible subscription leaks (${oldSubs.size} old subs)"
            metrics.reconnectionCount > 50 -> "⚠️  WARNING: High reconnection rate (${metrics.reconnectionCount})"
            metrics.averageEventSize > 10000 -> "⚠️  WARNING: Very large events (${formatBytes(metrics.averageEventSize)} avg)"
            metrics.bandwidthPerHour > 100 -> "⚠️  WARNING: High bandwidth usage (${String.format("%.1f", metrics.bandwidthPerHour)} MB/hour)"
            else -> "✅ All metrics look healthy"
        }}

📋 RECOMMENDATIONS
────────────────────────────────────────────────────────────────
${generateRecommendations(oldSubs.size, metrics.averageEventSize, metrics.reconnectionCount.toInt(), metrics.bandwidthPerHour)}

════════════════════════════════════════════════════════════════
Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))}
════════════════════════════════════════════════════════════════
        """.trimIndent()
    }

    /**
     * Validate subscription consistency between client and relays
     */
    fun validateSubscriptionConsistency(): SubscriptionConsistencyReport {
        val expectedSubs = getActiveSubscriptions().keys
        val actualSubsByRelay = getSubscriptions().toMap()
        val inconsistencies = mutableListOf<String>()

        val connections = getConnections()
        for ((relayUrl, _) in connections) {
            val actualSubs = actualSubsByRelay[relayUrl] ?: emptySet()
            val expectedSubsForRelay = getActiveSubscriptions().filter { (_, info) ->
                info.targetRelayUrls == null || info.targetRelayUrls.contains(relayUrl)
            }.keys

            val missing = expectedSubsForRelay - actualSubs
            val extra = actualSubs - expectedSubs

            if (missing.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl missing subscriptions: $missing")
            }
            if (extra.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl has extra subscriptions: $extra")
            }
        }

        return SubscriptionConsistencyReport(
            isConsistent = inconsistencies.isEmpty(),
            inconsistencies = inconsistencies,
            connectedRelayCount = connections.size
        )
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1048576 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1073741824 -> String.format("%.1f MB", bytes / 1048576.0)
            else -> String.format("%.2f GB", bytes / 1073741824.0)
        }
    }

    private fun generateRecommendations(oldSubCount: Int, avgEventSize: Long, reconnections: Int, bandwidthPerHour: Double): String {
        val recommendations = mutableListOf<String>()

        if (oldSubCount > 20) {
            recommendations.add("🚨 URGENT: Clear all subscriptions with panicReset() and restart")
        } else if (oldSubCount > 5) {
            recommendations.add("⚠️  Call clearAllSubscriptions() to remove leaked subscriptions")
        }

        if (avgEventSize > 10000) {
            recommendations.add("⚠️  Events are very large - investigate event content")
        }

        if (reconnections > 50) {
            recommendations.add("⚠️  Network unstable - check WiFi/Tor connection")
        }

        if (bandwidthPerHour > 100) {
            recommendations.add("⚠️  High bandwidth - consider reducing relay count from 5 to 2")
        }

        return if (recommendations.isEmpty()) {
            "✅ No issues detected"
        } else {
            recommendations.joinToString("\n")
        }
    }
}

/**
 * Report of subscription consistency check
 */
data class SubscriptionConsistencyReport(
    val isConsistent: Boolean,
    val inconsistencies: List<String>,
    val connectedRelayCount: Int
)
