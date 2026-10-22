package com.bitchat.android.ui

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver

internal data class PrivateMediaRecipient(
    val conversationID: String,
    val meshPeerID: String
)

/**
 * Private-chat state is keyed by a stable contact/conversation ID, while mesh
 * encryption and transport APIs require the current 16-hex peer ID.
 */
internal object PrivateMediaRecipientResolver {
    fun resolve(requestedRecipientID: String, meshService: MeshService): PrivateMediaRecipient? {
        val requested = requestedRecipientID.trim()
        val conversationID = ContactDirectory.canonicalConversationId(requested)

        val directoryPeerID = runCatching {
            ContactDirectory.resolve(requested).meshPeerID
        }.getOrNull()
        val directPeerID = requested.takeIf(ContactIdentityResolver::isMeshPeerId)
        val expectedFingerprint =
            ContactIdentityResolver.fingerprintFromContactConversationId(conversationID)
                ?: requested
                    .takeIf(ContactIdentityResolver::isNoiseKeyHex)
                    ?.let(ContactIdentityResolver::bytesFromHex)
                    ?.let(ContactIdentityResolver::fingerprintHex)
        val discoveredPeerID = expectedFingerprint?.let { fingerprint ->
            runCatching {
                meshService.getPeerNicknames().keys.firstOrNull { candidatePeerID ->
                    val info = meshService.getPeerInfo(candidatePeerID)
                    val noisePublicKey = info?.noisePublicKey
                    info?.isConnected == true &&
                        noisePublicKey != null &&
                        ContactIdentityResolver.fingerprintHex(noisePublicKey)
                            .equals(fingerprint, ignoreCase = true)
                }
            }.getOrNull()
        }

        val meshPeerID = (directoryPeerID ?: directPeerID ?: discoveredPeerID)
            ?.takeIf(ContactIdentityResolver::isMeshPeerId)
            ?: return null
        return PrivateMediaRecipient(conversationID, meshPeerID)
    }
}
