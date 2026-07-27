package com.bitchat.android.nostr

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.bitchat.android.model.NdrFeatureGate
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class NdrNostrService(
    private val relayManager: NdrRelayManager,
    private val runtimeFactory: NdrSessionManagerFactory,
    private val storageDirectoryProvider: () -> String,
    private val deviceIdProvider: () -> String,
    private val storageResetter: () -> Unit = {
        val storageDirectory = java.io.File(storageDirectoryProvider())
        if (storageDirectory.exists() && !storageDirectory.deleteRecursively()) {
            throw java.io.IOException("Failed to delete ${storageDirectory.absolutePath}")
        }
    },
    private val deviceIdResetter: () -> Unit = {},
    private val inviteOwnerResolver: (String) -> String? = Companion::resolveInviteOwnerPubkeyHex
) {

    companion object {
        private const val TAG = "NdrNostrService"
        private const val COMPACT_INVITE_URL_ROOT = "https://b"
        private const val NDR_APP_KEYS_KIND = 37368
        private const val NDR_APP_KEYS_TYPE = "app_keys_roster_snapshot"
        private const val NDR_MESSAGE_KIND = 1060
        private const val MAX_BUFFERED_DECRYPTED_MESSAGES = 128

        @Volatile
        private var INSTANCE: NdrNostrService? = null

        fun getInstance(context: Context): NdrNostrService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: create(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun create(context: Context): NdrNostrService {
            val storageDirectory = context.filesDir.resolve("ndr")
            val preferences = context.getSharedPreferences("bitchat_ndr", Context.MODE_PRIVATE)
            val relayManager = object : NdrRelayManager {
                override fun subscribe(filter: NostrFilter, id: String, handler: (NostrEvent) -> Unit) {
                    NostrRelayManager.getInstance(context).subscribe(filter, id, handler)
                }

                override fun unsubscribe(id: String) {
                    NostrRelayManager.getInstance(context).unsubscribe(id)
                }

                override fun sendEvent(event: NostrEvent) {
                    NostrRelayManager.getInstance(context).sendEvent(event)
                }
            }

            val runtimeFactory = object : NdrSessionManagerFactory {
                override fun newWithStoragePath(
                    ourPubkeyHex: String,
                    ourIdentityPrivkeyHex: String,
                    deviceId: String,
                    storagePath: String,
                    ownerPubkeyHex: String?
                ): NdrSessionManager {
                    return UniffiNdrSessionManager(
                        uniffi.ndr_ffi.SessionManagerHandle.newWithStoragePath(
                            ourPubkeyHex,
                            ourIdentityPrivkeyHex,
                            deviceId,
                            storagePath,
                            ownerPubkeyHex
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
                deviceIdProvider = {
                    preferences.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
                        preferences.edit { putString("device_id", it) }
                    }
                },
                storageResetter = {
                    if (storageDirectory.exists() && !storageDirectory.deleteRecursively()) {
                        throw java.io.IOException("Failed to delete ${storageDirectory.absolutePath}")
                    }
                },
                deviceIdResetter = {
                    removeDeviceIdSynchronously(preferences)
                }
            )
        }

        @SuppressLint("UseKtx")
        private fun removeDeviceIdSynchronously(preferences: SharedPreferences) {
            // KTX's commit=true overload discards Editor.commit()'s result, but
            // panic reset must fail closed unless the device-id wipe is durable.
            check(preferences.edit().remove("device_id").commit()) {
                "Failed to clear NDR device id"
            }
        }

        private fun resolveInviteOwnerPubkeyHex(payload: String): String? {
            return try {
                val invite = if (payload.startsWith("{")) {
                    uniffi.ndr_ffi.InviteHandle.fromEventJson(payload)
                } else {
                    uniffi.ndr_ffi.InviteHandle.fromUrl(payload)
                }
                invite.use { it.`getOwnerPubkeyHex`().lowercase() }
            } catch (_: Throwable) {
                null
            }
        }
    }

    @Volatile
    var onDecryptedMessage: ((NdrDecryptedMessage) -> Unit)? = null
        @Synchronized set(value) {
            field = value
            if (value != null && NdrFeatureGate.isEnabled()) {
                while (bufferedDecryptedMessages.isNotEmpty()) {
                    value(bufferedDecryptedMessages.removeFirst())
                }
            }
        }

    @Volatile
    var onOutOfBandPayloadsReady: ((ownerPubkeyHex: String, payloads: List<String>) -> Unit)? = null

    @Volatile
    private var sessionManager: NdrSessionManager? = null

    @Volatile
    private var configuredForPubkeyHex: String? = null

    @Volatile
    private var cachedInviteEventJson: String? = null

    @Volatile
    private var panicResetBlocked = false

    private val activeSubIds = linkedSetOf<String>()
    private val appKeysSubscriptionIdByOwner = linkedMapOf<String, String>()
    private val appKeysOwnerBySubscriptionId = linkedMapOf<String, String>()
    private val durableAppKeysOwners = linkedSetOf<String>()
    private val pendingInvitesByOwner = linkedMapOf<String, PendingOutOfBandInvite>()
    private val bufferedDecryptedMessages = ArrayDeque<NdrDecryptedMessage>()

    @get:Synchronized
    val isConfigured: Boolean
        get() = NdrFeatureGate.isEnabled() && sessionManager != null

    @Synchronized
    fun currentInviteEventJson(): String? =
        cachedInviteEventJson.takeIf { NdrFeatureGate.isEnabled() }

    @Synchronized
    fun configureIfNeeded(identity: NostrIdentity) {
        if (!NdrFeatureGate.isEnabled()) {
            teardownLocked()
            return
        }
        if (panicResetBlocked) {
            Log.e(TAG, "Refusing to configure NDR after an incomplete panic wipe")
            return
        }
        val pubkeyHex = identity.publicKeyHex.lowercase()
        if (configuredForPubkeyHex == pubkeyHex && sessionManager != null) {
            return
        }

        teardownLocked()
        configuredForPubkeyHex = pubkeyHex

        try {
            val runtime = runtimeFactory.newWithStoragePath(
                ourPubkeyHex = pubkeyHex,
                ourIdentityPrivkeyHex = identity.privateKeyHex,
                deviceId = deviceIdProvider(),
                // The FFI storage adapter uses fixed filenames. Namespace them
                // by account owner so an identity switch cannot load another
                // account's ratchet database.
                storagePath = java.io.File(
                    storageDirectoryProvider(),
                    pubkeyHex
                ).absolutePath,
                ownerPubkeyHex = null
            )
            runtime.init()
            sessionManager = runtime
            restoreDurableAppKeysSubscriptionsLocked(runtime)
            drainAndApplyPubSubEventsLocked()
        } catch (_: Throwable) {
            Log.e(TAG, "Failed to configure NDR")
            teardownLocked()
        }
    }

    @Synchronized
    fun hasActiveSession(peerPubkeyHex: String): Boolean {
        if (!NdrFeatureGate.isEnabled()) return false
        val runtime = sessionManager ?: return false
        return try {
            runtime.getActiveSessionState(peerPubkeyHex.lowercase()) != null
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun activeSessionStateJson(peerPubkeyHex: String): String? {
        if (!NdrFeatureGate.isEnabled()) return null
        val runtime = sessionManager ?: return null
        return try {
            runtime.getActiveSessionState(peerPubkeyHex.lowercase())
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun sendIfPossible(text: String, peerPubkeyHex: String): Boolean {
        if (!NdrFeatureGate.isEnabled()) return false
        val runtime = sessionManager ?: return false
        if (!hasActiveSession(peerPubkeyHex)) return false
        return try {
            runtime.sendText(peerPubkeyHex.lowercase(), text, null)
            drainAndApplyPubSubEventsLocked()
            true
        } catch (_: Throwable) {
            Log.d(TAG, "NDR send failed")
            drainAndApplyPubSubEventsLocked()
            false
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
        val runtime = sessionManager ?: return NdrOutOfBandProcessResult(emptyList())
        val trimmedPayload = eventJson.trim()
        val expectedPeer = expectedPeerPubkeyHex
            ?.lowercase()
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            ?: return NdrOutOfBandProcessResult(emptyList())
        if (!NdrInputPolicy.isWithinEncodedEventLimit(trimmedPayload)) {
            return NdrOutOfBandProcessResult(emptyList())
        }
        val inboundInvite = parseOutOfBandInvite(trimmedPayload)
        val parsedEvent = NostrEvent.fromJsonString(trimmedPayload)
        if (parsedEvent != null && !NdrInputPolicy.hasBoundedTags(parsedEvent)) {
            return NdrOutOfBandProcessResult(emptyList())
        }
        var acceptResult: NdrAcceptInviteResult? = null
        var processingSucceeded = false

        // Invite payloads carry an owner identity we can bind to the authenticated
        // favorite. Other OOB responses may be gift wraps whose outer pubkey is
        // intentionally ephemeral, so they must not be compared to the owner key.
        val claimedPeer = inboundInvite?.ownerPubkeyHex
        if (claimedPeer != null && claimedPeer != expectedPeer) {
            Log.w(TAG, "Rejecting OOB event with an authenticated-owner mismatch")
            return NdrOutOfBandProcessResult(emptyList())
        }
        if (inboundInvite != null) {
            if (pendingInvitesByOwner.containsKey(expectedPeer)) {
                return NdrOutOfBandProcessResult(
                    outboundPayloads = emptyList(),
                    sessionLookupPubkeyHex = expectedPeer
                )
            }
        }

        try {
            when {
                inboundInvite?.transport == OutOfBandInviteTransport.EVENT_JSON -> {
                    acceptResult = runtime.acceptInviteFromEventJson(trimmedPayload, expectedPeer)
                    pendingInvitesByOwner.remove(expectedPeer)
                    processingSucceeded = true
                }
                inboundInvite?.transport == OutOfBandInviteTransport.URL -> {
                    acceptResult = runtime.acceptInviteFromUrl(trimmedPayload, expectedPeer)
                    pendingInvitesByOwner.remove(expectedPeer)
                    processingSucceeded = true
                }
                parsedEvent?.kind == NostrKind.GIFT_WRAP -> {
                    runtime.processOutOfBandResponse(trimmedPayload, expectedPeer)
                    processingSucceeded = true
                }
                else -> {
                    Log.w(TAG, "Rejecting non-handshake OOB payload")
                }
            }
        } catch (t: NdrSessionNotReadyException) {
            if (inboundInvite != null) {
                pendingInvitesByOwner[expectedPeer] = PendingOutOfBandInvite(
                    payload = trimmedPayload,
                    transport = inboundInvite.transport
                )
                Log.d(TAG, "Retaining invite until its signed device roster arrives")
            } else {
                Log.d(TAG, "OOB session is not ready")
            }
        } catch (_: Throwable) {
            Log.d(TAG, "Ignoring invalid OOB event")
        }

        if (processingSucceeded && hasActiveSession(expectedPeer)) {
            ensureDurableAppKeysSubscriptionLocked(expectedPeer, runtime)
        }
        val outOfBandPublishes =
            drainAndApplyPubSubEventsLocked(collectOutOfBandPublishes = true)
        val sessionLookupPubkeyHex = acceptResult?.ownerPubkeyHex?.lowercase()
            ?: expectedPeer

        if (inboundInvite != null &&
            inboundInvite.transport == OutOfBandInviteTransport.EVENT_JSON &&
            outOfBandPublishes.isEmpty() &&
            hasActiveSession(sessionLookupPubkeyHex)
        ) {
            preferredInviteOobPayload()?.let {
                return NdrOutOfBandProcessResult(
                    outboundPayloads = outOfBandPublishes + it,
                    sessionLookupPubkeyHex = sessionLookupPubkeyHex
                )
            }
        }

        return NdrOutOfBandProcessResult(
            outboundPayloads = outOfBandPublishes,
            sessionLookupPubkeyHex = sessionLookupPubkeyHex
        )
    }

    @Synchronized
    fun processInboundRelayEvent(event: NostrEvent) {
        if (!NdrFeatureGate.isEnabled()) return
        val runtime = sessionManager ?: return
        if (event.kind != NDR_MESSAGE_KIND && event.kind != NDR_APP_KEYS_KIND) return
        if (!NdrInputPolicy.hasBoundedTags(event)) return
        val eventJson = event.toJsonString()
        if (!NdrInputPolicy.isWithinEncodedEventLimit(eventJson)) return

        try {
            runtime.processEvent(eventJson)
        } catch (_: Throwable) {
            Log.d(TAG, "Ignoring invalid NDR relay event")
            drainAndApplyPubSubEventsLocked()
            return
        }

        retryPendingInviteForRelayEventLocked(runtime, event)
        drainAndApplyPubSubEventsLocked()
    }

    private fun retryPendingInviteForRelayEventLocked(
        runtime: NdrSessionManager,
        event: NostrEvent
    ) {
        if (event.kind != NDR_APP_KEYS_KIND ||
            event.tags.none { tag ->
                tag.size >= 2 && tag[0] == "type" && tag[1] == NDR_APP_KEYS_TYPE
            }
        ) return
        val ownerPubkeyHex = event.pubkey.lowercase()
        val pending = pendingInvitesByOwner[ownerPubkeyHex] ?: return

        try {
            when (pending.transport) {
                OutOfBandInviteTransport.EVENT_JSON ->
                    runtime.acceptInviteFromEventJson(pending.payload, ownerPubkeyHex)
                OutOfBandInviteTransport.URL ->
                    runtime.acceptInviteFromUrl(pending.payload, ownerPubkeyHex)
            }
            pendingInvitesByOwner.remove(ownerPubkeyHex)
            if (hasActiveSession(ownerPubkeyHex)) {
                ensureDurableAppKeysSubscriptionLocked(ownerPubkeyHex, runtime)
            }
        } catch (_: NdrSessionNotReadyException) {
            return
        } catch (_: Throwable) {
            pendingInvitesByOwner.remove(ownerPubkeyHex)
            Log.w(TAG, "Dropping retained invite after roster validation failed")
            return
        }

        val outboundPayloads =
            drainAndApplyPubSubEventsLocked(collectOutOfBandPublishes = true)
        if (outboundPayloads.isNotEmpty()) {
            if (NdrFeatureGate.isEnabled()) {
                onOutOfBandPayloadsReady?.invoke(ownerPubkeyHex, outboundPayloads)
            }
        }
    }

    @Synchronized
    private fun drainAndApplyPubSubEventsLocked(
        collectOutOfBandPublishes: Boolean = false
    ): List<String> {
        val runtime = sessionManager ?: return emptyList()
        val outOfBandPublishes = mutableListOf<String>()

        val events = try {
            runtime.drainEvents()
        } catch (_: Throwable) {
            Log.e(TAG, "Failed to drain NDR events")
            return emptyList()
        }

        events.forEach { event ->
            applyPubSubEventLocked(
                event = event,
                collectOutOfBandPublish = if (collectOutOfBandPublishes) {
                    { value -> outOfBandPublishes.add(value) }
                } else {
                    null
                }
            )
        }

        return outOfBandPublishes
    }

    @Synchronized
    private fun applyPubSubEventLocked(
        event: NdrPubSubEvent,
        collectOutOfBandPublish: ((String) -> Unit)?
    ) {
        when (event.kind) {
            "subscribe" -> {
                val subid = event.subid ?: return
                val filterJson = event.filterJson ?: return
                val filter = try {
                    parseFilterJson(filterJson)
                } catch (_: Throwable) {
                    Log.w(TAG, "Ignoring malformed NDR relay filter")
                    return
                }
                if (shouldIgnoreNdrSubscription(filter)) return
                if (!activeSubIds.add(subid)) {
                    return
                }
                val appKeysOwner = appKeysSubscriptionOwner(filter)
                if (appKeysOwner != null) {
                    if (appKeysSubscriptionIdByOwner.containsKey(appKeysOwner)) {
                        activeSubIds.remove(subid)
                        return
                    }
                    appKeysSubscriptionIdByOwner[appKeysOwner] = subid
                    appKeysOwnerBySubscriptionId[subid] = appKeysOwner
                }
                try {
                    relayManager.subscribe(filter, subid) { inbound ->
                        processInboundRelayEvent(inbound)
                    }
                } catch (_: Throwable) {
                    activeSubIds.remove(subid)
                    if (appKeysOwner != null) {
                        appKeysSubscriptionIdByOwner.remove(appKeysOwner)
                        appKeysOwnerBySubscriptionId.remove(subid)
                        durableAppKeysOwners.remove(appKeysOwner)
                    }
                    Log.w(TAG, "Failed to install NDR relay filter")
                }
            }

            "unsubscribe" -> {
                val subid = event.subid ?: return
                appKeysOwnerBySubscriptionId.remove(subid)?.let { owner ->
                    if (appKeysSubscriptionIdByOwner[owner] == subid) {
                        appKeysSubscriptionIdByOwner.remove(owner)
                        durableAppKeysOwners.remove(owner)
                    }
                }
                if (activeSubIds.remove(subid)) {
                    relayManager.unsubscribe(subid)
                }
            }

            "publish_signed" -> {
                val eventJson = event.eventJson ?: return
                val nostrEvent = NostrEvent.fromJsonString(eventJson) ?: return
                when {
                    isDoubleRatchetInviteEvent(nostrEvent) -> {
                        cachedInviteEventJson = eventJson
                        collectOutOfBandPublish?.invoke(eventJson)
                    }

                    nostrEvent.kind == NostrKind.GIFT_WRAP -> {
                        collectOutOfBandPublish?.invoke(eventJson)
                    }

                    else -> relayManager.sendEvent(nostrEvent)
                }
            }

            "decrypted_message" -> {
                if (!NdrFeatureGate.isEnabled()) return
                val content = event.content ?: return
                val senderPubkeyHex = event.senderPubkeyHex ?: return
                if (!NdrInputPolicy.isWithinEncodedEventLimit(content) ||
                    !NdrInputPolicy.isPubkeyHex(senderPubkeyHex) ||
                    event.senderDevicePubkeyHex?.let(NdrInputPolicy::isPubkeyHex) == false ||
                    event.conversationOwnerPubkeyHex?.let(NdrInputPolicy::isPubkeyHex) == false ||
                    event.eventId?.let(NdrInputPolicy::isEventIdHex) == false
                ) return
                val message = NdrDecryptedMessage(
                    content = content,
                    senderPubkeyHex = senderPubkeyHex.lowercase(),
                    senderDevicePubkeyHex = event.senderDevicePubkeyHex?.lowercase(),
                    conversationOwnerPubkeyHex = event.conversationOwnerPubkeyHex?.lowercase(),
                    eventId = event.eventId?.lowercase()
                )
                val callback = onDecryptedMessage
                if (callback != null) {
                    callback(message)
                } else {
                    if (bufferedDecryptedMessages.size >= MAX_BUFFERED_DECRYPTED_MESSAGES) {
                        bufferedDecryptedMessages.removeFirst()
                    }
                    bufferedDecryptedMessages.addLast(message)
                }
            }
        }
    }

    @Synchronized
    private fun teardownLocked() {
        activeSubIds.forEach { subId ->
            runCatching { relayManager.unsubscribe(subId) }
                .onFailure { Log.w(TAG, "Failed to unsubscribe NDR relay filter") }
        }
        activeSubIds.clear()
        appKeysSubscriptionIdByOwner.clear()
        appKeysOwnerBySubscriptionId.clear()
        durableAppKeysOwners.clear()
        pendingInvitesByOwner.clear()
        bufferedDecryptedMessages.clear()
        cachedInviteEventJson = null
        configuredForPubkeyHex = null
        val runtime = sessionManager
        sessionManager = null
        runCatching { runtime?.destroy() }
            .onFailure { Log.w(TAG, "Failed to destroy NDR runtime") }
    }

    @Synchronized
    fun resetForPanic(): Boolean {
        onDecryptedMessage = null
        onOutOfBandPayloadsReady = null
        teardownLocked()
        val storageCleared = runCatching(storageResetter)
            .onFailure { Log.w(TAG, "Failed to delete NDR storage") }
            .isSuccess
        val deviceIdCleared = runCatching(deviceIdResetter)
            .onFailure { Log.w(TAG, "Failed to reset NDR device id") }
            .isSuccess
        panicResetBlocked = !(storageCleared && deviceIdCleared)
        return !panicResetBlocked
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
        val ownerPubkeyHex: String,
        val transport: OutOfBandInviteTransport
    )

    private data class PendingOutOfBandInvite(
        val payload: String,
        val transport: OutOfBandInviteTransport
    )

    private fun parseOutOfBandInvite(payload: String): ParsedOutOfBandInvite? {
        if (payload.isBlank()) return null

        if (payload.startsWith("{")) {
            val event = NostrEvent.fromJsonString(payload) ?: return null
            if (!isDoubleRatchetInviteEvent(event)) return null
            val ownerPubkeyHex = inviteOwnerResolver(payload)?.lowercase() ?: return null
            return ParsedOutOfBandInvite(
                ownerPubkeyHex = ownerPubkeyHex,
                transport = OutOfBandInviteTransport.EVENT_JSON
            )
        }

        val ownerPubkeyHex = inviteOwnerResolver(payload)?.lowercase() ?: return null
        return ParsedOutOfBandInvite(
            ownerPubkeyHex = ownerPubkeyHex,
            transport = OutOfBandInviteTransport.URL
        )
    }

    private fun preferredInviteOobPayload(): String? {
        val inviteEventJson = cachedInviteEventJson ?: return null
        return compactInviteUrl(inviteEventJson) ?: inviteEventJson
    }

    private fun compactInviteUrl(eventJson: String): String? {
        return try {
            val invite = uniffi.ndr_ffi.InviteHandle.fromEventJson(eventJson)
            invite.use { it.`toUrl`(COMPACT_INVITE_URL_ROOT) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun shouldIgnoreNdrSubscription(filter: NostrFilter): Boolean {
        val kinds = filter.kinds.orEmpty()
        // BitChat exchanges every invite/response bootstrap payload over an
        // authenticated local transport, never through public relay discovery.
        return NostrKind.GIFT_WRAP in kinds || 30078 in kinds
    }

    private fun restoreDurableAppKeysSubscriptionsLocked(runtime: NdrSessionManager) {
        runtime.knownPeerOwnerPubkeys().forEach { owner ->
            ensureDurableAppKeysSubscriptionLocked(owner, runtime)
        }
    }

    private fun ensureDurableAppKeysSubscriptionLocked(
        ownerPubkeyHex: String,
        runtime: NdrSessionManager
    ) {
        val owner = ownerPubkeyHex.lowercase()
            .takeIf(NdrInputPolicy::isPubkeyHex)
            ?: return
        if (owner in durableAppKeysOwners) return
        try {
            // setupUser emits both AppKeys and invite-discovery filters. The
            // former stays live for device revocation; policy drops the latter.
            runtime.setupUser(owner)
            durableAppKeysOwners.add(owner)
        } catch (_: Throwable) {
            Log.w(TAG, "Failed to retain NDR AppKeys updates")
        }
    }

    private fun appKeysSubscriptionOwner(filter: NostrFilter): String? {
        if (filter.kinds != listOf(NDR_APP_KEYS_KIND)) return null
        val owner = filter.authors?.singleOrNull()?.lowercase() ?: return null
        return owner.takeIf(NdrInputPolicy::isPubkeyHex)
    }

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

private class UniffiNdrSessionManager(
    private val handle: uniffi.ndr_ffi.SessionManagerHandle
) : NdrSessionManager {
    override fun init() {
        handle.`init`()
    }

    override fun knownPeerOwnerPubkeys(): List<String> =
        handle.`knownPeerOwnerPubkeys`()

    override fun setupUser(userPubkeyHex: String) {
        handle.`setupUser`(userPubkeyHex)
    }

    override fun acceptInviteFromEventJson(
        eventJson: String,
        ownerPubkeyHintHex: String?
    ): NdrAcceptInviteResult {
        val result = try {
            handle.`acceptInviteFromEventJson`(eventJson, ownerPubkeyHintHex)
        } catch (t: uniffi.ndr_ffi.NdrException.SessionNotReady) {
            throw NdrSessionNotReadyException(t.message, t)
        }
        return NdrAcceptInviteResult(
            ownerPubkeyHex = result.ownerPubkeyHex,
            inviterDevicePubkeyHex = result.inviterDevicePubkeyHex,
            deviceId = result.deviceId,
            createdNewSession = result.createdNewSession
        )
    }

    override fun acceptInviteFromUrl(
        inviteUrl: String,
        ownerPubkeyHintHex: String?
    ): NdrAcceptInviteResult {
        val result = try {
            handle.`acceptInviteFromUrl`(inviteUrl, ownerPubkeyHintHex)
        } catch (t: uniffi.ndr_ffi.NdrException.SessionNotReady) {
            throw NdrSessionNotReadyException(t.message, t)
        }
        return NdrAcceptInviteResult(
            ownerPubkeyHex = result.ownerPubkeyHex,
            inviterDevicePubkeyHex = result.inviterDevicePubkeyHex,
            deviceId = result.deviceId,
            createdNewSession = result.createdNewSession
        )
    }

    override fun processEvent(eventJson: String) {
        handle.`processEvent`(eventJson)
    }

    override fun processOutOfBandResponse(
        eventJson: String,
        expectedOwnerPubkeyHex: String
    ) {
        handle.`processOutOfBandResponse`(eventJson, expectedOwnerPubkeyHex)
    }

    override fun drainEvents(): List<NdrPubSubEvent> {
        return handle.`drainEvents`().map {
            NdrPubSubEvent(
                kind = it.kind,
                subid = it.subid,
                filterJson = it.filterJson,
                eventJson = it.eventJson,
                senderPubkeyHex = it.senderPubkeyHex,
                senderDevicePubkeyHex = it.senderDevicePubkeyHex,
                conversationOwnerPubkeyHex = it.conversationOwnerPubkeyHex,
                content = it.content,
                eventId = it.eventId
            )
        }
    }

    override fun getActiveSessionState(peerPubkeyHex: String): String? {
        return handle.`getActiveSessionState`(peerPubkeyHex)
    }

    override fun sendText(recipientPubkeyHex: String, text: String, expiresAtSeconds: ULong?): List<String> {
        return handle.`sendText`(recipientPubkeyHex, text, expiresAtSeconds)
    }

    override fun getOurPubkeyHex(): String = handle.`getOurPubkeyHex`()

    override fun getTotalSessions(): ULong = handle.`getTotalSessions`()

    override fun destroy() {
        handle.destroy()
    }
}
