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
import java.util.UUID

class GeohashViewModel(
    application: Application,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val dataManager: DataManager,
    private val notificationManager: NotificationManager
) : AndroidViewModel(application), DefaultLifecycleObserver {

    companion object { private const val TAG = "GeohashViewModel" }

    private val repo = GeohashRepository(application, state, dataManager)
    private val uiSubscriptionOwner = "geohash-ui-${UUID.randomUUID()}"
    private val subscriptionManager = NostrSubscriptionManager(
        application,
        owner = uiSubscriptionOwner
    )
    private val geohashMessageHandler = GeohashMessageHandler(
        application = application,
        repo = repo,
        scope = viewModelScope,
        dataManager = dataManager,
        addChannelMessage = messageManager::addChannelMessage
    )

    // Presence heartbeat firehose (kind 20001). High-volume; paused while backgrounded.
    private var currentGeohashPresenceSubId: String? = null
    private var geoTimer: Job? = null
    private var locationChannelManager: com.bitchat.android.geohash.LocationChannelManager? = null
    private val activeSamplingGeohashes = mutableSetOf<String>()
    private val samplingSubscriptionIds = mutableMapOf<String, String>()
    private val liveSamplingSubscriptionGeohashes = mutableSetOf<String>()
    private var requestedLiveSamplingGeohashes: Set<String> = emptySet()
    private var requestedUserSamplingGeohashes: Set<String> = emptySet()
    private var uiSubscriptionsShutdown = false
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
        // Observe process lifecycle to manage background sampling
        kotlin.runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
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
        try { com.bitchat.android.nostr.NostrRelayManager.getInstance(getApplication()).clearAllOnPanic() } catch (_: Exception) {}
        try { com.bitchat.android.nostr.LocationNotesManager.getInstance().stop() } catch (_: Exception) {}
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
                val identity = NostrIdentityBridge.deriveIdentity(
                    forGeohash = channel.geohash,
                    context = getApplication()
                )
                val teleported = locationChannelManager?.teleported?.value
                    ?: state.isTeleported.value
                val event = NostrProtocol.createEphemeralGeohashEvent(
                    content,
                    channel.geohash,
                    identity,
                    nickname,
                    teleported
                )
                val relayManager = NostrRelayManager.getInstance(getApplication())
                relayManager.sendEventToGeohash(
                    event,
                    channel.geohash,
                    includeDefaults = false,
                    nRelays = 5,
                    liveLocationToken = liveLocationToken
                )
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
        val geohash = repo.getConversationGeohash(conversationKey)
            ?: NostrBackgroundRuntime.conversationGeohash(conversationKey)
            ?: return
        NostrBackgroundRuntime.ensureConversationDm(geohash)
    }

    fun displayNameForNostrPubkeyUI(pubkeyHex: String): String {
        val foregroundName = repo.displayNameForNostrPubkeyUI(pubkeyHex)
        return foregroundName.takeUnless { it == "anon" }
            ?: NostrBackgroundRuntime.displayNameForNostrPubkey(pubkeyHex)
            ?: foregroundName
    }

    fun displayNameForGeohashConversation(pubkeyHex: String, sourceGeohash: String): String {
        val foregroundName = repo.displayNameForGeohashConversation(pubkeyHex, sourceGeohash)
        return foregroundName.takeUnless { it == "anon" }
            ?: NostrBackgroundRuntime.displayNameForGeohashConversation(pubkeyHex, sourceGeohash)
            ?: foregroundName
    }

    fun conversationGeohash(conversationKey: String): String? =
        repo.getConversationGeohash(conversationKey)
            ?: NostrBackgroundRuntime.conversationGeohash(conversationKey)

    fun peerIdentityForNostrPubkey(pubkeyHex: String): PeerIdentity =
        PeerIdentity.nostr(pubkeyHex)

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

                // Presence heartbeat firehose (kind 20001) is the high-volume data hog; only
                // run it in the foreground. It is restored in onStart() and torn down in onStop().
                if (isAppInForeground()) {
                    subscribeChannelPresence(channel.channel.geohash, liveLocationToken)
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

    private fun startGeoParticipantsTimer() {
        geoTimer = viewModelScope.launch {
            while (repo.getCurrentGeohash() != null) {
                delay(30000)
                repo.refreshGeohashPeople()
            }
        }
    }

    override fun onCleared() {
        shutdownUiSubscriptions()
        super.onCleared()
    }

    fun shutdownUiSubscriptions() {
        if (uiSubscriptionsShutdown) return
        uiSubscriptionsShutdown = true
        subscriptionManager.unsubscribeAllOwned()
        geoTimer?.cancel()
        geoTimer = null
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
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.d(TAG, "🌍 App backgrounded: pausing geohash presence firehose (keeping message + DM subscriptions)")
        // Drop the high-volume presence heartbeat firehose (kind 20001).
        // The chat message stream (kind 20000) is intentionally left active so messages still arrive.
        currentGeohashPresenceSubId?.let { subscriptionManager.unsubscribe(it); currentGeohashPresenceSubId = null }
        // Drop geohash sampling subscriptions
        activeSamplingGeohashes.forEach(::unsubscribeSampling)
        // Stop participant-refresh polling
        geoTimer?.cancel(); geoTimer = null
        // Process-owned message and DM subscriptions remain active.
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
