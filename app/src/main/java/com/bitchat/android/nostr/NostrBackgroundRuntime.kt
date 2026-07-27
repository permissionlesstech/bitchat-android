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
 * Stable subscriptions dispatch directly to a process-owned event processor. The UI hydrates from
 * the process state store, so relay events remain useful without retaining a cleared ViewModel.
 */
object NostrBackgroundRuntime {
    private const val TAG = "NostrBackground"

    private data class ActiveAccount(val epoch: NostrAccountEpoch)

    private data class GeohashDmSubscription(
        val geohash: String,
        val id: String,
        val liveLocationToken: Long?
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val random = SecureRandom().asKotlinRandom()
    private val lock = Any()

    @Volatile private var initialized = false
    @Volatile private var activeGeohash: String? = null
    @Volatile private var activeGeohashLiveToken: Long? = null
    @Volatile private var conversationGeohash: String? = null
    private var activeGeohashSubscriptionsEnabled = false
    private var activeAccount: ActiveAccount? = null
    private var subscriptionRevision = 0L
    private val installedGeohashDmSubscriptions =
        mutableMapOf<String, GeohashDmSubscription>()
    private val pendingGeohashDmSubscriptions = mutableSetOf<String>()
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
        synchronized(lock) {
            configureAccountLocked()
        }
        observeSelectedChannel()
        startPresenceScheduler()
    }

    /**
     * Cancel every account-bound receive before account storage or identity is
     * cleared. The relay connection remains process-owned and can be reused
     * after the caller completes its account reset.
     */
    fun invalidateAccount() {
        synchronized(lock) {
            if (!initialized) return
            invalidateAccountLocked()
        }
    }

    /**
     * Atomically replace process-owned subscriptions for the current identity.
     *
     * Ordered unsubscribe/subscribe operations and captured account epochs
     * avoid the former delay-based CLOSE/REQ race.
     */
    fun resetSubscriptions() {
        synchronized(lock) {
            if (!initialized) return
            invalidateAccountLocked()
            subscriptions.connect()
            configureAccountLocked()
        }
    }

    fun ensureConversationDm(geohash: String) {
        synchronized(lock) {
            if (!initialized) return
            val selectedLiveToken = activeGeohashLiveToken
            val selectedChannelSubscriptionIsUsable =
                activeGeohashSubscriptionsEnabled &&
                    geohash == activeGeohash &&
                    (selectedLiveToken == null ||
                        LiveLocationPrivacyGate.accepts(selectedLiveToken))
            conversationGeohash =
                if (selectedChannelSubscriptionIsUsable) null else geohash
            rebuildGeohashDmSubscriptionsLocked()
        }
    }

    internal fun currentAccountEpoch(): NostrAccountEpoch? =
        synchronized(lock) { activeAccount?.epoch }

    private fun configureAccountLocked() {
        val identity = NostrIdentityBridge.getCurrentNostrIdentity(application) ?: return
        val epoch = eventProcessor.configureAccount(identity)
        if (!NostrInboundAccountLifecycle.isCurrent(epoch)) return
        activeAccount = ActiveAccount(epoch)

        subscriptions.subscribeGiftWraps(
            pubkey = identity.publicKeyHex,
            sinceMs = System.currentTimeMillis() - 172_800_000L,
            id = "chat-messages",
            handler = { event ->
                eventProcessor.onAccountDm(event, identity, epoch)
            }
        )
        activeGeohash
            ?.takeIf { activeGeohashSubscriptionsEnabled }
            ?.let { subscribeSelectedGeohashLocked(it, activeGeohashLiveToken) }
        rebuildGeohashDmSubscriptionsLocked()
    }

    private fun invalidateAccountLocked() {
        activeAccount = null
        conversationGeohash = null
        subscriptionRevision += 1
        pendingGeohashDmSubscriptions.clear()
        // Invalidate first so an in-flight async derivation cannot install a
        // subscription after unsubscribeAllOwned().
        eventProcessor.invalidateAccount()
        subscriptions.unsubscribeAllOwned()
        installedGeohashDmSubscriptions.clear()
    }

    private fun observeSelectedChannel() {
        scope.launch {
            locationChannels.selectedChannel.collectLatest { channel ->
                val locationChannel = channel as? ChannelID.Location
                val next = locationChannel?.channel?.geohash
                val nextToken = locationChannel?.let {
                    locationChannels.liveLocationTokenForSelectedChannel(it.channel)
                }
                synchronized(lock) {
                    val previous = activeGeohash
                    if (previous == next && activeGeohashLiveToken == nextToken) {
                        return@synchronized
                    }

                    previous?.let {
                        subscriptions.unsubscribe("geohash-$it")
                    }
                    activeGeohash = next
                    activeGeohashLiveToken = nextToken
                    activeGeohashSubscriptionsEnabled =
                        locationChannel == null ||
                            !locationChannels
                                .isSelectedChannelLiveDerived(locationChannel.channel) ||
                            nextToken != null
                    if (conversationGeohash == next) {
                        conversationGeohash = null
                    }
                    next
                        ?.takeIf { activeGeohashSubscriptionsEnabled }
                        ?.let { geohash ->
                            subscribeSelectedGeohashLocked(geohash, nextToken)
                        }
                    rebuildGeohashDmSubscriptionsLocked()
                }
            }
        }
    }

    private fun subscribeSelectedGeohashLocked(
        geohash: String,
        liveLocationToken: Long?
    ) {
        val account = activeAccount ?: return
        subscriptions.subscribeGeohashMessages(
            geohash = geohash,
            sinceMs = System.currentTimeMillis() - 3_600_000L,
            limit = 200,
            id = "geohash-$geohash",
            handler = { event ->
                eventProcessor.onGeohashMessage(event, geohash, account.epoch)
            },
            liveLocationToken = liveLocationToken
        )
    }

    private fun rebuildGeohashDmSubscriptionsLocked() {
        val account = activeAccount
        subscriptionRevision += 1
        val revision = subscriptionRevision
        pendingGeohashDmSubscriptions.clear()

        val required = if (account == null) {
            emptyMap()
        } else {
            requiredGeohashDmSubscriptionsLocked()
        }
        installedGeohashDmSubscriptions.keys
            .filter { id -> installedGeohashDmSubscriptions[id] != required[id] }
            .forEach { id ->
                subscriptions.unsubscribe(id)
                installedGeohashDmSubscriptions.remove(id)
            }
        if (account == null) return

        required.values.forEach { request ->
            if (installedGeohashDmSubscriptions[request.id] == request) return@forEach
            pendingGeohashDmSubscriptions.add(request.id)
            scheduleGeohashDmSubscriptionLocked(account, request, revision)
        }
    }

    private fun requiredGeohashDmSubscriptionsLocked():
        Map<String, GeohashDmSubscription> {
        val required = linkedMapOf<String, GeohashDmSubscription>()
        val selected = activeGeohash
            ?.takeIf { activeGeohashSubscriptionsEnabled }
            ?.let { geohash ->
                GeohashDmSubscription(
                    geohash = geohash,
                    id = "geo-dm-$geohash",
                    liveLocationToken = activeGeohashLiveToken
                )
            }
        if (selected != null) {
            required[selected.id] = selected
        }

        val conversation = conversationGeohash
        val selectedCoversConversation =
            conversation != null &&
                selected?.geohash == conversation &&
                (selected.liveLocationToken == null ||
                    LiveLocationPrivacyGate.accepts(selected.liveLocationToken))
        if (conversation != null && !selectedCoversConversation) {
            val request = GeohashDmSubscription(
                geohash = conversation,
                id = "geo-dm-conversation-$conversation",
                liveLocationToken = null
            )
            required[request.id] = request
        }
        return required
    }

    private fun scheduleGeohashDmSubscriptionLocked(
        account: ActiveAccount,
        request: GeohashDmSubscription,
        revision: Long
    ) {
        val accountContext =
            NostrInboundAccountLifecycle.contextFor(account.epoch)
                ?: return
        accountContext.receiveScope.launch {
            synchronized(lock) {
                if (revision != subscriptionRevision ||
                    activeAccount != account ||
                    requiredGeohashDmSubscriptionsLocked()[request.id] != request
                ) {
                    return@synchronized
                }
                var didSubscribe = false
                val installed =
                    NostrInboundAccountLifecycle.runIfCurrent(account.epoch) {
                        val subscribe = {
                            val identity = NostrIdentityBridge.deriveIdentity(
                                request.geohash,
                                application
                            )
                            subscriptions.subscribeGiftWraps(
                                pubkey = identity.publicKeyHex,
                                sinceMs = System.currentTimeMillis() - 172_800_000L,
                                id = request.id,
                                handler = { event ->
                                    eventProcessor.onGeohashDm(
                                        event,
                                        request.geohash,
                                        identity,
                                        account.epoch
                                    )
                                },
                                liveLocationToken = request.liveLocationToken
                            )
                            didSubscribe = true
                            GeohashAliasRegistry.put(
                                "nostr_${identity.publicKeyHex.take(16)}",
                                identity.publicKeyHex
                            )
                        }
                        if (request.liveLocationToken == null) {
                            subscribe()
                        } else {
                            LiveLocationPrivacyGate.runIfAllowed(
                                request.liveLocationToken,
                                subscribe
                            )
                        }
                    }
                pendingGeohashDmSubscriptions.remove(request.id)
                if (installed && didSubscribe) {
                    installedGeohashDmSubscriptions[request.id] = request
                }
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
                            val account =
                                synchronized(lock) { activeAccount }
                                    ?: return@forEach
                            var token: Long? = null
                            var identity: NostrIdentity? = null
                            val prepared =
                                NostrInboundAccountLifecycle.runIfCurrent(
                                    account.epoch
                                ) mutation@{
                                    val capturedToken =
                                        LiveLocationPrivacyGate.captureToken()
                                        ?: return@mutation
                                    token = capturedToken
                                    if (geohash !in
                                        GeohashNostrPrivacyPolicy.livePresenceTargets(
                                            availableChannels =
                                                locationChannels.availableChannels.value,
                                            liveLocationEnabled = true
                                        )
                                    ) return@mutation

                                    LiveLocationPrivacyGate.runIfAllowed(capturedToken) {
                                        identity = NostrIdentityBridge.deriveIdentity(
                                            geohash,
                                            application
                                        )
                                    }
                                }
                            val liveToken = token
                            val preparedIdentity = identity
                            if (!prepared ||
                                liveToken == null ||
                                preparedIdentity == null ||
                                !LiveLocationPrivacyGate.accepts(liveToken)
                            ) return@forEach
                            val event = NostrProtocol.createGeohashPresenceEvent(
                                geohash,
                                preparedIdentity
                            )
                            NostrInboundAccountLifecycle.runIfCurrent(account.epoch) {
                                LiveLocationPrivacyGate.runIfAllowed(liveToken) {
                                    NostrRelayManager.getInstance(application)
                                        .sendEventToGeohash(
                                            event = event,
                                            geohash = geohash,
                                            includeDefaults = false,
                                            nRelays = 5,
                                            liveLocationToken = liveToken
                                        )
                                }
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
