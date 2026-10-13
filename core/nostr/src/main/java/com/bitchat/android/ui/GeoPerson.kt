package com.bitchat.android.ui

import java.util.Date

/** Someone active in a geohash channel. Kept free of Compose so the Nostr layer can build it. */
data class GeoPerson(
    val id: String,           // pubkey hex (lowercased) - matches iOS
    val displayName: String,  // nickname with #suffix - matches iOS
    val lastSeen: Date        // activity timestamp - matches iOS
)
