package com.bitchat.android.mesh

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

/**
 * Manages GATT server operations, advertising, and server-side connections
 */
class BluetoothGattServerManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?
) {
    
    companion object {
        private const val TAG = "BluetoothGattServerManager"
        // Use exact same UUIDs as iOS version
        private val SERVICE_UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        private val CHARACTERISTIC_UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        private val DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // GATT server for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    
    // State management
    private var isActive = false
    
    /**
     * Start GATT server
     */
    fun start(): Boolean {
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return false
        }
        
        isActive = true
        
        connectionScope.launch {
            setupGattServer()
            delay(300) // Brief delay to ensure GATT server is ready
            startAdvertising()
        }
        
        return true
    }
    
    /**
     * Stop GATT server
     */
    fun stop() {
        isActive = false
        
        connectionScope.launch {
            stopAdvertising()
            
            // Close GATT server
            gattServer?.close()
            gattServer = null
            
            Log.i(TAG, "GATT server stopped")
        }
    }
    
    /**
     * Get GATT server instance
     */
    fun getGattServer(): BluetoothGattServer? = gattServer
    
    /**
     * Get characteristic instance
     */
    fun getCharacteristic(): BluetoothGattCharacteristic? = characteristic
    
    /**
     * Setup GATT server with proper sequencing
     */
    @Suppress("DEPRECATION")
    private fun setupGattServer() {
        if (!permissionManager.hasBluetoothPermissions()) return
        
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring connection state change after shutdown")
                    return
                }
                
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Server: Device connected ${device.address}")
                        
                        // Get best available RSSI (scan RSSI for server connections)
                        val rssi = connectionTracker.getBestRSSI(device.address) ?: Int.MIN_VALUE
                        
                        val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                            device = device,
                            rssi = rssi,
                            isClient = false
                        )
                        connectionTracker.addDeviceConnection(device.address, deviceConn)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Server: Device disconnected ${device.address}")
                        connectionTracker.cleanupDeviceConnection(device.address)
                    }
                }
            }
            
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring service added callback after shutdown")
                    return
                }
                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Server: Service added successfully: ${service.uuid}")
                } else {
                    Log.e(TAG, "Server: Failed to add service: ${service.uuid}, status: $status")
                }
            }
            
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring characteristic write after shutdown")
                    return
                }
                
                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    Log.i(TAG, "Server: Received packet from ${device.address}, size: ${value.size} bytes")
                    val packet = BitchatPacket.fromBinaryData(value)
                    if (packet != null) {
                        val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                        Log.d(TAG, "Server: Parsed packet type ${packet.type} from $peerID")
                        delegate?.onPacketReceived(packet, peerID, device)
                    } else {
                        Log.w(TAG, "Server: Failed to parse packet from ${device.address}, size: ${value.size} bytes")
                        Log.w(TAG, "Server: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
            
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring descriptor write after shutdown")
                    return
                }
                
                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    Log.d(TAG, "Device ${device.address} subscribed to notifications")
                    connectionTracker.addSubscribedDevice(device)
                    
                    connectionScope.launch {
                        delay(100)
                        if (isActive) { // Check if still active
                            delegate?.onDeviceConnected(device)
                        }
                    }
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
        
        // Proper cleanup sequencing to prevent race conditions
        gattServer?.let { server ->
            Log.d(TAG, "Cleaning up existing GATT server")
            try {
                server.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing GATT server: ${e.message}")
            }
        }
        
        // Small delay to ensure cleanup is complete
        Thread.sleep(100)
        
        if (!isActive) {
            Log.d(TAG, "Service inactive, skipping GATT server creation")
            return
        }
        
        // Create new server
        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        
        // Create characteristic with notification support
        characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_WRITE or 
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or 
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        val descriptor = BluetoothGattDescriptor(
            DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)
        
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        
        gattServer?.addService(service)
        
        Log.i(TAG, "GATT server setup complete")
    }
    
    /**
     * Start advertising
     */
    @Suppress("DEPRECATION")
    private fun startAdvertising() {
        if (!permissionManager.hasBluetoothPermissions() || bleAdvertiser == null || !isActive) return

        val settings = powerManager.getAdvertiseSettings()
        
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Advertising started (power mode: ${powerManager.getPowerInfo().split("Current Mode: ")[1].split("\n")[0]})")
            }
            
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
            }
        }
        
        try {
            bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising: ${e.message}")
        }
    }
    
    /**
     * Stop advertising
     */
    @Suppress("DEPRECATION")
    private fun stopAdvertising() {
        if (!permissionManager.hasBluetoothPermissions() || bleAdvertiser == null) return
        try {
            advertiseCallback?.let { bleAdvertiser.stopAdvertising(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
        }
    }
    
    /**
     * Restart advertising (for power mode changes)
     */
    fun restartAdvertising() {
        if (!isActive) return
        
        connectionScope.launch {
            stopAdvertising()
            delay(100)
            startAdvertising()
        }
    }
} 