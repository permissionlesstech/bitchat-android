package com.bitchat.android.services.bridge

import com.bitchat.android.mesh.MeshPacketUtils
import com.bitchat.android.mesh.BridgeMeshPort
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType

/**
 * Shared construction policy for bridge protocol packets emitted by BLE and
 * Wi-Fi Aware mesh implementations.
 */
internal object BridgeProtocolPacketFactory {
    fun protocolPacket(
        type: MessageType,
        payload: ByteArray,
        senderPeerId: String,
        recipientPeerId: String?,
        ttl: UByte,
        nowMs: Long = System.currentTimeMillis()
    ): BitchatPacket? {
        if (payload.isEmpty()) return null
        return BitchatPacket(
            version = if (payload.size > 0xFFFF) 2u else 1u,
            type = type.value,
            senderID = MeshPacketUtils.hexStringToByteArray(senderPeerId),
            recipientID = recipientPeerId?.let(MeshPacketUtils::hexStringToByteArray),
            timestamp = nowMs.toULong(),
            payload = payload,
            signature = null,
            ttl = ttl
        )
    }

    fun identityAnnouncement(
        nickname: String,
        noiseStaticKey: ByteArray,
        signingKey: ByteArray
    ): IdentityAnnouncement =
        IdentityAnnouncement.forLocalPeer(
            nickname,
            noiseStaticKey,
            signingKey,
            BridgeMeshPort.advertisedCell()
        )
}
