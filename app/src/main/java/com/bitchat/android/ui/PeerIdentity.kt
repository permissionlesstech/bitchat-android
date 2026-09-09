package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import java.util.Locale

/**
 * Canonical, presentation-neutral identity used to derive a peer hue.
 *
 * Callers cannot construct arbitrary color seeds. Every identity is normalized and namespaced
 * here so the same mesh or Nostr user resolves to the same color on every surface.
 */
@JvmInline
value class PeerIdentity private constructor(internal val stableKey: String) {
    companion object {
        fun mesh(peerID: String): PeerIdentity =
            PeerIdentity("noise:${normalize(peerID)}")

        /**
         * Preserve the established geohash-chat color mapping while accepting a full public key.
         *
         * Older chat rendering hashed `nostr:nostr:<first 8 hex characters>`. Keeping that visual
         * key here avoids recoloring existing conversations; the important change is that every
         * surface now derives it from the same canonical Nostr identity.
         */
        fun nostr(pubkeyHex: String): PeerIdentity {
            val normalized = normalizeNostrIdentifier(pubkeyHex)
            return PeerIdentity("nostr:nostr:${normalized.take(8)}")
        }

        /**
         * Last-resort identity for legacy messages and mentions that carry no stable peer ID.
         */
        fun nickname(nickname: String): PeerIdentity =
            PeerIdentity(normalize(nickname))

        private fun normalize(value: String): String =
            value.trim().lowercase(Locale.ROOT)

        private fun normalizeNostrIdentifier(value: String): String {
            var normalized = normalize(value)
            while (normalized.startsWith("nostr:") || normalized.startsWith("nostr_")) {
                normalized = normalized.removePrefix("nostr:").removePrefix("nostr_")
            }
            return normalized
        }
    }
}

/**
 * Resolve the canonical identity attached to a rendered message.
 *
 * Full Nostr keys take precedence over routing aliases such as `nostr_abcd…`; aliases remain a
 * compatibility fallback for messages saved by older app versions.
 */
fun peerIdentityForMessage(message: BitchatMessage): PeerIdentity {
    message.senderNostrPubkey?.takeIf { it.isNotBlank() }?.let {
        return PeerIdentity.nostr(it)
    }

    val senderPeerID = message.senderPeerID
    return when {
        senderPeerID?.startsWith("nostr:", ignoreCase = true) == true ||
            senderPeerID?.startsWith("nostr_", ignoreCase = true) == true -> {
            PeerIdentity.nostr(senderPeerID)
        }
        senderPeerID?.length == 16 || senderPeerID?.length == 64 -> {
            PeerIdentity.mesh(senderPeerID)
        }
        else -> PeerIdentity.nickname(message.sender)
    }
}
