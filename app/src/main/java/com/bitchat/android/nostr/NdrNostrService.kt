package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.bitchat.android.model.NdrFeatureGate
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal class BitchatNdrRelayAdapter(
    private val relayManager: NostrRelayManager,
    private val accountRelayUrls: List<String> = NostrRelayManager.defaultRelays()
) : NdrRelayManager {
    override fun subscribe(
        filter: NostrFilter,
        id: String,
        handler: (NostrEvent) -> Boolean
    ) {
        check(
            relayManager.subscribeAfterSuccessfulProcessing(
                filter = filter,
                id = id,
                targetRelayUrls = accountRelayUrls,
                handler = handler
            )
        ) {
            "NDR relay subscription was rejected during account reset"
        }
    }

    override fun unsubscribe(id: String) {
        relayManager.unsubscribe(id)
    }

    override fun sendEventConfirmed(
        event: NostrEvent,
        completion: (accepted: Boolean) -> Unit
    ) {
        relayManager.sendEventConfirmed(
            event = event,
            relayUrls = accountRelayUrls,
            completion = completion
        )
    }

    override fun cancelConfirmedEvent(eventId: String) {
        relayManager.cancelConfirmedEvent(eventId)
    }

    override fun setOnConnectionAvailable(handler: () -> Unit) {
        relayManager.setNdrConnectionAvailableHandler(handler)
    }
}

class NdrNostrService(
    private val relayManager: NdrRelayManager,
    private val runtimeFactory: NdrPairwiseRuntimeFactory,
    private val storageDirectoryProvider: () -> String,
    private val storageResetter: () -> Unit = {
        val storageDirectory = java.io.File(storageDirectoryProvider())
        if (storageDirectory.exists() && !storageDirectory.deleteRecursively()) {
            throw java.io.IOException("Failed to delete ${storageDirectory.absolutePath}")
        }
    },
    private val establishedSessionMarkers: NdrEstablishedSessionMarkerStore =
        FileNdrEstablishedSessionMarkerStore(
            requireNotNull(java.io.File(storageDirectoryProvider()).parentFile)
                .resolve("ndr-established-sessions")
        ),
    private val panicStorageQuarantine: NdrPanicStorageQuarantine =
        FileNdrPanicStorageQuarantine(java.io.File(storageDirectoryProvider())),
    private val pairwiseStateExists: (String) -> Boolean = Companion::pairwiseStateExists,
    private val invitePeerResolver: (String) -> String? = Companion::resolvePairwiseInvitePubkeyHex,
    private val retryScheduler: NdrRetryScheduler = Companion.DEFAULT_RETRY_SCHEDULER,
    private val nowSecondsProvider: () -> ULong = {
        (System.currentTimeMillis() / 1_000L).coerceAtLeast(0L).toULong()
    }
) {

    companion object {
        private const val TAG = "NdrNostrService"
        private const val NDR_MESSAGE_KIND = 1060
        private const val PUBLISH_RETRY_INITIAL_DELAY_MS = 1_000L
        private const val PUBLISH_RETRY_MAX_DELAY_MS = 30_000L
        private const val OOB_RETRY_INITIAL_DELAY_MS = 250L
        private const val OOB_RETRY_MAX_DELAY_MS = 4_000L
        private const val OOB_RETRY_MAX_ATTEMPTS = 5
        private const val PAIRWISE_STATE_PREFIX = "ndr-pairwise-state-v1-"
        private const val PAIRWISE_STATE_SUFFIX = ".json"

        private val RETRY_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ndr-publish-retry").apply { isDaemon = true }
        }
        private val DEFAULT_RETRY_SCHEDULER = NdrRetryScheduler { delayMs, task ->
            val future = RETRY_EXECUTOR.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            NdrRetryCancellation { future.cancel(false) }
        }

        @Volatile
        private var INSTANCE: NdrNostrService? = null

        fun getInstance(context: Context): NdrNostrService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: create(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun create(context: Context): NdrNostrService {
            val storageDirectory = context.filesDir.resolve("ndr")
            val establishedMarkerDirectory =
                context.filesDir.resolve("ndr-established-sessions")
            val relayManager =
                BitchatNdrRelayAdapter(NostrRelayManager.getInstance(context))

            val runtimeFactory = object : NdrPairwiseRuntimeFactory {
                override fun newWithStoragePath(
                    ourPubkeyHex: String,
                    ourIdentityPrivkeyHex: String,
                    storagePath: String
                ): NdrPairwiseRuntime {
                    return UniffiNdrPairwiseRuntime(
                        uniffi.ndr_ffi.PairwiseManager.newWithStoragePath(
                            ourPubkeyHex,
                            ourIdentityPrivkeyHex,
                            storagePath
                        )
                    )
                }
            }

            return NdrNostrService(
                relayManager = relayManager,
                runtimeFactory = runtimeFactory,
                storageDirectoryProvider = {
                    storageDirectory.apply { mkdirs() }.absolutePath
                },
                storageResetter = {
                    if (storageDirectory.exists() && !storageDirectory.deleteRecursively()) {
                        throw java.io.IOException("Failed to delete ${storageDirectory.absolutePath}")
                    }
                },
                establishedSessionMarkers =
                    FileNdrEstablishedSessionMarkerStore(establishedMarkerDirectory)
            )
        }

        private fun resolvePairwiseInvitePubkeyHex(payload: String): String? {
            return try {
                val invite = if (payload.startsWith("{")) {
                    uniffi.ndr_ffi.PairwiseInvite.fromEventJson(payload)
                } else {
                    uniffi.ndr_ffi.PairwiseInvite.fromUrl(payload)
                }
                invite.use {
                    it.`getPeerPubkeyHex`()
                        .lowercase()
                        .takeIf(NdrInputPolicy::isPubkeyHex)
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun pairwiseStateExists(storagePath: String): Boolean {
            val directory = java.io.File(storagePath)
            return directory.isDirectory &&
                directory.listFiles()?.any { file ->
                    file.isFile &&
                        file.name.startsWith(PAIRWISE_STATE_PREFIX) &&
                        file.name.endsWith(PAIRWISE_STATE_SUFFIX)
                } == true
        }
    }

    @Volatile
    var onDecryptedMessage: ((
        message: NdrDecryptedMessage,
        completion: (NdrDeliveryResult) -> Unit
        ) -> Unit)? = null
        @Synchronized set(value) {
            field = value
            if (value != null && NdrFeatureGate.isEnabled()) {
                drainAndApplyPubSubEventsLocked()
            }
        }

    @Volatile
    var onOutOfBandPayload: ((
        payload: NdrOutOfBandPayload,
        completion: (admitted: Boolean) -> Unit
        ) -> Unit)? = null
        @Synchronized set(value) {
            field = value
            if (value != null && NdrFeatureGate.isEnabled()) {
                drainAndApplyPubSubEventsLocked()
            }
        }

    @Volatile
    private var pairwiseRuntime: NdrPairwiseRuntime? = null

    @Volatile
    private var configuredForPubkeyHex: String? = null

    @Volatile
    private var configurationFailurePubkeyHex: String? = null

    @Volatile
    private var panicResetBlocked = runCatching {
        establishedSessionMarkers.isPanicWipeRequired() ||
            panicStorageQuarantine.isPending()
    }.getOrDefault(true)

    private val activeSubIds = linkedSetOf<String>()
    private val inFlightRelayEventsByActionId = linkedMapOf<String, String>()
    private val publishRetryAttempts = linkedMapOf<String, Int>()
    private val publishRetryTasks = linkedMapOf<String, NdrRetryCancellation>()
    private val outOfBandRetryAttempts = linkedMapOf<String, Int>()
    private val outOfBandRetryTasks = linkedMapOf<String, NdrRetryCancellation>()
    private val dispatchedDeliveryActionIds = linkedSetOf<String>()
    private val dispatchedOutOfBandActionIds = linkedSetOf<String>()
    private val knownActivePeerPubkeys = linkedSetOf<String>()
    private var nextRuntimeEpoch = 0L
    private var activeRuntimeEpoch: Long? = null
    private var processExitShutdown = false

    init {
        relayManager.setOnConnectionAvailable {
            onRelayConnectionAvailable()
        }
    }

    @get:Synchronized
    val isConfigured: Boolean
        get() = NdrFeatureGate.isEnabled() && pairwiseRuntime != null

    @get:Synchronized
    val isPanicWipeRequired: Boolean
        get() = panicResetBlocked

    @Synchronized
    fun currentInviteEventJson(): String? {
        if (!NdrFeatureGate.isEnabled()) return null
        return runCatching { pairwiseRuntime?.currentInviteEventJson() }.getOrNull()
    }

    @Synchronized
    fun configureIfNeeded(
        identity: NostrIdentity,
        accountGuard: () -> Boolean = { true }
    ): Boolean {
        if (!accountGuard()) return false
        if (!NdrFeatureGate.isEnabled()) {
            teardownLocked()
            configurationFailurePubkeyHex = null
            return false
        }
        return configureRuntimeIfNeededLocked(identity, drainPendingActions = true)
    }

    private fun configureRuntimeIfNeededLocked(
        identity: NostrIdentity,
        drainPendingActions: Boolean
    ): Boolean {
        if (processExitShutdown) {
            Log.w(TAG, "Refusing to reopen NDR during committed process exit")
            return false
        }
        if (panicResetBlocked) {
            Log.e(TAG, "Refusing to configure NDR after an incomplete panic wipe")
            return false
        }
        val pubkeyHex = identity.publicKeyHex.lowercase()
        if (configurationFailurePubkeyHex == pubkeyHex) {
            Log.e(TAG, "Refusing to reopen failed NDR storage before reset or identity change")
            return false
        }
        if (configurationFailurePubkeyHex != null) {
            configurationFailurePubkeyHex = null
        }
        if (configuredForPubkeyHex == pubkeyHex && pairwiseRuntime != null) {
            if (drainPendingActions) {
                drainAndApplyPubSubEventsLocked()
            }
            return true
        }

        teardownLocked()
        configuredForPubkeyHex = pubkeyHex

        try {
            val storagePath = java.io.File(
                storageDirectoryProvider(),
                "pairwise-v1/$pubkeyHex"
            ).absolutePath
            if (establishedSessionMarkers.contains(pubkeyHex) &&
                !pairwiseStateExists(storagePath)
            ) {
                throw java.io.IOException(
                    "Established pairwise state is missing for $pubkeyHex"
                )
            }
            val runtime = runtimeFactory.newWithStoragePath(
                ourPubkeyHex = pubkeyHex,
                ourIdentityPrivkeyHex = identity.privateKeyHex,
                storagePath = storagePath
            )
            pairwiseRuntime = runtime
            activeRuntimeEpoch = ++nextRuntimeEpoch
            if (!persistEstablishedMarkerIfNeededLocked(runtime)) {
                return false
            }
            if (drainPendingActions) {
                drainAndApplyPubSubEventsLocked()
            }
            return true
        } catch (_: Throwable) {
            Log.e(TAG, "Failed to configure NDR")
            teardownLocked()
            configurationFailurePubkeyHex = pubkeyHex
            return false
        }
    }

    @Synchronized
    fun hasActiveSession(peerPubkeyHex: String): Boolean {
        if (!NdrFeatureGate.isEnabled()) return false
        val runtime = pairwiseRuntime ?: return false
        val peer = peerPubkeyHex.lowercase()
        return try {
            val sessionInfo = runtime.sessionInfo(peer) ?: return false
            knownActivePeerPubkeys.add(peer)
            persistEstablishedMarkerIfNeededLocked(runtime) &&
                sessionInfo.isActive
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * True for any durable native session record, including the half-ready state between the
     * authenticated OOB response and bootstrap kind-1060 delivery.
     */
    @Synchronized
    fun hasPairwiseSession(peerPubkeyHex: String): Boolean {
        if (!NdrFeatureGate.isEnabled()) return false
        val runtime = pairwiseRuntime ?: return false
        val peer = peerPubkeyHex.lowercase()
        return try {
            if (runtime.sessionInfo(peer) == null) {
                false
            } else {
                knownActivePeerPubkeys.add(peer)
                persistEstablishedMarkerIfNeededLocked(runtime)
            }
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun activeSessionStateJson(peerPubkeyHex: String): String? {
        if (!NdrFeatureGate.isEnabled()) return null
        val runtime = pairwiseRuntime ?: return null
        return try {
            runtime.sessionInfo(peerPubkeyHex.lowercase())?.let { info ->
                """{"send_ready":${info.sendReady},"receive_ready":${info.receiveReady}}"""
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun sendIfPossible(
        text: String,
        peerPubkeyHex: String,
        expiresAtSeconds: ULong? = null,
        accountGuard: () -> Boolean = { true }
    ): NdrSendResult {
        if (!accountGuard()) return NdrSendResult.FAILED
        if (!NdrFeatureGate.isEnabled()) return NdrSendResult.NO_SESSION
        val runtime = pairwiseRuntime ?: return if (configurationFailurePubkeyHex != null) {
            NdrSendResult.FAILED
        } else {
            NdrSendResult.NO_SESSION
        }
        val peer = peerPubkeyHex.lowercase()
        val sessionInfo = try {
            runtime.sessionInfo(peer)
        } catch (_: Throwable) {
            return NdrSendResult.FAILED
        }
        if (sessionInfo == null) {
            return NdrSendResult.NO_SESSION
        }
        knownActivePeerPubkeys.add(peer)
        if (!sessionInfo.sendReady) {
            return NdrSendResult.FAILED
        }
        try {
            runtime.sendText(peer, text, expiresAtSeconds)
        } catch (_: Throwable) {
            Log.d(TAG, "NDR send failed")
            runCatching { drainAndApplyPubSubEventsLocked() }
            return NdrSendResult.FAILED
        }

        // Once sendText returns, the native runtime has durably advanced the
        // ratchet and owns the pending publish action. Never ask the caller to
        // retry that plaintext, even if host-side marker/drain work now fails.
        if (!persistEstablishedMarkerIfNeededLocked(runtime)) {
            Log.e(TAG, "NDR message admitted before host marker persistence failed")
            return NdrSendResult.SENT
        }
        runCatching { drainAndApplyPubSubEventsLocked() }
            .onFailure { Log.w(TAG, "NDR message admitted but pending-action drain failed") }
        return NdrSendResult.SENT
    }

    @Synchronized
    fun retirePeer(peerPubkeyHex: String): Boolean {
        if (!NdrFeatureGate.isEnabled()) return false
        return retirePeerLocked(peerPubkeyHex, drainPendingActions = true)
    }

    /**
     * Open existing native state only to durably retire a rebound peer while rollout is off.
     */
    @Synchronized
    fun retirePeerForMaintenance(
        identity: NostrIdentity,
        peerPubkeyHex: String
    ): Boolean {
        if (!configureRuntimeIfNeededLocked(identity, drainPendingActions = false)) {
            return false
        }
        return retirePeerLocked(peerPubkeyHex, drainPendingActions = false)
    }

    private fun retirePeerLocked(
        peerPubkeyHex: String,
        drainPendingActions: Boolean
    ): Boolean {
        val peer = peerPubkeyHex.lowercase()
        if (!NdrInputPolicy.isPubkeyHex(peer)) return false
        val runtime = pairwiseRuntime ?: return false
        val existedBefore = try {
            runtime.sessionInfo(peer) != null
        } catch (_: Throwable) {
            return false
        }
        if (!existedBefore) {
            knownActivePeerPubkeys.remove(peer)
            return true
        }
        return try {
            val retired = runtime.retirePeer(peer)
            val absentAfter = runtime.sessionInfo(peer) == null
            if (retired || absentAfter) {
                knownActivePeerPubkeys.remove(peer)
                if (drainPendingActions) {
                    drainAndApplyPubSubEventsLocked()
                }
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            val absentAfter = runCatching {
                runtime.sessionInfo(peer) == null
            }.getOrDefault(false)
            if (absentAfter) {
                knownActivePeerPubkeys.remove(peer)
                if (drainPendingActions) {
                    runCatching { drainAndApplyPubSubEventsLocked() }
                }
                true
            } else {
                Log.e(TAG, "Failed to retire rebound NDR peer")
                false
            }
        }
    }

    @Synchronized
    fun processOutOfBandEventJson(
        eventJson: String,
        expectedPeerPubkeyHex: String? = null
    ): NdrOutOfBandProcessResult {
        if (!NdrFeatureGate.isEnabled()) {
            return NdrOutOfBandProcessResult(emptyList())
        }
        val runtime = pairwiseRuntime ?: return NdrOutOfBandProcessResult(emptyList())
        val trimmedPayload = eventJson.trim()
        val expectedPeer = expectedPeerPubkeyHex
            ?.lowercase()
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            ?: return NdrOutOfBandProcessResult(emptyList())
        if (!NdrInputPolicy.isWithinEncodedEventLimit(trimmedPayload)) {
            return NdrOutOfBandProcessResult(emptyList())
        }
        val parsedEvent = NostrEvent.fromJsonString(trimmedPayload)
        if (parsedEvent != null && !NdrInputPolicy.hasBoundedTags(parsedEvent)) {
            return NdrOutOfBandProcessResult(emptyList())
        }
        val inboundInvite = runCatching {
            parseOutOfBandInvite(trimmedPayload)
        }.getOrNull()
        var acceptResult: NdrAcceptInviteResult? = null
        var processingSucceeded = false

        // Pairwise invites must be authored by the exact peer authenticated by
        // the Noise session. Gift-wrap response authors remain ephemeral.
        val claimedPeer = inboundInvite?.peerPubkeyHex
        if (claimedPeer != null && claimedPeer != expectedPeer) {
            Log.w(TAG, "Rejecting OOB event with an authenticated-peer mismatch")
            return NdrOutOfBandProcessResult(emptyList())
        }
        val canMutateSession =
            inboundInvite?.transport == OutOfBandInviteTransport.EVENT_JSON ||
                inboundInvite?.transport == OutOfBandInviteTransport.URL ||
                parsedEvent?.kind == NostrKind.GIFT_WRAP
        if (!canMutateSession) {
            Log.w(TAG, "Rejecting non-handshake OOB payload")
            return NdrOutOfBandProcessResult(emptyList())
        }
        if (!persistEstablishedMarkerIfNeededLocked(runtime, force = true)) {
            return NdrOutOfBandProcessResult(emptyList())
        }

        try {
            when {
                inboundInvite?.transport == OutOfBandInviteTransport.EVENT_JSON -> {
                    acceptResult = runtime.acceptInviteFromEventJson(trimmedPayload, expectedPeer)
                    processingSucceeded = true
                }
                inboundInvite?.transport == OutOfBandInviteTransport.URL -> {
                    acceptResult = runtime.acceptInviteFromUrl(trimmedPayload, expectedPeer)
                    processingSucceeded = true
                }
                parsedEvent?.kind == NostrKind.GIFT_WRAP -> {
                    runtime.processOutOfBandResponse(trimmedPayload, expectedPeer)
                    processingSucceeded = true
                }
                else -> Unit
            }
        } catch (_: NdrSessionNotReadyException) {
            Log.d(TAG, "OOB session is not ready")
        } catch (_: Throwable) {
            Log.d(TAG, "Ignoring invalid OOB event")
        }

        acceptResult?.let {
            if (it.peerPubkeyHex.lowercase() != expectedPeer) {
                Log.w(TAG, "Rejecting OOB result with an authenticated-peer mismatch")
                return NdrOutOfBandProcessResult(emptyList())
            }
        }
        if (processingSucceeded &&
            !persistEstablishedMarkerIfNeededLocked(runtime)
        ) {
            return NdrOutOfBandProcessResult(emptyList())
        }
        val collectOutOfBandPublishes = onOutOfBandPayload == null
        val outOfBandPublishes = drainAndApplyPubSubEventsLocked(
            collectOutOfBandPublishes = collectOutOfBandPublishes
        )
        val sessionLookupPubkeyHex = if (processingSucceeded) {
            acceptResult?.peerPubkeyHex?.lowercase() ?: expectedPeer
        } else {
            null
        }

        return NdrOutOfBandProcessResult(
            outboundPayloads = outOfBandPublishes,
            sessionLookupPubkeyHex = sessionLookupPubkeyHex
        )
    }

    @Synchronized
    fun processInboundRelayEvent(event: NostrEvent): Boolean {
        if (!NdrFeatureGate.isEnabled()) return false
        val runtime = pairwiseRuntime ?: return false
        val runtimeEpoch = activeRuntimeEpoch ?: return false
        return processInboundRelayEventLocked(event, runtime, runtimeEpoch)
    }

    @Synchronized
    fun replayPendingOutOfBandPayloads() {
        if (!NdrFeatureGate.isEnabled() || onOutOfBandPayload == null) return
        drainAndApplyPubSubEventsLocked()
    }

    @Synchronized
    fun onOutOfBandTransportAvailable() {
        if (!NdrFeatureGate.isEnabled() || pairwiseRuntime == null) return
        outOfBandRetryTasks.values.forEach(NdrRetryCancellation::cancel)
        outOfBandRetryTasks.clear()
        outOfBandRetryAttempts.clear()
        drainAndApplyPubSubEventsLocked()
    }

    private fun processInboundRelayEventLocked(
        event: NostrEvent,
        runtime: NdrPairwiseRuntime,
        runtimeEpoch: Long
    ): Boolean {
        if (!isCurrentRuntimeLocked(runtime, runtimeEpoch)) return false
        if (event.kind != NDR_MESSAGE_KIND) return false
        if (!NdrInputPolicy.hasBoundedTags(event)) return false
        if (event.tags.any { it.firstOrNull() == "p" }) {
            Log.w(TAG, "Rejecting recipient-tagged inbound NDR relay event")
            return false
        }
        val eventJson = event.toJsonString()
        if (!NdrInputPolicy.isWithinEncodedEventLimit(eventJson)) return false

        try {
            runtime.processEvent(eventJson)
            if (!persistEstablishedMarkerIfNeededLocked(runtime)) {
                return false
            }
        } catch (_: Throwable) {
            Log.d(TAG, "Ignoring invalid NDR relay event")
            drainAndApplyPubSubEventsLocked()
            return false
        }

        drainAndApplyPubSubEventsLocked()
        return true
    }

    @Synchronized
    private fun drainAndApplyPubSubEventsLocked(
        collectOutOfBandPublishes: Boolean = false
    ): List<NdrOutOfBandPayload> {
        val runtime = pairwiseRuntime ?: return emptyList()
        val runtimeEpoch = activeRuntimeEpoch ?: return emptyList()
        val outOfBandPublishes = mutableListOf<NdrOutOfBandPayload>()
        val acknowledgedActionIds = mutableListOf<String>()

        val events = try {
            runtime.pendingActions(nowSecondsProvider())
        } catch (_: Throwable) {
            Log.e(TAG, "Failed to drain NDR events")
            return emptyList()
        }
        val sessionsAwaitingOutOfBandAdmission = events
            .asSequence()
            .filter { it.kind == "out_of_band" }
            .mapNotNull(NdrPubSubEvent::sessionId)
            .toSet()

        events.forEach { event ->
            if (applyPubSubEventLocked(
                runtime = runtime,
                runtimeEpoch = runtimeEpoch,
                event = event,
                sessionsAwaitingOutOfBandAdmission = sessionsAwaitingOutOfBandAdmission,
                collectOutOfBandPublish = if (collectOutOfBandPublishes) {
                    { value -> outOfBandPublishes.add(value) }
                } else {
                    null
                }
            )) {
                acknowledgedActionIds += event.actionId
            }
        }
        if (acknowledgedActionIds.isNotEmpty()) {
            acknowledgeEventsLocked(runtime, runtimeEpoch, acknowledgedActionIds)
        }

        return outOfBandPublishes
    }

    @Synchronized
    private fun applyPubSubEventLocked(
        runtime: NdrPairwiseRuntime,
        runtimeEpoch: Long,
        event: NdrPubSubEvent,
        sessionsAwaitingOutOfBandAdmission: Set<String>,
        collectOutOfBandPublish: ((NdrOutOfBandPayload) -> Unit)?
    ): Boolean {
        if (event.actionId.isBlank()) {
            Log.w(TAG, "Ignoring NDR action without a stable id")
            return false
        }
        return when (event.kind) {
            "subscribe" -> {
                val subid = event.subid?.takeIf(String::isNotBlank) ?: return false
                val filterJson = event.filterJson ?: return false
                if (hasRecipientFilter(filterJson)) {
                    Log.w(TAG, "Rejecting recipient-bearing NDR relay filter")
                    return false
                }
                val filter = try {
                    parseFilterJson(filterJson)
                } catch (_: Throwable) {
                    Log.w(TAG, "Ignoring malformed NDR relay filter")
                    return false
                }
                if (!isPairwiseMessageSubscription(filter)) {
                    Log.w(TAG, "Rejecting non-pairwise NDR relay filter")
                    return false
                }
                if (!activeSubIds.add(subid)) {
                    return true
                }
                try {
                    relayManager.subscribe(filter, subid) { inbound ->
                        synchronized(this) {
                            processInboundRelayEventLocked(
                                inbound,
                                runtime,
                                runtimeEpoch
                            )
                        }
                    }
                    true
                } catch (_: Throwable) {
                    activeSubIds.remove(subid)
                    Log.w(TAG, "Failed to install NDR relay filter")
                    false
                }
            }

            "unsubscribe" -> {
                val subid = event.subid ?: return true
                if (activeSubIds.remove(subid)) {
                    try {
                        relayManager.unsubscribe(subid)
                        true
                    } catch (_: Throwable) {
                        activeSubIds.add(subid)
                        Log.w(TAG, "Failed to remove NDR relay filter")
                        false
                    }
                } else {
                    true
                }
            }

            "publish" -> {
                val sessionId = event.sessionId?.takeIf(String::isNotBlank)
                    ?: run {
                        Log.w(TAG, "Rejecting NDR publish without a session id")
                        return true
                    }
                if (sessionId in sessionsAwaitingOutOfBandAdmission) {
                    return false
                }
                val eventJson = event.eventJson ?: return true
                val nostrEvent = NostrEvent.fromJsonString(eventJson) ?: return true
                if (nostrEvent.kind != NDR_MESSAGE_KIND) {
                    Log.w(TAG, "Rejecting non-message NDR relay publish")
                    return true
                }
                if (nostrEvent.tags.any { it.firstOrNull() == "p" }) {
                    Log.w(TAG, "Rejecting recipient-tagged NDR relay publish")
                    return true
                }
                if (event.actionId in publishRetryTasks ||
                    event.actionId in inFlightRelayEventsByActionId
                ) return false
                inFlightRelayEventsByActionId[event.actionId] = nostrEvent.id
                try {
                    relayManager.sendEventConfirmed(nostrEvent) { accepted ->
                        synchronized(this) {
                            if (!isCurrentRuntimeLocked(runtime, runtimeEpoch)) {
                                return@synchronized
                            }
                            inFlightRelayEventsByActionId.remove(event.actionId)
                            if (accepted) {
                                val acknowledged = acknowledgeEventsLocked(
                                    runtime,
                                    runtimeEpoch,
                                    listOf(event.actionId)
                                )
                                if (acknowledged) {
                                    clearPublishRetryLocked(event.actionId)
                                } else {
                                    schedulePublishRetryLocked(
                                        runtime,
                                        runtimeEpoch,
                                        event.actionId
                                    )
                                }
                            } else {
                                schedulePublishRetryLocked(
                                    runtime,
                                    runtimeEpoch,
                                    event.actionId
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {
                    inFlightRelayEventsByActionId.remove(event.actionId)
                    schedulePublishRetryLocked(runtime, runtimeEpoch, event.actionId)
                    Log.w(TAG, "Failed to queue NDR relay publish")
                }
                false
            }

            "out_of_band" -> {
                if (event.sessionId.isNullOrBlank()) {
                    Log.w(TAG, "Rejecting NDR out-of-band action without a session id")
                    return true
                }
                val eventJson = event.eventJson ?: return true
                val nostrEvent = NostrEvent.fromJsonString(eventJson) ?: return true
                if (nostrEvent.kind != NostrKind.GIFT_WRAP) {
                    Log.w(TAG, "Rejecting invalid NDR out-of-band action")
                    return true
                }
                val peerPubkeyHex = event.peerPubkeyHex
                    ?.lowercase()
                    ?.takeIf(NdrInputPolicy::isPubkeyHex)
                    ?: run {
                        Log.w(TAG, "Rejecting NDR out-of-band action without an exact peer")
                        return true
                    }
                val payload = NdrOutOfBandPayload(
                    actionId = event.actionId,
                    eventJson = eventJson,
                    peerPubkeyHex = peerPubkeyHex,
                    runtimeEpoch = runtimeEpoch,
                    runtime = runtime
                )
                if (collectOutOfBandPublish != null) {
                    collectOutOfBandPublish(payload)
                } else {
                    onOutOfBandPayload?.let { callback ->
                        dispatchOutOfBandPayloadLocked(
                            runtime,
                            runtimeEpoch,
                            payload,
                            callback
                        )
                    }
                }
                false
            }

            "delivery" -> {
                if (!NdrFeatureGate.isEnabled()) return true
                val content = event.content ?: return true
                val senderPubkeyHex = event.senderPubkeyHex ?: return true
                val innerEventId = event.eventId ?: return true
                if (!NdrInputPolicy.isWithinEncodedEventLimit(content) ||
                    !NdrInputPolicy.isPubkeyHex(senderPubkeyHex) ||
                    !NdrInputPolicy.isEventIdHex(innerEventId)
                ) return true
                val message = NdrDecryptedMessage(
                    actionId = event.actionId,
                    content = content,
                    senderPubkeyHex = senderPubkeyHex.lowercase(),
                    eventId = innerEventId.lowercase(),
                    expiresAtSeconds = event.expiresAtSeconds
                )
                val callback = onDecryptedMessage
                if (callback != null) {
                    dispatchDecryptedMessageLocked(
                        runtime,
                        runtimeEpoch,
                        message,
                        callback
                    )
                }
                false
            }

            else -> true
        }
    }

    @Synchronized
    private fun dispatchDecryptedMessageLocked(
        runtime: NdrPairwiseRuntime,
        runtimeEpoch: Long,
        message: NdrDecryptedMessage,
        callback: (
            message: NdrDecryptedMessage,
            completion: (NdrDeliveryResult) -> Unit
        ) -> Unit
    ) {
        if (!dispatchedDeliveryActionIds.add(message.actionId)) return
        try {
            callback(message) { result ->
                synchronized(this) {
                    if (!isCurrentRuntimeLocked(runtime, runtimeEpoch)) {
                        return@synchronized
                    }
                    dispatchedDeliveryActionIds.remove(message.actionId)
                    if (result.shouldAcknowledge) {
                        acknowledgeEventsLocked(
                            runtime,
                            runtimeEpoch,
                            listOf(message.actionId)
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            dispatchedDeliveryActionIds.remove(message.actionId)
            Log.w(TAG, "NDR delivery callback failed")
        }
    }

    @Synchronized
    fun acknowledgeOutOfBandPayload(payload: NdrOutOfBandPayload): Boolean {
        val runtime = payload.runtime ?: return false
        val runtimeEpoch = payload.runtimeEpoch ?: return false
        if (!NdrFeatureGate.isEnabled()) return false
        val acknowledged =
            acknowledgeEventsLocked(runtime, runtimeEpoch, listOf(payload.actionId))
        if (acknowledged) {
            clearOutOfBandRetryLocked(payload.actionId)
            drainAndApplyPubSubEventsLocked()
        }
        return acknowledged
    }

    @Synchronized
    private fun dispatchOutOfBandPayloadLocked(
        runtime: NdrPairwiseRuntime,
        runtimeEpoch: Long,
        payload: NdrOutOfBandPayload,
        callback: (
            payload: NdrOutOfBandPayload,
            completion: (admitted: Boolean) -> Unit
        ) -> Unit
    ) {
        if (!dispatchedOutOfBandActionIds.add(payload.actionId)) return
        try {
            callback(payload) { admitted ->
                synchronized(this) {
                    if (!isCurrentRuntimeLocked(runtime, runtimeEpoch)) {
                        return@synchronized
                    }
                    dispatchedOutOfBandActionIds.remove(payload.actionId)
                    if (admitted) {
                        val acknowledged = acknowledgeEventsLocked(
                            runtime,
                            runtimeEpoch,
                            listOf(payload.actionId)
                        )
                        if (acknowledged) {
                            clearOutOfBandRetryLocked(payload.actionId)
                            // A publish from this exact handshake session may
                            // now cross the relay boundary.
                            drainAndApplyPubSubEventsLocked()
                        } else {
                            scheduleOutOfBandRetryLocked(
                                runtime,
                                runtimeEpoch,
                                payload.actionId
                            )
                        }
                    } else {
                        scheduleOutOfBandRetryLocked(
                            runtime,
                            runtimeEpoch,
                            payload.actionId
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            dispatchedOutOfBandActionIds.remove(payload.actionId)
            scheduleOutOfBandRetryLocked(runtime, runtimeEpoch, payload.actionId)
            Log.w(TAG, "NDR out-of-band delivery callback failed")
        }
    }

    private fun acknowledgeEventsLocked(
        runtime: NdrPairwiseRuntime,
        runtimeEpoch: Long,
        actionIds: List<String>
    ): Boolean {
        if (!isCurrentRuntimeLocked(runtime, runtimeEpoch)) return false
        return try {
            runtime.ackActions(actionIds.distinct())
            true
        } catch (_: Throwable) {
            Log.w(TAG, "Failed to acknowledge NDR actions")
            false
        }
    }

    private fun isCurrentRuntimeLocked(
        runtime: NdrPairwiseRuntime,
        runtimeEpoch: Long
    ): Boolean {
        return pairwiseRuntime === runtime && activeRuntimeEpoch == runtimeEpoch
    }

    private fun persistEstablishedMarkerIfNeededLocked(
        runtime: NdrPairwiseRuntime,
        force: Boolean = false
    ): Boolean {
        val accountPubkeyHex = configuredForPubkeyHex ?: return false
        return try {
            val hasPairwiseSessionRecord = force ||
                runtime.knownPeerPubkeys().any { peerPubkeyHex ->
                    runtime.sessionInfo(peerPubkeyHex) != null
                }
            if (hasPairwiseSessionRecord) {
                establishedSessionMarkers.mark(accountPubkeyHex)
            }
            true
        } catch (_: Throwable) {
            Log.e(TAG, "Failed to persist NDR established-session marker")
            configurationFailurePubkeyHex = accountPubkeyHex
            teardownLocked()
            false
        }
    }

    private fun schedulePublishRetryLocked(
        runtime: NdrPairwiseRuntime,
        runtimeEpoch: Long,
        actionId: String
    ) {
        if (!isCurrentRuntimeLocked(runtime, runtimeEpoch) ||
            actionId in publishRetryTasks
        ) return
        val attempt = publishRetryAttempts[actionId] ?: 0
        val multiplier = 1L shl min(attempt, 5)
        val delayMs = (PUBLISH_RETRY_INITIAL_DELAY_MS * multiplier)
            .coerceAtMost(PUBLISH_RETRY_MAX_DELAY_MS)
        publishRetryAttempts[actionId] = attempt + 1
        publishRetryTasks[actionId] = retryScheduler.schedule(delayMs) {
            synchronized(this) {
                if (!isCurrentRuntimeLocked(runtime, runtimeEpoch)) {
                    return@synchronized
                }
                publishRetryTasks.remove(actionId)
                drainAndApplyPubSubEventsLocked()
            }
        }
    }

    private fun clearPublishRetryLocked(actionId: String) {
        publishRetryTasks.remove(actionId)?.cancel()
        publishRetryAttempts.remove(actionId)
    }

    private fun scheduleOutOfBandRetryLocked(
        runtime: NdrPairwiseRuntime,
        runtimeEpoch: Long,
        actionId: String
    ) {
        if (!isCurrentRuntimeLocked(runtime, runtimeEpoch) ||
            actionId in outOfBandRetryTasks
        ) return
        val attempt = outOfBandRetryAttempts[actionId] ?: 0
        if (attempt >= OOB_RETRY_MAX_ATTEMPTS) return
        val multiplier = 1L shl min(attempt, 4)
        val delayMs = (OOB_RETRY_INITIAL_DELAY_MS * multiplier)
            .coerceAtMost(OOB_RETRY_MAX_DELAY_MS)
        outOfBandRetryAttempts[actionId] = attempt + 1
        outOfBandRetryTasks[actionId] = retryScheduler.schedule(delayMs) {
            synchronized(this) {
                if (!isCurrentRuntimeLocked(runtime, runtimeEpoch)) {
                    return@synchronized
                }
                outOfBandRetryTasks.remove(actionId)
                drainAndApplyPubSubEventsLocked()
            }
        }
    }

    private fun clearOutOfBandRetryLocked(actionId: String) {
        outOfBandRetryTasks.remove(actionId)?.cancel()
        outOfBandRetryAttempts.remove(actionId)
    }

    @Synchronized
    private fun onRelayConnectionAvailable() {
        if (pairwiseRuntime == null || activeRuntimeEpoch == null) return
        publishRetryTasks.values.forEach(NdrRetryCancellation::cancel)
        publishRetryTasks.clear()
        drainAndApplyPubSubEventsLocked()
    }

    @Synchronized
    private fun teardownLocked() {
        val runtime = pairwiseRuntime
        pairwiseRuntime = null
        activeRuntimeEpoch = null
        publishRetryTasks.values.forEach(NdrRetryCancellation::cancel)
        publishRetryTasks.clear()
        publishRetryAttempts.clear()
        outOfBandRetryTasks.values.forEach(NdrRetryCancellation::cancel)
        outOfBandRetryTasks.clear()
        outOfBandRetryAttempts.clear()
        val confirmedEventIds = inFlightRelayEventsByActionId.values.toSet()
        inFlightRelayEventsByActionId.clear()
        confirmedEventIds.forEach(relayManager::cancelConfirmedEvent)
        activeSubIds.forEach { subId ->
            runCatching { relayManager.unsubscribe(subId) }
                .onFailure { Log.w(TAG, "Failed to unsubscribe NDR relay filter") }
        }
        activeSubIds.clear()
        dispatchedDeliveryActionIds.clear()
        dispatchedOutOfBandActionIds.clear()
        knownActivePeerPubkeys.clear()
        configuredForPubkeyHex = null
        runCatching { runtime?.destroy() }
            .onFailure { Log.w(TAG, "Failed to destroy NDR runtime") }
    }

    /** Quiesce account-bound runtime work for process exit without wiping durable sessions. */
    @Synchronized
    fun shutdownForProcessExit() {
        processExitShutdown = true
        NostrInboundAccountLifecycle.invalidate()
        onDecryptedMessage = null
        onOutOfBandPayload = null
        teardownLocked()
    }

    @Synchronized
    fun resetForPanic(): Boolean {
        NostrInboundAccountLifecycle.invalidate()
        onDecryptedMessage = null
        teardownLocked()
        panicResetBlocked = true

        val quarantineEstablished = runCatching {
            panicStorageQuarantine.begin()
        }.onFailure {
            Log.w(TAG, "Failed to establish NDR panic quarantine")
        }.isSuccess
        val retryMarkerPersisted = runCatching {
            establishedSessionMarkers.markPanicWipeRequired()
        }.onFailure {
            Log.w(TAG, "Failed to persist NDR panic retry marker")
        }.isSuccess

        val activeStorageCleared = runCatching(storageResetter)
            .onFailure { Log.w(TAG, "Failed to delete NDR storage") }
            .isSuccess
        val quarantineCleared = if (quarantineEstablished) {
            runCatching(panicStorageQuarantine::wipeNativeState)
                .onFailure { Log.w(TAG, "Failed to wipe quarantined NDR storage") }
                .isSuccess
        } else {
            true
        }
        val storageCleared = activeStorageCleared && quarantineCleared
        val markersCleared = storageCleared &&
            runCatching(establishedSessionMarkers::clearEstablishedSessions)
                .onFailure { Log.w(TAG, "Failed to delete NDR downgrade markers") }
                .isSuccess
        return (quarantineEstablished || retryMarkerPersisted) && markersCleared
    }

    /**
     * Clear the retry marker only after host identities and contact pins are wiped.
     */
    @Synchronized
    fun completePanicReset(): Boolean {
        if (!panicResetBlocked) return true
        val completed = runCatching {
            establishedSessionMarkers.clearPanicWipeRequired()
            panicStorageQuarantine.clear()
        }.onFailure {
            Log.w(TAG, "Failed to clear NDR panic retry state")
        }.isSuccess
        if (completed) {
            panicResetBlocked = false
            configurationFailurePubkeyHex = null
        }
        return completed
    }

    private fun isDoubleRatchetInviteEvent(event: NostrEvent): Boolean {
        if (event.kind != 30078) {
            return false
        }
        return event.tags.any { tag ->
            (tag.size >= 2 && tag[0] == "l" && tag[1] == "double-ratchet/invites") ||
                (tag.size >= 2 && tag[0] == "d" && tag[1].startsWith("double-ratchet/invites/"))
        }
    }

    private enum class OutOfBandInviteTransport {
        EVENT_JSON,
        URL
    }

    private data class ParsedOutOfBandInvite(
        val peerPubkeyHex: String,
        val transport: OutOfBandInviteTransport
    )

    private fun parseOutOfBandInvite(payload: String): ParsedOutOfBandInvite? {
        if (payload.isBlank()) return null

        if (payload.startsWith("{")) {
            val event = NostrEvent.fromJsonString(payload) ?: return null
            if (!isDoubleRatchetInviteEvent(event)) return null
            val peerPubkeyHex = invitePeerResolver(payload)?.lowercase() ?: return null
            return ParsedOutOfBandInvite(
                peerPubkeyHex = peerPubkeyHex,
                transport = OutOfBandInviteTransport.EVENT_JSON
            )
        }

        val peerPubkeyHex = invitePeerResolver(payload)?.lowercase() ?: return null
        return ParsedOutOfBandInvite(
            peerPubkeyHex = peerPubkeyHex,
            transport = OutOfBandInviteTransport.URL
        )
    }

    private fun isPairwiseMessageSubscription(filter: NostrFilter): Boolean {
        return filter.kinds == listOf(NDR_MESSAGE_KIND) &&
            filter.authors.orEmpty().isNotEmpty() &&
            filter.authors.orEmpty().all(NdrInputPolicy::isPubkeyHex)
    }

    private fun hasRecipientFilter(filterJson: String): Boolean =
        runCatching {
            JsonParser.parseString(filterJson).asJsonObject.has("#p")
        }.getOrDefault(true)

    private fun parseFilterJson(filterJson: String): NostrFilter {
        val root = JsonParser.parseString(filterJson).asJsonObject
        val builder = NostrFilter.Builder()

        root.strings("ids")?.let { if (it.isNotEmpty()) builder.ids(*it.toTypedArray()) }
        root.strings("authors")?.let { if (it.isNotEmpty()) builder.authors(*it.toTypedArray()) }
        root.ints("kinds")?.let { if (it.isNotEmpty()) builder.kinds(*it.toIntArray()) }
        root.get("since")?.takeIf { !it.isJsonNull }?.asLong?.let { builder.since(it * 1000L) }
        root.get("until")?.takeIf { !it.isJsonNull }?.asLong?.let { builder.until(it * 1000L) }
        root.get("limit")?.takeIf { !it.isJsonNull }?.asInt?.let { builder.limit(it) }

        root.entrySet().forEach { (key, value) ->
            if (!key.startsWith("#") || !value.isJsonArray) {
                return@forEach
            }
            val tagValues = value.asJsonArray.mapNotNull { if (it.isJsonNull) null else it.asString }
            if (tagValues.isNotEmpty()) {
                builder.tag(key.removePrefix("#"), *tagValues.toTypedArray())
            }
        }

        return builder.build()
    }

    private fun JsonObject.strings(name: String): List<String>? {
        return getAsJsonArray(name)?.mapNotNull { if (it.isJsonNull) null else it.asString }
    }

    private fun JsonObject.ints(name: String): List<Int>? {
        return getAsJsonArray(name)?.mapNotNull { if (it.isJsonNull) null else it.asInt }
    }
}

private class UniffiNdrPairwiseRuntime(
    private val manager: uniffi.ndr_ffi.PairwiseManager
) : NdrPairwiseRuntime {
    override fun currentInviteEventJson(): String = manager.`currentInviteEventJson`()

    override fun currentInviteUrl(root: String): String = manager.`currentInviteUrl`(root)

    override fun acceptInviteFromEventJson(
        eventJson: String,
        expectedPeerPubkeyHex: String
    ): NdrAcceptInviteResult {
        val result = try {
            manager.`acceptInviteFromEventJson`(eventJson, expectedPeerPubkeyHex)
        } catch (t: uniffi.ndr_ffi.NdrException.SessionNotReady) {
            throw NdrSessionNotReadyException(t.message, t)
        }
        val peer = result.peerPubkeyHex.lowercase()
        check(peer == expectedPeerPubkeyHex.lowercase()) {
            "Pairwise invite identity mismatch"
        }
        return NdrAcceptInviteResult(
            peerPubkeyHex = peer,
            createdNewSession = result.createdNewSession
        )
    }

    override fun acceptInviteFromUrl(
        inviteUrl: String,
        expectedPeerPubkeyHex: String
    ): NdrAcceptInviteResult {
        val result = try {
            manager.`acceptInviteFromUrl`(inviteUrl, expectedPeerPubkeyHex)
        } catch (t: uniffi.ndr_ffi.NdrException.SessionNotReady) {
            throw NdrSessionNotReadyException(t.message, t)
        }
        val peer = result.peerPubkeyHex.lowercase()
        check(peer == expectedPeerPubkeyHex.lowercase()) {
            "Pairwise invite identity mismatch"
        }
        return NdrAcceptInviteResult(
            peerPubkeyHex = peer,
            createdNewSession = result.createdNewSession
        )
    }

    override fun processEvent(eventJson: String) {
        manager.`processEvent`(eventJson)
    }

    override fun processOutOfBandResponse(
        eventJson: String,
        expectedPeerPubkeyHex: String
    ) {
        manager.`processOutOfBandResponse`(eventJson, expectedPeerPubkeyHex)
    }

    override fun pendingActions(nowSeconds: ULong): List<NdrPubSubEvent> =
        manager.`pendingActionsAt`(nowSeconds).map {
            NdrPubSubEvent(
                actionId = it.actionId,
                kind = it.kind,
                sessionId = it.sessionId,
                subid = it.subscriptionId,
                filterJson = it.filterJson,
                eventJson = it.eventJson,
                peerPubkeyHex = it.peerPubkeyHex?.lowercase(),
                senderPubkeyHex = it.peerPubkeyHex?.lowercase(),
                content = it.innerEventJson,
                eventId = it.innerEventId?.lowercase(),
                expiresAtSeconds = it.expiresAtSeconds
            )
        }

    override fun ackActions(actionIds: List<String>) {
        manager.`ackActions`(actionIds)
    }

    override fun sessionInfo(peerPubkeyHex: String): NdrPairwiseSessionInfo? =
        manager.`sessionInfo`(peerPubkeyHex)?.let {
            NdrPairwiseSessionInfo(
                sendReady = it.sendReady,
                receiveReady = it.receiveReady,
                trackedSenderPubkeys = it.trackedSenderPubkeys.map(String::lowercase)
            )
        }

    override fun knownPeerPubkeys(): List<String> =
        manager.`knownPeerPubkeys`().map(String::lowercase)

    override fun retirePeer(peerPubkeyHex: String): Boolean =
        manager.`retirePeer`(peerPubkeyHex)

    override fun sendText(
        recipientPubkeyHex: String,
        text: String,
        expiresAtSeconds: ULong?
    ): NdrPairwiseSendResult {
        val result = manager.`sendText`(recipientPubkeyHex, text, expiresAtSeconds)
        return NdrPairwiseSendResult(
            innerEventId = result.innerEventId,
            outerEventId = result.outerEventId
        )
    }

    override fun getOurPubkeyHex(): String = manager.`getOurPubkeyHex`()

    override fun getTotalSessions(): ULong = manager.`getTotalSessions`()

    override fun destroy() = manager.destroy()
}
