package com.bitchat.android.nostr

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

sealed interface NostrPublishResult {
    data class Accepted(val relayUrl: String) : NostrPublishResult
    data class Rejected(val reasons: Map<String, String?>) : NostrPublishResult
    data object TimedOut : NostrPublishResult
}

/**
 * Correlates NIP-20 OK responses with callers that require relay acceptance.
 *
 * Most Nostr publishers remain fire-and-forget. Security-sensitive callers,
 * such as courier delivery, opt into this tracker so local dedup state is not
 * advanced before a relay has actually stored the event.
 */
internal class NostrPublishTracker {
    private data class Attempt(
        val remainingRelays: MutableSet<String>,
        val rejections: MutableMap<String, String?>,
        val result: CompletableDeferred<NostrPublishResult>
    )

    private val attempts = ConcurrentHashMap<String, Attempt>()

    fun begin(eventId: String, relayUrls: Set<String>): CompletableDeferred<NostrPublishResult> {
        val result = CompletableDeferred<NostrPublishResult>()
        if (relayUrls.isEmpty()) {
            result.complete(NostrPublishResult.Rejected(emptyMap()))
            return result
        }
        attempts.put(eventId, Attempt(relayUrls.toMutableSet(), mutableMapOf(), result))
            ?.result
            ?.cancel()
        return result
    }

    fun record(
        eventId: String,
        relayUrl: String,
        accepted: Boolean,
        message: String?
    ) {
        val attempt = attempts[eventId] ?: return
        synchronized(attempt) {
            if (attempt.result.isCompleted || relayUrl !in attempt.remainingRelays) return
            if (accepted) {
                attempt.result.complete(NostrPublishResult.Accepted(relayUrl))
                attempts.remove(eventId, attempt)
                return
            }
            attempt.remainingRelays.remove(relayUrl)
            attempt.rejections[relayUrl] = message
            if (attempt.remainingRelays.isEmpty()) {
                attempt.result.complete(NostrPublishResult.Rejected(attempt.rejections.toMap()))
                attempts.remove(eventId, attempt)
            }
        }
    }

    fun cancel(eventId: String, result: CompletableDeferred<NostrPublishResult>) {
        attempts.computeIfPresent(eventId) { _, attempt ->
            if (attempt.result === result) null else attempt
        }
    }
}
