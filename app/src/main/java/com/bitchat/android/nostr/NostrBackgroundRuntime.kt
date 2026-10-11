package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashNostrPrivacyPolicy
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.mesh.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

/**
 * Process-owned Nostr connectivity and low-volume background subscriptions.
 *
 * Stable subscriptions dispatch directly to a process-owned event processor. The UI hydrates from
 * the process state store, so relay events remain useful without retaining a cleared ViewModel.
 */
object NostrBackgroundRuntime {
    private const val TAG = "NostrBackground"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val random = SecureRandom().asKotlinRandom()
    private val lock = Any()

    @Volatile private var initialized = false
    @Volatile private var activeGeohash: String? = null
    @Volatile private var activeGeohashLiveToken: Long? = null
    @Volatile private var conversationGeohash: String? = null
    private lateinit var application: Application
    private lateinit var subscriptions: NostrSubscriptionManager
    private lateinit var locationChannels: LocationChannelManager
    private lateinit var eventProcessor: NostrBackgroundEventProcessor

    fun initialize(app: Application) {
        synchronized(lock) {
            if (initialized) return
            application = app
            locationChannels = LocationChannelManager.getInstance(app)
            eventProcessor = NostrBackgroundEventProcessor(app, scope)
            subscriptions = NostrSubscriptionManager(
                app,
                owner = NostrRelayManager.OWNER_BACKGROUND
            )
            // Publish readiness only after every process-owned dependency is available.
            initialized = true
        }

        subscriptions.connect()
        subscribeAccountDm()
        startInboxSync()
        observeSelectedChannel()
        startPresenceScheduler()
    }

    fun resetSubscriptions() {
        if (!initialized) return
        subscriptions.unsubscribeAllOwned()
        scope.launch {
            subscriptions.connect()
            subscribeAccountDm()
            activeGeohash?.let { geohash ->
                subscribeSelectedGeohash(geohash, activeGeohashLiveToken)
            }
        }
    }

    fun ensureConversationDm(geohash: String) {
        if (!initialized || geohash == conversationGeohash) return
        val selectedLiveToken = activeGeohashLiveToken
        val selectedChannelSubscriptionIsUsable =
            geohash == activeGeohash &&
                (selectedLiveToken == null ||
                    LiveLocationPrivacyGate.accepts(selectedLiveToken))
        if (selectedChannelSubscriptionIsUsable) return
        conversationGeohash?.let { subscriptions.unsubscribe("geo-dm-conversation-$it") }
        conversationGeohash = geohash
        subscribeGeohashDm(
            geohash = geohash,
            subscriptionId = "geo-dm-conversation-$geohash",
            liveLocationToken = null
        )
    }

    private fun subscribeAccountDm() {
        val identity = NostrIdentityBridge.getCurrentNostrIdentity(application) ?: return
        subscriptions.subscribeGiftWraps(
            pubkey = identity.publicKeyHex,
            sinceMs = System.currentTimeMillis() - 172_800_000L,
            id = "account-dm-${java.util.UUID.randomUUID()}",
            targetRelayUrls = NostrRelayManager.getInstance(application).accountRelayUrls,
            handler = { event ->
                if (NostrIdentityBridge.getCurrentNostrIdentity(application)?.publicKeyHex == identity.publicKeyHex) {
                    eventProcessor.onAccountDm(event, identity)
                }
            }
        )
    }

    private fun startInboxSync() {
        scope.launch {
            while (true) {
                val manager = NostrRelayManager.getInstance(application)
                val identity = NostrIdentityBridge.getCurrentNostrIdentity(application)
                if (identity != null) {
                    val repository = com.bitchat.android.services.ConversationRepository.getInstance(application)
                    for (relay in manager.getRelayStatuses().filter { it.isConnected && it.url in manager.accountRelayUrls }) {
                        try {
                            val token = com.bitchat.android.services.AppStateStore.privateConversationToken() ?: continue
                            val checkpoint = "${identity.publicKeyHex}:${relay.url}"
                            val now = System.currentTimeMillis()
                            val sync = NostrInboxSync(
                                fetch = { since, until, limit -> manager.fetchGiftWraps(relay.url, identity.publicKeyHex, since, until, limit) },
                                process = { event ->
                                    NostrIdentityBridge.getCurrentNostrIdentity(application)?.publicKeyHex == identity.publicKeyHex &&
                                        eventProcessor.processAccountDm(event, identity, token)
                                }
                            )
                            if (sync.scan(NostrInboxSync.since(now, repository.syncCheckpoint(checkpoint)), (now / 1000).toInt())) {
                                if (com.bitchat.android.services.AppStateStore.privateConversationToken() == token && NostrIdentityBridge.getCurrentNostrIdentity(application)?.publicKeyHex == identity.publicKeyHex) {
                                    repository.saveSyncCheckpoint(checkpoint, now)
                                }
                            }
                        } catch (e: CancellationException) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        } catch (_: Exception) {
                            Log.w(TAG, "Inbox catch-up incomplete; retry scheduled")
                        }
                    }
                }
                delay(60_000)
            }
        }
    }

    private fun observeSelectedChannel() {
        scope.launch {
            locationChannels.selectedChannel.collectLatest { channel ->
                val locationChannel = channel as? ChannelID.Location
                val next = locationChannel?.channel?.geohash
                val nextToken = locationChannel?.let {
                    locationChannels.liveLocationTokenForSelectedChannel(it.channel)
                }
                val previous = activeGeohash
                if (previous == next && activeGeohashLiveToken == nextToken) {
                    return@collectLatest
                }

                previous?.let {
                    subscriptions.unsubscribe("geohash-$it")
                    subscriptions.unsubscribe("geo-dm-$it")
                }
                activeGeohash = next
                activeGeohashLiveToken = nextToken
                if (conversationGeohash == next) {
                    subscriptions.unsubscribe("geo-dm-conversation-$next")
                    conversationGeohash = null
                }
                next?.let { geohash ->
                    val isLiveDerived =
                        locationChannels.isSelectedChannelLiveDerived(locationChannel.channel)
                    if (!isLiveDerived || nextToken != null) {
                        subscribeSelectedGeohash(geohash, nextToken)
                    }
                }
            }
        }
    }

    private fun subscribeSelectedGeohash(
        geohash: String,
        liveLocationToken: Long?
    ) {
        subscriptions.subscribeGeohashMessages(
            geohash = geohash,
            sinceMs = System.currentTimeMillis() - 3_600_000L,
            limit = 200,
            id = "geohash-$geohash",
            handler = { event -> eventProcessor.onGeohashMessage(event, geohash) },
            liveLocationToken = liveLocationToken
        )
        subscribeGeohashDm(geohash, "geo-dm-$geohash", liveLocationToken)
    }

    private fun subscribeGeohashDm(
        geohash: String,
        subscriptionId: String,
        liveLocationToken: Long?
    ) {
        scope.launch {
            val subscribe = {
                val identity = NostrIdentityBridge.deriveIdentity(geohash, application)
                subscriptions.subscribeGiftWraps(
                    pubkey = identity.publicKeyHex,
                    sinceMs = System.currentTimeMillis() - 172_800_000L,
                    id = subscriptionId,
                    handler = { event ->
                        eventProcessor.onGeohashDm(event, geohash, identity)
                    },
                    liveLocationToken = liveLocationToken
                )
                GeohashAliasRegistry.put(
                    "nostr_${identity.publicKeyHex.take(16)}",
                    identity.publicKeyHex
                )
            }

            if (liveLocationToken == null) {
                subscribe()
            } else {
                LiveLocationPrivacyGate.runIfAllowed(liveLocationToken, subscribe)
            }
        }
    }

    private fun startPresenceScheduler() {
        val powerManager = PowerManager.getInstance(application)
        scope.launch {
            val nostrSchedule = powerManager.profile
                .map { it.nostr }
                .distinctUntilChanged()
            combine(
                locationChannels.availableChannels,
                LiveLocationPrivacyGate.enabled,
                nostrSchedule
            ) { channels, liveLocationEnabled, schedule ->
                val targets = GeohashNostrPrivacyPolicy.livePresenceTargets(
                    availableChannels = channels,
                    liveLocationEnabled = liveLocationEnabled
                )
                targets to schedule
            }.collectLatest { (targets, schedule) ->
                if (targets.isEmpty()) return@collectLatest
                while (true) {
                    val waitMs = if (schedule.presenceHeartbeatMaxMs > schedule.presenceHeartbeatMinMs) {
                        random.nextLong(
                            schedule.presenceHeartbeatMinMs,
                            schedule.presenceHeartbeatMaxMs + 1
                        )
                    } else {
                        schedule.presenceHeartbeatMinMs
                    }
                    delay(waitMs)

                    // Send every target in one wake window; do not spread the batch over seconds.
                    targets.forEach { geohash ->
                        try {
                            val token = LiveLocationPrivacyGate.captureToken()
                                ?: return@forEach
                            if (geohash !in GeohashNostrPrivacyPolicy.livePresenceTargets(
                                    availableChannels = locationChannels.availableChannels.value,
                                    liveLocationEnabled = true
                                )
                            ) return@forEach

                            var identity: NostrIdentity? = null
                            LiveLocationPrivacyGate.runIfAllowed(token) {
                                identity = NostrIdentityBridge.deriveIdentity(geohash, application)
                            }
                            val preparedIdentity = identity ?: return@forEach
                            if (!LiveLocationPrivacyGate.accepts(token)) return@forEach
                            val event = NostrProtocol.createGeohashPresenceEvent(
                                geohash,
                                preparedIdentity
                            )
                            LiveLocationPrivacyGate.runIfAllowed(token) {
                                NostrRelayManager.getInstance(application).sendEventToGeohash(
                                    event = event,
                                    geohash = geohash,
                                    includeDefaults = false,
                                    nRelays = 5,
                                    liveLocationToken = token
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Presence heartbeat failed for $geohash: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    fun conversationGeohash(conversationKey: String): String? =
        if (initialized) eventProcessor.conversationGeohash(conversationKey)
        else GeohashConversationRegistry.get(conversationKey)

    fun displayNameForNostrPubkey(pubkeyHex: String): String? =
        if (initialized) eventProcessor.displayNameForNostrPubkey(pubkeyHex) else null

    fun displayNameForGeohashConversation(
        pubkeyHex: String,
        sourceGeohash: String
    ): String? = if (initialized) {
        eventProcessor.displayNameForGeohashConversation(pubkeyHex, sourceGeohash)
    } else {
        null
    }
}
