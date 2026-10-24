package com.bitchat.android.nostr

/**
 * NIP-42 state for one identity on one WebSocket. Nothing survives reconnect.
 * A challenge advertises support; only an auth-required rejection triggers disclosure of identity.
 * Callers execute returned actions outside this object's lock and recheck routing/privacy there.
 */
internal class NostrRelayAuthentication {
    data class Actions(
        val challenge: String? = null,
        val subscriptionIds: Set<String> = emptySet(),
        val eventIds: Set<String> = emptySet(),
        val authResponse: Boolean = false,
        val rejected: Boolean = false,
        val proofSubscriptionIds: Set<String> = emptySet(),
        val proofEventIds: Set<String> = emptySet()
    )

    private var challenge: String? = null
    private var attempted = false
    private var attempts = 0
    private var proofId: String? = null
    private var authenticated = false
    private val blockedSubscriptions = linkedSetOf<String>()
    private val blockedEvents = linkedSetOf<String>()
    private val retriedSubscriptions = mutableSetOf<String>()
    private val retriedEvents = mutableSetOf<String>()

    @Synchronized
    fun onChallenge(value: String): Actions {
        if (!isValidChallenge(value) || value == challenge) {
            return Actions()
        }
        challenge = value
        attempted = false
        proofId = null
        authenticated = false
        retriedSubscriptions.clear()
        retriedEvents.clear()
        return nextActions()
    }

    @Synchronized
    fun onSubscriptionRejected(id: String): Actions {
        if (id in retriedSubscriptions || retriedSubscriptions.size >= MAX_PENDING_REQUESTS) return Actions()
        if (blockedSubscriptions.size < MAX_PENDING_REQUESTS) blockedSubscriptions.add(id)
        return nextActions()
    }

    @Synchronized
    fun onEventRejected(id: String): Actions {
        if (id in retriedEvents || retriedEvents.size >= MAX_PENDING_REQUESTS) return Actions()
        if (blockedEvents.size < MAX_PENDING_REQUESTS) blockedEvents.add(id)
        return nextActions()
    }

    @Synchronized
    fun recordProof(challenge: String, eventId: String): Boolean {
        if (this.challenge != challenge || !attempted || proofId != null) return false
        proofId = eventId
        return true
    }

    @Synchronized
    fun onOk(eventId: String, accepted: Boolean): Actions {
        if (eventId != proofId) return Actions()
        proofId = null
        authenticated = accepted
        return if (accepted) retries().copy(authResponse = true)
        else Actions(authResponse = true, rejected = true)
    }

    @Synchronized
    fun forgetSubscription(id: String) {
        blockedSubscriptions.remove(id)
        retriedSubscriptions.remove(id)
    }

    @Synchronized
    fun forgetEvent(id: String) {
        blockedEvents.remove(id)
        // Keep the retry guard until the challenge changes, including after a terminal rejection.
    }

    @Synchronized
    fun clearRequests() {
        blockedSubscriptions.clear()
        blockedEvents.clear()
        retriedSubscriptions.clear()
        retriedEvents.clear()
    }

    private fun nextActions(): Actions {
        if (authenticated) return retries()
        val currentChallenge = challenge ?: return Actions()
        if (attempted || attempts >= MAX_AUTH_ATTEMPTS ||
            (blockedSubscriptions.isEmpty() && blockedEvents.isEmpty())
        ) return Actions()
        attempted = true
        attempts++
        return Actions(
            challenge = currentChallenge,
            proofSubscriptionIds = blockedSubscriptions.toSet(),
            proofEventIds = blockedEvents.toSet()
        )
    }

    private fun retries(): Actions {
        val subscriptions = blockedSubscriptions.filterTo(linkedSetOf()) {
            it !in retriedSubscriptions
        }
        val events = blockedEvents.filterTo(linkedSetOf()) { it !in retriedEvents }
        retriedSubscriptions.addAll(subscriptions)
        retriedEvents.addAll(events)
        blockedSubscriptions.clear()
        blockedEvents.clear()
        return Actions(subscriptionIds = subscriptions, eventIds = events)
    }

    companion object {
        private const val MAX_CHALLENGE_LENGTH = 4096
        private const val MAX_AUTH_ATTEMPTS = 3
        private const val MAX_PENDING_REQUESTS = 500

        fun isValidChallenge(value: String): Boolean = value.isNotEmpty() && value.length <= MAX_CHALLENGE_LENGTH

        fun isAuthRequired(message: String?): Boolean = message?.startsWith("auth-required:") == true
    }
}
