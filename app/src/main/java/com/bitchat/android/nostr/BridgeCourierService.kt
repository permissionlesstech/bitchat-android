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

/** Parks opaque courier envelopes on default Nostr relays using the iOS kind-1401 contract. */
class BridgeCourierService(
    context: Context,
    private val encryptionService: EncryptionService,
    private val onEnvelope: (CourierEnvelope) -> Unit
) {
    companion object {
        private const val KIND = 1401
        private const val MAX_ENCODED_BYTES = 20 * 1024
        private const val TAG_REFRESH_INTERVAL_MS = 60 * 60 * 1000L
    }

    private val relayManager = NostrRelayManager.getInstance(context.applicationContext)
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
        val localKey = encryptionService.getStaticPublicKey() ?: ByteArray(0)
        if (localKey.size == 32) {
            val day = CourierEnvelope.epochDay(System.currentTimeMillis())
            if (subscribedDay == day) return
            if (subscribedDay != null) relayManager.unsubscribe(subscriptionID)
            val tags = listOf(day - 1u, day, day + 1u).map {
                CourierEnvelope.recipientTag(localKey, it).toHex()
            }
            val filter = NostrFilter.Builder()
                .kinds(KIND)
                .since(System.currentTimeMillis() - CourierEnvelope.MAX_LIFETIME_MS)
                .limit(100)
                .tag("x", *tags.toTypedArray())
                .build()
            relayManager.subscribe(
                filter = filter,
                id = subscriptionID,
                targetRelayUrls = NostrRelayManager.defaultRelays(),
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
        if (!relayManager.hasConnectedRelay(NostrRelayManager.defaultRelays())) return false
        val sealed = try { encryptionService.sealCourierPayload(typedPayload, recipientNoiseKey) } catch (_: Exception) { return false }
        val now = System.currentTimeMillis()
        val envelope = CourierEnvelope(
            recipientTag = CourierEnvelope.recipientTag(recipientNoiseKey, CourierEnvelope.epochDay(now)),
            expiry = (now + CourierEnvelope.MAX_LIFETIME_MS).toULong(),
            ciphertext = sealed,
            copies = 1u
        )
        val encoded = envelope.encode() ?: return false
        if (encoded.size > MAX_ENCODED_BYTES) return false
        val identity = try { NostrIdentity.generate() } catch (_: Exception) { return false }
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
        return relayManager.sendEvent(event, NostrRelayManager.defaultRelays(), onAccepted = onAccepted)
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
        val now = System.currentTimeMillis()
        if (envelope.expiry.toLong() <= now || envelope.expiry.toLong() > now + CourierEnvelope.MAX_LIFETIME_MS + 60 * 60 * 1000L) return
        val eventTag = event.tags.firstOrNull { it.size > 1 && it[0] == "x" }?.get(1) ?: return
        val expiration = event.tags.firstOrNull { it.size > 1 && it[0] == "expiration" }?.get(1)?.toULongOrNull() ?: return
        if (eventTag != envelope.recipientTag.toHex() || expiration != envelope.expiry / 1000u) return
        val localKey = encryptionService.getStaticPublicKey() ?: return
        if (!envelope.matchesRecipient(localKey, now)) return
        onEnvelope(envelope)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
