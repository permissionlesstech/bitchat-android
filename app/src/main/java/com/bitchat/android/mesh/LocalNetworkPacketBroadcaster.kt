package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles broadcasting packets over local network TCP connections
 */
class LocalNetworkPacketBroadcaster(
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "LocalNetworkPacketBroadcaster"
    }

    // Active transfers and connections
    private val activeTransfers = ConcurrentHashMap<String, Job>()
    private val socketMutex = Mutex()

    /**
     * Set nickname resolver for logging
     */
    fun setNicknameResolver(resolver: (String) -> String?) {
        // Not needed for local network
    }

    /**
     * Broadcast packet to all connected addresses
     */
    fun broadcastPacket(routed: RoutedPacket, addresses: List<String>) {
        if (addresses.isEmpty()) return

        val packetData = routed.packet.toBinaryData()
        if (packetData == null) {
            Log.w(TAG, "Failed to serialize packet for broadcast")
            return
        }

        Log.d(TAG, "Broadcasting packet to ${addresses.size} local network addresses")

        for (address in addresses) {
            sendPacketToAddress(routed.packet, address)
        }
    }

    /**
     * Send packet to specific address
     */
    fun sendPacketToAddress(packet: BitchatPacket, address: String) {
        val packetData = packet.toBinaryData()
        if (packetData == null) return

        scope.launch(Dispatchers.IO) {
            socketMutex.withLock {
                try {
                    val socket = Socket(address, 8989) // Connect to server port

                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                    // Write packet length
                    output.writeInt(packetData.size)
                    output.write(packetData)
                    output.flush()

                    Log.d(TAG, "Sent packet to $address, size: ${packetData.size} bytes")

                    socket.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending packet to address $address", e)
                }
            }
        }
    }

    /**
     * Send packet to existing socket
     */
    fun sendPacketToSocket(packet: BitchatPacket, socket: Socket): Boolean {
        if (socket.isClosed) return false

        val packetData = packet.toBinaryData()
        if (packetData == null) return false

        return try {
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            output.writeInt(packetData.size)
            output.write(packetData)
            output.flush()
            Log.d(TAG, "Sent packet via socket, size: ${packetData.size} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet via socket", e)
            false
        }
    }

    /**
     * Cancel specific transfer
     */
    fun cancelTransfer(transferId: String): Boolean {
        val job = activeTransfers.remove(transferId)
        job?.cancel()
        return job != null
    }

    /**
     * Cancel all transfers
     */
    fun cancelAllTransfers() {
        activeTransfers.values.forEach { it.cancel() }
        activeTransfers.clear()
    }
}
