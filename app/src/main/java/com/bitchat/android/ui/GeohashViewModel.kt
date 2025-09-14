package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.bitchat.android.nostr.GeohashMessageHandler
import com.bitchat.android.nostr.GeohashRepository
import com.bitchat.android.nostr.NostrDirectMessageHandler
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.nostr.NostrSubscriptionManager
import com.bitchat.android.nostr.PoWPreferenceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

class GeohashViewModel(
    application: Application,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val privateChatManager: PrivateChatManager,
    private val meshDelegateHandler: MeshDelegateHandler,
    private val dataManager: DataManager,
    private val notificationManager: NotificationManager
) : AndroidViewModel(application) {

    companion object { private const val TAG = "GeohashViewModel" }

    private val repo = GeohashRepository(application, state, dataManager)
    private val subscriptionManager = NostrSubscriptionManager(application, viewModelScope)
    private val geohashMessageHandler = GeohashMessageHandler(
        application = application,
        state = state,
        messageManager = messageManager,
        repo = repo,
        scope = viewModelScope,
        dataManager = dataManager
    )
    private val dmHandler = NostrDirectMessageHandler(
        application = application,
        state = state,
        privateChatManager = privateChatManager,
        meshDelegateHandler = meshDelegateHandler,
        scope = viewModelScope,
        repo = repo,
        dataManager = dataManager
    )

    private var currentGeohashSubId: String? = null
    private var currentDmSubId: String? = null
    private var geoTimer: Job? = null
    private var locationChannelManager: com.bitchat.android.geohash.LocationChannelManager? = null

    val geohashPeople: LiveData<List<GeoPerson>> = state.geohashPeople
    val geohashParticipantCounts: LiveData<Map<String, Int>> = state.geohashParticipantCounts
    val selectedLocationChannel: LiveData<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel

    fun initialize() {
        subscriptionManager.connect()
        val identity = NostrIdentityBridge.getCurrentNostrIdentity(getApplication())
        if (identity != null) {
            // Use global chat-messages only for full account DMs (mesh context). For geohash DMs, subscribe per-geohash below.
            subscriptionManager.subscribeGiftWraps(
                pubkey = identity.publicKeyHex,
                sinceMs = System.currentTimeMillis() - 172800000L,
                id = "chat-messages",
                handler = { event -> dmHandler.onGiftWrap(event, "", identity) } // geohash="" means global account DM (not geohash identity)
            )
        }
        try {
            locationChannelManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(getApplication())
            locationChannelManager?.selectedChannel?.observeForever { channel ->
                state.setSelectedLocationChannel(channel)
                switchLocationChannel(channel)
            }
            locationChannelManager?.teleported?.observeForever { teleported ->
                state.setIsTeleported(teleported)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize location channel state: ${e.message}")
            state.setSelectedLocationChannel(com.bitchat.android.geohash.ChannelID.Mesh)
            state.setIsTeleported(false)
        }
    }

    fun panicReset() {
        repo.clearAll()
        subscriptionManager.disconnect()
        currentGeohashSubId = null
        currentDmSubId = null
        geoTimer?.cancel()
        geoTimer = null
        try { NostrIdentityBridge.clearAllAssociations(getApplication()) } catch (_: Exception) {}
        initialize()
    }

    fun sendGeohashMessage(content: String, channel: com.bitchat.android.geohash.GeohashChannel, myPeerID: String, nickname: String?) {
        viewModelScope.launch {
            try {
                val tempId = "temp_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000)}"
                val pow = PoWPreferenceManager.getCurrentSettings()
                val localMsg = com.bitchat.android.model.BitchatMessage(
                    id = tempId,
                    sender = nickname ?: myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = "geohash:${channel.geohash}",
                    channel = "#${channel.geohash}",
                    powDifficulty = if (pow.enabled) pow.difficulty else null
                )
                messageManager.addChannelMessage("geo:${channel.geohash}", localMsg)
                val startedMining = pow.enabled && pow.difficulty > 0
                if (startedMining) {
                    com.bitchat.android.ui.PoWMiningTracker.startMiningMessage(tempId)
                }
                try {
                    val identity = NostrIdentityBridge.deriveIdentity(forGeohash = channel.geohash, context = getApplication())
                    val teleported = state.isTeleported.value ?: false
                    val event = NostrProtocol.createEphemeralGeohashEvent(content, channel.geohash, identity, nickname, teleported)
                    val relayManager = NostrRelayManager.getInstance(getApplication())
                    relayManager.sendEventToGeohash(event, channel.geohash, includeDefaults = false, nRelays = 5)
                } finally {
                    // Ensure we stop the per-message mining animation regardless of success/failure
                    if (startedMining) {
                        com.bitchat.android.ui.PoWMiningTracker.stopMiningMessage(tempId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash message: ${e.message}")
            }
        }
    }

    fun beginGeohashSampling(geohashes: List<String>) {
        if (geohashes.isEmpty()) return
        Log.d(TAG, "🌍 Beginning geohash sampling for ${geohashes.size} geohashes")
        viewModelScope.launch {
            geohashes.forEach { geohash ->
                subscriptionManager.subscribeGeohash(
                    geohash = geohash,
                    sinceMs = System.currentTimeMillis() - 86400000L,
                    limit = 200,
                    id = "sampling-$geohash",
                    handler = { event -> geohashMessageHandler.onEvent(event, geohash) }
                )
            }
        }
    }

    fun endGeohashSampling() { Log.d(TAG, "🌍 Ending geohash sampling") }
    fun geohashParticipantCount(geohash: String): Int = repo.geohashParticipantCount(geohash)
    fun isPersonTeleported(pubkeyHex: String): Boolean = repo.isPersonTeleported(pubkeyHex)

    fun startGeohashDM(pubkeyHex: String, onStartPrivateChat: (String) -> Unit) {
        val convKey = "nostr_${pubkeyHex.take(16)}"
        repo.putNostrKeyMapping(convKey, pubkeyHex)
        // Record the conversation's geohash using the currently selected location channel (if any)
        val current = state.selectedLocationChannel.value
        val gh = (current as? com.bitchat.android.geohash.ChannelID.Location)?.channel?.geohash
        if (!gh.isNullOrEmpty()) {
            repo.setConversationGeohash(convKey, gh)
            com.bitchat.android.nostr.GeohashConversationRegistry.set(convKey, gh)
        }
        onStartPrivateChat(convKey)
        Log.d(TAG, "🗨️ Started geohash DM with ${pubkeyHex} -> ${convKey} (geohash=${gh})")
    }

    fun getNostrKeyMapping(): Map<String, String> = repo.getNostrKeyMapping()

    fun blockUserInGeohash(targetNickname: String) {
        val pubkey = repo.findPubkeyByNickname(targetNickname)
        if (pubkey != null) {
            dataManager.addGeohashBlockedUser(pubkey)
            // Refresh people list and counts to remove blocked entry immediately
            repo.refreshGeohashPeople()
            repo.updateReactiveParticipantCounts()
            val sysMsg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "blocked $targetNickname in geohash channels",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(sysMsg)
        } else {
            val sysMsg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "user '$targetNickname' not found in current geohash",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(sysMsg)
        }
    }

    fun selectLocationChannel(channel: com.bitchat.android.geohash.ChannelID) {
        locationChannelManager?.select(channel) ?: run { Log.w(TAG, "Cannot select location channel - not initialized") }
    }

    fun displayNameForNostrPubkeyUI(pubkeyHex: String): String = repo.displayNameForNostrPubkeyUI(pubkeyHex)

    fun colorForNostrPubkey(pubkeyHex: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        val seed = "nostr:${pubkeyHex.lowercase()}"
        return colorForPeerSeed(seed, isDark).copy()
    }

    private fun switchLocationChannel(channel: com.bitchat.android.geohash.ChannelID?) {
        geoTimer?.cancel(); geoTimer = null
        currentGeohashSubId?.let { subscriptionManager.unsubscribe(it); currentGeohashSubId = null }
        currentDmSubId?.let { subscriptionManager.unsubscribe(it); currentDmSubId = null }

        when (channel) {
            is com.bitchat.android.geohash.ChannelID.Mesh -> {
                Log.d(TAG, "📡 Switched to mesh channel")
                repo.setCurrentGeohash(null)
                notificationManager.setCurrentGeohash(null)
                notificationManager.clearMeshMentionNotifications()
                repo.refreshGeohashPeople()
            }
            is com.bitchat.android.geohash.ChannelID.Location -> {
                Log.d(TAG, "📍 Switching to geohash channel: ${channel.channel.geohash}")
                repo.setCurrentGeohash(channel.channel.geohash)
                notificationManager.setCurrentGeohash(channel.channel.geohash)
                notificationManager.clearNotificationsForGeohash(channel.channel.geohash)
                try { messageManager.clearChannelUnreadCount("geo:${channel.channel.geohash}") } catch (_: Exception) { }

                try {
                    val identity = NostrIdentityBridge.deriveIdentity(channel.channel.geohash, getApplication())
                    repo.updateParticipant(channel.channel.geohash, identity.publicKeyHex, Date())
                    val teleported = state.isTeleported.value ?: false
                    if (teleported) repo.markTeleported(identity.publicKeyHex)
                } catch (e: Exception) { Log.w(TAG, "Failed identity setup: ${e.message}") }

                startGeoParticipantsTimer()
                
                viewModelScope.launch {
                    val geohash = channel.channel.geohash
                    val subId = "geohash-$geohash"; currentGeohashSubId = subId
                    subscriptionManager.subscribeGeohash(
                        geohash = geohash,
                        sinceMs = System.currentTimeMillis() - 3600000L,
                        limit = 200,
                        id = subId,
                        handler = { event -> geohashMessageHandler.onEvent(event, geohash) }
                    )
                    val dmIdentity = NostrIdentityBridge.deriveIdentity(geohash, getApplication())
                    val dmSubId = "geo-dm-$geohash"; currentDmSubId = dmSubId
                    subscriptionManager.subscribeGiftWraps(
                        pubkey = dmIdentity.publicKeyHex,
                        sinceMs = System.currentTimeMillis() - 172800000L,
                        id = dmSubId,
                        handler = { event -> dmHandler.onGiftWrap(event, geohash, dmIdentity) }
                    )
                    // Also register alias in global registry for routing convenience
                    com.bitchat.android.nostr.GeohashAliasRegistry.put("nostr_${dmIdentity.publicKeyHex.take(16)}", dmIdentity.publicKeyHex)
                }
            }
            null -> {
                Log.d(TAG, "📡 No channel selected")
                repo.setCurrentGeohash(null)
                repo.refreshGeohashPeople()
            }
        }
    }

    private fun startGeoParticipantsTimer() {
        geoTimer = viewModelScope.launch {
            while (repo.getCurrentGeohash() != null) {
                delay(30000)
                repo.refreshGeohashPeople()
            }
        }
    }
}
