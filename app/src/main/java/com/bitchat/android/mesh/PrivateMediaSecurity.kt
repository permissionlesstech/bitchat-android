package com.bitchat.android.mesh

import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.noise.AuthenticatedNoiseSession

internal sealed interface PrivateMediaPolicyDecision {
    data class Encrypted(
        val authenticatedSession: AuthenticatedNoiseSession
    ) : PrivateMediaPolicyDecision
    data object RequiresLegacyConsent : PrivateMediaPolicyDecision
    data object NeedsHandshake : PrivateMediaPolicyDecision
    data object AwaitingPeerState : PrivateMediaPolicyDecision
    data class Blocked(val reason: String) : PrivateMediaPolicyDecision
}

/**
 * Binds an advertised private-media capability to a live Noise remote-static
 * key and persists an HSTS-style pin by that authenticated key's SHA-256
 * fingerprint. Announcements alone can never create a pin.
 */
internal class PrivateMediaSecurityController(
    private val authenticatedSessionProvider: (String) -> AuthenticatedNoiseSession?,
    private val peerStateStatusProvider: (
        String,
        AuthenticatedNoiseSession
    ) -> AuthenticatedPeerStateStatus,
    private val isPrivateMediaPinned: (String) -> Boolean
) {
    fun sendPolicy(peerID: String): PrivateMediaPolicyDecision {
        val authenticatedSession = authenticatedSessionProvider(peerID)
            ?.takeIf { it.remoteStaticKey.size == 32 && it.sessionToken.size == 32 }
            ?: return PrivateMediaPolicyDecision.NeedsHandshake

        return when (val status = peerStateStatusProvider(peerID, authenticatedSession)) {
            AuthenticatedPeerStateStatus.Awaiting -> PrivateMediaPolicyDecision.AwaitingPeerState
            is AuthenticatedPeerStateStatus.Proven -> {
                if (status.state.capabilities.contains(PeerCapabilities.PRIVATE_MEDIA)) {
                    PrivateMediaPolicyDecision.Encrypted(authenticatedSession)
                } else if (isPrivateMediaPinned(peerID)) {
                    PrivateMediaPolicyDecision.Blocked(
                        "Encrypted private media was previously pinned, but this session proved no support; send blocked"
                    )
                } else {
                    PrivateMediaPolicyDecision.RequiresLegacyConsent
                }
            }
            AuthenticatedPeerStateStatus.Missing ->
                // A live crypto session can become visible just before its authenticated callback
                // installs the coordinator generation. Never treat that gap as a watchdog timeout.
                PrivateMediaPolicyDecision.AwaitingPeerState
            AuthenticatedPeerStateStatus.TimedOut -> {
                if (isPrivateMediaPinned(peerID)) {
                    PrivateMediaPolicyDecision.Blocked(
                        "Encrypted private media was previously pinned, but this session did not provide authenticated peer state"
                    )
                } else {
                    // A Noise-capable old client that does not know 0x21 may use explicit one-shot
                    // legacy consent after the five-second generation watchdog.
                    PrivateMediaPolicyDecision.RequiresLegacyConsent
                }
            }
        }
    }
}
