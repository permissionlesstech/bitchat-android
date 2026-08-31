package com.bitchat.android.nostr

import android.content.Context
import android.util.Base64
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.CourierEnvelope
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Minimal relay surface used by the bridge courier.
 *
 * Keeping this adapter boundary small lets the kind-1401 contract be exercised with a
 * deterministic in-memory relay in unit tests, without opening a network connection.
 */
internal interface BridgeCourierRelay {
    fun subscribe(
        filter: NostrFilter,
        id: String,
        targetRelayUrls: List<String>,
        handler: (NostrEvent) -> Unit
    )

    fun unsubscribe(id: String)

    fun hasConnectedRelay(relayUrls: Collection<String>): Boolean

    fun sendEvent(
        event: NostrEvent,
        relayUrls: List<String>,
        onAccepted: () -> Unit
    ): Boolean
}

internal interface BridgeCourierCipher {
    fun staticPublicKey(): ByteArray?

    fun seal(payload: ByteArray, recipientNoiseKey: ByteArray): ByteArray
}

private class NostrBridgeCourierRelay(
    private val relayManager: NostrRelayManager
) : BridgeCourierRelay {
    override fun subscribe(
        filter: NostrFilter,
        id: String,
        targetRelayUrls: List<String>,
        handler: (NostrEvent) -> Unit
    ) {
        relayManager.subscribe(
            filter = filter,
            id = id,
            targetRelayUrls = targetRelayUrls,
            handler = handler
        )
    }

    override fun unsubscribe(id: String) {
        relayManager.unsubscribe(id)
    }

    override fun hasConnectedRelay(relayUrls: Collection<String>): Boolean =
        relayManager.hasConnectedRelay(relayUrls)

    override fun sendEvent(
        event: NostrEvent,
        relayUrls: List<String>,
        onAccepted: () -> Unit
    ): Boolean = relayManager.sendEvent(event, relayUrls, onAccepted = onAccepted)
}

private class EncryptionBridgeCourierCipher(
    private val encryptionService: EncryptionService
) : BridgeCourierCipher {
    override fun staticPublicKey(): ByteArray? = encryptionService.getStaticPublicKey()

    override fun seal(payload: ByteArray, recipientNoiseKey: ByteArray): ByteArray =
        encryptionService.sealCourierPayload(payload, recipientNoiseKey)
}

/** Parks opaque courier envelopes on default Nostr relays using the iOS kind-1401 contract. */
class BridgeCourierService internal constructor(
    private val cipher: BridgeCourierCipher,
    private val onEnvelope: (CourierEnvelope) -> Unit,
    private val relayManager: BridgeCourierRelay,
    private val relayUrls: List<String> = NostrRelayManager.defaultRelays(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val identityFactory: () -> NostrIdentity = NostrIdentity::generate
) {
    constructor(
        context: Context,
        encryptionService: EncryptionService,
        onEnvelope: (CourierEnvelope) -> Unit
    ) : this(
        cipher = EncryptionBridgeCourierCipher(encryptionService),
        onEnvelope = onEnvelope,
        relayManager = NostrBridgeCourierRelay(
            NostrRelayManager.getInstance(context.applicationContext)
        )
    )

    companion object {
        private const val KIND = 1401
        private const val MAX_ENCODED_BYTES = 20 * 1024
        private const val TAG_REFRESH_INTERVAL_MS = 60 * 60 * 1000L
    }

    private val seenEvents = Collections.synchronizedMap(
        object : LinkedHashMap<String, Unit>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 512
        }
    )
    private val subscriptionID = "bridge-courier-drops-${System.identityHashCode(this)}"
    @Volatile private var subscribedDay: UInt? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null
    val isStarted: Boolean get() = subscribedDay != null

    @Synchronized
    fun start() {
        val localKey = cipher.staticPublicKey() ?: ByteArray(0)
        if (localKey.size == 32) {
            val now = clock()
            val day = CourierEnvelope.epochDay(now)
            if (subscribedDay == day) return
            if (subscribedDay != null) relayManager.unsubscribe(subscriptionID)
            val tags = listOf(day - 1u, day, day + 1u).map {
                CourierEnvelope.recipientTag(localKey, it).toHex()
            }
            val filter = NostrFilter.Builder()
                .kinds(KIND)
                .since(now - CourierEnvelope.MAX_LIFETIME_MS)
                .limit(100)
                .tag("x", *tags.toTypedArray())
                .build()
            relayManager.subscribe(
                filter = filter,
                id = subscriptionID,
                targetRelayUrls = relayUrls,
                handler = ::handleEvent
            )
            subscribedDay = day
            if (refreshJob?.isActive != true) {
                refreshJob = scope.launch {
                    while (isActive) {
                        delay(TAG_REFRESH_INTERVAL_MS)
                        start()
                    }
                }
            }
        }
    }

    fun deposit(
        content: String,
        messageID: String,
        recipientNoiseKey: ByteArray,
        onAccepted: () -> Unit = {}
    ): Boolean {
        val privateMessage = com.bitchat.android.model.PrivateMessagePacket(messageID, content).encode() ?: return false
        val typed = com.bitchat.android.model.NoisePayload(
            com.bitchat.android.model.NoisePayloadType.PRIVATE_MESSAGE,
            privateMessage
        ).encode()
        return depositPayload(typed, recipientNoiseKey, onAccepted)
    }

    fun depositPayload(
        typedPayload: ByteArray,
        recipientNoiseKey: ByteArray,
        onAccepted: () -> Unit = {}
    ): Boolean {
        start()
        if (!relayManager.hasConnectedRelay(relayUrls)) return false
        val sealed = try { cipher.seal(typedPayload, recipientNoiseKey) } catch (_: Exception) { return false }
        val now = clock()
        val envelope = CourierEnvelope(
            recipientTag = CourierEnvelope.recipientTag(recipientNoiseKey, CourierEnvelope.epochDay(now)),
            expiry = (now + CourierEnvelope.MAX_LIFETIME_MS).toULong(),
            ciphertext = sealed,
            copies = 1u
        )
        val encoded = envelope.encode() ?: return false
        if (encoded.size > MAX_ENCODED_BYTES) return false
        val identity = try { identityFactory() } catch (_: Exception) { return false }
        val event = identity.signEvent(
            NostrEvent(
                pubkey = identity.publicKeyHex,
                createdAt = (now / 1000).toInt(),
                kind = KIND,
                tags = listOf(
                    listOf("x", envelope.recipientTag.toHex()),
                    listOf("expiration", (envelope.expiry / 1000u).toString())
                ),
                content = Base64.encodeToString(encoded, Base64.NO_WRAP)
            )
        )
        return relayManager.sendEvent(event, relayUrls, onAccepted)
    }

    @Synchronized
    fun stop() {
        relayManager.unsubscribe(subscriptionID)
        subscribedDay = null
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun handleEvent(event: NostrEvent) {
        if (event.kind != KIND || !event.isValidSignature()) return
        synchronized(seenEvents) { if (seenEvents.put(event.id, Unit) != null) return }
        if (event.content.length > ((MAX_ENCODED_BYTES + 2) / 3) * 4) return
        val encoded = try { Base64.decode(event.content, Base64.DEFAULT) } catch (_: Exception) { return }
        if (encoded.size > MAX_ENCODED_BYTES) return
        val envelope = CourierEnvelope.decode(encoded) ?: return
        val now = clock()
        if (envelope.expiry.toLong() <= now || envelope.expiry.toLong() > now + CourierEnvelope.MAX_LIFETIME_MS + 60 * 60 * 1000L) return
        val eventTag = event.tags.firstOrNull { it.size > 1 && it[0] == "x" }?.get(1) ?: return
        val expiration = event.tags.firstOrNull { it.size > 1 && it[0] == "expiration" }?.get(1)?.toULongOrNull() ?: return
        if (eventTag != envelope.recipientTag.toHex() || expiration != envelope.expiry / 1000u) return
        val localKey = cipher.staticPublicKey() ?: return
        if (!envelope.matchesRecipient(localKey, now)) return
        onEnvelope(envelope)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
