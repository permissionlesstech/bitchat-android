package com.bitchat.android.services.bridge

import android.content.Context
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.model.NostrCarrierPacket
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.nostr.*
import com.bitchat.android.service.MeshServiceHolder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal object GatewayEventPolicy {
    fun inspect(carrier: NostrCarrierPacket, nowSeconds: Long): NostrEvent? {
        if (!Regex("[0123456789bcdefghjkmnpqrstuvwxyz]{1,12}").matches(carrier.geohash)) return null
        val event = carrier.event() ?: return null
        if (event.kind != NostrKind.EPHEMERAL_EVENT ||
            event.tags.none { it.size >= 2 && it[0] == "g" && it[1] == carrier.geohash } ||
            nowSeconds - event.createdAt.toLong() !in -900L..900L) return null
        return event
    }
}

/** Opt-in internet sharing for signed public geohash events (carrier directions 1 and 2). */
object MeshGatewayService {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val generation = AtomicLong()
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private var context: Context? = null
    private val radioEvents = BoundedIdSet(512)
    private val published = BoundedIdSet(512)
    private val delivered = BoundedIdSet(512)
    private val inboundTimes = ArrayDeque<Long>()
    private val perPeer = linkedMapOf<String, ArrayDeque<Long>>()
    private val downlinkTimes = ArrayDeque<Long>()
    private val pending = linkedMapOf<String, Pair<NostrCarrierPacket, Long?>>()
    private var drain: Job? = null
    private val mesh get() = MeshServiceHolder.unifiedMeshService
    private val relays get() = context?.let(NostrRelayManager::getInstance)

    @Synchronized
    fun initialize(application: Context) {
        if (context != null) return
        context = application.applicationContext
        _enabled.value = application.getSharedPreferences("mesh_gateway", Context.MODE_PRIVATE).getBoolean("enabled", false)
        PeerCapabilities.setGatewayEnabled(_enabled.value)
    }

    fun setEnabled(enabled: Boolean) {
        generation.incrementAndGet()
        _enabled.value = enabled
        context?.getSharedPreferences("mesh_gateway", Context.MODE_PRIVATE)?.edit()?.putBoolean("enabled", enabled)?.apply()
        PeerCapabilities.setGatewayEnabled(enabled)
        if (!enabled) scope.launch { pending.clear(); drain?.cancel(); drain = null }
        mesh?.sendBroadcastAnnounce()
    }

    suspend fun wipe() {
        setEnabled(false)
        withContext(dispatcher) {
            pending.clear()
            drain?.cancel()
            drain = null
            radioEvents.clear()
            published.clear()
            delivered.clear()
            inboundTimes.clear()
            perPeer.clear()
            downlinkTimes.clear()
            check(context?.getSharedPreferences("mesh_gateway", Context.MODE_PRIVATE)?.edit()?.clear()?.commit() != false)
        }
    }

    private fun permit(): () -> Boolean {
        val captured = generation.get()
        val enabledAtSend = _enabled.value
        return { enabledAtSend && _enabled.value && generation.get() == captured }
    }

    fun uplinkIfOffline(event: NostrEvent, cell: String, liveToken: Long?) {
        val current = mesh ?: return
        if (liveToken != null && !LiveLocationPrivacyGate.accepts(liveToken)) return
        if (relays?.hasConnectedRelay(relays?.getRelaysForGeohash(cell).orEmpty()) == true) return
        val gateway = current.getPeerNicknames().keys.firstOrNull { peer ->
            current.getPeerInfo(peer)?.let { it.isConnected && it.hasVerifiedAnnouncement &&
                it.capabilities?.contains(PeerCapabilities.GATEWAY) == true } == true
        } ?: return
        val carrier = NostrCarrierPacket.fromEvent(NostrCarrierPacket.Direction.TO_GATEWAY, cell, event) ?: return
        scope.launch {
            if (liveToken != null && !LiveLocationPrivacyGate.accepts(liveToken)) return@launch
            radioEvents.add(event.id)
            current.sendNostrCarrier(carrier.encode(), gateway)
        }
    }

    fun handleCarrier(carrier: NostrCarrierPacket, peerID: String, directedToUs: Boolean) {
        val application = context ?: return
        val allowed = permit()
        scope.launch {
            val event = GatewayEventPolicy.inspect(carrier, System.currentTimeMillis() / 1000) ?: return@launch
            if (carrier.direction == NostrCarrierPacket.Direction.TO_GATEWAY) {
                if (!directedToUs || !allowed() || published.contains(event.id)) return@launch
                if (mesh?.getPeerInfo(peerID)?.hasVerifiedAnnouncement != true || !allowInbound(peerID)) return@launch
                if (!event.isValidSignature() || !allowed()) return@launch
                radioEvents.add(event.id)
                published.add(event.id)
                relays?.sendEventToGeohash(event, carrier.geohash, publicationAllowed = allowed)
                NostrBackgroundRuntime.receiveGatewayEvent(event, carrier.geohash)
            } else if (carrier.direction == NostrCarrierPacket.Direction.FROM_GATEWAY) {
                if (directedToUs || delivered.contains(event.id) || !allowInbound(peerID)) return@launch
                val channelManager = LocationChannelManager.getInstance(application)
                val selected = (channelManager.selectedChannel.value as? ChannelID.Location)?.channel ?: return@launch
                if (selected.geohash != carrier.geohash || !channelManager.canUseSelectedLocationChannel(selected)) return@launch
                if (!event.isValidSignature()) return@launch
                radioEvents.add(event.id)
                delivered.add(event.id)
                NostrBackgroundRuntime.receiveGatewayEvent(event, carrier.geohash)
            }
        }
    }

    fun rebroadcastRelayEvent(event: NostrEvent, cell: String, liveToken: Long?) {
        val allowed = permit()
        if (!allowed()) return
        val carrier = NostrCarrierPacket.fromEvent(NostrCarrierPacket.Direction.FROM_GATEWAY, cell, event) ?: return
        scope.launch {
            if (!allowed() || (liveToken != null && !LiveLocationPrivacyGate.accepts(liveToken)) ||
                GatewayEventPolicy.inspect(carrier, System.currentTimeMillis() / 1000) == null ||
                radioEvents.contains(event.id) || delivered.contains(event.id) || pending.containsKey(event.id)) return@launch
            if (!event.isValidSignature() || pending.size >= 100) return@launch
            pending[event.id] = carrier to liveToken
            if (drain?.isActive != true) drain = scope.launch {
                while (pending.isNotEmpty() && allowed()) {
                    delay(kotlin.random.Random.nextLong(200, 1501))
                    val now = System.currentTimeMillis()
                    while (downlinkTimes.firstOrNull()?.let { now - it >= 60_000 } == true) downlinkTimes.removeFirst()
                    if (downlinkTimes.size >= 30) { delay(1000); continue }
                    val next = pending.entries.first()
                    pending.remove(next.key)
                    val (outgoing, token) = next.value
                    if (!allowed() || (token != null && !LiveLocationPrivacyGate.accepts(token)) || radioEvents.contains(next.key) ||
                        GatewayEventPolicy.inspect(outgoing, now / 1000) == null) continue
                    mesh?.sendNostrCarrier(outgoing.encode())
                    delivered.add(next.key)
                    downlinkTimes.addLast(now)
                }
            }
        }
    }

    private fun allowInbound(peerID: String): Boolean {
        val now = System.currentTimeMillis()
        while (inboundTimes.firstOrNull()?.let { now - it >= 60_000 } == true) inboundTimes.removeFirst()
        perPeer.entries.removeAll { it.value.lastOrNull()?.let { now - it >= 60_000 } != false }
        if (perPeer.size >= 200 && peerID !in perPeer) return false
        val times = perPeer.getOrPut(peerID) { ArrayDeque() }
        while (times.firstOrNull()?.let { now - it >= 60_000 } == true) times.removeFirst()
        if (inboundTimes.size >= 20 || times.size >= 5) return false
        inboundTimes.addLast(now)
        times.addLast(now)
        return true
    }
}
