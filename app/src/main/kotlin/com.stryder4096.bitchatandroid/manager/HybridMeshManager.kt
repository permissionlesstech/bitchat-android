package com.stryder4096.bitchatandroid 
 
import android.content.Context 
import kotlinx.coroutines.CoroutineScope 
import kotlinx.coroutines.Dispatchers 
import kotlinx.coroutines.launch 
 
class HybridMeshManager(private val context: Context) { 
    private val bleService = BluetoothMeshService(context) // Existing from repo 
    private val wifiDirectService = WifiDirectMeshService(context) 
    private val scope = CoroutineScope(Dispatchers.IO) 
 
    fun initialize() { 
        bleService.startScanning() // Existing BLE init 
        wifiDirectService.discoverPeers() // Wi-Fi Direct init 
    } 
 
    fun sendMessage(message: ByteArray, target: String, onFailure: () -> Unit) { 
        scope.launch { 
            val bleSuccess = bleService.relayMessage(message, target) // Assume existing method 
            if (bleSuccess) { 
                onFailure() // Trigger UI prompt 
                wifiDirectService.discoverPeers() 
                // Attempt Wi-Fi Direct send 
            } 
        } 
    } 
 
    fun cleanup() { 
        bleService.stop() 
        wifiDirectService.cleanup() 
    } 
} 
