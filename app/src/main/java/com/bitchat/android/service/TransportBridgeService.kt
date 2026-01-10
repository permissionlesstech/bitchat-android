package com.bitchat.android.service

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
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
         * Send a packet to a specific peer via this transport (optional).
         */
        fun sendToPeer(peerID: String, packet: BitchatPacket) { }
    }

    private val transports = ConcurrentHashMap<String, TransportLayer>()

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

        // Log.v(TAG, "Bridging packet type ${packet.packet.type} from $sourceId to ${targets.keys}")
        
        targets.forEach { (id, layer) ->
            try {
                layer.send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bridge packet to $id: ${e.message}")
            }
        }
    }

    /**
     * Send a packet to a specific peer across all other transports.
     */
    fun sendToPeer(sourceId: String, peerID: String, packet: BitchatPacket) {
        val targets = transports.filterKeys { it != sourceId }
        if (targets.isEmpty()) return

        targets.forEach { (id, layer) ->
            try {
                layer.sendToPeer(peerID, packet)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bridge unicast packet to $id: ${e.message}")
            }
        }
    }
}
