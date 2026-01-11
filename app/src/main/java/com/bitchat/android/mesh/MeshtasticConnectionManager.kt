package com.bitchat.android.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.bitchat.android.util.AppConstants
import org.meshtastic.proto.MeshProtos.MeshPacket
import org.meshtastic.proto.MeshProtos.Data
import org.meshtastic.proto.Portnums
import org.meshtastic.proto.MeshProtos.ToRadio
import org.meshtastic.proto.MeshProtos.FromRadio
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Manages connections to Meshtastic devices to use them as relay nodes.
 * Encapsulates Bitchat packets within Meshtastic PRIVATE_APP messages.
 */
class MeshtasticConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPacketReceived: (ByteArray) -> Unit
) {
    private val TAG = "MeshtasticManager"
    private var bluetoothGatt: BluetoothGatt? = null
    private var toRadioChar: BluetoothGattCharacteristic? = null
    
    // Meshtastic packet handling requires random IDs
    private val random = Random()
    
    // Channel for queuing outgoing packets
    private val sendChannel = Channel<ByteArray>(Channel.UNLIMITED)
    
    // Track connection state
    private var isConnected = false
    private var isConnecting = false

    init {
        // Start the sender loop
        scope.launch {
            for (packet in sendChannel) {
                internalSend(packet)
            }
        }
    }

    /**
     * Connect to a specific Meshtastic device
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (isConnected || isConnecting) return
        
        Log.i(TAG, "Connecting to Meshtastic device: ${device.address}")
        isConnecting = true
        
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT server")
                    bluetoothGatt = gatt
                    gatt.discoverServices()
                    isConnecting = false
                    isConnected = true
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT server")
                    isConnecting = false
                    isConnected = false
                    bluetoothGatt = null
                    toRadioChar = null
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered")
                    val service = gatt.getService(AppConstants.Mesh.Meshtastic.SERVICE_UUID)
                    if (service != null) {
                        toRadioChar = service.getCharacteristic(AppConstants.Mesh.Meshtastic.TO_RADIO_UUID)
                        
                        // Enable notifications on FromRadio
                        val fromRadioChar = service.getCharacteristic(AppConstants.Mesh.Meshtastic.FROM_RADIO_UUID)
                        if (fromRadioChar != null) {
                            gatt.setCharacteristicNotification(fromRadioChar, true)
                            val descriptor = fromRadioChar.getDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    } else {
                        Log.w(TAG, "Meshtastic service not found on device")
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                handleCharacteristicChanged(characteristic, value)
            }
            // For older API levels compatibility
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                handleCharacteristicChanged(characteristic, characteristic.value)
            }
        })
    }
    
    private fun handleCharacteristicChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == AppConstants.Mesh.Meshtastic.FROM_RADIO_UUID) {
            try {
                // Meshtastic BLE packets might be fragmented or just stream of bytes.
                // But usually FromRadio is a protobuf.
                // Assuming single-packet for now or that BLE layer handles reassembly if using MTU
                // Note: Meshtastic Protobuf over BLE is typically "ToRadio" / "FromRadio"
                // wrapped in a simple frame if it exceeds MTU, but for simplicity we try direct parse.
                try {
                    val fromRadio = FromRadio.parseFrom(value)
                    if (fromRadio.hasPacket()) {
                       handleMeshtasticPacket(fromRadio.packet)
                    }
                } catch (e: Exception) {
                    // It might be a partial packet or different format. 
                    // Logging for debug
                    // Log.v(TAG, "Failed to parse FromRadio directly: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing FromRadio packet", e)
            }
        }
    }
    
    private fun handleMeshtasticPacket(meshPacket: MeshPacket) {
        // We only care about decoded packets with our app port
        if (meshPacket.hasDecoded()) {
            val decoded = meshPacket.decoded
            if (decoded.portnumValue == AppConstants.Mesh.Meshtastic.PRIVATE_APP_PORT) {
                // Extract the Bitchat payload
                val payload = decoded.payload.toByteArray()
                Log.d(TAG, "Received encapsulated Bitchat packet of size: ${payload.size}")
                onPacketReceived(payload)
            }
        }
    }

    /**
     * Send a Bitchat packet through the connected Meshtastic device
     */
    fun sendPacket(data: ByteArray) {
        if (!isConnected) {
            return
        }
        scope.launch {
            sendChannel.send(data)
        }
    }
    
    @SuppressLint("MissingPermission")
    private suspend fun internalSend(dataBytes: ByteArray) {
        if (bluetoothGatt == null || toRadioChar == null) return

        try {
            // 1. Create Data object
            val data = Data.newBuilder()
                .setPortnumValue(AppConstants.Mesh.Meshtastic.PRIVATE_APP_PORT)
                .setPayload(ByteString.copyFrom(dataBytes))
                .build()
                
            // 2. Wrap in MeshPacket
            val meshPacket = MeshPacket.newBuilder()
                .setFrom(0) // 0 tells the device to use its own node ID
                .setTo(-1) // Broadcast to all
                .setDecoded(data)
                .setId(random.nextInt()) // Needs a unique ID for de-duplication
                .build()
                
            // 3. Wrap in ToRadio packet
            val toRadio = ToRadio.newBuilder()
                .setPacket(meshPacket)
                .build()
                
            val serializedToRadio = toRadio.toByteArray()
            
            // 4. Write to characteristic
            // Note: Meshtastic BLE might require MTU handling or chunking if the protobuf is large
            // For now assuming the BLE library / MTU negotiation handles < 512 bytes properly,
            // or we might need to verify MTU.
            // Bitchat packets are already fragmented to ~200-400 bytes usually.
            
            toRadioChar?.let { char ->
                // Write type defaults to WRITE_TYPE_DEFAULT (Response needed)
                // Meshtastic usually expects Write Request (with response)
                char.value = serializedToRadio
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                
                bluetoothGatt?.writeCharacteristic(char)
                // We should ideally wait for onCharacteristicWrite callback before sending next
                // to avoid flooding, but for now relying on slow cadence
                delay(100) 
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet to Meshtastic", e)
        }
    }
    
    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.let {
            it.disconnect()
            it.close()
        }
        bluetoothGatt = null
        toRadioChar = null
        isConnected = false
        isConnecting = false
    }
}
