package com.bitchat.android.groups

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.mesh.GroupMessagePort
import com.bitchat.android.mesh.GroupMessageReceiver
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.ui.DataManager
import java.lang.ref.WeakReference
import java.util.Date
import kotlinx.coroutines.*

interface GroupUiDelegate {
    val nickname: String
    fun markGroupUnread(groupPeerID: String)
    fun openGroupConversation(groupPeerID: String)
    fun closeGroupConversation()
}

/** Keeps group membership, decryption, and durable delivery alive when the Activity closes. */
class GroupRuntime private constructor(private val application: Context) : GroupMessageReceiver {
    val store = GroupStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ui = WeakReference<GroupUiDelegate>(null)
    private val mesh get() = MeshServiceHolder.unifiedMeshService
    private val notifications = com.bitchat.android.ui.NotificationManager(application, NotificationManagerCompat.from(application))

    val coordinator = GroupCoordinator(object : GroupCoordinatorContext {
        override val groupStore get() = store
        override val nickname get() = ui.get()?.nickname ?: AppStateStore.nickname.value
        override val myPeerID get() = mesh?.myPeerID.orEmpty()
        override val selectedConversationID get() = AppStateStore.selectedPrivateChatPeer.value
        override fun myNoiseFingerprint() = mesh?.getIdentityFingerprint().orEmpty()
        override fun mySigningPublicKey() = mesh?.getSigningPublicKey()
        override fun sign(data: ByteArray) = mesh?.signData(data)
        override fun peerIDsForNickname(nickname: String) = mesh?.getPeerNicknames().orEmpty()
            .filterValues { it.equals(nickname, ignoreCase = true) }.keys.toList()
        override fun isPeerConnected(peerID: String) = mesh?.getPeerInfo(peerID)?.isConnected == true && mesh?.hasEstablishedSession(peerID) == true
        override fun peerGroupCapability(peerID: String): PeerGroupCapability {
            val peer = mesh?.getPeerInfo(peerID) ?: return PeerGroupCapability.UNKNOWN
            return PeerGroupCapability.fromPeerState(peer.capabilities, peer.hasVerifiedAnnouncement)
        }
        override fun peerNickname(peerID: String) = mesh?.getPeerNicknames()?.get(peerID)
        override fun peerIdentity(peerID: String): GroupPeerIdentity? {
            val info = mesh?.getPeerInfo(peerID) ?: return null
            val signing = info.signingPublicKey?.takeIf { it.size == 32 } ?: return null
            val key = info.noisePublicKey ?: return null
            val fingerprint = ContactIdentityResolver.fingerprintHex(key)
            if (!fingerprint.equals(mesh?.getPeerFingerprint(peerID), ignoreCase = true)) return null
            return GroupPeerIdentity(fingerprint, signing.copyOf())
        }
        override fun connectedPeerID(fingerprint: String) = mesh?.getPeerNicknames()?.keys?.firstOrNull {
            isPeerConnected(it) && fingerprint.equals(mesh?.getPeerFingerprint(it), ignoreCase = true)
        }
        override fun isFingerprintBlocked(fingerprint: String) = DataManager.isFingerprintBlocked(application, fingerprint)
        override fun sendGroupInvite(payload: ByteArray, peerID: String) { mesh?.sendGroupInvite(payload, peerID) }
        override fun sendGroupKeyUpdate(payload: ByteArray, peerID: String) { mesh?.sendGroupKeyUpdate(payload, peerID) }
        override fun broadcastGroupMessage(payload: ByteArray) { mesh?.broadcastGroupMessage(payload) }
        override fun appendGroupMessage(groupPeerID: String, message: BitchatMessage): Boolean = runBlocking {
            AppStateStore.addPrivateMessageDurably(groupPeerID, message, selectedConversationID == groupPeerID || message.senderPeerID == myPeerID)
        }
        override fun markGroupUnread(groupPeerID: String) { ui.get()?.markGroupUnread(groupPeerID) }
        override fun removeGroupConversation(groupPeerID: String) { AppStateStore.deletePrivateConversation(groupPeerID) }
        override fun openGroupConversation(groupPeerID: String) { scope.launch(Dispatchers.Main) { ui.get()?.openGroupConversation(groupPeerID) } }
        override fun closeGroupConversation() { scope.launch(Dispatchers.Main) { ui.get()?.closeGroupConversation() } }
        override fun addSystemMessage(message: String) { AppStateStore.addPublicMessage(BitchatMessage(sender = "system", content = message, timestamp = Date())) }
        override fun addGroupSystemMessage(groupPeerID: String, message: String) {
            appendGroupMessage(groupPeerID, BitchatMessage(sender = "system", content = message, timestamp = Date(), isPrivate = true))
        }
        override fun notifyGroupMessage(groupPeerID: String, sender: String, message: String) {
            notifications.showPrivateMessageNotification(groupPeerID, sender, message)
        }
    })

    init {
        GroupMessagePort.receiver = this
        scope.launch { if (store.initialize()) coordinator.onStoreReady() }
    }

    fun attach(context: GroupUiDelegate) { ui = WeakReference(context) }
    fun detach(context: GroupUiDelegate) { if (ui.get() === context) ui.clear() }
    override fun invite(peerID: String, authenticatedKey: ByteArray, payload: ByteArray) = coordinator.handleInvite(peerID, authenticatedKey, payload)
    override fun keyUpdate(peerID: String, authenticatedKey: ByteArray, payload: ByteArray) = coordinator.handleKeyUpdate(peerID, authenticatedKey, payload)
    override fun message(payload: ByteArray, timestampMs: Long) = coordinator.handleMessage(payload, timestampMs)
    override fun peerAuthenticated(peerID: String) { scope.launch { coordinator.handlePeerAuthenticated(peerID) } }

    companion object {
        @Volatile private var instance: GroupRuntime? = null
        fun getInstance(context: Context): GroupRuntime = instance ?: synchronized(this) {
            instance ?: GroupRuntime(context.applicationContext).also { instance = it }
        }
    }
}
