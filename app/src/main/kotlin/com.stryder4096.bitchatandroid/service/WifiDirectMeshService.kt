package com.stryder4096.bitchatandroid 
 
import android.content.BroadcastReceiver 
import android.content.Context 
import android.content.Intent 
import android.content.IntentFilter 
import android.net.wifi.p2p.WifiP2pConfig 
import android.net.wifi.p2p.WifiP2pDevice 
import android.net.wifi.p2p.WifiP2pDeviceList 
import android.net.wifi.p2p.WifiP2pManager 
import android.os.Looper 
import android.util.Log 
import kotlinx.coroutines.CoroutineScope 
import kotlinx.coroutines.Dispatchers 
import kotlinx.coroutines.launch 
import java.net.Socket 
 
class WifiDirectMeshService(private val context: Context) { 
    private val TAG = "WifiDirectMeshService" 
    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager? 
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, Looper.getMainLooper(), null) 
    private val peers = mutableListOf<WifiP2pDevice>() 
    private val scope = CoroutineScope(Dispatchers.IO) 
 
    private val intentFilter = IntentFilter().apply { 
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) 
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) 
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) 
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION) 
    } 
 
    private val peerListListener = WifiP2pManager.PeerListListener { peerList -> 
        peers.clear() 
        peers.addAll(peerList.deviceList) 
        Log.d(TAG, "Discovered ${peers.size} peers") 
    } 
 
    private val receiver = object : BroadcastReceiver() { 
        override fun onReceive(context: Context, intent: Intent) { 
            when (intent.action) { 
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> manager?.requestPeers(channel, peerListListener) 
            } 
        } 
    } 
 
    init { 
        context.registerReceiver(receiver, intentFilter) 
    } 
 
    fun discoverPeers() { 
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener { 
            override fun onSuccess() { Log.d(TAG, "Discovery started") } 
            override fun onFailure(reason: Int) { Log.e(TAG, "Discovery failed: $reason") } 
        }) 
    } 
 
    fun connectToPeer(device: WifiP2pDevice, onConnected: (Socket) -> Unit) { 
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress } 
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener { 
            override fun onSuccess() { 
                scope.launch { 
                    val socket = Socket() // Placeholder; implement actual connection 
                    onConnected(socket) 
                } 
            } 
            override fun onFailure(reason: Int) { Log.e(TAG, "Connect failed: $reason") } 
        }) 
    } 
 
    fun sendMessage(socket: Socket, message: ByteArray) { 
        socket.outputStream.write(message) 
        socket.outputStream.flush() 
    } 
 
    fun cleanup() { 
        context.unregisterReceiver(receiver) 
        manager?.removeGroup(channel, null) 
    } 
} 
