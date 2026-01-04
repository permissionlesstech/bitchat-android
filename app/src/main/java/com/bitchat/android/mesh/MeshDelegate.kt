package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatMessage

/**
 * Shared mesh delegate interface for transport-agnostic callbacks.
 */
interface MeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String)
    fun didReceiveReadReceipt(messageID: String, recipientPeerID: String)
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
}

typealias BluetoothMeshDelegate = MeshDelegate
