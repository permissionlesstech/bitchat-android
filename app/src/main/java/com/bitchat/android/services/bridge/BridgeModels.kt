package com.bitchat.android.services.bridge

import com.bitchat.android.model.PeerCapabilities

data class BridgedParticipant(
    val pubkey: String,
    val nickname: String?,
    val lastSeenMs: Long
) {
    val displayName: String
        get() = "${nickname?.trim()?.takeIf { it.isNotEmpty() } ?: "anon"}#${pubkey.takeLast(4)}"
}

data class BridgeUiState(
    val enabled: Boolean = false,
    val nearbyOnly: Boolean = false,
    val participants: List<BridgedParticipant> = emptyList()
)

sealed interface CourierDepositResult {
    data object Published : CourierDepositResult
    data object ForwardedToGateway : CourierDepositResult
    data object QueuedLocally : CourierDepositResult
    data object AlreadyPublished : CourierDepositResult
    data class Rejected(val reason: Reason) : CourierDepositResult

    enum class Reason {
        BRIDGE_DISABLED,
        CONTENT_TOO_LARGE,
        INVALID_MESSAGE,
        ENCRYPTION_FAILED
    }
}

internal data class VerifiedBridgePeer(
    val peerId: String,
    val nickname: String,
    val noiseKey: ByteArray,
    val signingKey: ByteArray,
    val isVerifiedNickname: Boolean,
    val capabilities: PeerCapabilities?,
    val bridgeCell: String?,
    val lastSeenMs: Long
)
