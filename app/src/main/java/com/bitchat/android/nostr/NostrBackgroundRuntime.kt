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
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

/**
 * Process-owned Nostr connectivity and low-volume background subscriptions.
 *
 * Stable subscription handlers delegate to the most recently attached UI-independent handlers.
 * This avoids relay subscriptions retaining a cleared ViewModel while keeping DMs and the selected
 * geohash channel alive for the lifetime of the foreground-service process.
 */
object NostrBackgroundRuntime {
    private const val TAG = "NostrBackground"
    private const val MAX_PENDING_EVENTS = 256

    data class Handlers(
        val accountDm: (NostrEvent, NostrIdentity) -> Unit,
        val geohashMessage: (NostrEvent, String) -> Unit,
        val geohashDm: (NostrEvent, String, NostrIdentity) -> Unit
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal val eventScope: CoroutineScope
        get() = scope
    private val random = SecureRandom().asKotlinRandom()
    private val lock = Any()
    private val pendingEvents = ArrayDeque<(Handlers) -> Unit>()

    @Volatile private var handlers: Handlers? = null
    @Volatile private var initialized = false
    @Volatile private var activeGeohash: String? = null
    @Volatile private var activeGeohashLiveToken: Long? = null
    @Volatile private var conversationGeohash: String? = null
    private lateinit var application: Application
    private lateinit var subscriptions: NostrSubscriptionManager
    private lateinit var locationChannels: LocationChannelManager

    fun initialize(app: Application) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            application = app
            locationChannels = LocationChannelManager.getInstance(app)
            subscriptions = NostrSubscriptionManager(
                app,
                scope,
                owner = NostrRelayManager.OWNER_BACKGROUND
            )
        }

        subscriptions.connect()
        subscribeAccountDm()
        observeSelectedChannel()
        startPresenceScheduler()
    }

    fun attachHandlers(newHandlers: Handlers) {
        handlers = newHandlers
        val pending = synchronized(lock) {
            pendingEvents.toList().also { pendingEvents.clear() }
        }
        pending.forEach { event -> runCatching { event(newHandlers) } }
    }

    fun resetSubscriptions() {
        if (!initialized) return
        subscriptions.unsubscribeAllOwned()
        scope.launch {
            // Let CLOSE frames be queued before replacing the deterministic IDs.
            delay(100)
            subscribeAccountDm()
            activeGeohash?.let { geohash ->
                subscribeSelectedGeohash(geohash, activeGeohashLiveToken)
            }
        }
    }

    fun ensureConversationDm(geohash: String) {
        if (!initialized || geohash == activeGeohash || geohash == conversationGeohash) return
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
            id = "chat-messages",
            handler = { event ->
                dispatch { it.accountDm(event, identity) }
            }
        )
    }

    private fun observeSelectedChannel() {
        scope.launch {
            locationChannels.selectedChannel.collectLatest { channel ->
                val next = (channel as? ChannelID.Location)?.channel?.geohash
                val nextToken = (channel as? ChannelID.Location)?.let {
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
                    val isLiveDerived = (channel as? ChannelID.Location)?.let {
                        locationChannels.isSelectedChannelLiveDerived(it.channel)
                    } == true
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
            handler = { event -> dispatch { it.geohashMessage(event, geohash) } },
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
            if (liveLocationToken != null &&
                !LiveLocationPrivacyGate.accepts(liveLocationToken)
            ) return@launch
            val identity = NostrIdentityBridge.deriveIdentity(geohash, application)
            subscriptions.subscribeGiftWraps(
                pubkey = identity.publicKeyHex,
                sinceMs = System.currentTimeMillis() - 172_800_000L,
                id = subscriptionId,
                handler = { event -> dispatch { it.geohashDm(event, geohash, identity) } },
                liveLocationToken = liveLocationToken
            )
            GeohashAliasRegistry.put(
                "nostr_${identity.publicKeyHex.take(16)}",
                identity.publicKeyHex
            )
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

    private fun dispatch(event: (Handlers) -> Unit) {
        val current = handlers
        if (current != null) {
            event(current)
            return
        }
        synchronized(lock) {
            if (pendingEvents.size >= MAX_PENDING_EVENTS) pendingEvents.removeFirst()
            pendingEvents.addLast(event)
        }
    }
}
