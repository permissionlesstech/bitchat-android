package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import java.util.Locale

/**
 * Stable, presentation-neutral identity used to derive a peer hue.
 *
 * ViewModels may expose this value, but only the UI theme resolves it to a rendered color.
 */
@JvmInline
value class PeerColorSeed(val value: String)

fun meshPeerColorSeed(peerID: String): PeerColorSeed =
    PeerColorSeed("noise:${peerID.lowercase(Locale.ROOT)}")

fun nostrPeerColorSeed(pubkeyHex: String): PeerColorSeed =
    PeerColorSeed("nostr:${pubkeyHex.lowercase(Locale.ROOT)}")

fun peerColorSeedForMessage(message: BitchatMessage): PeerColorSeed {
    // Read once into a local: BitchatMessage lives in :core:domain, and Kotlin will
    // not smart-cast a property declared in another module.
    val senderPeerID = message.senderPeerID
    val value = when {
        senderPeerID?.startsWith("nostr:") == true ||
            senderPeerID?.startsWith("nostr_") == true -> {
            "nostr:${senderPeerID.lowercase(Locale.ROOT)}"
        }
        senderPeerID?.length == 16 || senderPeerID?.length == 64 -> {
            "noise:${senderPeerID.lowercase(Locale.ROOT)}"
        }
        else -> message.sender.lowercase(Locale.ROOT)
    }

    return PeerColorSeed(value)
}
