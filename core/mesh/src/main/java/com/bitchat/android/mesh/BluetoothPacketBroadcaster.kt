
package com.bitchat.android.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.util.Log
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.actor
import java.util.ArrayDeque

/**
 * Handles packet broadcasting to connected devices using actor pattern for serialization
 * 
 * In Bluetooth Low Energy (BLE):
 *
 * Peripheral (server):
 * Advertises.
 * Accepts connections.
 * Hosts a GATT server.
 * Remote devices read/write/subscribe to characteristics.
 *
 *  Central (client):
 * Scans.
 * Initiates connections.
 * Hosts a GATT client.
 * Reads/writes to the peripheral’s characteristics.
 */
class BluetoothPacketBroadcaster(
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val fragmentManager: FragmentManager?,
    private val myPeerID: String
) {
    
    companion object {
        private const val TAG = "BluetoothPacketBroadcaster"
        private const val MAX_PENDING_SENDS_PER_LINK = 256
        private const val MAX_PENDING_BYTES_PER_LINK = 1_048_576
        private const val SEND_RETRY_DELAY_MS = 15L
        private const val MAX_CALLBACK_RETRIES = 3
    }

    // Optional nickname resolver injected by higher layer (peerID -> nickname?)
    private var nicknameResolver: ((String) -> String?)? = null

    fun setNicknameResolver(resolver: (String) -> String?) {
        nicknameResolver = resolver
    }
    
    /**
     * Debug logging helper - can be easily removed/disabled for production
     */
    private fun logPacketRelay(
        typeName: String,
        senderPeerID: String,
        senderNick: String?,
        incomingPeer: String?,
        incomingAddr: String?,
        toPeer: String?,
        toDeviceAddress: String,
        ttl: UByte,
        packetVersion: UByte = 1u,
        routeInfo: String? = null
    ) {
        try {
            val fromNick = incomingPeer?.let { nicknameResolver?.invoke(it) }
            val toNick = toPeer?.let { nicknameResolver?.invoke(it) }
            val manager = com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
            // Always log outgoing for the actual transmission target
            manager.logOutgoing(
                packetType = typeName,
                toPeerID = toPeer,
                toNickname = toNick,
                toDeviceAddress = toDeviceAddress,
                previousHopPeerID = incomingPeer,
                packetVersion = packetVersion,
                routeInfo = routeInfo
            )
            // Keep the verbose relay message for human readability
            manager.logPacketRelayDetailed(
                packetType = typeName,
                senderPeerID = senderPeerID,
                senderNickname = senderNick,
                fromPeerID = incomingPeer,
                fromNickname = fromNick,
                fromDeviceAddress = incomingAddr,
                toPeerID = toPeer,
                toNickname = toNick,
                toDeviceAddress = toDeviceAddress,
                ttl = ttl,
                isRelay = true,
                packetVersion = packetVersion,
                routeInfo = routeInfo
            )
        } catch (_: Exception) { 
            // Silently ignore debug logging failures
        }
    }
    
    // Data class to hold broadcast request information
    private data class BroadcastRequest(
        val routed: RoutedPacket,
        val gattServer: BluetoothGattServer?,
        val characteristic: BluetoothGattCharacteristic?,
        val accepted: CompletableDeferred<Boolean>? = null
    )
    
    // Actor scope for the broadcaster
    private val broadcasterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fragmentingSender = FragmentingPacketSender(connectionScope, fragmentManager, TAG)

    private enum class SendDirection { CLIENT_WRITE, SERVER_NOTIFICATION }

    private data class SendKey(
        val deviceAddress: String,
        val linkID: String,
        val direction: SendDirection
    )

    private data class PendingSend(
        val data: ByteArray,
        val device: BluetoothDevice,
        val gatt: BluetoothGatt? = null,
        val gattServer: BluetoothGattServer? = null,
        val characteristic: BluetoothGattCharacteristic,
        var callbackFailures: Int = 0
    )

    private class LinkSendState {
        val pending = ArrayDeque<PendingSend>()
        var pendingBytes = 0
        var inFlight = false
        var retryScheduled = false
    }

    private val sendLock = Any()
    private val sendStates = mutableMapOf<SendKey, LinkSendState>()
    
    // SERIALIZATION: Actor to serialize all broadcast operations
    @OptIn(kotlinx.coroutines.ObsoleteCoroutinesApi::class)
    private val broadcasterActor = broadcasterScope.actor<BroadcastRequest>(
        capacity = 256
    ) {
        for (request in channel) {
            val accepted = try {
                broadcastSinglePacketInternal(
                    request.routed,
                    request.gattServer,
                    request.characteristic
                )
            } catch (e: Exception) {
                Log.w(TAG, "Broadcast request failed: ${e.message}")
                false
            }
            request.accepted?.complete(accepted)
        }
    }
    
    fun broadcastPacket(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        return fragmentingSender.send(routed, "BLE broadcast") { packet ->
            broadcastSinglePacket(packet, gattServer, characteristic)
            true
        }
    }

    fun cancelTransfer(transferId: String): Boolean {
        return fragmentingSender.cancelTransfer(transferId)
    }

    /**
     * Send a packet to a specific peer only, without broadcasting.
     * Returns true if a direct path was found and used.
     */
    fun sendPacketToPeer(
        routed: RoutedPacket,
        targetPeerID: String,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        if (!hasPeerConnection(targetPeerID)) return false
        return fragmentingSender.send(routed, "BLE peer ${targetPeerID.take(8)}") { packet ->
            sendSinglePacketToPeer(packet, targetPeerID, gattServer, characteristic)
        }
    }

    fun sendPacketToLink(
        routed: RoutedPacket,
        deviceAddress: String,
        linkID: String,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean = fragmentingSender.send(routed, "BLE link $deviceAddress") { packet ->
        val data = packet.packet.toBinaryData(
            padding = BLEPacketPaddingPolicy.shouldPadForBLE(packet.packet.type)
        ) ?: return@send false
        val currentLink = connectionTracker.getDeviceConnection(deviceAddress)
            ?.takeIf { it.linkID == linkID }
            ?: return@send false
        if (currentLink.isClient) {
            return@send writeToDeviceConn(currentLink, data)
        }
        val serverTarget = connectionTracker.getSubscribedDevices()
            .firstOrNull { it.address == deviceAddress }
            ?: return@send false
        notifyDevice(serverTarget, data, gattServer, characteristic)
    }

    private fun sendSinglePacketToPeer(
        routed: RoutedPacket,
        targetPeerID: String,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        val packet = routed.packet
        // iOS-compatible: Use selective padding policy for BLE
        val padForBLE = BLEPacketPaddingPolicy.shouldPadForBLE(packet.type)
        val data = packet.toBinaryData(padding = padForBLE) ?: return false
        val typeName = MessageType.fromValue(packet.type)?.name ?: packet.type.toString()
        val senderPeerID = routed.peerID ?: packet.senderID.toHexString()
        val incomingAddr = routed.relayAddress
        val incomingPeer = incomingAddr?.let { connectionTracker.addressPeerMap[it] }
        val senderNick = senderPeerID.let { pid -> nicknameResolver?.invoke(pid) }
        val route = packet.route
        val routeInfo = if (!route.isNullOrEmpty()) "routed: ${route.size} hops" else null

        // Prefer server-side subscriptions
        val serverTarget = connectionTracker.getSubscribedDevices()
            .firstOrNull { connectionTracker.addressPeerMap[it.address] == targetPeerID }
        if (serverTarget != null) {
            if (notifyDevice(serverTarget, data, gattServer, characteristic)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, serverTarget.address, packet.ttl, packet.version, routeInfo)
                return true
            }
        }

        // Then client connections
        val clientTarget = connectionTracker.getConnectedDevices().values
            .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == targetPeerID }
        if (clientTarget != null) {
            if (writeToDeviceConn(clientTarget, data)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, clientTarget.device.address, packet.ttl, packet.version, routeInfo)
                return true
            }
        }

        return false
    }

    
    /**
     * Public entry point for broadcasting - submits request to actor for serialization
     */
    fun broadcastSinglePacket(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        // Submit broadcast request to actor for serialized processing
        broadcasterScope.launch {
            try {
                broadcasterActor.send(BroadcastRequest(routed, gattServer, characteristic))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send broadcast request to actor: ${e.message}")
                // Fallback to direct processing if actor fails
                broadcastSinglePacketInternal(routed, gattServer, characteristic)
            }
        }
    }

    /**
     * Serializes a small control packet with normal BLE traffic and waits for the platform write
     * API to accept at least one notification/write.
     */
    suspend fun broadcastControlPacketAndAwaitAcceptance(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        return try {
            broadcasterActor.send(
                BroadcastRequest(routed, gattServer, characteristic, accepted)
            )
            accepted.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to queue control packet: ${e.message}")
            broadcastSinglePacketInternal(routed, gattServer, characteristic)
        }
    }

    /**
     * Targeted send to a specific peer (by peerID) if directly connected.
     * Returns true if sent to at least one matching connection.
     */
    fun sendToPeer(
        targetPeerID: String,
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        if (!hasPeerConnection(targetPeerID)) return false
        return fragmentingSender.send(routed, "BLE peer ${targetPeerID.take(8)}") { packet ->
            sendSinglePacketToPeer(packet, targetPeerID, gattServer, characteristic)
        }
    }

    private fun hasPeerConnection(targetPeerID: String): Boolean {
        val hasServerTarget = connectionTracker.getSubscribedDevices()
            .any { connectionTracker.addressPeerMap[it.address] == targetPeerID }
        if (hasServerTarget) return true

        return connectionTracker.getConnectedDevices().values
            .any { connectionTracker.addressPeerMap[it.device.address] == targetPeerID }
    }
    
    /**
     * Internal broadcast implementation - runs in serialized actor context
     */
    private suspend fun broadcastSinglePacketInternal(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        val packet = routed.packet
        // iOS-compatible: Use selective padding policy for BLE
        val padForBLE = BLEPacketPaddingPolicy.shouldPadForBLE(packet.type)
        val data = packet.toBinaryData(padding = padForBLE) ?: return false
        val typeName = MessageType.fromValue(packet.type)?.name ?: packet.type.toString()
        val senderPeerID = routed.peerID ?: packet.senderID.toHexString()
        val incomingAddr = routed.relayAddress
        val incomingPeer = incomingAddr?.let { connectionTracker.addressPeerMap[it] }
        val senderNick = senderPeerID.let { pid -> nicknameResolver?.invoke(pid) }
        val route = packet.route
        val routeInfo = if (!route.isNullOrEmpty()) "routed: ${route.size} hops" else null

        // Source Routing for Originating Packets
        // If we are the sender and a source route is defined, we must send ONLY to the first hop.
        if (packet.senderID.toHexString() == myPeerID && !packet.route.isNullOrEmpty()) {
            val firstHop = packet.route!![0].toHexString()

            var sent = false

            // Try to find first hop in server connections (subscribedDevices)
            val serverTarget = connectionTracker.getSubscribedDevices()
                .firstOrNull { connectionTracker.addressPeerMap[it.address] == firstHop }

            if (serverTarget != null) {
                if (notifyDevice(serverTarget, data, gattServer, characteristic)) {
                    val toPeer = connectionTracker.addressPeerMap[serverTarget.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, serverTarget.address, packet.ttl, packet.version, routeInfo)
                    sent = true
                }
            }

            // Try to find first hop in client connections if not sent yet
            if (!sent) {
                val clientTarget = connectionTracker.getConnectedDevices().values
                    .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == firstHop }
                
                if (clientTarget != null) {
                    if (writeToDeviceConn(clientTarget, data)) {
                        val toPeer = connectionTracker.addressPeerMap[clientTarget.device.address]
                        logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, clientTarget.device.address, packet.ttl, packet.version, routeInfo)
                        sent = true
                    }
                }
            }

            if (sent) return true

            Log.d(TAG, "Source Routing: First hop $firstHop not connected. Falling back to standard broadcast logic.")
        }
        
        if (packet.recipientID != SpecialRecipients.BROADCAST) {
            val recipientID = packet.recipientID?.toHexString() ?: ""

            // Try to find the recipient in server connections (subscribedDevices)
            val targetDevice = connectionTracker.getSubscribedDevices()
                .firstOrNull { connectionTracker.addressPeerMap[it.address] == recipientID }
            
            // If found, send directly
            if (targetDevice != null) {
                if (notifyDevice(targetDevice, data, gattServer, characteristic)) {
                    val toPeer = connectionTracker.addressPeerMap[targetDevice.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, targetDevice.address, packet.ttl, packet.version, routeInfo)
                    return true
                }
            }

            // Try to find the recipient in client connections (connectedDevices)
            val targetDeviceConn = connectionTracker.getConnectedDevices().values
                .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == recipientID }
            
            // If found, send directly
            if (targetDeviceConn != null) {
                if (writeToDeviceConn(targetDeviceConn, data)) {
                    val toPeer = connectionTracker.addressPeerMap[targetDeviceConn.device.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, targetDeviceConn.device.address, packet.ttl, packet.version, routeInfo)
                    return true
                }
            }
        }

        // Else, continue with broadcasting to all devices
        val subscribedDevices = connectionTracker.getSubscribedDevices()
        val connectedDevices = connectionTracker.getConnectedDevices()

        val senderID = packet.senderID.toHexString()
        var accepted = false

        // Send to server connections (devices connected to our GATT server)
        subscribedDevices.forEach { device ->
            if (device.address == routed.relayAddress) {
                return@forEach
            }
            if (connectionTracker.addressPeerMap[device.address] == senderID) {
                return@forEach
            }
            val sent = notifyDevice(device, data, gattServer, characteristic)
            if (sent) {
                accepted = true
                val toPeer = connectionTracker.addressPeerMap[device.address]
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, device.address, packet.ttl, packet.version, routeInfo)
            }
        }
        
        // Send to client connections (GATT servers we are connected to)
        connectedDevices.values.forEach { deviceConn ->
            if (deviceConn.isClient && deviceConn.gatt != null && deviceConn.characteristic != null) {
                if (deviceConn.device.address == routed.relayAddress) {
                    return@forEach
                }
                if (connectionTracker.addressPeerMap[deviceConn.device.address] == senderID) {
                    return@forEach
                }
                val sent = writeToDeviceConn(deviceConn, data)
                if (sent) {
                    accepted = true
                    val toPeer = connectionTracker.addressPeerMap[deviceConn.device.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, deviceConn.device.address, packet.ttl, packet.version, routeInfo)
                }
            }
        }
        return accepted
    }
    
    /**
     * Send data to a single device (server->client)
     */
    private fun notifyDevice(
        device: BluetoothDevice, 
        data: ByteArray,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        val server = gattServer ?: return false
        val char = characteristic ?: return false
        val linkID = connectionTracker.getDeviceConnection(device.address)
            ?.takeIf { !it.isClient }
            ?.linkID
            ?: return false
        return enqueueSend(
            SendKey(device.address, linkID, SendDirection.SERVER_NOTIFICATION),
            PendingSend(data.copyOf(), device, gattServer = server, characteristic = char)
        )
    }

    /**
     * Send data to a single device (client->server)
     */
    private fun writeToDeviceConn(
        deviceConn: BluetoothConnectionTracker.DeviceConnection, 
        data: ByteArray
    ): Boolean {
        val gatt = deviceConn.gatt ?: return false
        val char = deviceConn.characteristic ?: return false
        return enqueueSend(
            SendKey(deviceConn.device.address, deviceConn.linkID, SendDirection.CLIENT_WRITE),
            PendingSend(data.copyOf(), deviceConn.device, gatt = gatt, characteristic = char)
        )
    }

    /**
     * Android permits only one outstanding GATT operation per link. Queueing here mirrors the
     * readiness-driven iOS transport and prevents later voice frames from overwriting an operation
     * that the controller has not completed yet.
     */
    private fun enqueueSend(key: SendKey, request: PendingSend): Boolean {
        val startNow = synchronized(sendLock) {
            val state = sendStates.getOrPut(key, ::LinkSendState)
            if (
                state.pending.size >= MAX_PENDING_SENDS_PER_LINK ||
                state.pendingBytes + request.data.size > MAX_PENDING_BYTES_PER_LINK
            ) {
                Log.w(TAG, "BLE send queue full for ${key.direction}; rejecting ${request.data.size} bytes")
                return false
            }
            state.pending.addLast(request)
            state.pendingBytes += request.data.size
            if (!state.inFlight && !state.retryScheduled) {
                state.inFlight = true
                true
            } else {
                false
            }
        }
        if (startNow) startHead(key)
        return true
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "ObsoleteSdkInt")
    private fun startHead(key: SendKey) {
        val request = synchronized(sendLock) { sendStates[key]?.pending?.peekFirst() } ?: return
        val accepted = try {
            when (key.direction) {
                SendDirection.CLIENT_WRITE -> {
                    val gatt = request.gatt
                    if (gatt == null) {
                        false
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(
                            request.characteristic,
                            request.data,
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        request.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        request.characteristic.value = request.data
                        gatt.writeCharacteristic(request.characteristic)
                    }
                }
                SendDirection.SERVER_NOTIFICATION -> {
                    val server = request.gattServer
                    if (server == null) {
                        false
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        server.notifyCharacteristicChanged(
                            request.device,
                            request.characteristic,
                            false,
                            request.data
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        request.characteristic.value = request.data
                        server.notifyCharacteristicChanged(request.device, request.characteristic, false)
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "BLE ${key.direction} failed to start: ${error.message}")
            false
        }
        if (!accepted) rejectStart(key)
    }

    private fun rejectStart(key: SendKey): Boolean {
        val schedule = synchronized(sendLock) {
            val state = sendStates[key] ?: return false
            state.inFlight = false
            if (state.retryScheduled || state.pending.isEmpty()) false else {
                state.retryScheduled = true
                true
            }
        }
        if (schedule) {
            connectionScope.launch {
                delay(SEND_RETRY_DELAY_MS)
                val retry = synchronized(sendLock) {
                    val state = sendStates[key] ?: return@synchronized false
                    state.retryScheduled = false
                    if (!state.inFlight && state.pending.isNotEmpty()) {
                        state.inFlight = true
                        true
                    } else false
                }
                if (retry) startHead(key)
            }
        }
        return false
    }

    fun onGattClientWriteComplete(deviceAddress: String, linkID: String, status: Int) {
        completeSend(SendKey(deviceAddress, linkID, SendDirection.CLIENT_WRITE), status)
    }

    fun onGattServerNotificationComplete(deviceAddress: String, linkID: String?, status: Int) {
        if (linkID == null) return
        completeSend(SendKey(deviceAddress, linkID, SendDirection.SERVER_NOTIFICATION), status)
    }

    private fun completeSend(key: SendKey, status: Int) {
        var retry = false
        val startNext = synchronized(sendLock) {
            val state = sendStates[key] ?: return
            val head = state.pending.peekFirst() ?: run {
                sendStates.remove(key)
                return
            }
            state.inFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS && head.callbackFailures < MAX_CALLBACK_RETRIES) {
                head.callbackFailures++
                retry = true
                false
            } else {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "BLE ${key.direction} failed with status $status after retries")
                }
                state.pending.removeFirst()
                state.pendingBytes -= head.data.size
                if (state.pending.isEmpty()) {
                    sendStates.remove(key)
                    false
                } else {
                    state.inFlight = true
                    true
                }
            }
        }
        if (retry) rejectStart(key) else if (startNext) startHead(key)
    }

    fun onLinkDisconnected(deviceAddress: String, linkID: String?) {
        synchronized(sendLock) {
            sendStates.keys.removeAll { key ->
                key.deviceAddress == deviceAddress && (linkID == null || key.linkID == linkID)
            }
        }
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Broadcaster Debug Info ===")
            appendLine("Broadcaster Scope Active: ${broadcasterScope.isActive}")
            appendLine("Actor Channel Closed: ${broadcasterActor.isClosedForSend}")
            appendLine("Connection Scope Active: ${connectionScope.isActive}")
        }
    }
    
    /**
     * Shutdown the broadcaster actor gracefully
     */
    fun shutdown() {
        synchronized(sendLock) { sendStates.clear() }
        // Close the actor gracefully
        broadcasterActor.close()

        // Cancel the broadcaster scope
        broadcasterScope.cancel()
    }
} 
