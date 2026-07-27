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
import com.bitchat.android.nostr.NostrBackgroundRuntime
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
    private val uiSubscriptionOwner = "geohash-ui-${System.identityHashCode(this)}"
    private val subscriptionManager = NostrSubscriptionManager(
        application,
        viewModelScope,
        owner = uiSubscriptionOwner
    )
    private val geohashMessageHandler = GeohashMessageHandler(
        application = application,
        state = state,
        messageManager = messageManager,
        repo = repo,
        scope = NostrBackgroundRuntime.eventScope,
        dataManager = dataManager
    )
    private val dmHandler = NostrDirectMessageHandler(
        application = application,
        state = state,
        privateChatManager = privateChatManager,
        meshDelegateHandler = meshDelegateHandler,
        scope = NostrBackgroundRuntime.eventScope,
        repo = repo,
        dataManager = dataManager
    )

    // Presence heartbeat firehose (kind 20001). High-volume; paused while backgrounded.
    private var currentGeohashPresenceSubId: String? = null
    private var geoTimer: Job? = null
    private var locationChannelManager: com.bitchat.android.geohash.LocationChannelManager? = null
    private val activeSamplingGeohashes = mutableSetOf<String>()

    // Geohash of the currently selected Location channel (null for Mesh/none).
    private var activeChannelGeohash: String? = null

    val geohashPeople: StateFlow<List<GeoPerson>> = state.geohashPeople
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = state.geohashParticipantCounts
    val selectedLocationChannel: StateFlow<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel

    fun initialize() {
        // Observe process lifecycle to manage background sampling
        kotlin.runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
        try {
            locationChannelManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(getApplication())
            NostrBackgroundRuntime.attachHandlers(
                NostrBackgroundRuntime.Handlers(
                    accountDm = { event, identity -> dmHandler.onGiftWrap(event, "", identity) },
                    geohashMessage = { event, geohash -> geohashMessageHandler.onEvent(event, geohash) },
                    geohashDm = { event, geohash, identity ->
                        dmHandler.onGiftWrap(event, geohash, identity)
                    }
                )
            )
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize location channel state: ${e.message}")
            state.setSelectedLocationChannel(com.bitchat.android.geohash.ChannelID.Mesh)
            state.setIsTeleported(false)
        }
    }

    fun panicReset() {
        repo.clearAll()
        GeohashAliasRegistry.clear()
        GeohashConversationRegistry.clear()
        subscriptionManager.unsubscribeAllOwned()
        currentGeohashPresenceSubId = null
        activeChannelGeohash = null
        geoTimer?.cancel()
        geoTimer = null
        try { NostrIdentityBridge.clearAllAssociations(getApplication()) } catch (_: Exception) {}
        NostrBackgroundRuntime.resetSubscriptions()
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
                val identity = NostrIdentityBridge.deriveIdentity(forGeohash = channel.geohash, context = getApplication())
                val teleported = state.isTeleported.value
                val event = NostrProtocol.createEphemeralGeohashEvent(content, channel.geohash, identity, nickname, teleported)
                val relayManager = NostrRelayManager.getInstance(getApplication())
                relayManager.sendEventToGeohash(event, channel.geohash, includeDefaults = false, nRelays = 5)
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

    fun startGeohashDMByNickname(nickname: String, onStartPrivateChat: (String) -> Unit) {
        val pubkey = repo.findPubkeyByNickname(nickname)
        if (pubkey != null) {
            startGeohashDM(pubkey, onStartPrivateChat)
        } else {
            Log.w(TAG, "Cannot start geohash DM: nickname '$nickname' not found in repo")
            // Optionally notify user
        }
    }

    fun startGeohashDMByShortId(shortId: String, onStartPrivateChat: (String) -> Unit) {
        val pubkey = repo.findPubkeyByShortId(shortId)
        if (pubkey != null) {
            startGeohashDM(pubkey, onStartPrivateChat)
        } else {
             Log.w(TAG, "Cannot start geohash DM: shortId '$shortId' not found in repo")
        }
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
    fun conversationGeohash(conversationKey: String): String? = repo.getConversationGeohash(conversationKey)

    fun peerColorSeedForNostrPubkey(pubkeyHex: String): PeerColorSeed =
        nostrPeerColorSeed(pubkeyHex)

    private fun switchLocationChannel(channel: com.bitchat.android.geohash.ChannelID?) {
        geoTimer?.cancel(); geoTimer = null
        currentGeohashPresenceSubId?.let { subscriptionManager.unsubscribe(it); currentGeohashPresenceSubId = null }

        when (channel) {
            is com.bitchat.android.geohash.ChannelID.Mesh -> {
                Log.d(TAG, "📡 Switched to mesh channel")
                activeChannelGeohash = null
                repo.setCurrentGeohash(null)
                notificationManager.setCurrentGeohash(null)
                notificationManager.clearMeshMentionNotifications()
                repo.refreshGeohashPeople()
            }
            is com.bitchat.android.geohash.ChannelID.Location -> {
                Log.d(TAG, "📍 Switching to geohash channel: ${channel.channel.geohash}")
                activeChannelGeohash = channel.channel.geohash
                repo.setCurrentGeohash(channel.channel.geohash)
                repo.refreshGeohashPeople()
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

                // Presence heartbeat firehose (kind 20001) is the high-volume data hog; only
                // run it in the foreground. It is restored in onStart() and torn down in onStop().
                if (isAppInForeground()) {
                    subscribeChannelPresence(channel.channel.geohash)
                }
            }
            null -> {
                Log.d(TAG, "📡 No channel selected")
                repo.setCurrentGeohash(null)
                repo.refreshGeohashPeople()
            }
        }
    }

    /**
     * Subscribe to the presence heartbeat firehose (kind 20001) for a geohash channel.
     * High-volume; only used to refresh the participant list, so it is torn down in
     * onStop() and restored in onStart() to cut background mobile data.
     */
    private fun subscribeChannelPresence(geohash: String) {
        val subId = "geohash-presence-$geohash"; currentGeohashPresenceSubId = subId
        subscriptionManager.subscribeGeohashPresence(
            geohash = geohash,
            sinceMs = System.currentTimeMillis() - 3600000L,
            limit = 200,
            id = subId,
            handler = { event -> geohashMessageHandler.onEvent(event, geohash) }
        )
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
        shutdownUiSubscriptions()
    }

    fun shutdownUiSubscriptions() {
        subscriptionManager.unsubscribeAllOwned()
        kotlin.runCatching {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        Log.d(TAG, "🌍 App foregrounded: resuming Nostr streaming")
        // Restore the presence heartbeat firehose for the selected geohash channel.
        // (The chat message stream is kept alive in the background, so it is not restored here.)
        activeChannelGeohash?.let { subscribeChannelPresence(it) }
        // Resume geohash sampling subscriptions
        activeSamplingGeohashes.forEach { performSubscribeSampling(it) }
        // Resume the participant-refresh polling timer if a geohash is selected
        if (repo.getCurrentGeohash() != null && geoTimer?.isActive != true) {
            startGeoParticipantsTimer()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.d(TAG, "🌍 App backgrounded: pausing geohash presence firehose (keeping message + DM subscriptions)")
        // Drop the high-volume presence heartbeat firehose (kind 20001).
        // The chat message stream (kind 20000) is intentionally left active so messages still arrive.
        currentGeohashPresenceSubId?.let { subscriptionManager.unsubscribe(it); currentGeohashPresenceSubId = null }
        // Drop geohash sampling subscriptions
        activeSamplingGeohashes.forEach { subscriptionManager.unsubscribe("sampling-$it") }
        // Stop participant-refresh polling
        geoTimer?.cancel(); geoTimer = null
        // Process-owned message and DM subscriptions remain active.
    }

    private fun performSubscribeSampling(geohash: String) {
        // Sampling only needs participant counts, never message bodies, so it subscribes to
        // presence heartbeats only (kind 20001) to keep the payload small.
        subscriptionManager.subscribeGeohashPresence(
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
