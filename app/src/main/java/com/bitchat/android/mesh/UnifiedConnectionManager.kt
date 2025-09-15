package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * UnifiedConnectionManager wraps BLE and Wi‑Fi Direct transports and exposes the
 * same interface used by BluetoothMeshService today. This allows us to add Wi‑Fi
 * bridging transparently without changing higher layers.
 */
class UnifiedConnectionManager(
    private val context: Context,
    private val myPeerID: String,
    private val fragmentManager: FragmentManager? = null
) : PowerManagerDelegate {

    companion object { private const val TAG = "UnifiedConnectionManager" }

    // Underlying transport managers
    private val bleManager = BluetoothConnectionManager(context, myPeerID, fragmentManager)
    private val wifiManager = WifiDirectConnectionManager(context, myPeerID)

    // State and scope
    private var isActive = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Delegate (reuses BLE delegate interface)
    var delegate: BluetoothConnectionManagerDelegate? = null

    // Expose BLE address map for now (Wi‑Fi uses its own logical link id). This keeps
    // existing UI/debug code working while we add Wi‑Fi pathing.
    val addressPeerMap get() = bleManager.addressPeerMap

    init {
        // Bridge BLE callbacks upward unchanged
        bleManager.delegate = object : BluetoothConnectionManagerDelegate {
            override fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?) {
                delegate?.onPacketReceived(packet, peerID, device)
            }
            override fun onRelayPacketReceived(packet: BitchatPacket, peerID: String, relayAddress: String) {
                // BLE layer doesn't produce relay-only callbacks; forward defensively
                delegate?.onRelayPacketReceived(packet, peerID, relayAddress)
            }
            override fun onDeviceConnected(device: BluetoothDevice) {
                delegate?.onDeviceConnected(device)
            }
            override fun onDeviceDisconnected(device: BluetoothDevice) {
                delegate?.onDeviceDisconnected(device)
            }
            override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
                delegate?.onRSSIUpdated(deviceAddress, rssi)
            }
        }

        // Bridge Wi‑Fi callbacks: device is not applicable; pass null.
        wifiManager.delegate = object : WifiDirectConnectionManager.WifiDirectDelegate {
            override fun onMeshFrameReceived(frame: BitchatPacket, peerID: String, linkId: String) {
                // Relay address uses WFD: prefix to play nicely with loop-avoidance.
                delegate?.onRelayPacketReceived(frame, peerID, linkId)
            }
            override fun onLinkEstablished(linkId: String) {
                Log.i(TAG, "Wi‑Fi Direct link established: $linkId")
            }
            override fun onLinkClosed(linkId: String, reason: String?) {
                Log.i(TAG, "Wi‑Fi Direct link closed: $linkId ${reason ?: ""}")
            }
        }

        // Observe debug toggle for Wi‑Fi Direct enable/disable
        try {
            val dbg = com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
            scope.launch {
                dbg.wifiDirectEnabled.collect { enabled ->
                    if (!isActive) return@collect
                    if (enabled) wifiManager.start() else wifiManager.stop()
                }
            }
        } catch (_: Exception) { }
    }

    fun startServices(): Boolean {
        isActive = true
        val ok = bleManager.startServices()
        // Start Wi‑Fi Direct if enabled in debug settings (default true)
        val wifiEnabled = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().wifiDirectEnabled.value } catch (_: Exception) { true }
        if (wifiEnabled) wifiManager.start() else Log.i(TAG, "Wi‑Fi Direct disabled via debug settings")
        return ok
    }

    fun stopServices() {
        isActive = false
        wifiManager.stop()
        bleManager.stopServices()
        scope.cancel()
    }

    fun setAppBackgroundState(inBackground: Boolean) {
        // Forward to BLE PowerManager; Wi‑Fi may also observe this later if needed.
        bleManager.setAppBackgroundState(inBackground)
    }

    fun broadcastPacket(routed: RoutedPacket) {
        // BLE path (existing)
        bleManager.broadcastPacket(routed)
        // Wi‑Fi path: broadcast over the single active WFD link, with echo prevention inside
        wifiManager.broadcastPacket(routed)
    }

    fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        // Prefer Wi‑Fi for unicast if configured; fallback to BLE.
        val preferWifi = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().wifiPreferDirectForUnicast.value } catch (_: Exception) { true }
        var sent = false
        if (preferWifi) {
            sent = wifiManager.sendPacketToPeer(peerID, packet)
            if (!sent) sent = bleManager.sendPacketToPeer(peerID, packet)
        } else {
            sent = bleManager.sendPacketToPeer(peerID, packet)
            if (!sent) sent = wifiManager.sendPacketToPeer(peerID, packet)
        }
        return sent
    }

    fun startServer() { bleManager.startServer() }
    fun stopServer() { bleManager.stopServer() }
    fun startClient() { bleManager.startClient() }
    fun stopClient() { bleManager.stopClient() }

    fun setNicknameResolver(resolver: (String) -> String?) {
        bleManager.setNicknameResolver(resolver)
        wifiManager.setNicknameResolver(resolver)
    }

    fun getConnectedDeviceEntries(): List<Triple<String, Boolean, Int?>> {
        // Return BLE entries for now; Wi‑Fi will be added later with a synthetic entry.
        return bleManager.getConnectedDeviceEntries()
    }

    fun getLocalAdapterAddress(): String? = bleManager.getLocalAdapterAddress()

    fun isClientConnection(address: String): Boolean? = bleManager.isClientConnection(address)

    fun connectToAddress(address: String): Boolean = bleManager.connectToAddress(address)
    fun disconnectAddress(address: String) { bleManager.disconnectAddress(address) }

    fun disconnectAll() {
        bleManager.disconnectAll()
        wifiManager.disconnect()
    }

    fun getConnectedDeviceCount(): Int = bleManager.getConnectedDeviceCount()

    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Unified Connection Manager ===")
            appendLine("Active: $isActive")
            appendLine()
            appendLine("-- BLE --")
            appendLine(bleManager.getDebugInfo())
            appendLine()
            appendLine("-- Wi‑Fi Direct --")
            appendLine(wifiManager.getDebugInfo())
        }
    }

    // PowerManagerDelegate passthrough from BLE PowerManager
    override fun onPowerModeChanged(newMode: PowerManager.PowerMode) {
        bleManager.onPowerModeChanged(newMode)
    }

    override fun onScanStateChanged(shouldScan: Boolean) {
        bleManager.onScanStateChanged(shouldScan)
    }
}
