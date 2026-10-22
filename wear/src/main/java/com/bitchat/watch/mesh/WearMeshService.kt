package com.bitchat.watch.mesh

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.mesh.BluetoothConnectionManager
import com.bitchat.android.mesh.BluetoothConnectionManagerDelegate
import com.bitchat.android.mesh.DirectLinkAnnouncementPolicy
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watch mesh service: composes the shared BLE transport (BluetoothConnectionManager) with the
 * shared mesh coordinator (MeshCore), mirroring how the phone's Wi-Fi Aware service is built.
 * Bluetooth mesh only — no internet, no other transports.
 */
class WearMeshService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WearMeshService"
        private val MAX_TTL: UByte = AppConstants.MESSAGE_TTL_HOPS
        private val PEER_DISCONNECT_GRACE_MS: Long = AppConstants.Mesh.PEER_DISCONNECT_GRACE_MS

        @Volatile
        private var instance: WearMeshService? = null

        fun getOrCreate(context: Context): WearMeshService {
            return instance ?: synchronized(this) {
                instance ?: WearMeshService(context.applicationContext).also { instance = it }
            }
        }

        fun peek(): WearMeshService? = instance
    }

    val encryptionService = EncryptionService(context)
    val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bleTransport = BleTransport()
    private val meshCore: MeshCore
    private var connectionManager: BluetoothConnectionManager

    @Volatile
    var nickname: String = loadNickname()
        private set

    @Volatile
    private var isActive = false

    /** UI hook fired for every incoming private message (after storing). */
    var onPrivateMessage: ((com.bitchat.android.model.BitchatMessage) -> Unit)? = null

    init {
        meshCore = MeshCore(
            context = context.applicationContext,
            scope = serviceScope,
            transport = bleTransport,
            encryptionService = encryptionService,
            myPeerID = myPeerID,
            maxTtl = MAX_TTL,
            sharedGossipManager = null,
            gossipConfigProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = 500
                override fun gcsMaxBytes(): Int = 400
                override fun gcsTargetFpr(): Double = 0.01
            },
            hooks = MeshCore.Hooks(
                onMessageReceived = { message -> handleMessageReceived(message) },
                onAnnounceProcessed = { routed, _ ->
                    // Mirror the phone's BluetoothMeshService: learn the direct BLE
                    // address↔peerID mapping from direct-link announcements.
                    DirectLinkAnnouncementPolicy.observationFor(routed, MAX_TTL)?.let { obs ->
                        val observed = connectionManager.observePeerIfCurrent(
                            obs.relayAddress,
                            obs.ingressLinkID,
                            obs.peerID
                        )
                        if (observed) {
                            meshCore.setDirectConnection(obs.peerID, true)
                            try {
                                meshCore.gossipSyncManager.scheduleInitialSyncToPeer(obs.peerID, 1_000)
                            } catch (_: Exception) { }
                        }
                    }
                    routed.peerID?.let { pid ->
                        maybeAutoHandshake(pid)
                        try {
                            meshCore.gossipSyncManager.scheduleInitialSyncToPeer(pid, 1_000)
                        } catch (_: Exception) { }
                    }
                },
                announcementNicknameProvider = { nickname },
                leavePayloadProvider = { nickname.toByteArray(Charsets.UTF_8) }
            )
        )
        connectionManager = BluetoothConnectionManager(context, myPeerID, meshCore.fragmentManager)
        bleTransport.connectionManager = connectionManager
        wireBluetoothDelegate()
    }

    private inner class BleTransport : MeshTransport {
        lateinit var connectionManager: BluetoothConnectionManager

        override val id: String = "BLE"

        override fun broadcastPacket(routed: RoutedPacket): Boolean =
            connectionManager.broadcastPacket(routed)

        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean =
            connectionManager.sendPacketToPeer(peerID, packet)

        override fun sendPacketToLink(
            relayAddress: String,
            ingressLinkID: String,
            packet: BitchatPacket
        ): Boolean = connectionManager.sendPacketToLink(relayAddress, ingressLinkID, packet)

        override fun cancelTransfer(transferId: String): Boolean =
            connectionManager.cancelTransfer(transferId)

        override fun getDeviceAddressForPeer(peerID: String): String? =
            connectionManager.addressPeerMap.entries.firstOrNull { it.value == peerID }?.key

        override fun getDeviceAddressToPeerMapping(): Map<String, String> =
            connectionManager.addressPeerMap.toMap()

        override fun getTransportDebugInfo(): String = connectionManager.getDebugInfo()
    }

    private fun wireBluetoothDelegate() {
        connectionManager.delegate = object : BluetoothConnectionManagerDelegate {
            override fun onPacketReceived(
                packet: BitchatPacket,
                peerID: String,
                device: BluetoothDevice?,
                ingressLinkID: String
            ) {
                try {
                    com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().logIncoming(
                        packet = packet,
                        fromPeerID = peerID,
                        fromNickname = null,
                        fromDeviceAddress = device?.address,
                        myPeerID = myPeerID
                    )
                } catch (_: Exception) { }
                meshCore.processIncoming(packet, peerID, device?.address, ingressLinkID)
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                Log.i(TAG, "Device connected: ${device.address}")
                serviceScope.launch {
                    delay(200)
                    meshCore.sendBroadcastAnnounce()
                }
            }

            override fun onDeviceDisconnected(
                device: BluetoothDevice,
                linkID: String?,
                peerID: String?
            ) {
                Log.i(TAG, "Device disconnected: ${device.address} (peerID: $peerID)")
                try { meshCore.refreshPeerList() } catch (_: Exception) { }
                if (peerID != null) {
                    meshCore.setDirectConnection(peerID, false)
                    val deviceAddress = device.address
                    serviceScope.launch {
                        delay(PEER_DISCONNECT_GRACE_MS)
                        try {
                            val linkBack =
                                connectionManager.addressPeerMap.containsKey(deviceAddress) ||
                                    connectionManager.addressPeerMap.containsValue(peerID)
                            if (!linkBack) {
                                Log.i(TAG, "Peer $peerID did not return after disconnect; removing")
                                meshCore.removePeer(peerID)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Disconnect grace check failed for $peerID: ${e.message}")
                        }
                    }
                }
            }

            override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
                connectionManager.addressPeerMap[deviceAddress]?.let { peerID ->
                    meshCore.updatePeerRSSI(peerID, rssi)
                }
            }
        }
    }

    /**
     * Proactively establish a Noise session with peers we have no session for (throttled to
     * one attempt per peer per 60 s). Peers may hold a stale session after we restart — the
     * protocol has no decrypt-failure kick path, so our fresh handshake replaces it and
     * restores encrypted DM/file delivery.
     */
    private val handshakeAttempts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun maybeAutoHandshake(peerID: String) {
        if (peerID == myPeerID || hasEstablishedSession(peerID)) return
        val now = System.currentTimeMillis()
        val last = handshakeAttempts[peerID] ?: 0L
        if (now - last < 60_000) return
        handshakeAttempts[peerID] = now
        serviceScope.launch {
            delay(1_500)
            if (!hasEstablishedSession(peerID)) {
                try {
                    Log.d(TAG, "Auto-initiating Noise handshake with ${peerID.take(8)}")
                    initiateNoiseHandshake(peerID)
                } catch (_: Exception) { }
            }
        }
    }

    private fun handleMessageReceived(
        message: com.bitchat.android.model.BitchatMessage
    ): Boolean = try {
        when {
            message.isPrivate -> {
                val peer = message.senderPeerID ?: return false
                if (!AppStateStore.addPrivateMessage(peer, message)) return false
                try { onPrivateMessage?.invoke(message) } catch (_: Exception) { }
                true
            }
            message.channel != null -> {
                AppStateStore.addChannelMessage(message.channel, message)
                true
            }
            else -> {
                AppStateStore.addPublicMessage(message)
                true
            }
        }
    } catch (_: Exception) {
        !message.isPrivate
    }

    fun startServices() {
        if (isActive) {
            Log.w(TAG, "Mesh already active, ignoring duplicate start")
            return
        }
        if (!connectionManager.isReusable()) {
            // A previous stopServices() cancelled the manager's coroutine scope; the shared
            // API marks such managers single-use, so build a fresh one instead of starting
            // a zombie mesh that reports active while scanning nothing.
            Log.i(TAG, "Recreating BluetoothConnectionManager after terminal stop")
            connectionManager = BluetoothConnectionManager(context, myPeerID, meshCore.fragmentManager)
            bleTransport.connectionManager = connectionManager
            wireBluetoothDelegate()
        }
        val started = connectionManager.startServices()
        if (started) {
            isActive = true
            meshCore.startCore()
            serviceScope.launch {
                delay(500)
                meshCore.sendBroadcastAnnounce()
            }
            Log.i(TAG, "Mesh services started (peerID: $myPeerID)")
        } else {
            Log.e(TAG, "Failed to start Bluetooth services (permissions? BT off?)")
        }
    }

    fun stopServices() {
        if (!isActive) return
        isActive = false
        meshCore.stopCore()
        connectionManager.stopServices()
        Log.i(TAG, "Mesh services stopped")
    }

    fun isRunning(): Boolean = isActive

    fun setNickname(name: String) {
        val trimmed = name.trim().take(32)
        if (trimmed.isEmpty() || trimmed == nickname) return
        nickname = trimmed
        saveNickname(trimmed)
        if (isActive) {
            serviceScope.launch { meshCore.sendBroadcastAnnounce() }
        }
    }

    fun sendMessage(content: String, mentions: List<String> = emptyList()) {
        meshCore.sendMessage(content, mentions, null)
    }

    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String) {
        meshCore.sendPrivateMessage(content, recipientPeerID, recipientNickname)
    }

    /**
     * Favorite controls travel as encrypted private control messages. If the profile is opened
     * while a handshake is still settling, wait briefly rather than silently losing the change.
     */
    fun sendFavoriteNotification(peerID: String, isFavorite: Boolean) {
        serviceScope.launch {
            val deadline = System.currentTimeMillis() + 15_000L
            if (!hasEstablishedSession(peerID)) {
                runCatching { initiateNoiseHandshake(peerID) }
            }
            while (!hasEstablishedSession(peerID) && System.currentTimeMillis() < deadline) {
                delay(250L)
            }
            if (!hasEstablishedSession(peerID)) {
                Log.w(TAG, "Favorite update could not be sent before the Noise timeout")
                return@launch
            }
            val recipientNickname = getPeerNickname(peerID) ?: peerID.take(8)
            meshCore.sendPrivateMessage(
                content = FavoriteControlMessage.encode(isFavorite, npub = null),
                recipientPeerID = peerID,
                recipientNickname = recipientNickname
            )
        }
    }

    fun initiateNoiseHandshake(peerID: String) = meshCore.initiateNoiseHandshake(peerID)

    fun hasEstablishedSession(peerID: String): Boolean = meshCore.hasEstablishedSession(peerID)

    fun getSessionState(peerID: String) = meshCore.getSessionState(peerID)

    fun getPeerInfo(peerID: String) = meshCore.getPeerInfo(peerID)

    fun getIdentityFingerprint(): String = encryptionService.getIdentityFingerprint()

    fun getStaticNoisePublicKey(): ByteArray? = meshCore.getStaticNoisePublicKey()

    fun sendBroadcastAnnounce() = meshCore.sendBroadcastAnnounce()

    fun sendChannelMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null) {
        meshCore.sendMessage(content, mentions, channel)
    }

    fun sendPrivateMessageWithId(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String?
    ) {
        meshCore.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
    }

    fun getDeviceAddressForPeer(peerID: String): String? = meshCore.getDeviceAddressForPeer(peerID)

    fun getDeviceAddressToPeerMapping(): Map<String, String> = meshCore.getDeviceAddressToPeerMapping()

    fun connectToPeer(peerID: String): Boolean {
        val address = getDeviceAddressForPeer(peerID) ?: return false
        return connectionManager.connectToAddress(address)
    }

    fun sendFileBroadcast(file: com.bitchat.android.model.BitchatFilePacket) {
        meshCore.sendFileBroadcast(file)
    }

    fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray) {
        meshCore.sendVoiceFrame(recipientPeerID, payload)
    }

    /**
     * Noise-encrypted private file transfer with session/prep retry (mirrors the phone's
     * dispatchFileSend): ensures an established session, then retries transient
     * preparation states (AwaitingPeerState/NeedsHandshake) before giving up.
     */
    fun sendFilePrivateEncrypted(recipientPeerID: String, file: com.bitchat.android.model.BitchatFilePacket) {
        serviceScope.launch {
            val sessionDeadline = System.currentTimeMillis() + 15_000
            while (!hasEstablishedSession(recipientPeerID) && System.currentTimeMillis() < sessionDeadline) {
                try { initiateNoiseHandshake(recipientPeerID) } catch (_: Exception) { }
                delay(500)
            }
            val transferId = com.bitchat.android.mesh.MeshPacketUtils.sha256Hex(
                file.encode() ?: return@launch
            )
            val prepDeadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < prepDeadline) {
                when (val prep = meshCore.prepareFilePrivate(recipientPeerID, file, transferId, allowLegacyFallback = false)) {
                    is com.bitchat.android.mesh.PrivateMediaPreparation.Ready -> {
                        prep.transfer.commit()
                        return@launch
                    }
                    com.bitchat.android.mesh.PrivateMediaPreparation.AwaitingPeerState,
                    com.bitchat.android.mesh.PrivateMediaPreparation.NeedsHandshake -> {
                        if (prep == com.bitchat.android.mesh.PrivateMediaPreparation.NeedsHandshake) {
                            try { initiateNoiseHandshake(recipientPeerID) } catch (_: Exception) { }
                        }
                        delay(500)
                    }
                    else -> {
                        Log.w(TAG, "private voice note preparation failed: $prep")
                        return@launch
                    }
                }
            }
            Log.w(TAG, "private voice note preparation timed out")
        }
    }

    fun getPeerFingerprint(peerID: String): String? = meshCore.getPeerFingerprint(peerID)

    fun getPeerNicknames(): Map<String, String> = meshCore.getPeerNicknames()

    fun getPeerRSSI(): Map<String, Int> = meshCore.getPeerRSSI()

    fun getPeerNickname(peerID: String): String? = meshCore.getPeerNickname(peerID)

    fun getDebugStatus(): String = meshCore.getDebugStatus(
        transportInfo = connectionManager.getDebugInfo(),
        deviceMap = connectionManager.addressPeerMap.toMap(),
        title = "Wear BLE Mesh Debug Status"
    )

    private fun prefs() = context.getSharedPreferences("bitchat_watch_prefs", Context.MODE_PRIVATE)

    private fun loadNickname(): String =
        prefs().getString("nickname", null) ?: "watch-${myPeerID.take(4)}"

    private fun saveNickname(name: String) {
        prefs().edit().putString("nickname", name).apply()
    }
}
