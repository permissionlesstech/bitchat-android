package com.bitchat.android.mesh

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.PeerCapabilities
import java.security.MessageDigest

internal interface PrivateMediaCapabilityPinStore {
    fun contains(fingerprint: String): Boolean
    fun insert(fingerprint: String)
}

internal class SecurePrivateMediaCapabilityPinStore(context: Context) :
    PrivateMediaCapabilityPinStore {
    private val identityState = SecureIdentityStateManager(context.applicationContext)

    override fun contains(fingerprint: String): Boolean =
        identityState.isPrivateMediaCapable(fingerprint)

    override fun insert(fingerprint: String) {
        identityState.markPrivateMediaCapable(fingerprint)
    }
}

internal sealed interface PrivateMediaPolicyDecision {
    data object Encrypted : PrivateMediaPolicyDecision
    data object RequiresLegacyConsent : PrivateMediaPolicyDecision
    data object NeedsHandshake : PrivateMediaPolicyDecision
    data class Blocked(val reason: String) : PrivateMediaPolicyDecision
}

/**
 * Binds an advertised private-media capability to a live Noise remote-static
 * key and persists an HSTS-style pin by that authenticated key's SHA-256
 * fingerprint. Announcements alone can never create a pin.
 */
internal class PrivateMediaSecurityController(
    private val peerInfoProvider: (String) -> PeerInfo?,
    private val authenticatedRemoteStaticProvider: (String) -> ByteArray?,
    private val pinStore: PrivateMediaCapabilityPinStore
) {
    fun refreshAuthenticatedCapability(peerID: String): Boolean {
        val remoteStatic = authenticatedRemoteStaticProvider(peerID)
            ?.takeIf { it.size == 32 }
            ?: return false
        val peer = peerInfoProvider(peerID) ?: return false
        if (!peer.hasVerifiedAnnouncement) return false
        val announcedStatic = peer.verifiedAnnouncementNoisePublicKey ?: return false
        if (!announcedStatic.contentEquals(remoteStatic)) return false
        if (peer.capabilities?.contains(PeerCapabilities.PRIVATE_MEDIA) != true) return false

        pinStore.insert(fingerprint(remoteStatic))
        return true
    }

    fun sendPolicy(peerID: String): PrivateMediaPolicyDecision {
        val remoteStatic = authenticatedRemoteStaticProvider(peerID)
            ?.takeIf { it.size == 32 }
            ?: return PrivateMediaPolicyDecision.NeedsHandshake
        val authenticatedFingerprint = fingerprint(remoteStatic)

        // Once a fingerprint has authenticated encrypted media support, never
        // downgrade it because a later announce omits or clears the bit.
        if (pinStore.contains(authenticatedFingerprint)) {
            return PrivateMediaPolicyDecision.Encrypted
        }

        val peer = peerInfoProvider(peerID)
            ?: return PrivateMediaPolicyDecision.Blocked(
                "No signature-verified identity announcement is available"
            )
        if (!peer.hasVerifiedAnnouncement) {
            return PrivateMediaPolicyDecision.Blocked(
                "The peer identity announcement has not been verified"
            )
        }
        val announcedStatic = peer.verifiedAnnouncementNoisePublicKey
            ?: return PrivateMediaPolicyDecision.Blocked(
                "The verified announcement did not contain a Noise identity"
            )
        if (!announcedStatic.contentEquals(remoteStatic)) {
            return PrivateMediaPolicyDecision.Blocked(
                "The authenticated Noise identity does not match the verified announcement"
            )
        }

        return if (peer.capabilities?.contains(PeerCapabilities.PRIVATE_MEDIA) == true) {
            pinStore.insert(authenticatedFingerprint)
            PrivateMediaPolicyDecision.Encrypted
        } else {
            // Both a missing TLV and an explicitly empty bitfield are legacy.
            PrivateMediaPolicyDecision.RequiresLegacyConsent
        }
    }

    internal fun authenticatedFingerprint(peerID: String): String? =
        authenticatedRemoteStaticProvider(peerID)
            ?.takeIf { it.size == 32 }
            ?.let(::fingerprint)

    private fun fingerprint(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(publicKey)
            .joinToString("") { "%02x".format(it) }
}
