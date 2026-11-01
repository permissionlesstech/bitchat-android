package com.bitchat.android.ui

import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.model.BitchatMessage
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject

/**
 * What the mesh reports to while the UI is attached: incoming messages, peer
 * changes, receipts, verification and media policy, each routed to the
 * session object that owns it.
 *
 * ChatViewModel used to be the delegate and forward every call. This is the
 * same routing, moved unchanged, so MainActivity and the panic path hand the
 * mesh the session's delegate instead of the ViewModel.
 */
@ActivityRetainedScoped
class ChatMeshDelegate @Inject constructor(
    private val meshDelegateHandler: MeshDelegateHandler,
    private val verificationHandler: VerificationHandler,
    private val mediaSendingManager: MediaSendingManager,
) : BluetoothMeshDelegate {

    override fun didReceiveMessage(message: BitchatMessage) {
        meshDelegateHandler.didReceiveMessage(message)
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        meshDelegateHandler.didUpdatePeerList(peers)
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        meshDelegateHandler.didReceiveChannelLeave(channel, fromPeer)
    }
    
    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveDeliveryAck(messageID, recipientPeerID)
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveReadReceipt(messageID, recipientPeerID)
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyChallenge(peerID, payload)
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyResponse(peerID, payload)
    }

    override fun didResolvePrivateMediaPolicy(peerID: String) {
        mediaSendingManager.retryPendingPrivateMedia(peerID)
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return meshDelegateHandler.decryptChannelMessage(encryptedContent, channel)
    }
    
    override fun getNickname(): String? {
        return meshDelegateHandler.getNickname()
    }
    
    override fun isFavorite(peerID: String): Boolean {
        return meshDelegateHandler.isFavorite(peerID)
    }
}
