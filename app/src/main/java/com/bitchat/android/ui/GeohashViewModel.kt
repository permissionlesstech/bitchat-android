package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.bitchat.android.nostr.GeohashMessageHandler
import com.bitchat.android.nostr.GeohashRepository
import com.bitchat.android.nostr.NostrDirectMessageHandler
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.nostr.NostrSubscriptionManager
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Date
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers

class GeohashViewModel(
    application: Application,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val privateChatManager: PrivateChatManager,
    private val meshDelegateHandler: MeshDelegateHandler,
    private val dataManager: DataManager,
    private val notificationManager: NotificationManager
) : AndroidViewModel(application), DefaultLifecycleObserver {

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
    private var globalPresenceJob: Job? = null
    private var locationChannelManager: com.bitchat.android.geohash.LocationChannelManager? = null
    private val activeSamplingGeohashes = mutableSetOf<String>()

    val geohashPeople: StateFlow<List<GeoPerson>> = state.geohashPeople
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = state.geohashParticipantCounts
    val selectedLocationChannel: StateFlow<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel

    fun initialize() {
        subscriptionManager.connect()
        // Observe process lifecycle to manage background sampling
        kotlin.runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
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
            viewModelScope.launch {
                locationChannelManager?.selectedChannel?.collect { channel ->
                    state.setSelectedLocationChannel(channel)
                    switchLocationChannel(channel)
                }
            }
            viewModelScope.launch {
                locationChannelManager?.teleported?.collect { teleported ->
                    state.setIsTeleported(teleported)
                }
            }
            
            // Start global presence heartbeat loop
            startGlobalPresenceHeartbeat()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize location channel state: ${e.message}")
            state.setSelectedLocationChannel(com.bitchat.android.geohash.ChannelID.Mesh)
            state.setIsTeleported(false)
        }
    }

    private fun startGlobalPresenceHeartbeat() {
        globalPresenceJob?.cancel()
        globalPresenceJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Reactively restart heartbeat whenever available channels change
            locationChannelManager?.availableChannels?.collectLatest { channels ->
                // Filter for REGION (2), PROVINCE (4), CITY (5) - precision <= 5
                val targetGeohashes = channels.filter { it.level.precision <= 5 }.map { it.geohash }

                if (targetGeohashes.isNotEmpty()) {
                    // Enter heartbeat loop for this set of channels
                    // If channels change (e.g. user moves), collectLatest cancels this loop and starts a new one immediately
                    while (true) {
                        // Randomize loop interval (40-80s, average 60s)
                        val loopInterval = kotlin.random.Random.nextLong(40000L, 80000L)
                        var timeSpent = 0L

                        try {
                            Log.v(TAG, "💓 Broadcasting global presence to ${targetGeohashes.size} channels")
                            targetGeohashes.forEach { geohash ->
                                // Decorrelate individual broadcasts with random delay (1s-5s)
                                val stepDelay = kotlin.random.Random.nextLong(1000L, 10000L)
                                delay(stepDelay)
                                timeSpent += stepDelay
                                
                                broadcastPresence(geohash)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Global presence heartbeat error: ${e.message}")
                        }
                        
                        // Wait remaining time to satisfy target average cadence
                        val remaining = loopInterval - timeSpent
                        if (remaining > 0) {
                            delay(remaining)
                        } else {
                            delay(10000L) // Minimum guard delay
                        }
                    }
                }
            }
        }
    }

    fun panicReset() {
        repo.clearAll()
        GeohashAliasRegistry.clear()
        GeohashConversationRegistry.clear()
        subscriptionManager.disconnect()
        currentGeohashSubId = null
        currentDmSubId = null
        geoTimer?.cancel()
        geoTimer = null
        globalPresenceJob?.cancel()
        globalPresenceJob = null
        try { NostrIdentityBridge.clearAllAssociations(getApplication()) } catch (_: Exception) {}
        initialize()
    }

    private suspend fun broadcastPresence(geohash: String) {
        try {
            val identity = NostrIdentityBridge.deriveIdentity(geohash, getApplication())
            val event = NostrProtocol.createGeohashPresenceEvent(geohash, identity)
            val relayManager = NostrRelayManager.getInstance(getApplication())
            // Presence is lightweight, send to geohash relays
            relayManager.sendEventToGeohash(event, geohash, includeDefaults = false, nRelays = 5)
            Log.v(TAG, "💓 Sent presence heartbeat for $geohash")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send presence for $geohash: ${e.message}")
        }
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
                    val teleported = state.isTeleported.value
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
        if (geohashes.isEmpty()) {
            endGeohashSampling()
            return
        }
        
        // Diffing logic to avoid redundant REQ and leaks
        val currentSet = activeSamplingGeohashes.toSet()
        val newSet = geohashes.toSet()

        val toRemove = currentSet - newSet
        val toAdd = newSet - currentSet

        if (toAdd.isEmpty() && toRemove.isEmpty()) return

        Log.d(TAG, "🌍 Updating sampling: +${toAdd.size} new, -${toRemove.size} removed")
        
        // Remove old subscriptions
        toRemove.forEach { geohash ->
            subscriptionManager.unsubscribe("sampling-$geohash")
            activeSamplingGeohashes.remove(geohash)
        }

        // Add new subscriptions
        activeSamplingGeohashes.addAll(toAdd)
        if (isAppInForeground()) {
            toAdd.forEach { geohash ->
                performSubscribeSampling(geohash)
            }
        }
    }

    fun endGeohashSampling() { 
        if (activeSamplingGeohashes.isEmpty()) return
        Log.d(TAG, "🌍 Ending geohash sampling (cleaning up ${activeSamplingGeohashes.size} subs)")
        
        activeSamplingGeohashes.toList().forEach { geohash ->
            subscriptionManager.unsubscribe("sampling-$geohash")
        }
        activeSamplingGeohashes.clear()
    }
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
            GeohashConversationRegistry.set(convKey, gh)
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
    fun displayNameForGeohashConversation(pubkeyHex: String, sourceGeohash: String): String = repo.displayNameForGeohashConversation(pubkeyHex, sourceGeohash)

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
                    // We don't update participant here anymore; presence loop handles it via Kind 20001
                    val teleported = state.isTeleported.value
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
                    GeohashAliasRegistry.put("nostr_${dmIdentity.publicKeyHex.take(16)}", dmIdentity.publicKeyHex)
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

    override fun onCleared() {
        super.onCleared()
        kotlin.runCatching {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        Log.d(TAG, "🌍 App foregrounded: Resuming sampling for ${activeSamplingGeohashes.size} geohashes")
        activeSamplingGeohashes.forEach { performSubscribeSampling(it) }
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.d(TAG, "🌍 App backgrounded: Pausing sampling for ${activeSamplingGeohashes.size} geohashes")
        activeSamplingGeohashes.forEach { subscriptionManager.unsubscribe("sampling-$it") }
    }

    private fun performSubscribeSampling(geohash: String) {
        subscriptionManager.subscribeGeohash(
            geohash = geohash,
            sinceMs = System.currentTimeMillis() - 86400000L,
            limit = 200,
            id = "sampling-$geohash",
            handler = { event -> geohashMessageHandler.onEvent(event, geohash) }
        )
    }

    private fun isAppInForeground(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}
