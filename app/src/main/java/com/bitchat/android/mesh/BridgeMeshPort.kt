package com.bitchat.android.mesh

import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.protocol.BitchatPacket

/**
 * Transport-facing bridge boundary.
 *
 * BLE/Wi-Fi packet code depends only on this protocol surface; application
 * bootstrap installs the process bridge controller. Tests can install a fake
 * without constructing relay or persistence infrastructure.
 */
interface BridgeMeshDelegate {
    fun advertisedCell(): String?

    fun bridgeOutgoing(
        content: String,
        senderPeerId: String,
        timestampMs: Long,
        nickname: String?
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

    override fun bridgeOutgoing(
        content: String,
        senderPeerId: String,
        timestampMs: Long,
        nickname: String?
    ) {
        delegate?.bridgeOutgoing(content, senderPeerId, timestampMs, nickname)
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
