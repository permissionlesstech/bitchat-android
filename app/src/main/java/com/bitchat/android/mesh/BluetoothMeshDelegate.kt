package com.bitchat.android.mesh

import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.ChannelManager
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.CommandSuggestion
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MeshDelegateHandler
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.NotificationManager
import com.bitchat.android.ui.PrivateChatManager


/**
 * Delegate interface for mesh service callbacks (maintains exact same interface)
 */
interface BluetoothMeshDelegate {
    val dataManager: DataManager
    val privateChatManager: PrivateChatManager
    val meshDelegateHandler: MeshDelegateHandler
    val messageManager: MessageManager
    val channelManager: ChannelManager
    val notificationManager: NotificationManager
    val state: ChatState
    fun setNickname(newNickname: String)
    fun didReceiveMessage(message: BitchatMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String)
    fun didReceiveReadReceipt(messageID: String, recipientPeerID: String)
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
    fun cancelMediaSend(messageId: String)
    fun joinChannel(channel: String, password: String? = null): Boolean

    fun switchToChannel(channel: String?)

    fun leaveChannel(channel: String)

    fun setAppBackgroundState(inBackground: Boolean)

    fun setCurrentPrivateChatPeer(peerID: String?)

    fun setCurrentGeohash(geohash: String?)

    fun clearNotificationsForSender(peerID: String)

    fun clearNotificationsForGeohash(geohash: String)
    fun changeMeshServiceBGState(b: Boolean)
    fun startMeshServices()
    fun stopMeshServices()

    fun clearMeshMentionNotifications()
    fun updateCommandSuggestions(input: String)

    fun selectCommandSuggestion(suggestion: CommandSuggestion): String

    fun updateMentionSuggestions(input: String)
    fun selectMentionSuggestion(nickname: String, currentText: String): String
    fun panicClearAllData(onPanicReset: () -> Unit)
    fun openLatestUnreadPrivateChat(onEnsureGeohashDMSubscription: (String) -> Unit)
    fun startPrivateChat(peerID: String, onEnsureGeohashDMSubscription: () -> Unit)
    fun endPrivateChat()
    fun loadAndInitialize(
        logCurrentFavoriteState: () -> Unit, initializeSessionStateMonitoring: () -> Unit,
        initializeGeoHashVM: () -> Unit
    )
    fun updateReactiveStates()

    fun toggleFavorite(peerID: String, logCurrentFavoriteState: () -> Unit)
    fun sendMessage(content: String, onSendGeohashMessage: (String, GeohashChannel) -> Unit)

// registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager
}