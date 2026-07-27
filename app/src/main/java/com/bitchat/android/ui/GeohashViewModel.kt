package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.bitchat.android.geohash.GeohashNostrPrivacyPolicy
import com.bitchat.android.geohash.LiveLocationPrivacyGate
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Date
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import java.security.SecureRandom
import java.util.UUID
import kotlin.random.asKotlinRandom

class GeohashViewModel(
    application: Application,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val privateChatManager: PrivateChatManager,
    private val meshDelegateHandler: MeshDelegateHandler,
    private val dataManager: DataManager,
    private val notificationManager: NotificationManager
) : AndroidViewModel(application), DefaultLifecycleObserver {

    companion object { 
        private const val TAG = "GeohashViewModel" 
        private val secureRandom = SecureRandom().asKotlinRandom()
    }

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

    // Live channel message stream (kind 20000). Low-volume; kept alive in the background.
    private var currentGeohashMsgSubId: String? = null
    // Presence heartbeat firehose (kind 20001). High-volume; paused while backgrounded.
    private var currentGeohashPresenceSubId: String? = null
    private var currentDmSubId: String? = null
    private var currentDmGeohash: String? = null
    private var geoTimer: Job? = null
    private var globalPresenceJob: Job? = null
    private var locationChannelManager: com.bitchat.android.geohash.LocationChannelManager? = null
    private val activeSamplingGeohashes = mutableSetOf<String>()
    private val samplingSubscriptionIds = mutableMapOf<String, String>()
    private val liveSamplingSubscriptionGeohashes = mutableSetOf<String>()
    private var requestedLiveSamplingGeohashes: Set<String> = emptySet()
    private var requestedUserSamplingGeohashes: Set<String> = emptySet()
    private val liveLocationRevocationListener: () -> Unit = {
        val revokedLiveGeohashes = liveSamplingSubscriptionGeohashes.toSet()
        revokedLiveGeohashes.forEach { geohash ->
            samplingSubscriptionIds.remove(geohash)
            activeSamplingGeohashes.remove(geohash)
        }
        liveSamplingSubscriptionGeohashes.clear()
        requestedLiveSamplingGeohashes = emptySet()
    }

    // Geohash of the currently selected Location channel (null for Mesh/none).
    private var activeChannelGeohash: String? = null

    val geohashPeople: StateFlow<List<GeoPerson>> = state.geohashPeople
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = state.geohashParticipantCounts
    val selectedLocationChannel: StateFlow<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel

    init {
        LiveLocationPrivacyGate.addRevocationListener(liveLocationRevocationListener)
    }

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
            val manager = locationChannelManager ?: return@launch
            combine(
                manager.availableChannels,
                LiveLocationPrivacyGate.enabled
            ) { channels, enabled ->
                GeohashNostrPrivacyPolicy.livePresenceTargets(channels, enabled)
            }.collectLatest { targetGeohashes ->

                if (targetGeohashes.isNotEmpty()) {
                    // Enter heartbeat loop for this set of channels
                    // If channels change (e.g. user moves), collectLatest cancels this loop and starts a new one immediately
                    while (true) {
                        // Randomize loop interval (40-80s, average 60s)
                        val loopInterval = secureRandom.nextLong(40000L, 80000L)
                        var timeSpent = 0L

                        try {
                            Log.v(TAG, "💓 Broadcasting global presence to ${targetGeohashes.size} channels")
                            targetGeohashes.forEach { geohash ->
                                // Decorrelate individual broadcasts with random delay (1s-5s)
                                val stepDelay = secureRandom.nextLong(1000L, 10000L)
                                delay(stepDelay)
                                timeSpent += stepDelay
                                
                                broadcastLiveLocationPresence(geohash)
                            }
                        } catch (e: CancellationException) {
                            throw e
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
        currentGeohashMsgSubId = null
        currentGeohashPresenceSubId = null
        currentDmSubId = null
        currentDmGeohash = null
        activeChannelGeohash = null
        geoTimer?.cancel()
        geoTimer = null
        globalPresenceJob?.cancel()
        globalPresenceJob = null
        try { NostrIdentityBridge.clearAllAssociations(getApplication()) } catch (_: Exception) {}
        initialize()
    }

    private suspend fun broadcastLiveLocationPresence(geohash: String) {
        val manager = locationChannelManager ?: return
        val token = LiveLocationPrivacyGate.captureToken() ?: return
        val isCurrentLiveTarget = GeohashNostrPrivacyPolicy.livePresenceTargets(
            manager.availableChannels.value,
            liveLocationEnabled = true
        ).contains(geohash)
        if (!isCurrentLiveTarget || !LiveLocationPrivacyGate.accepts(token)) return

        try {
            var identity: com.bitchat.android.nostr.NostrIdentity? = null
            LiveLocationPrivacyGate.runIfAllowed(token) {
                identity = NostrIdentityBridge.deriveIdentity(geohash, getApplication())
            }
            val preparedIdentity = identity ?: return
            if (!LiveLocationPrivacyGate.accepts(token)) return
            val event = NostrProtocol.createGeohashPresenceEvent(geohash, preparedIdentity)
            LiveLocationPrivacyGate.runIfAllowed(token) {
                val relayManager = NostrRelayManager.getInstance(getApplication())
                relayManager.sendEventToGeohash(
                    event,
                    geohash,
                    includeDefaults = false,
                    nRelays = 5,
                    liveLocationToken = token
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send live-location presence")
        }
    }

    fun sendGeohashMessage(content: String, channel: com.bitchat.android.geohash.GeohashChannel, myPeerID: String, nickname: String?) {
        viewModelScope.launch {
            try {
                val canUseChannel = locationChannelManager
                    ?.canUseSelectedLocationChannel(channel) == true
                if (!canUseChannel) {
                    Log.w(TAG, "Blocked message to a stale live-location channel")
                    return@launch
                }
                val isLiveDerived = locationChannelManager
                    ?.isSelectedChannelLiveDerived(channel) == true
                val liveLocationToken = locationChannelManager
                    ?.liveLocationTokenForSelectedChannel(channel)
                if (isLiveDerived && liveLocationToken == null) return@launch
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
                    val teleported = locationChannelManager?.teleported?.value
                        ?: state.isTeleported.value
                    val event = NostrProtocol.createEphemeralGeohashEvent(content, channel.geohash, identity, nickname, teleported)
                    val relayManager = NostrRelayManager.getInstance(getApplication())
                    relayManager.sendEventToGeohash(
                        event,
                        channel.geohash,
                        includeDefaults = false,
                        nRelays = 5,
                        liveLocationToken = liveLocationToken
                    )
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

    fun beginGeohashSampling(
        liveLocationGeohashes: Collection<String>,
        userSelectedGeohashes: Collection<String>,
    ) {
        requestedLiveSamplingGeohashes = liveLocationGeohashes.toSet()
        requestedUserSamplingGeohashes = userSelectedGeohashes.toSet()
        reconcileSamplingSubscriptions()
    }

    private fun reconcileSamplingSubscriptions() {
        val currentSet = activeSamplingGeohashes.toSet()
        val newSet = GeohashNostrPrivacyPolicy.samplingTargets(
            liveLocationGeohashes = requestedLiveSamplingGeohashes,
            userSelectedGeohashes = requestedUserSamplingGeohashes,
            liveLocationEnabled = LiveLocationPrivacyGate.isEnabled
        )

        val toRemove = currentSet - newSet
        val toAdd = newSet - currentSet
        val toPromoteToUserSelection = currentSet
            .intersect(requestedUserSamplingGeohashes)
            .intersect(liveSamplingSubscriptionGeohashes)

        if (toAdd.isEmpty() && toRemove.isEmpty() && toPromoteToUserSelection.isEmpty()) return

        Log.d(TAG, "🌍 Updating sampling: +${toAdd.size} new, -${toRemove.size} removed")
        
        // Remove old subscriptions
        toRemove.forEach { geohash ->
            unsubscribeSampling(geohash)
            activeSamplingGeohashes.remove(geohash)
        }

        // A bookmark must remain functional after live access is revoked. Replace a
        // live-tagged subscription with an untagged manual subscription immediately.
        toPromoteToUserSelection.forEach { geohash ->
            unsubscribeSampling(geohash)
            if (isAppInForeground()) performSubscribeSampling(geohash)
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
        requestedLiveSamplingGeohashes = emptySet()
        requestedUserSamplingGeohashes = emptySet()
        if (activeSamplingGeohashes.isEmpty()) return
        Log.d(TAG, "🌍 Ending geohash sampling (cleaning up ${activeSamplingGeohashes.size} subs)")
        
        activeSamplingGeohashes.toList().forEach { geohash ->
            unsubscribeSampling(geohash)
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
        Log.d(TAG, "🗨️ Started geohash DM")
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

    fun ensureGeohashDMSubscriptionForConversation(conversationKey: String) {
        val geohash = repo.getConversationGeohash(conversationKey) ?: return
        if (currentDmGeohash == geohash && currentDmSubId != null) return

        currentDmSubId?.let(subscriptionManager::unsubscribe)
        currentDmSubId = null
        currentDmGeohash = null
        subscribeChannelDM(geohash)
    }

    fun displayNameForNostrPubkeyUI(pubkeyHex: String): String = repo.displayNameForNostrPubkeyUI(pubkeyHex)
    fun displayNameForGeohashConversation(pubkeyHex: String, sourceGeohash: String): String = repo.displayNameForGeohashConversation(pubkeyHex, sourceGeohash)

    fun colorForNostrPubkey(pubkeyHex: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        val seed = "nostr:${pubkeyHex.lowercase()}"
        return colorForPeerSeed(seed, isDark).copy()
    }

    private fun switchLocationChannel(channel: com.bitchat.android.geohash.ChannelID?) {
        geoTimer?.cancel(); geoTimer = null
        currentGeohashMsgSubId?.let { subscriptionManager.unsubscribe(it); currentGeohashMsgSubId = null }
        currentGeohashPresenceSubId?.let { subscriptionManager.unsubscribe(it); currentGeohashPresenceSubId = null }
        currentDmSubId?.let { subscriptionManager.unsubscribe(it); currentDmSubId = null }
        currentDmGeohash = null

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
                if (locationChannelManager?.canUseSelectedLocationChannel(channel.channel) != true) {
                    Log.w(TAG, "Ignoring a stale live-location channel selection")
                    switchLocationChannel(com.bitchat.android.geohash.ChannelID.Mesh)
                    return
                }
                Log.d(TAG, "📍 Switching to geohash channel")
                activeChannelGeohash = channel.channel.geohash
                repo.setCurrentGeohash(channel.channel.geohash)
                repo.refreshGeohashPeople()
                notificationManager.setCurrentGeohash(channel.channel.geohash)
                notificationManager.clearNotificationsForGeohash(channel.channel.geohash)
                try { messageManager.clearChannelUnreadCount("geo:${channel.channel.geohash}") } catch (_: Exception) { }

                try {
                    val identity = NostrIdentityBridge.deriveIdentity(channel.channel.geohash, getApplication())
                    // We don't update participant here anymore; presence loop handles it via Kind 20001
                    val teleported = locationChannelManager?.teleported?.value
                        ?: state.isTeleported.value
                    if (teleported) repo.markTeleported(identity.publicKeyHex)
                } catch (e: Exception) { Log.w(TAG, "Failed identity setup: ${e.message}") }

                startGeoParticipantsTimer()
                val liveLocationToken = locationChannelManager
                    ?.liveLocationTokenForSelectedChannel(channel.channel)

                // Chat message stream (kind 20000) is low-volume; keep it alive even when
                // backgrounded so geohash messages still arrive.
                subscribeChannelMessages(channel.channel.geohash, liveLocationToken)
                // Presence heartbeat firehose (kind 20001) is the high-volume data hog; only
                // run it in the foreground. It is restored in onStart() and torn down in onStop().
                if (isAppInForeground()) {
                    subscribeChannelPresence(channel.channel.geohash, liveLocationToken)
                }
                // Gift-wrap DM subscription is lightweight (filtered to our pubkey) and is
                // kept alive in the background so geohash DMs still arrive.
                subscribeChannelDM(channel.channel.geohash)
            }
            null -> {
                Log.d(TAG, "📡 No channel selected")
                repo.setCurrentGeohash(null)
                repo.refreshGeohashPeople()
            }
        }
    }

    /**
     * Subscribe to the chat message stream (kind 20000) for a geohash channel.
     * Low-volume; kept alive in the background so messages keep arriving.
     */
    private fun subscribeChannelMessages(
        geohash: String,
        liveLocationToken: Long?
    ) {
        val subId = "geohash-${UUID.randomUUID()}"; currentGeohashMsgSubId = subId
        subscriptionManager.subscribeGeohashMessages(
            geohash = geohash,
            sinceMs = System.currentTimeMillis() - 3600000L,
            limit = 200,
            id = subId,
            handler = { event -> geohashMessageHandler.onEvent(event, geohash) },
            liveLocationToken = liveLocationToken
        )
    }

    /**
     * Subscribe to the presence heartbeat firehose (kind 20001) for a geohash channel.
     * High-volume; only used to refresh the participant list, so it is torn down in
     * onStop() and restored in onStart() to cut background mobile data.
     */
    private fun subscribeChannelPresence(
        geohash: String,
        liveLocationToken: Long?
    ) {
        val subId = "geohash-presence-${UUID.randomUUID()}"; currentGeohashPresenceSubId = subId
        subscriptionManager.subscribeGeohashPresence(
            geohash = geohash,
            sinceMs = System.currentTimeMillis() - 3600000L,
            limit = 200,
            id = subId,
            handler = { event -> geohashMessageHandler.onEvent(event, geohash) },
            liveLocationToken = liveLocationToken
        )
    }

    /**
     * Subscribe to gift-wrap DMs for a geohash channel's derived identity.
     * Lightweight (filtered to our pubkey); kept alive in the background.
     */
    private fun subscribeChannelDM(geohash: String) {
        val dmIdentity = NostrIdentityBridge.deriveIdentity(geohash, getApplication())
        val dmSubId = "geo-dm-${UUID.randomUUID()}"
        currentDmSubId = dmSubId
        currentDmGeohash = geohash
        subscriptionManager.subscribeGiftWraps(
            pubkey = dmIdentity.publicKeyHex,
            sinceMs = System.currentTimeMillis() - 172800000L,
            id = dmSubId,
            handler = { event -> dmHandler.onGiftWrap(event, geohash, dmIdentity) }
        )
        // Also register alias in global registry for routing convenience
        GeohashAliasRegistry.put("nostr_${dmIdentity.publicKeyHex.take(16)}", dmIdentity.publicKeyHex)
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
        LiveLocationPrivacyGate.removeRevocationListener(liveLocationRevocationListener)
    }

    override fun onStart(owner: LifecycleOwner) {
        Log.d(TAG, "🌍 App foregrounded: resuming Nostr streaming")
        // Android permission may have changed while backgrounded. Invalidate the
        // process-wide token before restoring any subscription or heartbeat.
        locationChannelManager?.syncPermissionState()

        // Restore the presence heartbeat firehose for the selected geohash channel.
        // (The chat message stream is kept alive in the background, so it is not restored here.)
        val selected = locationChannelManager?.selectedChannel?.value
        val selectedLocation = selected as? com.bitchat.android.geohash.ChannelID.Location
        if (selectedLocation != null &&
            selectedLocation.channel.geohash == activeChannelGeohash &&
            locationChannelManager?.canUseSelectedLocationChannel(selectedLocation.channel) == true
        ) {
            subscribeChannelPresence(
                selectedLocation.channel.geohash,
                locationChannelManager
                    ?.liveLocationTokenForSelectedChannel(selectedLocation.channel)
            )
        }
        // Resume geohash sampling subscriptions
        activeSamplingGeohashes.forEach { performSubscribeSampling(it) }
        // Resume the participant-refresh polling timer if a geohash is selected
        if (repo.getCurrentGeohash() != null && geoTimer?.isActive != true) {
            startGeoParticipantsTimer()
        }
        // Resume the global presence heartbeat
        if (globalPresenceJob?.isActive != true) {
            startGlobalPresenceHeartbeat()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.d(TAG, "🌍 App backgrounded: pausing geohash presence firehose (keeping message + DM subscriptions)")
        // Drop the high-volume presence heartbeat firehose (kind 20001).
        // The chat message stream (kind 20000) is intentionally left active so messages still arrive.
        currentGeohashPresenceSubId?.let { subscriptionManager.unsubscribe(it); currentGeohashPresenceSubId = null }
        // Drop geohash sampling subscriptions
        activeSamplingGeohashes.forEach(::unsubscribeSampling)
        // Stop broadcasting presence heartbeats
        globalPresenceJob?.cancel(); globalPresenceJob = null
        // Stop participant-refresh polling
        geoTimer?.cancel(); geoTimer = null
        // NOTE: gift-wrap DM subscriptions (per-geohash + global "chat-messages") are intentionally
        // left active so direct messages still arrive while backgrounded.
    }

    private fun performSubscribeSampling(geohash: String) {
        val subscriptionId = samplingSubscriptionIds.getOrPut(geohash) {
            "sampling-${UUID.randomUUID()}"
        }
        // Sampling only needs participant counts, never message bodies, so it subscribes to
        // presence heartbeats only (kind 20001) to keep the payload small.
        val subscribe = {
            liveSamplingSubscriptionGeohashes.remove(geohash)
            subscriptionManager.subscribeGeohashPresence(
                geohash = geohash,
                sinceMs = System.currentTimeMillis() - 86400000L,
                limit = 200,
                id = subscriptionId,
                handler = { event -> geohashMessageHandler.onEvent(event, geohash) }
            )
        }

        if (geohash in requestedUserSamplingGeohashes) {
            subscribe()
        } else if (geohash in requestedLiveSamplingGeohashes) {
            val isCurrentLiveTarget = locationChannelManager
                ?.availableChannels
                ?.value
                ?.any { it.geohash == geohash } == true
            if (!isCurrentLiveTarget) return
            val token = LiveLocationPrivacyGate.captureToken() ?: return
            LiveLocationPrivacyGate.runIfAllowed(token) {
                liveSamplingSubscriptionGeohashes.add(geohash)
                subscriptionManager.subscribeGeohashPresence(
                    geohash = geohash,
                    sinceMs = System.currentTimeMillis() - 86400000L,
                    limit = 200,
                    id = subscriptionId,
                    handler = { event -> geohashMessageHandler.onEvent(event, geohash) },
                    liveLocationToken = token
                )
            }
        }
    }

    private fun unsubscribeSampling(geohash: String) {
        samplingSubscriptionIds.remove(geohash)?.let(subscriptionManager::unsubscribe)
        liveSamplingSubscriptionGeohashes.remove(geohash)
    }

    private fun isAppInForeground(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}
