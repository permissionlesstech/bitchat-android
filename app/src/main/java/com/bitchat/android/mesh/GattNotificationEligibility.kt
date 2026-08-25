package com.bitchat.android.mesh

/**
 * Whether a BLE central may receive the mesh broadcast notification feed.
 *
 * Granting the feed before the peer has sent a verified ANNOUNCE would let a
 * passive listener harvest it with no participation (#901). Android clients
 * write the CCCD during service discovery, before they send that ANNOUNCE, and
 * they do not retry the descriptor write — so a GATT reject here would leave a
 * legitimate peer unsubscribed even after they announce. Defer instead: accept
 * the CCCD write, withhold packets, then grant once the ANNOUNCE verifies.
 */
internal enum class GattSubscriptionAction {
    GRANT,
    DEFER,
}

internal object GattNotificationEligibility {
    fun action(hasVerifiedAnnounce: Boolean): GattSubscriptionAction =
        if (hasVerifiedAnnounce) GattSubscriptionAction.GRANT else GattSubscriptionAction.DEFER
}
