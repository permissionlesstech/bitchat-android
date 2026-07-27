package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MeshDiagnosticsConstants
import com.bitchat.android.protocol.MeshPingPayload
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class MeshPingResult(val rttMillis: Long, val hopCount: Int)

internal class MeshPingManager(
    private val myPeerID: String,
    private val scope: CoroutineScope,
    private val send: (BitchatPacket) -> Unit,
) {
    companion object {
        /**
         * BLE and Wi-Fi Aware have separate manager instances, but a reply can return over either
         * transport. Pending probes therefore live at process scope and are namespaced by the
         * local identity.
         */
        private val pending = ConcurrentHashMap<String, Pending>()
    }

    private data class Pending(
        val peerID: String,
        val startedNanos: Long,
        val callback: (MeshPingResult?) -> Unit,
        val timeout: Job,
    )

    private val inboundByLink = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun ping(peerID: String, callback: (MeshPingResult?) -> Unit) {
        val payload = MeshPingPayload.create(MeshDiagnosticsConstants.TTL)
        val key = pendingKey(payload)
        val timeout = scope.launch {
            delay(MeshDiagnosticsConstants.TIMEOUT_MILLIS)
            pending.remove(key)?.callback?.invoke(null)
        }
        pending[key] = Pending(peerID, System.nanoTime(), callback, timeout)
        send(packet(MessageType.PING, peerID, payload))
    }

    fun handlePing(routed: RoutedPacket) {
        val packet = routed.packet
        val payload = MeshPingPayload.decode(packet.payload) ?: return
        val sender = packet.senderID.toHexString()
        val ingress = routed.ingressLinkID ?: routed.relayAddress ?: routed.peerID ?: return
        if (!consumeInboundBudget(ingress)) return
        send(packet(MessageType.PONG, sender, payload))
    }

    fun handlePong(routed: RoutedPacket) {
        val payload = MeshPingPayload.decode(routed.packet.payload) ?: return
        val key = pendingKey(payload)
        val candidate = pending[key] ?: return
        if (routed.packet.senderID.toHexString() != candidate.peerID) return
        if (!pending.remove(key, candidate)) return
        candidate.timeout.cancel()
        val elapsed = (System.nanoTime() - candidate.startedNanos) / 1_000_000
        candidate.callback(MeshPingResult(elapsed, payload.hopCount(routed.packet.ttl)))
    }

    private fun pendingKey(payload: MeshPingPayload): String =
        "$myPeerID:${payload.nonce.toHexString()}"

    private fun consumeInboundBudget(link: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = inboundByLink.computeIfAbsent(link) { ArrayDeque() }
        synchronized(timestamps) {
            while (timestamps.firstOrNull()?.let {
                    now - it >= MeshDiagnosticsConstants.INBOUND_RATE_WINDOW_MILLIS
                } == true
            ) {
                timestamps.removeFirst()
            }
            if (timestamps.size >= MeshDiagnosticsConstants.INBOUND_RATE_LIMIT) return false
            timestamps.addLast(now)
            return true
        }
    }

    private fun packet(type: MessageType, recipientPeerID: String, payload: MeshPingPayload) =
        BitchatPacket(
            version = 1u,
            type = type.value,
            senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
            recipientID = MeshPacketUtils.hexStringToByteArray(recipientPeerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload.encode(),
            signature = null,
            ttl = MeshDiagnosticsConstants.TTL,
        )
}
