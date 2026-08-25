package com.bitchat.android.mesh

/**
 * Whether a BLE central may subscribe to the mesh broadcast notification feed.
 *
 * Granting notifications before the peer has sent a verified ANNOUNCE would let
 * a passive listener harvest the full mesh feed with no participation (#901).
 */
internal object GattNotificationEligibility {
    fun maySubscribe(hasVerifiedAnnounce: Boolean): Boolean = hasVerifiedAnnounce
}
