package com.bitchat.android.mesh

import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.protocol.BitchatPacket

/**
 * Immutable privacy decision captured when a public message is accepted.
 *
 * A later opt-in must not authorize a message that was composed while
 * bridging was disabled or nearby-only. Publication therefore requires both
 * this send-time decision and the current policy to allow bridging.
 */
class BridgeOutboundPolicy internal constructor(
    internal val enabled: Boolean,
    internal val nearbyOnly: Boolean
) {
    val allowsBridging: Boolean
        get() = enabled && !nearbyOnly

    fun permitsPublication(current: BridgeOutboundPolicy): Boolean =
        allowsBridging && current.allowsBridging

    companion object {
        val Denied = BridgeOutboundPolicy(enabled = false, nearbyOnly = false)

        internal fun capture(enabled: Boolean, nearbyOnly: Boolean): BridgeOutboundPolicy =
            BridgeOutboundPolicy(enabled = enabled, nearbyOnly = nearbyOnly)
    }
}

/**
 * Transport-facing bridge boundary.
 *
 * BLE/Wi-Fi packet code depends only on this protocol surface; application
 * bootstrap installs the process bridge controller. Tests can install a fake
 * without constructing relay or persistence infrastructure.
 */
interface BridgeMeshDelegate {
    fun advertisedCell(): String?
    fun outboundPolicy(): BridgeOutboundPolicy

    fun bridgeOutgoing(
        content: String,
        senderPeerId: String,
        timestampMs: Long,
        nickname: String?,
        policyAtSend: BridgeOutboundPolicy
    )

    fun handleAuthenticatedRadioMessage(messageId: String)
    fun handleVerifiedAnnouncement(peerId: String, announcement: IdentityAnnouncement)
    fun handlePrekeyPacket(packet: BitchatPacket)
    fun handleCarrier(payload: ByteArray, fromPeerId: String, directedToUs: Boolean)
    fun handleCourierEnvelope(payload: ByteArray)
}

object BridgeMeshPort : BridgeMeshDelegate {
    @Volatile
    private var delegate: BridgeMeshDelegate? = null

    fun install(delegate: BridgeMeshDelegate) {
        this.delegate = delegate
    }

    override fun advertisedCell(): String? = delegate?.advertisedCell()

    override fun outboundPolicy(): BridgeOutboundPolicy =
        delegate?.outboundPolicy() ?: BridgeOutboundPolicy.Denied

    override fun bridgeOutgoing(
        content: String,
        senderPeerId: String,
        timestampMs: Long,
        nickname: String?,
        policyAtSend: BridgeOutboundPolicy
    ) {
        delegate?.bridgeOutgoing(content, senderPeerId, timestampMs, nickname, policyAtSend)
    }

    override fun handleAuthenticatedRadioMessage(messageId: String) {
        delegate?.handleAuthenticatedRadioMessage(messageId)
    }

    override fun handleVerifiedAnnouncement(
        peerId: String,
        announcement: IdentityAnnouncement
    ) {
        delegate?.handleVerifiedAnnouncement(peerId, announcement)
    }

    override fun handlePrekeyPacket(packet: BitchatPacket) {
        delegate?.handlePrekeyPacket(packet)
    }

    override fun handleCarrier(payload: ByteArray, fromPeerId: String, directedToUs: Boolean) {
        delegate?.handleCarrier(payload, fromPeerId, directedToUs)
    }

    override fun handleCourierEnvelope(payload: ByteArray) {
        delegate?.handleCourierEnvelope(payload)
    }
}
