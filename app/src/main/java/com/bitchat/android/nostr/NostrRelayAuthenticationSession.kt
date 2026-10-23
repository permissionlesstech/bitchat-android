package com.bitchat.android.nostr

/**
 * A relay can authenticate several keys on a connection (NIP-42). Authorization and retries belong
 * to each key independently: authenticating a channel key must not authorize account or other
 * channel requests. Keys are supplied by the rejected operation and are never chosen by a relay.
 */
internal class NostrRelayAuthenticationSession {
    class Actions(
        val identity: NostrIdentity,
        val actions: NostrRelayAuthentication.Actions
    )

    private class IdentityState(
        val identity: NostrIdentity,
        val authentication: NostrRelayAuthentication = NostrRelayAuthentication()
    )

    private var challenge: String? = null
    private val identities: MutableMap<String, IdentityState> = linkedMapOf()

    @Synchronized
    fun onChallenge(value: String): List<Actions> {
        if (!NostrRelayAuthentication.isValidChallenge(value) || value == challenge) return emptyList()
        challenge = value
        return identities.values.map { Actions(it.identity, it.authentication.onChallenge(value)) }
    }

    @Synchronized
    fun onSubscriptionRejected(id: String, identity: NostrIdentity): Actions? =
        stateFor(identity)?.let { Actions(identity, it.onSubscriptionRejected(id)) }

    @Synchronized
    fun onEventRejected(queueId: Long, identity: NostrIdentity): Actions? =
        stateFor(identity)?.let { Actions(identity, it.onEventRejected(queueId.toString())) }

    @Synchronized
    fun recordProof(identity: NostrIdentity, challenge: String, eventId: String): Boolean =
        identities[identity.publicKeyHex]?.authentication?.recordProof(challenge, eventId) == true

    @Synchronized
    fun onOk(eventId: String, accepted: Boolean): Actions? {
        identities.values.forEach { state ->
            val result = state.authentication.onOk(eventId, accepted)
            if (result.authResponse) return Actions(state.identity, result)
        }
        return null
    }

    @Synchronized
    fun forgetSubscription(id: String) {
        identities.values.forEach { it.authentication.forgetSubscription(id) }
    }

    @Synchronized
    fun forgetEvent(queueId: Long) {
        identities.values.forEach { it.authentication.forgetEvent(queueId.toString()) }
    }

    @Synchronized
    fun clearRequests() {
        identities.values.forEach { it.authentication.clearRequests() }
    }

    private fun stateFor(identity: NostrIdentity): NostrRelayAuthentication? {
        identities[identity.publicKeyHex]?.let { return it.authentication }
        if (identities.size >= MAX_AUTHENTICATED_IDENTITIES) return null
        val state = IdentityState(identity)
        challenge?.let { state.authentication.onChallenge(it) }
        identities[identity.publicKeyHex] = state
        return state.authentication
    }

    companion object {
        private const val MAX_AUTHENTICATED_IDENTITIES = 32
    }
}
