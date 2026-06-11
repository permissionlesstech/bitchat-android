package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class NdrNostrService(
    private val relayManager: NdrRelayManager,
    private val runtimeFactory: NdrSessionManagerFactory,
    private val storageDirectoryProvider: () -> String,
    private val deviceIdProvider: () -> String
) {

    companion object {
        private const val TAG = "NdrNostrService"
        private const val COMPACT_INVITE_URL_ROOT = "https://b"

        @Volatile
        private var INSTANCE: NdrNostrService? = null

        fun getInstance(context: Context): NdrNostrService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: create(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun create(context: Context): NdrNostrService {
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
                    context.filesDir.resolve("ndr").apply { mkdirs() }.absolutePath
                },
                deviceIdProvider = {
                    val prefs = context.getSharedPreferences("bitchat_ndr", Context.MODE_PRIVATE)
                    prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
                        prefs.edit().putString("device_id", it).apply()
                    }
                }
            )
        }
    }

    @Volatile
    var onDecryptedMessage: ((NdrDecryptedMessage) -> Unit)? = null

    @Volatile
    private var sessionManager: NdrSessionManager? = null

    @Volatile
    private var configuredForPubkeyHex: String? = null

    @Volatile
    private var cachedInviteEventJson: String? = null

    private val activeSubIds = linkedSetOf<String>()

    val isConfigured: Boolean
        get() = sessionManager != null

    fun currentInviteEventJson(): String? = cachedInviteEventJson

    @Synchronized
    fun configureIfNeeded(identity: NostrIdentity) {
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
                storagePath = storageDirectoryProvider(),
                ownerPubkeyHex = null
            )
            runtime.init()
            sessionManager = runtime
            drainAndApplyPubSubEventsLocked()
            Log.d(TAG, "Configured NDR for ${pubkeyHex.take(8)}...")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to configure NDR: ${t.message}")
            teardownLocked()
        }
    }

    fun hasActiveSession(peerPubkeyHex: String): Boolean {
        val runtime = sessionManager ?: return false
        return try {
            runtime.getActiveSessionState(peerPubkeyHex.lowercase()) != null
        } catch (_: Throwable) {
            false
        }
    }

    fun activeSessionStateJson(peerPubkeyHex: String): String? {
        val runtime = sessionManager ?: return null
        return try {
            runtime.getActiveSessionState(peerPubkeyHex.lowercase())
        } catch (_: Throwable) {
            null
        }
    }

    fun sendIfPossible(text: String, peerPubkeyHex: String): Boolean {
        val runtime = sessionManager ?: return false
        if (!hasActiveSession(peerPubkeyHex)) return false
        return try {
            val outboundEventIds = runtime.sendText(peerPubkeyHex.lowercase(), text, null)
            synchronized(this) {
                drainAndApplyPubSubEventsLocked()
            }
            if (outboundEventIds.isEmpty()) {
                Log.d(TAG, "NDR send queued no relay publish for ${peerPubkeyHex.take(8)}...")
            }
            true
        } catch (t: Throwable) {
            Log.d(TAG, "NDR send failed: ${t.message}")
            synchronized(this) {
                drainAndApplyPubSubEventsLocked()
            }
            false
        }
    }

    fun processOutOfBandEventJson(
        eventJson: String,
        expectedPeerPubkeyHex: String? = null
    ): NdrOutOfBandProcessResult {
        val runtime = sessionManager ?: return NdrOutOfBandProcessResult(emptyList())
        val trimmedPayload = eventJson.trim()
        val expectedPeer = expectedPeerPubkeyHex
            ?.lowercase()
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
        val inboundInvite = parseOutOfBandInvite(trimmedPayload)
        val parsedEventPubkeyHex = NostrEvent.fromJsonString(trimmedPayload)?.pubkey?.lowercase()
        var acceptResult: NdrAcceptInviteResult? = null

        try {
            when {
                inboundInvite?.transport == OutOfBandInviteTransport.EVENT_JSON -> {
                    acceptResult = runtime.acceptInviteFromEventJson(trimmedPayload, expectedPeer)
                }
                inboundInvite?.transport == OutOfBandInviteTransport.URL || !trimmedPayload.startsWith("{") -> {
                    acceptResult = runtime.acceptInviteFromUrl(trimmedPayload, expectedPeer)
                }
                else -> {
                    runtime.processEvent(trimmedPayload)
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Ignoring OOB event: ${t.message}")
        }

        val outOfBandPublishes = synchronized(this) {
            drainAndApplyPubSubEventsLocked(collectOutOfBandPublishes = true)
        }
        val sessionLookupPubkeyHex = acceptResult?.ownerPubkeyHex?.lowercase()
            ?: expectedPeer?.takeIf { hasActiveSession(it) }
            ?: parsedEventPubkeyHex
            ?: inboundInvite?.senderPubkeyHex

        if (inboundInvite != null &&
            inboundInvite.transport == OutOfBandInviteTransport.EVENT_JSON &&
            outOfBandPublishes.isEmpty() &&
            sessionLookupPubkeyHex != null &&
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

    fun processInboundRelayEvent(event: NostrEvent) {
        val runtime = sessionManager ?: return

        try {
            runtime.processEvent(event.toJsonString())
        } catch (t: Throwable) {
            Log.d(TAG, "Ignoring relay event ${event.id.take(8)}...: ${t.message}")
        }

        synchronized(this) {
            drainAndApplyPubSubEventsLocked()
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
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to drain NDR events: ${t.message}")
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
                if (shouldIgnoreNdrSubscription(filterJson)) {
                    return
                }
                if (!activeSubIds.add(subid)) {
                    return
                }
                val filter = parseFilterJson(filterJson)
                relayManager.subscribe(filter, subid) { inbound ->
                    processInboundRelayEvent(inbound)
                }
            }

            "unsubscribe" -> {
                val subid = event.subid ?: return
                relayManager.unsubscribe(subid)
                activeSubIds.remove(subid)
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
                val content = event.content ?: return
                val senderPubkeyHex = event.senderPubkeyHex ?: return
                onDecryptedMessage?.invoke(
                    NdrDecryptedMessage(
                        content = content,
                        senderPubkeyHex = senderPubkeyHex.lowercase(),
                        eventId = event.eventId,
                        innerEventJson = content.takeIf { it.trimStart().startsWith("{") }
                    )
                )
            }
        }
    }

    @Synchronized
    private fun teardownLocked() {
        activeSubIds.forEach { relayManager.unsubscribe(it) }
        activeSubIds.clear()
        cachedInviteEventJson = null
        configuredForPubkeyHex = null
        sessionManager?.destroy()
        sessionManager = null
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
        val senderPubkeyHex: String,
        val transport: OutOfBandInviteTransport
    )

    fun outOfBandSenderPubkeyHex(payload: String): String? {
        return parseOutOfBandInvite(payload.trim())?.senderPubkeyHex
    }

    private fun parseOutOfBandInvite(payload: String): ParsedOutOfBandInvite? {
        if (payload.isBlank()) return null

        if (payload.startsWith("{")) {
            val event = NostrEvent.fromJsonString(payload) ?: return null
            if (!isDoubleRatchetInviteEvent(event)) return null
            return ParsedOutOfBandInvite(
                senderPubkeyHex = event.pubkey.lowercase(),
                transport = OutOfBandInviteTransport.EVENT_JSON
            )
        }

        return try {
            val invite = uniffi.ndr_ffi.InviteHandle.fromUrl(payload)
            invite.use {
                ParsedOutOfBandInvite(
                    senderPubkeyHex = it.`getInviterPubkeyHex`().lowercase(),
                    transport = OutOfBandInviteTransport.URL
                )
            }
        } catch (_: Throwable) {
            null
        }
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

    private fun shouldIgnoreNdrSubscription(filterJson: String): Boolean {
        return try {
            val root = JsonParser.parseString(filterJson).asJsonObject
            val kinds = root.getAsJsonArray("kinds")?.mapNotNull { it.asInt } ?: emptyList()
            if (NostrKind.GIFT_WRAP in kinds) {
                return true
            }
            if (30078 !in kinds) {
                return false
            }
            val labelValues = root.getAsJsonArray("#l")?.mapNotNull { it.asString } ?: emptyList()
            labelValues.contains("double-ratchet/invites")
        } catch (_: Throwable) {
            false
        }
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

    override fun acceptInviteFromEventJson(
        eventJson: String,
        ownerPubkeyHintHex: String?
    ): NdrAcceptInviteResult {
        val result = handle.`acceptInviteFromEventJson`(eventJson, ownerPubkeyHintHex)
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
        val result = handle.`acceptInviteFromUrl`(inviteUrl, ownerPubkeyHintHex)
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

    override fun drainEvents(): List<NdrPubSubEvent> {
        return handle.`drainEvents`().map {
            NdrPubSubEvent(
                kind = it.kind,
                subid = it.subid,
                filterJson = it.filterJson,
                eventJson = it.eventJson,
                senderPubkeyHex = it.senderPubkeyHex,
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
