package com.bitchat.android.mesh

/**
 * Ensures a Noise completion promotes only the BLE connection whose ANNOUNCE started that
 * authentication attempt.
 */
internal object AuthenticatedBleLinkPolicy {
    data class Claim(val deviceAddress: String, val linkID: String)

    fun matches(claim: Claim?, authenticatedAddress: String?, authenticatedLinkID: String?): Boolean =
        claim != null &&
            claim.deviceAddress == authenticatedAddress &&
            claim.linkID == authenticatedLinkID
}
