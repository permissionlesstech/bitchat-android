package com.bitchat.android.service

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Central bridge for routing packets between different transport layers
 * (e.g., Bluetooth LE <-> Wi-Fi Aware).
 * 
 * Allows a packet received on one transport to be seamlessly relayed
 * to all other active transports, effectively bridging separate meshes.
 */
object TransportBridgeService {
    private const val TAG = "TransportBridgeService"
    private const val MAX_SEEN_PACKETS = 4096
    private const val SEEN_PACKET_TTL_MS = 5 * 60 * 1000L

    /**
     * Interface that any transport layer (BLE, WiFi, Tor, etc.) must implement
     * to receive bridged packets.
     */
    interface TransportLayer {
        /**
         * Send a packet out via this transport.
         */
        fun send(packet: RoutedPacket)

        /**
         * Send a packet and report whether at least one concrete transport write was accepted.
         *
         * Receipt retries use this path so a registered-but-disconnected transport cannot be
         * mistaken for a successful send.
         */
        suspend fun sendAndReport(packet: RoutedPacket): Boolean = false

        /**
         * Send a packet to a specific peer via this transport (optional).
         */
        fun sendToPeer(peerID: String, packet: BitchatPacket) { }
    }

    private val transports = ConcurrentHashMap<String, TransportLayer>()
    private val seenPackets = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(MAX_SEEN_PACKETS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > MAX_SEEN_PACKETS
            }
        }
    )
    private data class PreparedForward(
        val packet: BitchatPacket,
        val seenKey: String,
        val reservedAtMs: Long
    )

    /**
     * Register a transport layer to receive bridged packets.
     * @param id Unique identifier (e.g., "BLE", "WIFI")
     * @param layer The transport implementation
     */
    fun register(id: String, layer: TransportLayer) {
        Log.i(TAG, "Registering transport layer: $id")
        transports[id] = layer
    }

    /**
     * Unregister a transport layer.
     */
    fun unregister(id: String) {
        Log.i(TAG, "Unregistering transport layer: $id")
        transports.remove(id)
    }

    /**
     * Broadcast a packet from a specific source transport to ALL other registered transports.
     * 
     * @param sourceId The ID of the transport initiating the broadcast (e.g., "BLE").
     *                 The packet will NOT be sent back to this source.
     * @param packet The packet to bridge.
     */
    fun broadcast(sourceId: String, packet: RoutedPacket) {
        val targets = transports.filterKeys { it != sourceId }
        if (targets.isEmpty()) return
        val prepared = prepareForwardedPacket("broadcast", packet.packet) ?: return
        val forwardedPacket = prepared.packet
        // Prepared private-media fragments must remain the admitted plan when
        // crossing transports, but relay TTL still has to advance on every
        // hop. TTL is excluded from the signature and does not affect size.
        val forwarded = packet.copy(
            packet = forwardedPacket,
            preparedPackets = packet.preparedPackets?.map { prepared ->
                prepared.copy(ttl = forwardedPacket.ttl)
            }
        )

        // Log.v(TAG, "Bridging packet type ${packet.packet.type} from $sourceId to ${targets.keys}")
        
        targets.forEach { (id, layer) ->
            try {
                layer.send(forwarded)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bridge packet to $id: ${e.message}")
            }
        }
    }

    /**
     * Broadcasts through every other active transport and reports whether any concrete write was
     * accepted. Failed attempts release their duplicate-suppression reservation so a later retry
     * can use a transport that reconnects during the retry window.
     */
    suspend fun broadcastAndReport(sourceId: String, packet: RoutedPacket): Boolean {
        val targets = transports.filterKeys { it != sourceId }
        if (targets.isEmpty()) return false
        val kind = "broadcast"
        val prepared = prepareForwardedPacket(kind, packet.packet) ?: return false
        val forwardedPacket = prepared.packet
        val forwarded = packet.copy(
            packet = forwardedPacket,
            preparedPackets = packet.preparedPackets?.map { prepared ->
                prepared.copy(ttl = forwardedPacket.ttl)
            }
        )

        var accepted = false
        targets.forEach { (id, layer) ->
            val targetAccepted = try {
                layer.sendAndReport(forwarded)
            } catch (e: CancellationException) {
                releaseSeenPacket(prepared)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bridge packet to $id: ${e.message}")
                false
            }
            accepted = targetAccepted || accepted
        }
        if (!accepted) {
            releaseSeenPacket(prepared)
        }
        return accepted
    }

    /**
     * Send a packet to a specific peer across all other transports.
     */
    fun sendToPeer(sourceId: String, peerID: String, packet: BitchatPacket) {
        val targets = transports.filterKeys { it != sourceId }
        if (targets.isEmpty()) return
        val forwardedPacket =
            prepareForwardedPacket("peer:$peerID", packet)?.packet ?: return

        targets.forEach { (id, layer) ->
            try {
                layer.sendToPeer(peerID, forwardedPacket)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bridge unicast packet to $id: ${e.message}")
            }
        }
    }

    /**
     * Send a locally originated packet to every active transport without applying relay TTL
     * handling. This is used for neighbor-only packets such as REQUEST_SYNC whose TTL is
     * intentionally zero on the first radio hop.
     */
    fun broadcastFromLocal(packet: RoutedPacket) {
        val targets = transports.toMap()
        if (targets.isEmpty()) return

        targets.forEach { (id, layer) ->
            try {
                layer.send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send local packet to $id: ${e.message}")
            }
        }
    }

    /**
     * Send a locally originated packet directly to a peer on every active transport.
     */
    fun sendToPeerFromLocal(peerID: String, packet: BitchatPacket) {
        val targets = transports.toMap()
        if (targets.isEmpty()) return

        targets.forEach { (id, layer) ->
            try {
                layer.sendToPeer(peerID, packet)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send local peer packet to $id: ${e.message}")
            }
        }
    }

    private fun prepareForwardedPacket(kind: String, packet: BitchatPacket): PreparedForward? {
        if (packet.ttl == 0u.toUByte()) {
            Log.d(TAG, "Dropping bridged packet type ${packet.type}: TTL expired")
            return null
        }

        val key = "$kind:${logicalPacketId(packet)}"
        val now = System.currentTimeMillis()
        synchronized(seenPackets) {
            pruneSeen(now)
            val previous = seenPackets[key]
            if (previous != null && now - previous < SEEN_PACKET_TTL_MS) {
                Log.d(TAG, "Dropping duplicate bridged packet type ${packet.type}")
                return null
            }
            seenPackets[key] = now
        }

        return PreparedForward(
            packet = packet.copy(ttl = (packet.ttl - 1u).toUByte()),
            seenKey = key,
            reservedAtMs = now
        )
    }

    private fun releaseSeenPacket(prepared: PreparedForward) {
        synchronized(seenPackets) {
            if (seenPackets[prepared.seenKey] == prepared.reservedAtMs) {
                seenPackets.remove(prepared.seenKey)
            }
        }
    }

    private fun pruneSeen(now: Long) {
        val iterator = seenPackets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > SEEN_PACKET_TTL_MS) {
                iterator.remove()
            }
        }
    }

    private fun logicalPacketId(packet: BitchatPacket): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(packet.type.toByte())
        digest.update(packet.senderID)
        packet.recipientID?.let { digest.update(it) }
        digest.update(packet.timestamp.toString().toByteArray(Charsets.UTF_8))
        digest.update(packet.payload)
        packet.route?.forEach { digest.update(it) }
        packet.signature?.let { digest.update(it) }
        return digest.digest().toHexString()
    }
}
